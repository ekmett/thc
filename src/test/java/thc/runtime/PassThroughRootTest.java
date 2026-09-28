// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

class PassThroughRootTest {
    private final CoreRepresentation longProof = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
    private final CoreRepresentation intProof = new CoreRepresentation(CoreKind.LONG, true, true, List.of("Int8Rep"), null, null, null, null, null);
    @FunctionalInterface private interface Action { void run(Language language) throws ReflectiveOperationException; }
    private void withLanguage(Action action) throws ReflectiveOperationException {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private void compile(RootCallTarget target) throws ReflectiveOperationException {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
    }
    private static final class Value extends Expr {
        private final Object value;
        Value(Object value, CoreRepresentation proof) { this.value = value; setRepresentation(proof); }
        @Override public Object execute(VirtualFrame frame) { return value; }
    }
    private static final class Function extends Expr {
        Closure value;
        Function() { setRepresentation(new CoreRepresentation(CoreKind.CLOSURE, true, false, null, null, null, null, null, null)); }
        @Override public Object execute(VirtualFrame frame) { return Objects.requireNonNull(value); }
        @Override public Closure executeClosure(VirtualFrame frame) { return Objects.requireNonNull(value); }
    }
    @Test void firstCompiledEntryPreservesExactScalarCarriersAndCloneRole() throws ReflectiveOperationException {
        withLanguage(language -> {
            var floatProof = new CoreRepresentation(CoreKind.FLOAT, true, true, List.of("FloatRep"), null, null, null, null, null);
            var doubleProof = new CoreRepresentation(CoreKind.DOUBLE, true, true, List.of("DoubleRep"), null, null, null, null, null);
            var proofs = List.of(intProof, longProof, floatProof, doubleProof);
            Object[] values = {-128, Long.MIN_VALUE, Float.intBitsToFloat(0x80000000), Double.longBitsToDouble(0x7ff8000000000001L)};
            for (int i = 0; i < proofs.size(); i++) {
                var proof = proofs.get(i); var value = values[i]; var layout = new FrameLayout(); int slot = layout.bind("argument"); var metrics = new Metrics(true);
                var body = new Expr() {
                    { setRepresentation(proof); }
                    @Override public Object execute(VirtualFrame frame) { return FrameAccess.read(frame, slot); }
                    @Override public int executeInt(VirtualFrame frame) { return frame.getInt(slot); }
                    @Override public long executeLong(VirtualFrame frame) { return frame.getLong(slot); }
                    @Override public float executeFloat(VirtualFrame frame) { return frame.getFloat(slot); }
                    @Override public double executeDouble(VirtualFrame frame) { return frame.getDouble(slot); }
                };
                var root = new FunctionRoot(language, layout.build(), "pass scalar", null, new int[0], new int[]{slot}, new int[]{0}, body, metrics, new CoreRepresentation[]{proof}, proof, body.getCoreSourceLocation(),
                    new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.PASS_THROUGH, false);
                var target = root.getCallTarget(); compile(target); assertEquals(0L, metrics.getCompiledEntries()); var result = Calls.target(target, new Object[]{0L, value});
                assertEquals(value.getClass(), Objects.requireNonNull(result).getClass());
                if (value instanceof Float number) assertEquals(Float.floatToRawIntBits(number), Float.floatToRawIntBits((Float) result));
                else if (value instanceof Double number) assertEquals(Double.doubleToRawLongBits(number), Double.doubleToRawLongBits((Double) result)); else assertEquals(value, result);
                assertEquals(1L, metrics.getCompiledEntries()); assertSame(target, root.getCallTarget()); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                var clone = NodeUtil.cloneNode(root); assertEquals(FunctionRootRole.PASS_THROUGH, clone.getRole()); var clonedTarget = clone.getCallTarget(); compile(clonedTarget);
                long before = metrics.getCompiledEntries(); Calls.target(clonedTarget, new Object[]{0L, value}); assertEquals(before + 1, metrics.getCompiledEntries());
                assertEquals(true, clonedTarget.getClass().getMethod("isValidLastTier").invoke(clonedTarget));
            }
        });
    }
    @Test void explicitSelfTailAndDenseTailEscapeUnchangedWithoutInterceptionOrLoanConsumption() throws ReflectiveOperationException {
        withLanguage(language -> {
            var metrics = new Metrics(true);
            var body = new Expr() {
                ControlFlowException transfer; int entries;
                @Override public Object execute(VirtualFrame frame) { entries++; if (entries > 1) throw new IllegalStateException("Side root must not repeat"); throw Objects.requireNonNull(transfer); }
            };
            var root = new FunctionRoot(language, new FrameLayout().build(), "pass self", null, new int[0], new int[0], new int[0], body, metrics, new CoreRepresentation[0], body.getRepresentation(), body.getCoreSourceLocation(),
                new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.PASS_THROUGH, false);
            var target = root.getCallTarget(); Object[] packet = {17L, new Object()}; var tail = new TailCall(target, packet); body.transfer = tail;
            assertSame(tail, assertThrows(TailCall.class, () -> Calls.target(target, new Object[]{0L}))); assertSame(packet, tail.getArgs()); assertEquals(17L, packet[0]); assertEquals(1, body.entries); assertEquals(0L, metrics.getSelfTailReentries());
            var layout = new HandoffLayout(language, 0, List.of("long", "reference")); var state = language.getHandoffState().get(); var input = state.getArguments().acquire(layout);
            var marker = new Object(); layout.setObject(input, 1, marker); long generation = input.getGeneration();
            try {
                var dense = new HandoffTailCall(target, input); body.transfer = dense; body.entries = 0;
                assertSame(dense, assertThrows(HandoffTailCall.class, () -> Calls.target(target, new Object[]{0L}))); assertEquals(1, body.entries); assertTrue(input.getLive()); assertEquals(generation, input.getGeneration());
                assertSame(marker, layout.getObject(input, 1)); assertEquals(1, state.getArguments().getDepth());
            } finally { state.getArguments().release(input, layout); }
            assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0L, metrics.getTrampolineIterations());
        });
    }
    @Test void inheritedBloomBackedgeCrossesSideRootAndClosesAtOuterOwner() throws ReflectiveOperationException {
        withLanguage(language -> {
            var outerLayout = new FrameLayout(); int n = outerLayout.bind("n"); var sideLayout = new FrameLayout(); int sideN = sideLayout.bind("n");
            var outerMetrics = new Metrics(true); var sideMetrics = new Metrics(true); FunctionRoot[] outer = {null};
            var sideBody = new Expr() {
                @Child private TailCheck tail = new TailCheck(sideMetrics); int entries; boolean ancestryPreserved = true;
                { setRepresentation(longProof); }
                @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                @Override public long executeLong(VirtualFrame frame) {
                    entries++; long inherited = ((GuestRoot) getRootNode()).bloom(frame); ancestryPreserved = ancestryPreserved && (inherited & outer[0].mask) == outer[0].mask;
                    long remaining = frame.getLong(sideN); if (remaining == 0L) return 73L; tail.check(frame, outer[0].getCallTarget(), new Object[]{0L, remaining - 1}); throw new IllegalStateException("Inherited outer Bloom must bounce");
                }
            };
            var side = new FunctionRoot(language, sideLayout.build(), "D", null, new int[0], new int[]{sideN}, new int[]{0}, sideBody, sideMetrics, new CoreRepresentation[]{longProof}, longProof, sideBody.getCoreSourceLocation(),
                new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.PASS_THROUGH, false);
            var outerBody = new Expr() {
                @Child private DirectCallNode call = DirectCallNode.create(side.getCallTarget()); int entries;
                { setRepresentation(longProof); }
                @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                @Override public long executeLong(VirtualFrame frame) {
                    entries++; // An outlined arm inherits ancestry; this is NOT call(false).
                    return (Long) Calls.direct(call, new Object[]{((GuestRoot) getRootNode()).bloom(frame), frame.getLong(n)});
                }
            };
            outer[0] = new FunctionRoot(language, outerLayout.build(), "A", null, new int[0], new int[]{n}, new int[]{0}, outerBody, outerMetrics, new CoreRepresentation[]{longProof}, longProof, outerBody.getCoreSourceLocation(),
                new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, false);
            assertEquals(73L, Calls.target(outer[0].getCallTarget(), new Object[]{0L, 4L})); assertEquals(5, outerBody.entries); assertEquals(5, sideBody.entries); assertTrue(sideBody.ancestryPreserved);
            assertEquals(4L, outerMetrics.getSelfTailReentries()); assertEquals(0L, sideMetrics.getSelfTailReentries()); assertEquals(0L, outerMetrics.getTrampolineIterations()); assertEquals(0L, sideMetrics.getTrampolineIterations());
        });
    }
    @Test void scalarSelfShortcutCannotMutateSideFrameOrEmitTargetlessAstSelfCall() throws ReflectiveOperationException {
        withLanguage(language -> {
            var layout = new FrameLayout(); int slot = layout.bind("argument"), temporary = layout.bind("temporary"); var function = new Function();
            var self = new AstSelfLayout(null, new int[0], new int[]{slot}, new CoreRepresentation[]{longProof}, new boolean[]{false});
            var app = new AstTailApplication(function, new Expr[]{new Value(22L, longProof)}, self, new int[]{temporary}, new Metrics(false)); Object[] originalArgument = {null};
            var body = new Expr() { @Child private AstTailApplication child = app; @Override public Object execute(VirtualFrame frame) { try { return child.execute(frame); } finally { originalArgument[0] = FrameAccess.read(frame, slot); } } };
            var root = new FunctionRoot(language, layout.build(), "scalar self side", null, new int[0], new int[]{slot}, new int[]{0}, body, new Metrics(false), new CoreRepresentation[]{longProof}, longProof, body.getCoreSourceLocation(),
                new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.PASS_THROUGH, false);
            function.value = new Closure(null, 1, root.getCallTarget()); var transfer = assertThrows(TailCall.class, () -> Calls.target(root.getCallTarget(), new Object[]{0L, 11L}));
            assertSame(root.getCallTarget(), transfer.getTarget()); assertEquals(22L, transfer.getArgs()[1]); assertEquals(11L, originalArgument[0]);
        });
    }
    @Test void typedSelfShortcutUsesOwnedExplicitPacketWithoutMutatingSideFrame() throws ReflectiveOperationException {
        withLanguage(language -> {
            var layout = new FrameLayout(); int slot = layout.bind("argument"); var function = new Function(); var input = Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(intProof)));
            var app = new AstTypedApplication(function, new Expr[]{new Value(22, intProof)}, layout, true, new Metrics(false), null, true); Object[] originalArgument = {null};
            var body = new Expr() { @Child private AstTypedApplication child = app; @Override public Object execute(VirtualFrame frame) { try { return child.execute(frame); } finally { originalArgument[0] = FrameAccess.read(frame, slot); } } };
            var root = new FunctionRoot(language, layout.build(), "typed self side", null, new int[0], new int[]{slot}, new int[]{0}, body, new Metrics(false), new CoreRepresentation[]{intProof}, intProof, body.getCoreSourceLocation(),
                new boolean[0], null, null, new int[0], input, false, new int[0][], false, FunctionRootRole.PASS_THROUGH, false);
            var typed = Objects.requireNonNull(TypedInputLayout.create(language, input, false)); root.configureTypedInput(typed); function.value = new Closure(null, 1, root.getCallTarget());
            var incoming = typed.getPacket().create(); incoming.setInputMode(2); typed.getPacket().setLong(incoming, 0, 0); typed.getPacket().setInt(incoming, 1, 11);
            var transfer = assertThrows(TailCall.class, () -> Calls.target(root.getCallTarget(), new Object[]{incoming})); assertSame(root.getCallTarget(), transfer.getTarget()); assertEquals(11, originalArgument[0]); assertEquals(0, incoming.getInputMode());
            var outgoing = Objects.requireNonNull(transfer.getInput());
            try { assertEquals(22, typed.getPacket().getInt(outgoing, 1)); assertTrue(outgoing.getInputMode() >= 1 && outgoing.getInputMode() <= 3); } finally { TypedInputs.discardTypedInput(language, outgoing); }
            var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        });
    }
    @Test void ordinaryNontailCallInsideSideBodyRetainsItsOwnReturningContinuation() throws ReflectiveOperationException {
        withLanguage(language -> {
            var metrics = new Metrics(true); var result = new FunctionRoot(language, new FrameLayout().build(), "ordinary tail destination", null, new int[0], new int[0], new int[0], new Value(41L, longProof), metrics);
            var childBody = new Expr() { @Override public Object execute(VirtualFrame frame) { assertEquals(0L, frame.getArguments()[0], "Only the ordinary non-tail call resets ancestry"); throw new TailCall(result.getCallTarget(), new Object[]{0L}); } };
            var child = new FunctionRoot(language, new FrameLayout().build(), "ordinary child", null, new int[0], new int[0], new int[0], childBody, metrics);
            var body = new Expr() {
                @Child private DirectCallerNode caller = new DirectCallerNode(child.getCallTarget(), metrics);
                { setRepresentation(longProof); }
                @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                @Override public long executeLong(VirtualFrame frame) {
                    long before = ((GuestRoot) getRootNode()).bloom(frame); long answer = (Long) caller.call(frame, new Object[]{0L}, false);
                    assertEquals(before, ((GuestRoot) getRootNode()).bloom(frame)); return answer + 1;
                }
            };
            var side = new FunctionRoot(language, new FrameLayout().build(), "returning side", null, new int[0], new int[0], new int[0], body, metrics, new CoreRepresentation[0], body.getRepresentation(), body.getCoreSourceLocation(),
                new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.PASS_THROUGH, false);
            assertEquals(42L, Calls.target(side.getCallTarget(), new Object[]{0x12345678L})); assertEquals(1L, metrics.getTrampolineIterations());
        });
    }
}
