// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Listener-boundary classification controls; genuine installation failure evidence is separate. */
class GraphRecoverySizeFailureTest {
    @Test void acceptsOnlyExactPermanentSizeFailures() throws Exception {
        Truffle.getRuntime();
        Controls.classification();
    }

    @Test void sizeReceiptsKeepTheirOwnerAndSingleClaim() throws Exception {
        Truffle.getRuntime();
        Controls.ownership();
    }

    @Test void siblingFailureSurvivesAnUnextractableClaim() throws Exception {
        Truffle.getRuntime();
        Controls.siblingFailure(false);
    }

    @Test void laterUnextractableSiblingCannotDisplacePendingReduction() throws Exception {
        Truffle.getRuntime();
        Controls.siblingFailure(true);
    }

    private static final class Controls {
        private static final String CODE_SIZE =
                "jdk.vm.ci.code.BailoutException: Code installation failed: code is too large";
        private static final String GRAPH_SIZE =
                "jdk.graal.compiler.truffle.GraphTooBigBailoutException: " +
                "Graph too big to safely compile. Node count: 87. Graph Size: 101. Limit: 100.";

        private static OptimizedTruffleRuntimeListener listener(GraphRecovery service) throws Exception {
            var field = GraphRecovery.class.getDeclaredField("listener");
            field.setAccessible(true);
            return (OptimizedTruffleRuntimeListener) field.get(service);
        }
        private static ContextRoot root() {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            return new ContextRoot(language, FrameDescriptor.newBuilder().build()) {
                @Override public Object execute(VirtualFrame frame) {
                    throw new AssertionError("Compiler callbacks must not execute a guest body");
                }
            };
        }
        private static Engine engine() {
            return Engine.newBuilder().allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000").build();
        }

        private static RootCallTarget sideTarget(AstSameFrameArm arm) throws Exception {
            var field = AstSameFrameArm.class.getDeclaredField("targets");
            field.setAccessible(true);
            var targets = (RootCallTarget[]) field.get(arm);
            return targets == null ? null : targets[0];
        }

        private static final class FailureDuringCopy extends Expr {
            @Children private AstSameFrameArm[] arms;
            private final AtomicReference<Runnable> onCopy = new AtomicReference<>();
            private final AtomicInteger effects;
            private final AtomicReference<Node> executed;
            FailureDuringCopy(AstSameFrameArm[] arms, AtomicInteger effects, AtomicReference<Node> executed) {
                this.arms = arms; this.effects = effects; this.executed = executed;
                setRepresentation(arms[0].getRepresentation());
            }
            @Override public Node copy() {
                Node copy = super.copy();
                Runnable callback = onCopy.getAndSet(null);
                if (callback != null) callback.run();
                return copy;
            }
            @Override public Object execute(VirtualFrame frame) {
                effects.incrementAndGet(); executed.set(getRootNode());
                long result = 0;
                for (var arm : arms) result += (Long) arm.execute(frame);
                return result;
            }
        }

        static void siblingFailure(boolean laterUnextractable) throws Exception {
            try (var engine = engine(); var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var effects = new AtomicInteger(); var executed = new AtomicReference<Node>();
                    var first = new AstSameFrameArm(new Literal(1L));
                    var inner = new AstSameFrameArm(new Literal(2L));
                    var second = new AstSameFrameArm(inner);
                    var last = new AstSameFrameArm(new Literal(3L));
                    var body = new FailureDuringCopy(new AstSameFrameArm[]{first, second, last}, effects, executed);
                    var root = new FunctionRoot(language, new FrameLayout().build(), "sibling failures", null,
                            new int[0], new int[0], new int[0], body, new Metrics(true));
                    var target = (OptimizedCallTarget) root.getCallTarget();
                    target.prepareForAOT();
                    assertFalse(target.wasExecuted());
                    assertTrue(AstSameFrameArm.extract(first));
                    assertTrue(AstSameFrameArm.extract(second));
                    assertTrue(AstSameFrameArm.extract(last));
                    var firstTarget = (OptimizedCallTarget) sideTarget(first);
                    var secondTarget = (OptimizedCallTarget) sideTarget(second);
                    var lastTarget = (OptimizedCallTarget) sideTarget(last);
                    var listener = listener(Language.currentState().getGraphRecovery());
                    listener.onCompilationFailed(firstTarget, CODE_SIZE, true, true, 2, () -> "unused");
                    body.onCopy.set(() -> {
                        assertTrue(root.graphFailure.get().claimed());
                        assertSame(firstTarget, root.graphFailure.get().target());
                        assertEquals(0, effects.get());
                        listener.onCompilationFailed(secondTarget, CODE_SIZE, true, true, 2, () -> "unused");
                        if (laterUnextractable)
                            listener.onCompilationFailed(lastTarget, CODE_SIZE, true, true, 2, () -> "unused");
                    });
                    assertEquals(6L, Calls.target(target, new Object[]{0L}));
                    assertEquals(1, effects.get()); assertSame(root, executed.get());
                    assertEquals(0, root.getGraphBudgetGeneration());
                    assertEquals(6L, Calls.target(target, new Object[]{0L}));
                    assertEquals(2, effects.get());
                    assertNotSame(root, executed.get(), "the reducible sibling failure must survive the first claim");
                    var fresh = (FunctionRoot) executed.get();
                    assertEquals(1, fresh.getGraphBudgetGeneration());
                    assertEquals(0, root.getGraphBudgetGeneration());
                    assertSame(root.compilationOwner(), fresh.compilationOwner());
                    assertSame(secondTarget, sideTarget(second)); assertNull(sideTarget(inner));
                    int innerIndex = NodeUtil.findAllNodeInstances(root, AstSameFrameArm.class).indexOf(inner);
                    var copiedInner = NodeUtil.findAllNodeInstances(fresh, AstSameFrameArm.class).get(innerIndex);
                    assertNotNull(sideTarget(copiedInner), "recovery must reduce the failed sibling's nested arm");
                } finally { context.leave(); }
            }
        }

        static void classification() throws Exception {
            try (var engine = engine(); var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var root = root(); var target = (OptimizedCallTarget) root.getCallTarget();
                    var listener = listener(Language.currentState().getGraphRecovery());
                    for (String reason : new String[]{GRAPH_SIZE, CODE_SIZE}) {
                        root.graphFailure.set(null);
                        listener.onCompilationFailed(target, reason, true, true, 2, () -> "unused");
                        var receipt = root.graphFailure.get();
                        assertNotNull(receipt, reason);
                        assertSame(target, receipt.target()); assertEquals(reason, receipt.reason());
                        assertFalse(receipt.claimed()); assertFalse(target.isValid());
                    }
                    for (String reason : new String[]{null, "", "prefix " + CODE_SIZE, CODE_SIZE + " suffix",
                            CODE_SIZE + "\n", CODE_SIZE + "\r\n", " " + CODE_SIZE, CODE_SIZE + " ",
                            "java.lang.RuntimeException: " + CODE_SIZE,
                            "jdk.vm.ci.code.BailoutException:java.lang.RuntimeException:java.lang.Exception:\n" + CODE_SIZE,
                            "jdk.vm.ci.code.BailoutException: Code installation failed: code cache is full",
                            "jdk.vm.ci.code.BailoutException: Code installation failed: dependencies failed",
                            "jdk.vm.ci.code.BailoutException: Code installation failed: nmethod reclaimed",
                            "jdk.graal.compiler.core.common.RetryableBailoutException: Compilable not ready for compilation.",
                            "jdk.graal.compiler.core.common.RetryableBailoutException: Compilation cancelled.",
                            "jdk.graal.compiler.core.common.PermanentBailoutException: Compilation timeout.",
                            "prefix " + GRAPH_SIZE, GRAPH_SIZE + "\n"}) {
                        root.graphFailure.set(null);
                        listener.onCompilationFailed(target, reason, true, true, 2, () -> "unused");
                        assertNull(root.graphFailure.get(), reason);
                    }
                    for (String reason : new String[]{GRAPH_SIZE, CODE_SIZE}) {
                        for (boolean[] flags : new boolean[][]{{false, false}, {false, true}, {true, false}}) {
                            root.graphFailure.set(null);
                            listener.onCompilationFailed(target, reason, flags[0], flags[1], 2, () -> "unused");
                            assertNull(root.graphFailure.get(), reason + " bailout=" + flags[0] + " permanent=" + flags[1]);
                        }
                    }
                } finally { context.leave(); }
            }
        }

        static void ownership() throws Exception {
            try (var engine = engine();
                 var first = Context.newBuilder("thc").engine(engine).build();
                 var second = Context.newBuilder("thc").engine(engine).build()) {
                first.initialize("thc"); second.initialize("thc");
                ContextRoot root; OptimizedCallTarget target; GraphRecovery owner;
                OptimizedTruffleRuntimeListener owningListener;
                first.enter();
                try {
                    root = root(); target = (OptimizedCallTarget) root.getCallTarget();
                    owner = Language.currentState().getGraphRecovery(); owningListener = listener(owner);
                    var shared = root(); shared.shareCompilationOwnership();
                    owningListener.onCompilationFailed((OptimizedCallTarget) shared.getCallTarget(), CODE_SIZE,
                            true, true, 2, () -> "unused");
                    assertNull(shared.graphFailure.get());
                } finally { first.leave(); }
                second.enter();
                try {
                    var other = Language.currentState().getGraphRecovery();
                    listener(other).onCompilationFailed(target, CODE_SIZE, true, true, 2, () -> "unused");
                    assertNull(root.graphFailure.get());
                    // A compiler callback uses its captured owner, never the currently entered context.
                    owningListener.onCompilationFailed(target, CODE_SIZE, true, true, 2, () -> "unused");
                    assertNotNull(root.graphFailure.get());
                    assertNull(other.claim(root)); assertNull(owner.claim(root));
                } finally { second.leave(); }
                first.enter();
                try {
                    var claim = owner.claim(root); assertNotNull(claim); assertTrue(claim.claimed());
                    assertTrue(owner.publishable(root, claim)); assertNull(owner.claim(root));
                    owningListener.onCompilationFailed(target, GRAPH_SIZE, true, true, 2, () -> "unused");
                    assertSame(claim, root.graphFailure.get());
                    root.graphFailure.set(null); owner.close();
                    owningListener.onCompilationFailed(target, CODE_SIZE, true, true, 2, () -> "unused");
                    assertNull(root.graphFailure.get()); assertNull(owner.claim(root));
                    assertFalse(owner.publishable(root, claim));
                } finally { first.leave(); }
            }
        }
    }
}
