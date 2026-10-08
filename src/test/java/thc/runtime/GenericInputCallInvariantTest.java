// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

class GenericInputCallInvariantTest {
    @Test void delimitedTypedOverapplicationRetainsArgumentsAndWritesItsFreshAggregateResult() {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", "false").build();
             var context = Context.newBuilder("thc").engine(engine).build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var number = new CoreRepresentation(CoreKind.DOUBLE, true, true, List.of("DoubleRep"), null, null, null, null, null);
                var answer = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
                var aggregate = new CoreRepresentation(CoreKind.UNKNOWN, true, true, answer.getPrimReps(), List.of(answer), null, null, null, null);
                var operand = new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("DoubleRep", "IntRep"), List.of(number, answer), null, null, null, null);
                var shape = new TupleShape(aggregate, language);
                TupleShape producer;
                try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                    preparation.initialize("thc"); preparation.enter();
                    try { assertSame(language, TruffleLanguage.LanguageReference.create(Language.class).get(null)); producer = new TupleShape(aggregate, language); }
                    finally { preparation.leave(); }
                }
                assertTrue(shape.matches(producer)); assertNotSame(shape.getLayout(), producer.getLayout());
                var input = new TypedInputLayout(language, ArgumentLayout.fromProofs(List.of(operand)), false);
                for (boolean generic : new boolean[]{false, true}) {
                    int[] effects = new int[3];
                    var middle = new GuestRoot(language, new FrameLayout().build()) {
                        { configureEntry(new boolean[]{false}, false); configureInput(input.getLogical()); configureTypedInput(input); }
                        @Override public long bloom(VirtualFrame frame) { return 0L; }
                        @Override public Object execute(VirtualFrame frame) {
                            var carrier = input.take(frame.getArguments());
                            try { assertEquals(2.0, input.getPacket().getDouble(carrier, input.getHeader())); assertEquals(22L, input.getPacket().getLong(carrier, input.getHeader() + 1)); effects[1]++;
                                throw new DelimitedCut(new PromptTag(Language.currentState()), null, null, SynchronousMasking.current(this), this);
                            } finally { input.releaseChecked(carrier); }
                        }
                    };
                    var first = new GuestRoot(language, new FrameLayout().build()) {
                        { configureEntry(new boolean[]{false}, false); configureInput(input.getLogical()); configureTypedInput(input); }
                        @Override public long bloom(VirtualFrame frame) { return 0L; }
                        @Override public Object execute(VirtualFrame frame) {
                            var carrier = input.take(frame.getArguments());
                            try { assertEquals(1.0, input.getPacket().getDouble(carrier, input.getHeader())); assertEquals(11L, input.getPacket().getLong(carrier, input.getHeader() + 1)); effects[0]++;
                                return new Closure(null, 1, middle.getCallTarget());
                            } finally { input.releaseChecked(carrier); }
                        }
                    };
                    var layout = new FrameLayout();
                    int[] arguments = {layout.bind("first number"), layout.bind("first marker"), layout.bind("second number"), layout.bind("second marker"), layout.bind("remaining number"), layout.bind("remaining marker")};
                    int[] result = {layout.bind("answer")};
                    var source = new AstInputSource(ArgumentLayout.fromProofs(List.of(operand, operand, operand)), arguments);
                    var call = new Expr() {
                        @Child private InputDispatch cached = new InputDispatch(source, 3, false, new Metrics(false), new AstTupleDestination(shape, result, 0));
                        @Child private GenericInputCall indirect = new GenericInputCall(source, 3, false, new Metrics(false), new AstTupleDestination(shape, result, 0), 0);
                        { setRepresentation(aggregate); }
                        @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Tuple destination required"); }
                        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
                            FrameAccess.writeDouble(frame, arguments[0], 1.0); FrameAccess.writeLong(frame, arguments[1], 11L);
                            FrameAccess.writeDouble(frame, arguments[2], 2.0); FrameAccess.writeLong(frame, arguments[3], 22L);
                            FrameAccess.writeDouble(frame, arguments[4], 37.0); FrameAccess.writeLong(frame, arguments[5], 99L);
                            var closure = new Closure(null, 1, first.getCallTarget());
                            return generic ? indirect.execute(frame, closure, null) : cached.execute(frame, closure);
                        }
                    };
                    var body = new TupleCase(call, result, new LocalRead(result[0], false).proven(answer));
                    var root = new FunctionRoot(language, layout.build(), "typed delimited application", null, new int[0], new int[0], new int[0],
                        body, new Metrics(false), new CoreRepresentation[0], answer, null, new boolean[0], null,
                        null, new int[0], null, true, new int[0][], true, FunctionRootRole.FUNCTION, false);
                    var cut = assertThrows(DelimitedCut.class, () -> root.getCallTarget().call(0L));
                    var image = new DelimitedStack(cut, null);
                    var tail = new GuestRoot(language, new FrameLayout().build()) {
                        { configureTupleResult(producer); }
                        @Override public long bloom(VirtualFrame frame) { return 0L; }
                        @Override public Object execute(VirtualFrame frame) {
                            throw new DelimitedCut(new PromptTag(Language.currentState()), null, producer, SynchronousMasking.current(this), this);
                        }
                    };
                    var last = new GuestRoot(language, new FrameLayout().build()) {
                        { configureEntry(new boolean[]{false}, false); configureInput(input.getLogical()); configureTypedInput(input); configureTupleResult(producer); }
                        @Override public long bloom(VirtualFrame frame) { return 0L; }
                        @Override public Object execute(VirtualFrame frame) {
                            var carrier = input.take(frame.getArguments());
                            try { assertEquals(37.0, input.getPacket().getDouble(carrier, input.getHeader())); assertEquals(99L, input.getPacket().getLong(carrier, input.getHeader() + 1)); effects[2]++;
                                throw new TailCall(tail.getCallTarget(), new Object[]{0L});
                            } finally { input.releaseChecked(carrier); }
                        }
                    };
                    class Owner extends GuestRoot {
                        @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                        Owner() { super(language, new FrameLayout().build()); }
                        @Override public long bloom(VirtualFrame frame) { return 0L; }
                        @Override public Object execute(VirtualFrame frame) { return frame.getArguments()[1]; }
                        Object resume(DelimitedStack saved, Object value) {
                            var action = new Closure(null, new Object[]{value}, 1, getCallTarget());
                            return saved.resume(site, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()), action);
                        }
                    }
                    var owner = new Owner(); owner.getCallTarget();
                    for (long add : new long[]{5L, 13L}) {
                        var fresh = assertThrows(DelimitedCut.class, () -> owner.resume(image, new Closure(null, 1, last.getCallTarget())));
                        var suffix = new DelimitedStack(fresh, null);
                        var value = producer.getLayout().create(); producer.getLayout().setLong(value, 0, 37L + add);
                        assertEquals(37L + add, owner.resume(suffix, value));
                        assertEquals(37L + add, owner.resume(suffix, value), "Each fresh image owns its copied tuple destination");
                    }
                    assertArrayEquals(new int[]{1, 1, 2}, effects, "Copied images must not replay consumed calls");
                    var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences());
                    assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                    assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root));
                }
            } finally { context.leave(); }
        }
    }
    private static final class ReferenceAccess extends RootNode {
        private final boolean write;
        private final ScalarArrayInputSource source = new ScalarArrayInputSource(null);
        int entries, compiledEntries, argumentOrder, completed;
        ReferenceAccess(Language language, boolean write) { super(language); this.write = write; }
        private Object argument(VirtualFrame frame, int index) { argumentOrder = argumentOrder * 10 + index + 1; return frame.getArguments()[index]; }
        @Override public Object execute(VirtualFrame frame) {
            entries++; if (CompilerDirectives.inCompiledCode()) compiledEntries++;
            var values = (Object[]) argument(frame, 0); int index = (Integer) argument(frame, 1);
            Object result;
            if (write) { var value = argument(frame, 2); source.setReference(frame, this, values, index, value); result = value; }
            else result = source.reference(frame, this, values, index);
            completed++; return result;
        }
    }
    @FunctionalInterface private interface Action { void run(ReferenceAccess root, RootCallTarget target) throws ReflectiveOperationException; }
    private void coldReferenceAccess(boolean write, Action action) throws ReflectiveOperationException {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var root = new ReferenceAccess(language, write); var target = root.getCallTarget();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                // Restore the shared entry stub without a settling guest call.
                var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                assertEquals(0, root.entries); assertEquals(0, root.argumentOrder); action.run(root, target);
                assertSame(target, root.getCallTarget()); assertEquals(1, root.entries, "The operation must execute once, including failure paths");
                assertEquals(1, root.compiledEntries, "The first call must enter installed code"); assertEquals(write ? 123 : 12, root.argumentOrder);
            } finally { context.leave(); }
        }
    }
    @Test void scalarReferenceReadPreservesNullElementsAndIdentityOnItsFirstInstalledCall() throws ReflectiveOperationException {
        for (var value : Arrays.asList(new Object(), null)) coldReferenceAccess(false, (root, target) -> {
            var sentinel = new Object(); Object[] values = {sentinel, value};
            assertSame(value, Calls.target(target, new Object[]{values, 1})); assertSame(sentinel, values[0]); assertSame(value, values[1]); assertEquals(1, root.completed);
            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
        });
    }
    @Test void scalarReferenceWritePreservesNullElementsAndIdentityOnItsFirstInstalledCall() throws ReflectiveOperationException {
        for (var value : Arrays.asList(new Object(), null)) coldReferenceAccess(true, (root, target) -> {
            var sentinel = new Object(); Object[] values = {sentinel, new Object()};
            assertSame(value, Calls.target(target, new Object[]{values, 1, value})); assertSame(sentinel, values[0]); assertSame(value, values[1]); assertEquals(1, root.completed);
            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
        });
    }
    @Test void scalarReferenceNullArrayKeepsItsOriginalFailureAfterArgumentEvaluation() throws ReflectiveOperationException {
        for (boolean write : new boolean[]{false, true}) coldReferenceAccess(write, (root, target) -> {
            // Both operands are invalid: the null assertion precedes bounds checking.
            var failure = assertThrows(NullPointerException.class, () -> Calls.target(target, new Object[]{null, -1, new Object()}));
            assertEquals(NullPointerException.class, failure.getClass()); assertNull(failure.getMessage()); assertEquals(0, root.completed);
        });
    }
    @Test void scalarReferenceBoundsFailureLeavesTheArrayUntouchedOnItsFirstInstalledCall() throws ReflectiveOperationException {
        for (boolean write : new boolean[]{false, true}) for (int index : new int[]{-1, 2}) coldReferenceAccess(write, (root, target) -> {
            var sentinel = new Object(); Object[] values = {sentinel, null};
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> Calls.target(target, new Object[]{values, index, new Object()}));
            assertSame(sentinel, values[0]); assertNull(values[1]); assertEquals(0, root.completed);
        });
    }
    private Object dispatch(RootCallTarget target, Object[] values) {
        var call = new GenericInputCall(new ScalarArrayInputSource(null), 1, false, new Metrics(false), null, 0);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], new FrameLayout().build()); return call.execute(frame, new Closure(null, 1, target), values);
    }
    @Test void missingRootRetainsItsNullCastFailureBeforeInputPreparation() {
        var target = new RootCallTarget() {
            @Override public RootNode getRootNode() { return null; }
            @Override public Object call(Object... arguments) { throw new IllegalStateException("Invalid target reached dispatch"); }
        };
        Object[] values = {23L}; var failure = assertThrows(NullPointerException.class, () -> dispatch(target, values));
        assertEquals("null cannot be cast to non-null type thc.runtime.GuestRoot", failure.getMessage()); assertArrayEquals(new Object[]{23L}, values);
    }
    @Test void nonGuestRootRetainsItsClassCastFailureBeforeInputPreparation() {
        var target = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Non-guest target reached dispatch"); } }.getCallTarget();
        Object[] values = {23L}; assertThrows(ClassCastException.class, () -> dispatch(target, values)); assertArrayEquals(new Object[]{23L}, values);
    }
    private <T extends Throwable> T preparationFailure(RootNode root, Class<T> type) {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var fields = List.of(new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null),
                    new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null));
                var tuple = new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("IntRep", "BoxedRep (Just Lifted)"), fields, null, null, null, null);
                var input = new TypedInputLayout(language, Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(tuple))), false);
                int[] reads = {0}; var target = new RootCallTarget() {
                    @Override public RootNode getRootNode() { reads[0]++; return root; }
                    @Override public Object call(Object... arguments) { throw new IllegalStateException("Invalid target reached dispatch"); }
                };
                var marker = new Object(); var layout = new FrameLayout(); int[] slots = {layout.bind("number"), layout.bind("reference")};
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build()); FrameAccess.writeLong(frame, slots[0], 23L); TypedInputs.writeInputReference(frame, slots[1], marker);
                var source = new AstInputSource(input.getLogical(), slots); var node = new Node() {};
                var failure = assertThrows(type, () -> GenericTypedInputs.prepareGenericInput(frame, node, new Closure(null, 1, target), input, source, null, 1, 0, 1, new Force(new Metrics(false))));
                assertEquals(1, reads[0]); assertEquals(23L, FrameAccess.read(frame, slots[0])); assertSame(marker, FrameAccess.read(frame, slots[1]));
                var state = language.getHandoffState().get(); assertNull(state.getPending());
                assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
                return failure;
            } finally { context.leave(); }
        }
    }
    @Test void missingRootFailsBeforeGenericPreparationAcquiresALoan() {
        var failure = preparationFailure(null, NullPointerException.class); assertEquals("null cannot be cast to non-null type thc.runtime.GuestRoot", failure.getMessage());
    }
    @Test void nonGuestRootFailsBeforeGenericPreparationAcquiresALoan() {
        var root = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Non-guest target reached dispatch"); } };
        preparationFailure(root, ClassCastException.class);
    }
    @Test void invalidTypedTargetLeavesExistingLoansUntouchedBeforeTheAction() {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var field = new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
                var tuple = new CoreRepresentation(CoreKind.UNKNOWN, true, true, field.getPrimReps(), List.of(field), null, null, null, null);
                var layout = new TypedInputLayout(language, Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(tuple))), false);
                var nonGuest = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Non-guest target reached dispatch"); } };
                var untyped = new GuestRoot(language, new FrameLayout().build()) {
                    @Override public long bloom(VirtualFrame frame) { return 0L; }
                    @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Untyped target reached dispatch"); }
                };
                var roots = Arrays.asList(null, nonGuest, untyped); var failures = List.of(NullPointerException.class, ClassCastException.class, RuntimeFault.class); var state = language.getHandoffState().get();
                for (int i = 0; i < Math.min(roots.size(), failures.size()); i++) {
                    var root = roots.get(i); var type = failures.get(i); int[] reads = {0};
                    var target = new RootCallTarget() {
                        @Override public RootNode getRootNode() { reads[0]++; return root; }
                        @Override public Object call(Object... arguments) { throw new IllegalStateException("Invalid target reached dispatch"); }
                    };
                    var input = state.getArguments().acquire(layout.getPacket()); input.setInputMode(1); var output = state.getResults().acquire(layout.getPacket()); long generation = input.getGeneration();
                    var marker = new Object(); var outputMarker = new Object(); layout.getPacket().setObject(input, layout.getHeader(), marker); layout.getPacket().setObject(output, layout.getHeader(), outputMarker);
                    var failure = assertThrows(type, () -> TypedInputs.invokeTypedInput(target, input, packet -> { throw new IllegalStateException("Invalid target reached the action"); }));
                    if (root == null) assertEquals("null cannot be cast to non-null type thc.runtime.GuestRoot", failure.getMessage());
                    if (root == untyped) assertEquals("Target has no typed input entry", failure.getMessage());
                    assertEquals(1, reads[0]); assertTrue(input.getLive()); assertEquals(1, input.getInputMode()); assertEquals(generation, input.getGeneration()); assertSame(marker, layout.getPacket().getObject(input, layout.getHeader()));
                    assertTrue(output.getLive()); assertSame(outputMarker, layout.getPacket().getObject(output, layout.getHeader())); assertEquals(1, state.getArguments().getDepth()); assertEquals(1, state.getResults().getDepth()); assertNull(state.getPending());
                    layout.release(input); state.getResults().release(output, layout.getPacket());
                    assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
                }
            } finally { context.leave(); }
        }
    }
}
