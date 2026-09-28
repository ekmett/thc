// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0
package protocolprobe;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.BytecodeOSRNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.BytecodeOSRMetadata;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedOSRLoopNode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import thc.Language;

/** Deterministic real compiler/AST-lock controls, with no guest training calls. */
public final class OsrSchedulingProbe {
    static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    static void await(CountDownLatch latch) {
        try { require(latch.await(10, TimeUnit.SECONDS), "bounded compiler handshake"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    static void join(Thread thread) throws InterruptedException {
        thread.join(10000);
        require(!thread.isAlive(), "bounded OSR requester completion");
    }
    static Thread start(Context context, AtomicReference<Throwable> failure, Runnable action) {
        Thread thread = new Thread(() -> {
            try {
                context.enter();
                try { action.run(); } finally { context.leave(); }
            } catch (Throwable problem) { failure.compareAndSet(null, problem); }
        }, "OSR-control-requester");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
    static void bypass(OptimizedCallTarget target) throws Exception {
        Object runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", OptimizedCallTarget.class).invoke(runtime, target);
    }
    static FrameDescriptor descriptor() {
        var builder = FrameDescriptor.newBuilder();
        builder.addSlot(FrameSlotKind.Long, "value", null);
        builder.addSlot(FrameSlotKind.Long, "remaining", null);
        builder.addSlot(FrameSlotKind.Long, "entry", null);
        return builder.build();
    }

    abstract static class Source extends RootNode {
        final CountDownLatch compilerEntered = new CountDownLatch(1);
        final CountDownLatch releaseCompiler = new CountDownLatch(1);
        final AtomicInteger compilations = new AtomicInteger();
        final AtomicInteger effects = new AtomicInteger();
        final AtomicInteger compiledEffects = new AtomicInteger();
        Source(Language language) { super(language, descriptor()); }
        @Override protected boolean prepareForCompilation(boolean root, int tier, boolean last) {
            if (root) {
                compilations.incrementAndGet();
                compilerEntered.countDown();
                await(releaseCompiler);
            }
            return true;
        }
    }
    static final class Step extends Node {
        final long value;
        Step(long value) { this.value = value; }
        long execute() { return value; }
    }
    static final class Iteration extends Node implements RepeatingNode {
        @Child Step step = new Step(42);
        final Source owner;
        Iteration(Source owner) { this.owner = owner; }
        @Override public boolean executeRepeating(VirtualFrame frame) {
            owner.effects.incrementAndGet();
            if (CompilerDirectives.inCompiledCode()) owner.compiledEffects.incrementAndGet();
            frame.setLong(0, step.execute());
            return false;
        }
    }
    static final class LoopSource extends Source {
        @Child OptimizedOSRLoopNode loop;
        LoopSource(Language language) {
            super(language);
            loop = (OptimizedOSRLoopNode) Truffle.getRuntime().createLoopNode(new Iteration(this));
        }
        @Override public Object execute(VirtualFrame frame) {
            frame.setLong(0, 0);
            loop.execute(frame);
            return frame.getLong(0);
        }
    }

    static void loop(Context context, Language language, int change) throws Exception {
        var root = new LoopSource(language);
        var original = root.getCallTarget();
        var failure = new AtomicReference<Throwable>();
        Thread first = start(context, failure, root.loop::forceOSR);
        try {
            await(root.compilerEntered);
            // The owner lock must be available while compilation is blocked.
            var target = root.atomic(root.loop::getCompiledOSRLoop);
            require(target != null && target.isSubmittedForCompilation(), "only a submitted target is published");
            Thread second = start(context, failure, root.loop::forceOSR);
            join(second);
            require(root.loop.getCompiledOSRLoop() == target && root.compilations.get() == 1,
                    "concurrent first request has one target/task winner");
            require(root.effects.get() == 0, "compilation requests execute no guest work");
            if (change == 1) {
                ((Iteration) root.loop.getRepeatingNode()).step.replace(new Step(43), "ordinary replacement during OSR compilation");
                require(root.loop.getCompiledOSRLoop() == target, "replacement retains pending publication until completion");
            } else if (change == 2) {
                require(target.cancelCompilation("ordinary OSR cancellation control"), "actual pending task cancelled");
            }
            root.releaseCompiler.countDown();
            join(first);
            if (failure.get() != null) throw new AssertionError("requester failed", failure.get());
            require(!target.isSubmittedForCompilation() && root.compilations.get() == 1,
                    "task finishes without an unchanged-graph retry");
            if (change == 0) {
                bypass(target);
                require(target.isValidLastTier(), "winner compiled");
                require(original.call().equals(42L), "first compiled loop result");
                require(root.effects.get() == 1 && root.compiledEffects.get() == 1 && target.isValidLastTier(),
                        "first compiled loop effect once with retained target");
            } else {
                require(!target.isValid(), "cancelled target never becomes installed");
                require(original.call().equals(change == 1 ? 43L : 42L), "ordinary interpreter fallback preserves current body");
                require(root.effects.get() == 1 && root.compiledEffects.get() == 0, "cancelled request never replays guest effects");
                require(root.loop.getCompiledOSRLoop() == null, "cancelled request releases its reservation for normal invalid-target cleanup");
            }
            System.out.println("PASS concurrent OSR first winner; change=" + change);
        } finally {
            root.releaseCompiler.countDown();
            join(first);
        }
    }

    static final class BytecodeBody extends Node implements BytecodeOSRNode {
        @CompilerDirectives.CompilationFinal private Object metadata;
        final Source owner;
        BytecodeBody(Source owner) { this.owner = owner; }
        @Override public Object getOSRMetadata() { return metadata; }
        @Override public void setOSRMetadata(Object value) { metadata = value; }
        @Override public Object executeOSR(VirtualFrame frame, long target, Object state) { return loop(frame); }
        Object loop(VirtualFrame frame) {
            while (frame.getLong(1) != 0) {
                owner.effects.incrementAndGet();
                if (CompilerDirectives.inCompiledCode()) owner.compiledEffects.incrementAndGet();
                frame.setLong(0, frame.getLong(0) + 1);
                frame.setLong(1, frame.getLong(1) - 1);
                if (CompilerDirectives.inInterpreter() && BytecodeOSRNode.pollOSRBackEdge(this)) {
                    Object result = BytecodeOSRNode.tryOSR(this, frame.getLong(2), null, null, frame);
                    if (result != null) return result;
                }
            }
            return frame.getLong(0);
        }
    }
    static final class BytecodeSource extends Source {
        @Child BytecodeBody body = new BytecodeBody(this);
        BytecodeSource(Language language) { super(language); }
        @Override public Object execute(VirtualFrame frame) {
            frame.setLong(0, 0); frame.setLong(1, 1100);
            frame.setLong(2, (Long) frame.getArguments()[0]);
            return body.loop(frame);
        }
    }
    static void bytecode(Context context, Language language, boolean otherEntry) throws Exception {
        var root = new BytecodeSource(language);
        var original = root.getCallTarget();
        var failure = new AtomicReference<Throwable>();
        Thread first = start(context, failure,
                () -> require(original.call(0L).equals(1100L), "first bytecode caller frame result"));
        try {
            await(root.compilerEntered);
            var targets = root.atomic(() -> ((BytecodeOSRMetadata) root.body.getOSRMetadata()).getOSRCompilations());
            require(targets.size() == 1, "single bytecode OSR entry");
            var target = targets.get(0L);
            require(target.isSubmittedForCompilation(), "bytecode publication is actually submitted");
            Thread second = start(context, failure,
                    () -> require(original.call(otherEntry ? 1L : 0L).equals(1100L), "independent bytecode caller frame result"));
            join(second);
            require(targets.size() == (otherEntry ? 2 : 1) && targets.get(0L) == target && root.compilations.get() == 1,
                    "concurrent bytecode invocation preserves one submitted winner");
            if (otherEntry) require(!targets.get(1L).isSubmittedForCompilation() && !targets.get(1L).isValid(),
                    "a distinct bytecode entry cannot bypass the in-progress request exclusion");
            root.releaseCompiler.countDown();
            join(first);
            if (failure.get() != null) throw new AssertionError("bytecode requester failed", failure.get());
            require(target.isValidLastTier() && !target.isSubmittedForCompilation(), "actual bytecode OSR winner installed");
            require(root.effects.get() == 2200 && root.compiledEffects.get() > 0,
                    "two actual calls preserve exactly-once effects and enter compiled suffix");
            System.out.println("PASS concurrent bytecode OSR first winner and distinct caller frames; otherEntry=" + otherEntry);
        } finally { root.releaseCompiler.countDown(); join(first); }
    }

    public static void main(String[] args) throws Exception {
        try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.OSRCompilationThreshold", "1024")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (int change = 0; change < 3; change++) loop(context, language, change);
                bytecode(context, language, false);
                bytecode(context, language, true);
            } finally { context.leave(); }
        }
    }
}
