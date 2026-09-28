// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0
package protocolprobe;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.BytecodeOSRNode;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedOSRLoopNode;
import com.oracle.truffle.runtime.BytecodeOSRMetadata;
import com.oracle.truffle.runtime.BaseOSRRootNode;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import thc.Language;

/** Actual graph-size failure/rewrite/retry control, not a production outlining policy. */
public final class GraphBudgetProbe {
    private static void require(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    private static final class State {
        final long[] values = new long[4096];
        int effects;
        int compiledEntries;
        State() { for (int i = 0; i < values.length; i++) values[i] = i + 1; }
        long expected(long seed) {
            for (int i = 0; i < values.length; i++) seed = (seed ^ values[i]) * 17L + i;
            return seed;
        }
    }

    private abstract static class Work extends Node {
        abstract long execute(long seed);
    }

    private static final class HeavyWork extends Work {
        final State state;
        HeavyWork(State state) { this.state = state; }
        @Override @ExplodeLoop long execute(long seed) {
            state.effects++;
            for (int i = 0; i < 4096; i++) seed = (seed ^ state.values[i]) * 17L + i;
            return seed;
        }
    }

    private static final class CallWork extends Work {
        @Child private DirectCallNode call;
        CallWork(RootCallTarget target) { call = DirectCallNode.create(target); }
        @Override long execute(long seed) { return (Long) call.call(seed); }
    }

    private static final class SideRoot extends RootNode {
        @Child private Work work;
        SideRoot(Language language, Work work) { super(language); this.work = work; }
        @Override public Object execute(VirtualFrame frame) { return work.execute((Long) frame.getArguments()[0]); }
        @Override protected boolean prepareForCompilation(boolean root, int tier, boolean last) {
            // This particular edge exists only because its caller's real graph
            // already exceeded the budget. Standalone compilation stays allowed.
            return root;
        }
        @Override public boolean requiresUnprofiledReturn() { return true; }
        @Override protected ExecutionSignature prepareForAOT() {
            return ExecutionSignature.create(Long.class, new Class<?>[]{Long.class});
        }
        @Override public String getName() { return "budget extracted side"; }
    }

    private static final class BudgetRoot extends RootNode {
        final State state;
        final boolean recover;
        @Child private Work work;
        volatile long generation;
        int failures;
        final RootCallTarget side;

        BudgetRoot(Language language, State state, boolean recover) {
            super(language);
            this.state = state;
            this.recover = recover;
            this.work = new HeavyWork(state);
            // Prepare the candidate under its owning language context. Root
            // initialization must not run on the compiler thread. This model
            // deliberately duplicates a candidate body; no product planner or
            // claim of demand-only candidate allocation is implied.
            if (recover) {
                side = new SideRoot(language, NodeUtil.cloneNode(work)).getCallTarget();
                ((OptimizedCallTarget) side).ensureInitialized();
                ((OptimizedCallTarget) side).prepareForAOT();
            } else {
                side = null;
            }
        }
        @Override public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) state.compiledEntries++;
            return work.execute((Long) frame.getArguments()[0]);
        }
        @Override public long getGraphBudgetGeneration() { return generation; }
        @Override public synchronized long prepareGraphBudgetRetry(long failedGeneration) {
            failures++;
            if (!recover || generation != 0 || failedGeneration != 0) return generation;
            // Actual structural extraction: the failing root's heavy subtree
            // becomes one call. Preserve state identity, computation and arguments.
            // No guest call, profiling or waiting occurs on the compiler thread.
            work.replace(new CallWork(side), "actual graph budget extraction");
            return ++generation;
        }
        @Override public boolean requiresUnprofiledReturn() { return true; }
        @Override protected ExecutionSignature prepareForAOT() {
            return ExecutionSignature.create(Long.class, new Class<?>[]{Long.class});
        }
        @Override public boolean isCloningAllowed() { return true; }
        @Override public String getName() { return "budget caller"; }
    }

    private static void bypass(OptimizedCallTarget target) throws Exception {
        Object runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", OptimizedCallTarget.class).invoke(runtime, target);
    }

    /** Real LoopNode OSR, sharing the original frame and recovery owner. */
    private static final class OsrBudgetRoot extends RootNode {
        final State state;
        final boolean recover;
        final RootCallTarget side;
        @Child private OptimizedOSRLoopNode loop;
        volatile long generation;
        int failures;
        CountDownLatch retrySubmissionEntered;
        CountDownLatch releaseRetrySubmission;

        OsrBudgetRoot(Language language, State state) {
            this(language, state, true);
        }
        OsrBudgetRoot(Language language, State state, boolean recover) {
            super(language, descriptor());
            this.state = state;
            this.recover = recover;
            HeavyWork work = new HeavyWork(state);
            side = new SideRoot(language, NodeUtil.cloneNode(work)).getCallTarget();
            ((OptimizedCallTarget) side).ensureInitialized();
            ((OptimizedCallTarget) side).prepareForAOT();
            loop = (OptimizedOSRLoopNode) Truffle.getRuntime().createLoopNode(new Iteration(state, work));
        }
        private static FrameDescriptor descriptor() {
            var builder = FrameDescriptor.newBuilder();
            builder.addSlot(FrameSlotKind.Long, "value", null);
            builder.addSlot(FrameSlotKind.Long, "remaining", null);
            return builder.build();
        }
        @Override public Object execute(VirtualFrame frame) {
            frame.setLong(0, (Long) frame.getArguments()[0]);
            frame.setLong(1, (Long) frame.getArguments()[1]);
            loop.execute(frame);
            return frame.getLong(0);
        }
        @Override public long getGraphBudgetGeneration() {
            if (generation != 0 && retrySubmissionEntered != null) {
                retrySubmissionEntered.countDown();
                OsrSchedulingProbe.await(releaseRetrySubmission);
            }
            return generation;
        }
        @Override public synchronized long prepareGraphBudgetRetry(long failedGeneration) {
            failures++;
            if (!recover || generation != 0 || failedGeneration != 0) return generation;
            Iteration body = (Iteration) loop.getRepeatingNode();
            body.work.replace(new CallWork(side), "extract actual oversized OSR work");
            return ++generation;
        }
        @Override public String getName() { return "budget OSR original owner"; }
    }

    private static final class Iteration extends Node implements RepeatingNode {
        final State state;
        @Child private Work work;
        Iteration(State state, Work work) { this.state = state; this.work = work; }
        @Override public boolean executeRepeating(VirtualFrame frame) {
            long remaining = frame.getLong(1);
            if (remaining == 0) return false;
            if (CompilerDirectives.inCompiledCode()) state.compiledEntries++;
            frame.setLong(0, work.execute(frame.getLong(0)));
            frame.setLong(1, remaining - 1);
            return remaining > 1;
        }
    }

    private static void runOsr(Language language) throws Exception {
        State state = new State();
        OsrBudgetRoot root = new OsrBudgetRoot(language, state);
        RootCallTarget original = root.getCallTarget();
        // Compilation only: no profiling or warmup iteration is executed.
        try { root.loop.forceOSR(); }
        catch (com.oracle.truffle.api.OptimizationFailedException failure) {
            throw new AssertionError("actual OSR failure did not recover through original owner; callbacks=" + root.failures, failure);
        }
        OptimizedCallTarget osr = root.loop.getCompiledOSRLoop();
        require(root.failures == 1 && root.generation == 1, "OSR invokes the original recovery owner exactly once");
        require(((com.oracle.truffle.runtime.BaseOSRRootNode) osr.getRootNode()).getSourceRootNode() == root,
                "OSR exposes the exact original root, not a clone or context substitute");
        require(osr != null && osr.getRootNode().getGraphBudgetGeneration() == 1,
                "OSR target observes the original owner's generation");
        require(osr.isValidLastTier() && !osr.isSubmittedForCompilation(), "reduced OSR graph installed");
        require(state.effects == 0 && state.compiledEntries == 0, "OSR compilation executes no guest effects");
        bypass(osr);
        long expected = state.expected(state.expected(state.expected(7L)));
        require(original.call(7L, 3L).equals(expected), "OSR preserves original frame and result");
        require(state.effects == 3 && state.compiledEntries == 3, "first OSR entry executes all three effects once in compiled code");
        require(root.loop.getCompiledOSRLoop() == osr && osr.isValidLastTier(), "same OSR target remains installed");
        System.out.println("PASS actual OSR budget failure -> original owner extraction -> same-target compiled execution");
    }

    private static final class BytecodeBudgetRoot extends RootNode {
        final State state;
        final boolean recover;
        final RootCallTarget side;
        @Child private BytecodeIteration body;
        volatile long generation;
        int failures;
        int prefixAtFailure;

        BytecodeBudgetRoot(Language language, State state) {
            this(language, state, true);
        }
        BytecodeBudgetRoot(Language language, State state, boolean recover) {
            super(language, OsrBudgetRoot.descriptor());
            this.state = state;
            this.recover = recover;
            var work = new HeavyWork(state);
            side = new SideRoot(language, NodeUtil.cloneNode(work)).getCallTarget();
            ((OptimizedCallTarget) side).ensureInitialized();
            ((OptimizedCallTarget) side).prepareForAOT();
            body = new BytecodeIteration(state, work);
        }
        @Override public Object execute(VirtualFrame frame) {
            frame.setLong(0, (Long) frame.getArguments()[0]);
            frame.setLong(1, (Long) frame.getArguments()[1]);
            return body.loop(frame);
        }
        @Override public long getGraphBudgetGeneration() { return generation; }
        @Override public synchronized long prepareGraphBudgetRetry(long failedGeneration) {
            failures++;
            if (!recover || generation != 0 || failedGeneration != 0) return generation;
            prefixAtFailure = state.effects;
            body.work.replace(new CallWork(side), "extract actual oversized bytecode OSR work");
            return ++generation;
        }
        @Override public String getName() { return "budget bytecode OSR original owner"; }
    }

    private static final class BytecodeIteration extends Node implements BytecodeOSRNode {
        @CompilerDirectives.CompilationFinal private Object metadata;
        @Child private Work work;
        final State state;
        BytecodeIteration(State state, Work work) { this.state = state; this.work = work; }
        @Override public Object getOSRMetadata() { return metadata; }
        @Override public void setOSRMetadata(Object value) { metadata = value; }
        @Override public Object executeOSR(VirtualFrame frame, long bci, Object interpreterState) { return loop(frame); }
        Object loop(VirtualFrame frame) {
            while (frame.getLong(1) != 0) {
                if (CompilerDirectives.inCompiledCode()) state.compiledEntries++;
                frame.setLong(0, work.execute(frame.getLong(0)));
                frame.setLong(1, frame.getLong(1) - 1);
                if (CompilerDirectives.inInterpreter() && BytecodeOSRNode.pollOSRBackEdge(this)) {
                    Object result = BytecodeOSRNode.tryOSR(this, 0L, null, null, frame);
                    if (result != null) return result;
                }
            }
            return frame.getLong(0);
        }
    }

    private static void runBytecodeOsr(Language language) {
        State state = new State();
        var root = new BytecodeBudgetRoot(language, state);
        RootCallTarget original = root.getCallTarget();
        long expected = 7L;
        for (int i = 0; i < 1500; i++) expected = state.expected(expected);
        // One actual invocation reaches the ordinary OSR threshold. There is
        // no preceding guest invocation, seeded backedge count, or replay.
        Object actual;
        try { actual = original.call(7L, 1500L); }
        catch (com.oracle.truffle.api.OptimizationFailedException failure) {
            throw new AssertionError("actual bytecode OSR failure did not recover; callbacks=" + root.failures
                    + ", completed prefix=" + state.effects, failure);
        }
        require(actual.equals(expected) && state.effects == 1500, "bytecode OSR preserves result and each effect once");
        require(root.failures == 1 && root.generation == 1, "one bytecode OSR structural recovery");
        require(root.prefixAtFailure > 0 && root.prefixAtFailure < 1500, "first invocation genuinely crosses OSR threshold");
        require(state.compiledEntries == 1500 - root.prefixAtFailure, "remaining iterations all execute compiled without replay");
        var targets = ((BytecodeOSRMetadata) root.body.getOSRMetadata()).getOSRCompilations();
        require(targets.size() == 1, "one retained bytecode OSR target");
        var osr = targets.values().iterator().next();
        require(osr.isValidLastTier() && !osr.isSubmittedForCompilation(), "bytecode OSR stays installed after completion");
        require(((com.oracle.truffle.runtime.BaseOSRRootNode) osr.getRootNode()).getSourceRootNode() == root,
                "bytecode OSR retains original recovery owner");
        System.out.println("PASS actual bytecode OSR budget failure -> original owner extraction -> once-only compiled suffix");
    }

    private static void runOsrExhausted(Language language) {
        State state = new State();
        var root = new OsrBudgetRoot(language, state, false);
        root.getCallTarget();
        try {
            root.loop.forceOSR();
            throw new AssertionError("no OSR boundary must retain failure");
        } catch (com.oracle.truffle.api.OptimizationFailedException expected) {
            var target = root.loop.getCompiledOSRLoop();
            require(expected.getCallTarget() == target && !target.canBeInlined() && !target.isValid(), "exact exhausted loop target failure");
        }
        require(root.failures == 1 && root.generation == 0 && state.effects == 0,
                "exhausted loop boundary neither retries nor executes guest work");
        var bytecodeState = new State();
        var bytecode = new BytecodeBudgetRoot(language, bytecodeState, false);
        try {
            bytecode.getCallTarget().call(7L, 1500L);
            throw new AssertionError("no bytecode OSR boundary must retain failure");
        } catch (com.oracle.truffle.api.OptimizationFailedException expected) {
            var target = (OptimizedCallTarget) expected.getCallTarget();
            require(!target.canBeInlined() && !target.isValid(), "bytecode target remains permanently failed");
        }
        require(bytecode.failures == 1 && bytecode.generation == 0 && bytecodeState.effects == 1024,
                "exhausted bytecode boundary preserves completed prefix without suffix or retry");
        require(((BytecodeOSRMetadata) bytecode.body.getOSRMetadata()).isDisabled(), "bytecode failure keeps original disable policy");
        System.out.println("PASS actual loop and bytecode OSR exhausted boundaries keep terminal failure");
    }

    private static void runOsrBackground(Language language) throws Exception {
        State state = new State();
        var root = new OsrBudgetRoot(language, state);
        var original = root.getCallTarget();
        root.loop.forceOSR();
        var target = root.loop.getCompiledOSRLoop();
        require(target != null, "background target published after submission");
        target.waitForCompilation();
        require(root.failures == 1 && root.generation == 1 && !target.isSubmittedForCompilation(), "background failed task completed");
        require(!target.isValid() && target.canBeInlined() && state.effects == 0,
                "background extraction neither recursively compiles nor executes guest work");
        require(!target.compile(true), "later background request remains asynchronous");
        target.waitForCompilation();
        bypass(target);
        require(target.isValidLastTier() && root.loop.getCompiledOSRLoop() == target, "later request installs same reduced target");
        require(original.call(7L, 1L).equals(state.expected(7L)), "background OSR result");
        require(state.effects == 1 && state.compiledEntries == 1 && target.isValidLastTier(), "first installed background OSR executes once");
        System.out.println("PASS background OSR recovery remains eligible without recursive submission or guest replay");
    }

    private static void runFastBytecodeFailure(Language language) {
        var state = new State();
        var root = new BytecodeBudgetRoot(language, state, false);
        var observed = new AtomicReference<OptimizedCallTarget>();
        var runtime = (OptimizedTruffleRuntime) Truffle.getRuntime();
        Thread submitter = Thread.currentThread();
        var listener = new OptimizedTruffleRuntimeListener() {
            @Override public void onCompilationQueued(OptimizedCallTarget target, int tier) {
                // Diagnose-mode retry notifications also invoke this listener
                // from the compiler thread; never make a task wait on itself.
                if (Thread.currentThread() != submitter) return;
                if (!(target.getRootNode() instanceof BaseOSRRootNode osr) || osr.getSourceRootNode() != root) return;
                // Deterministically finish the real failing task before its
                // submission returns. This non-recovering root replaces no nodes.
                try { target.waitForCompilation(); }
                catch (com.oracle.truffle.api.OptimizationFailedException expected) {
                    require(expected.getCallTarget() == target, "listener observed exact real failed task");
                    require(observed.compareAndSet(null, target), "one actual failure, no retry");
                }
            }
        };
        runtime.addListener(listener);
        try {
            try {
                root.getCallTarget().call(7L, 1500L);
                throw new AssertionError("foreground bytecode OSR fast failure was swallowed");
            } catch (com.oracle.truffle.api.OptimizationFailedException expected) {
                require(expected.getCallTarget() == observed.get(), "foreground receives exact completed-task failure");
            }
            require(observed.get() != null && root.failures == 1 && state.effects == 1024,
                    "fast failure propagates without retry or suffix execution");
            System.out.println("PASS real bytecode OSR failure completed before submission returns is not swallowed");
        } finally { runtime.removeListener(listener); }
    }

    private static void runRetryPublication(Context context, Language language) throws Exception {
        var state = new State();
        var root = new OsrBudgetRoot(language, state);
        root.retrySubmissionEntered = new CountDownLatch(1);
        root.releaseRetrySubmission = new CountDownLatch(1);
        var original = root.getCallTarget();
        var failure = new AtomicReference<Throwable>();
        Thread requester = OsrSchedulingProbe.start(context, failure, root.loop::forceOSR);
        try {
            OsrSchedulingProbe.await(root.retrySubmissionEntered);
            var target = root.loop.getCompiledOSRLoop();
            require(target != null && !target.isSubmittedForCompilation() && !target.isValid(),
                    "real interval after failed task completion and before retry submission");
            var clone = NodeUtil.cloneNode(root);
            clone.retrySubmissionEntered = null;
            clone.releaseRetrySubmission = null;
            require(clone.loop.getCompiledOSRLoop() == null, "clone does not publish the original reserved target");
            Thread caller = OsrSchedulingProbe.start(context, failure,
                    () -> require(original.call(11L, 1L).equals(state.expected(11L)), "concurrent interpreter caller result"));
            try {
                OsrSchedulingProbe.join(caller);
                require(root.loop.getCompiledOSRLoop() == target && state.effects == 1 && state.compiledEntries == 0,
                        "concurrent caller keeps the reserved target and executes once without waiting on compilation");
            } finally {
                root.releaseRetrySubmission.countDown();
                OsrSchedulingProbe.join(caller);
            }
            OsrSchedulingProbe.join(requester);
            if (failure.get() != null) throw new AssertionError("retry-gap requester failed", failure.get());
            bypass(target);
            require(target.isValidLastTier() && root.loop.getCompiledOSRLoop() == target,
                    "exact reserved target is installed, not orphaned or replaced");
            require(original.call(7L, 1L).equals(state.expected(7L)), "first compiled call after interval");
            require(state.effects == 2 && state.compiledEntries == 1 && target.isValidLastTier(),
                    "interval caller and compiled caller each execute once");
            clone.getCallTarget();
            clone.loop.forceOSR();
            var clonedTarget = clone.loop.getCompiledOSRLoop();
            require(clonedTarget != null && clonedTarget != target && clonedTarget.isValidLastTier(),
                    "clone has no inherited reservation and independently compiles its own target");
            require(state.effects == 2, "cloned compilation executes no guest work");
            System.out.println("PASS real failed-task/retry interval retains publication under a concurrent guest caller");
        } finally { root.releaseRetrySubmission.countDown(); OsrSchedulingProbe.join(requester); }
    }

    private static void run(Language language) throws Exception {
        require(RootNode.graphBudgetPolicyVersion() == 1 && OptimizedCallTarget.graphBudgetPolicyVersion() == 1,
                "matching budget API/runtime");
        State state = new State();
        BudgetRoot root = new BudgetRoot(language, state, true);
        OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
        require(target.compile(true), "actual structural recovery installs the caller");
        require(root.failures == 1 && root.generation == 1 && root.side != null,
                "one actual budget callback and one structural generation");
        require(!target.isSubmittedForCompilation(), "failed and replacement tasks are complete");
        require(target.canBeInlined(), "recovered caller is not marked permanently uninlinable");
        require(state.effects == 0 && state.compiledEntries == 0, "compilation executed no guest work");
        bypass(target);
        require(target.isValidLastTier(), "installed last-tier caller");
        require(target.call(7L).equals(state.expected(7L)), "unchanged result");
        require(state.effects == 1 && state.compiledEntries == 1, "first installed call executes once");
        require(target.isValidLastTier() && root.getCallTarget() == target, "same target retained after first call");
        require(root.prepareGraphBudgetRetry(1) == 1, "finite model has no further extraction");
        System.out.println("PASS actual graph bailout -> body extraction -> completed-task retry -> strict first call");

        BudgetRoot clone = NodeUtil.cloneNode(root);
        OptimizedCallTarget cloneTarget = (OptimizedCallTarget) clone.getCallTarget();
        require(cloneTarget != target && clone.generation == 1 && clone.side == root.side,
                "new target shares existing outlined code and exact guest state");
        require(cloneTarget.compile(true), "clone compiles existing reduced body");
        require(clone.failures == root.failures, "clone does not fabricate a recovery generation");
        bypass(cloneTarget);
        require(cloneTarget.call(11L).equals(state.expected(11L)), "clone result");
        require(state.effects == 2 && state.compiledEntries == 2 && cloneTarget.isValidLastTier(),
                "clone first installed call retains exact state and target");
        System.out.println("PASS clone samples current generation without replay or fake recovery");

        State noBoundaryState = new State();
        BudgetRoot noBoundary = new BudgetRoot(language, noBoundaryState, false);
        OptimizedCallTarget failed = (OptimizedCallTarget) noBoundary.getCallTarget();
        try {
            failed.compile(true);
            throw new AssertionError("no eligible boundary must retain permanent failure");
        } catch (com.oracle.truffle.api.OptimizationFailedException expected) {
            require(expected.getCallTarget() == failed, "failure belongs to the actual root");
        }
        require(noBoundary.failures == 1 && noBoundary.generation == 0 && noBoundaryState.effects == 0,
                "actual no-boundary failure neither retries nor executes guest");
        require(!failed.canBeInlined() && !failed.isValidLastTier(), "ordinary permanent failure policy retained");
        System.out.println("PASS actual exhausted-boundary permanent failure");

        BudgetRoot unrelated = new BudgetRoot(language, new State(), true);
        OptimizedCallTarget unrelatedTarget = (OptimizedCallTarget) unrelated.getCallTarget();
        try {
            unrelatedTarget.onCompilationFailed(() -> "non-budget model failure", false, true, true, false);
            throw new AssertionError("non-budget failure must throw");
        } catch (com.oracle.truffle.api.OptimizationFailedException expected) {
            require(expected.getCallTarget() == unrelatedTarget, "non-budget failure target identity");
            require(unrelated.failures == 0 && unrelated.generation == 0, "non-budget API model never invokes recovery");
        }
        System.out.println("PASS non-budget model keeps ordinary failure handling");
    }

    private static void runBackground(Language language) throws Exception {
        State state = new State();
        BudgetRoot root = new BudgetRoot(language, state, true);
        OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
        target.ensureInitialized();
        target.prepareForAOT();
        require(!target.compile(true), "background submission is not synchronous installation");
        target.waitForCompilation();
        require(root.failures == 1 && root.generation == 1 && !target.isSubmittedForCompilation(),
                "background failed task completes after one structural change");
        require(!target.isValidLastTier() && target.canBeInlined() && state.effects == 0,
                "background recovery does not recursively compile, execute guest or mark permanent failure");
        require(!target.compile(true), "next explicit background request submits the reduced body");
        target.waitForCompilation();
        require(target.isValidLastTier() && root.failures == 1, "later request installs without another recovery");
        bypass(target);
        require(target.call(19L).equals(state.expected(19L)), "background first-call result");
        require(state.effects == 1 && state.compiledEntries == 1 && target.isValidLastTier(),
                "background first installed call executes once and retains target");
        System.out.println("PASS background recovery remains eligible for a later request without guest work");
    }

    private static Context context(boolean background) {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", Boolean.toString(background)).option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.OSRCompilationThreshold", "1024")
                .option("engine.CompilationFailureAction", "Throw")
                .option("compiler.MaximumGraalGraphSize", "10000")
                .option("compiler.CompilationTimeout", "30").build();
    }

    public static void main(String[] args) throws Exception {
        try (Context context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                if (args.length == 1 && args[0].equals("osr")) runOsr(language);
                else if (args.length == 1 && args[0].equals("bytecode-osr")) runBytecodeOsr(language);
                else if (args.length == 1 && args[0].equals("osr-exhausted")) runOsrExhausted(language);
                else if (args.length == 1 && args[0].equals("osr-fast-failure")) runFastBytecodeFailure(language);
                else if (args.length == 1 && args[0].equals("osr-retry-publication")) runRetryPublication(context, language);
                else if (args.length == 1 && args[0].equals("osr-background")) { /* Separate background context below. */ }
                else run(language);
            }
            finally { context.leave(); }
        }
        if (args.length != 0 && !args[0].equals("osr-background")) return;
        try (Context context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                if (args.length != 0) runOsrBackground(language);
                else runBackground(language);
            }
            finally { context.leave(); }
        }
    }
}
