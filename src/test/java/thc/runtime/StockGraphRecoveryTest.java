// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.OptimizationFailedException;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.compiler.TruffleCompilerListener;
import com.oracle.truffle.runtime.AbstractCompilationTask;
import com.oracle.truffle.runtime.BaseOSRRootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedOSRLoopNode;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class StockGraphRecoveryTest {
    @Test void stockAstOsrCallbackExposesOriginalOwnerThroughPublicChildren() { ownership(false); }
    @Test void stockBytecodeOsrCallbackExposesOriginalOwnerThroughPublicChildren() { ownership(true); }
    @Test void terminalStockFailureRecoversOnlyFreshEntryWithoutReplayingEffects() {
        Truffle.getRuntime();
        Controls.recoverAst(false);
    }
    @Test void repeatedRealFailuresKeepEarlierReplacementLocalExtractions() {
        Truffle.getRuntime();
        Controls.recoverAst(true);
    }
    @Test void realAstOsrFailureFinishesOldActivationThenFreshEntryRunsCompiledOsr() {
        Truffle.getRuntime();
        Controls.recoverOsr();
    }

    private static void ownership(boolean bytecode) {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.OSRCompilationThreshold", "1024")
                .option("engine.CompilationFailureAction", "Throw").build();
             var first = Context.newBuilder("thc").engine(engine).build();
             var second = Context.newBuilder("thc").engine(engine).build()) {
            first.initialize("thc"); second.initialize("thc");
            // Load runtime implementation types only after the stock runtime bootstrap.
            Controls.ownership(first, second, bytecode);
        }
    }

    private static final class Controls {
        private static final CoreRepresentation LONG = new CoreRepresentation(CoreKind.LONG, true, true,
                List.of("IntRep"), null, null, null, null, null);
        private record Event(OptimizedCallTarget target, ContextRoot source, Object owner) {}

        static void recoverOsr() {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.OSRCompilationThreshold", "1024")
                    .option("engine.CompilationFailureAction", "Print")
                    .option("compiler.MaximumGraalGraphSize", "10000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var effects = new AtomicInteger(); var compiled = new AtomicInteger();
                    var source = new AtomicReference<RootNode>();
                    long[] values = new long[4096];
                    for (int i = 0; i < values.length; i++) values[i] = i + 1;
                    var layout = new FrameLayout(); int count = layout.bind("count", FrameSlotKind.Long);
                    var body = new LoopWork(new AstSameFrameArm(new Heavy(values, effects, source)), count, compiled);
                    var root = new FunctionRoot(language, layout.build(), "stock OSR recovery", null,
                            new int[0], new int[0], new int[0], body, new Metrics(true));
                    var target = root.getCallTarget();
                    var oldLoop = (OptimizedOSRLoopNode) body.loop;
                    assertEquals(4096L, Calls.target(target, new Object[]{0L}));
                    assertEquals(4096, effects.get(), "the failed OSR must not replay or abandon the old activation");
                    assertEquals(0, compiled.get()); assertSame(root, source.get());
                    var failure = root.graphFailure.get();
                    assertNotNull(failure, "a real OSR failure must be attributed to its original owner");
                    assertInstanceOf(BaseOSRRootNode.class, failure.target().getRootNode());
                    assertTrue(GraphRecovery.graphTooBig(failure.reason(), true, true));
                    assertFalse(failure.target().isSubmittedForCompilation()); assertFalse(failure.target().isValid());
                    assertEquals(0, root.getGraphBudgetGeneration());
                    assertEquals(4096L, Calls.target(target, new Object[]{0L}));
                    assertEquals(8192, effects.get(), "two complete invocations, not failed-call replay");
                    var fresh = (FunctionRoot) source.get();
                    assertNotSame(root, fresh); assertTrue(root.isSelf(fresh.getCallTarget()));
                    assertEquals(1, fresh.getGraphBudgetGeneration());
                    assertTrue(compiled.get() > 0, "the replacement must really execute OSR-compiled iterations");
                    assertSame(oldLoop, body.loop); assertEquals(0, root.getGraphBudgetGeneration());
                    var freshLoop = NodeUtil.findAllNodeInstances(fresh, LoopWork.class).getFirst().loop;
                    assertNotSame(oldLoop, freshLoop);
                    assertNotNull(((OptimizedOSRLoopNode) freshLoop).getCompiledOSRLoop());
                    System.out.println("STOCK AST OSR RECOVERY effects=" + effects.get() + " compiledIterations=" + compiled.get());
                } finally { context.leave(); }
            }
        }

        private static final class LoopWork extends Expr {
            @Child private LoopNode loop;
            private final int count;
            LoopWork(Expr work, int count, AtomicInteger compiled) {
                this.count = count; setRepresentation(LONG);
                loop = Truffle.getRuntime().createLoopNode(new WorkRepeater(work, count, compiled));
            }
            @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
            @Override public long executeLong(VirtualFrame frame) {
                frame.setLong(count, 0L); loop.execute(frame); return frame.getLong(count);
            }
        }
        private static final class WorkRepeater extends Node implements RepeatingNode {
            @Child private Expr work;
            private final int count;
            private final AtomicInteger compiled;
            WorkRepeater(Expr work, int count, AtomicInteger compiled) {
                this.work = work; this.count = count; this.compiled = compiled;
            }
            @Override public boolean executeRepeating(VirtualFrame frame) {
                try { work.executeLong(frame); }
                catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw new AssertionError(failure); }
                if (CompilerDirectives.inCompiledCode()) compiled.incrementAndGet();
                long next = frame.getLong(count) + 1; frame.setLong(count, next);
                return next < 4096;
            }
        }

        static void recoverAst(boolean twice) {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw")
                    .option("compiler.MaximumGraalGraphSize", "10000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var effects = new AtomicInteger(); var source = new AtomicReference<RootNode>();
                    long[] values = new long[4096];
                    for (int i = 0; i < values.length; i++) values[i] = i + 1;
                    Expr heavy = new AstSameFrameArm(new Heavy(values, effects, source));
                    if (twice) heavy = new Pair(heavy, new AstSameFrameArm(new Heavy(values, effects, source)));
                    var metrics = new Metrics(true);
                    var root = new FunctionRoot(language, new FrameLayout().build(), "stock recovery", null,
                            new int[0], new int[0], new int[0], heavy, metrics);
                    var target = (OptimizedCallTarget) root.getCallTarget();
                    var oldLoops = NodeUtil.findAllNodeInstances(root, OptimizedOSRLoopNode.class);
                    var failure = assertThrows(OptimizationFailedException.class, () -> target.compile(true));
                    assertSame(target, failure.getCallTarget());
                    assertTrue(failure.toString().contains("GraphTooBigBailoutException"));
                    assertFalse(target.isSubmittedForCompilation()); assertFalse(target.isValidLastTier());
                    assertFalse(target.canBeInlined()); assertEquals(0, effects.get());
                    assertEquals(0, root.getGraphBudgetGeneration(), "callback must not rewrite the old body");
                    long expected = 17;
                    for (int i = 0; i < values.length; i++) expected = (expected ^ values[i]) * 17 + i;
                    int perCall = twice ? 2 : 1;
                    if (twice) expected *= 2;
                    assertEquals(expected, Calls.target(target, new Object[]{0L}));
                    assertEquals(perCall, effects.get());
                    assertNotSame(root, source.get(), "the next fresh entry must run replacement-owned code");
                    var replacement = (FunctionRoot) source.get();
                    assertTrue(root.isSelf(replacement.getCallTarget())); assertEquals(root.mask, replacement.mask);
                    assertSame(root.getFrameDescriptor(), replacement.getFrameDescriptor());
                    assertEquals(oldLoops, NodeUtil.findAllNodeInstances(root, OptimizedOSRLoopNode.class));
                    assertEquals(0, root.getGraphBudgetGeneration());
                    assertEquals(1, replacement.getGraphBudgetGeneration());
                    var fresh = (OptimizedCallTarget) replacement.getCallTarget();
                    if (twice) {
                        var nextFailure = assertThrows(OptimizationFailedException.class, () ->
                                ((OptimizedCallTarget) source.get().getCallTarget()).compile(true));
                        assertSame(fresh, nextFailure.getCallTarget());
                        assertTrue(nextFailure.toString().contains("GraphTooBigBailoutException"));
                        assertFalse(fresh.isSubmittedForCompilation()); assertEquals(perCall, effects.get());
                        assertEquals(expected, Calls.target(target, new Object[]{0L}));
                        assertEquals(perCall * 2, effects.get());
                        assertNotSame(replacement, source.get());
                        replacement = (FunctionRoot) source.get();
                        assertEquals(2, replacement.getGraphBudgetGeneration());
                        fresh = (OptimizedCallTarget) replacement.getCallTarget();
                    }
                    assertTrue(fresh.compile(true)); assertTrue(fresh.isValidLastTier());
                    int beforeEffects = twice ? perCall * 2 : perCall;
                    assertEquals(beforeEffects, effects.get(), "explicit fresh compilation has no guest effects");
                    long before = metrics.getCompiledEntries();
                    assertEquals(expected, Calls.target(target, new Object[]{0L}));
                    assertEquals(beforeEffects + perCall, effects.get()); assertTrue(metrics.getCompiledEntries() > before);
                    assertSame(replacement, source.get());
                } finally { context.leave(); }
            }
        }

        private static final class Pair extends Expr {
            @Child private Expr left;
            @Child private Expr right;
            Pair(Expr left, Expr right) { this.left = left; this.right = right; setRepresentation(LONG); }
            @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
            @Override public long executeLong(VirtualFrame frame) {
                try { return left.executeLong(frame) + right.executeLong(frame); }
                catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw new AssertionError(failure); }
            }
        }

        private static final class Heavy extends Expr {
            private final long[] values;
            private final AtomicInteger effects;
            private final AtomicReference<RootNode> source;
            Heavy(long[] values, AtomicInteger effects, AtomicReference<RootNode> source) {
                this.values = values; this.effects = effects; this.source = source; setRepresentation(LONG);
            }
            @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
            @Override @ExplodeLoop public long executeLong(VirtualFrame frame) {
                effect();
                long result = 17;
                for (int i = 0; i < values.length; i++) result = (result ^ values[i]) * 17 + i;
                return result;
            }
            @CompilerDirectives.TruffleBoundary private void effect() {
                effects.incrementAndGet(); source.set(getRootNode());
            }
        }

        private static ContextRoot source(RootNode root) {
            if (root instanceof ContextRoot contextRoot) return contextRoot;
            if (root instanceof BaseOSRRootNode) {
                ContextRoot source = null;
                for (Node child : root.getChildren()) {
                    if (!(child.getRootNode() instanceof ContextRoot candidate)) continue;
                    if (source != null && source != candidate) return null;
                    source = candidate;
                }
                return source;
            }
            return null;
        }

        static void ownership(Context first, Context second, boolean bytecode) {
            var events = new CopyOnWriteArrayList<Event>();
            var runtime = (OptimizedTruffleRuntime) Truffle.getRuntime();
            var listener = new OptimizedTruffleRuntimeListener() {
                @Override public void onCompilationSuccess(OptimizedCallTarget target, AbstractCompilationTask task,
                        TruffleCompilerListener.GraphInfo graph, TruffleCompilerListener.CompilationResultInfo result) {
                    if (!(target.getRootNode() instanceof BaseOSRRootNode)) return;
                    ContextRoot root = source(target.getRootNode());
                    events.add(new Event(target, root, root == null ? null : root.compilationOwner()));
                }
            };
            runtime.addListener(listener);
            try {
                Object previousOwner = null;
                for (Context context : List.of(first, second)) {
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        Object owner = Language.currentState().getCompilationOwner();
                        assertNotSame(previousOwner, owner);
                        previousOwner = owner;
                        for (boolean shared : List.of(false, true)) {
                            ContextRoot root = bytecode ? bytecodeLoop(language) : new AstLoop(language);
                            if (shared) root.shareCompilationOwnership();
                            int before = events.size();
                            if (root instanceof AstLoop ast) {
                                ast.getCallTarget();
                                ast.loop.forceOSR();
                                assertEquals(0, ((Once) ast.loop.getRepeatingNode()).effects,
                                        "forced compilation must not execute the guest");
                            } else {
                                assertEquals(20000L, Calls.target(root.getCallTarget(), new Object[0]),
                                        "one real loop invocation, not a training call");
                            }
                            List<Event> actual = events.subList(before, events.size()).stream()
                                    .filter(event -> event.source == root).toList();
                            assertFalse(actual.isEmpty(), "an actual OSR compiler callback must identify the original root");
                            for (Event event : actual) {
                                assertSame(root, event.source);
                                assertSame(shared ? null : owner, event.owner);
                                assertFalse(event.target.isSubmittedForCompilation());
                            }
                            System.out.println("STOCK OSR OWNER " + (bytecode ? "bytecode" : "ast") +
                                    " shared=" + shared + " callbacks=" + actual.size());
                        }
                    } finally { context.leave(); }
                }
            } finally { runtime.removeListener(listener); }
        }

        private static BytecodeRoot bytecodeLoop(Language language) {
            return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                b.beginRoot(); b.beginBlock();
                var counter = b.createLocal("counter", null);
                b.beginStoreLocal(counter); b.emitLoadConstant(0L); b.endStoreLocal();
                b.beginWhile();
                b.beginLiteralBelow(20000L); b.emitLoadLocal(counter); b.endLiteralBelow();
                b.beginStoreLocal(counter); b.beginAdd(); b.emitLoadLocal(counter);
                b.emitLoadConstant(1L); b.endAdd(); b.endStoreLocal();
                b.endWhile();
                b.beginReturn(); b.emitLoadLocal(counter); b.endReturn();
                b.endBlock(); b.endRoot();
            }).getNode(0);
        }

        private static final class Once extends Node implements RepeatingNode {
            int effects;
            @Override public boolean executeRepeating(VirtualFrame frame) { effects++; return false; }
        }
        private static final class AstLoop extends ContextRoot {
            @Child OptimizedOSRLoopNode loop;
            AstLoop(Language language) {
                super(language, null);
                loop = (OptimizedOSRLoopNode) Truffle.getRuntime().createLoopNode(new Once());
            }
            @Override public Object execute(VirtualFrame frame) { loop.execute(frame); return 47L; }
        }
    }
}
