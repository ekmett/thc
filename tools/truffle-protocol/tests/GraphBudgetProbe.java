// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0
package protocolprobe;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
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
                .option("engine.CompilationFailureAction", "Throw")
                .option("compiler.MaximumGraalGraphSize", "10000")
                .option("compiler.CompilationTimeout", "30").build();
    }

    public static void main(String[] args) throws Exception {
        try (Context context = context(false)) {
            context.initialize("thc"); context.enter();
            try { run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
        try (Context context = context(true)) {
            context.initialize("thc"); context.enter();
            try { runBackground(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }
}
