// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Adversarial ownership controls using the production completion implementation. */
class TupleCompletionTest {
    private static boolean valid(RootCallTarget target) throws Exception { return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private static void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target));
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private static void withLanguage(Action action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private static TupleShape shape(Language language) {
        var leaves = List.of(new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null),
            new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null));
        return shape(language, leaves);
    }
    private static TupleShape shape(Language language, List<CoreRepresentation> leaves) {
        var reps = new ArrayList<String>();
        for (var leaf : leaves) reps.addAll(leaf.getPrimReps());
        return new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, true, true, reps, leaves, null, null, null, null), language);
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable, T> T rethrow(Throwable failure) throws E { throw (E) failure; }
    private static final class Slots {
        final FrameLayout layout = new FrameLayout();
        @CompilationFinal(dimensions = 1) final int[] fields = {layout.bind("integer"), layout.bind("lazy pointer")};
    }
    private static final class Producer extends GuestRoot {
        final TupleShape shape;
        private final Slots slots;
        private final RootCallTarget fallback;
        private final CaptureLayout environment;
        private final boolean capturesFrame;
        @Child private IndirectCallNode residual = IndirectCallNode.create();
        Producer(Language language, TupleShape shape) { this(language, shape, new Slots(), null, null, false); }
        Producer(Language language, TupleShape shape, Slots slots, RootCallTarget fallback, CaptureLayout environment, boolean capturesFrame) {
            super(language, slots.layout.build()); this.shape = shape; this.slots = slots; this.fallback = fallback;
            this.environment = environment; this.capturesFrame = capturesFrame;
            configureTupleResult(shape); configureEntry(new boolean[]{false, false}, environment != null);
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public String getName() { return "tuple ownership producer"; }
        @Override public Object execute(VirtualFrame frame) {
            int offset = environment == null ? 1 : 2;
            long value = (Long) frame.getArguments()[offset] + (environment == null ? 0L : environment.readLong((CapturedFrame) frame.getArguments()[1], 0));
            if (fallback != null && value < 0) {
                shape.consume(frame, Calls.indirect(residual, fallback, new Object[]{0L, value, frame.getArguments()[2]}), slots.fields, 0);
            } else {
                FrameAccess.writeLong(frame, slots.fields[0], value + 17L);
                FrameAccess.write(frame, slots.fields[1], frame.getArguments()[offset + 1]);
            }
            return shape.finish(frame, slots.fields, capturesFrame);
        }
    }
    private static final class Consumer extends RootNode {
        private final TupleShape shape;
        private final boolean barrier;
        private final Slots slots;
        @Child private DirectCallNode call;
        Consumer(Language language, TupleShape shape, RootCallTarget target, boolean barrier) { this(language, shape, target, barrier, new Slots()); }
        Consumer(Language language, TupleShape shape, RootCallTarget target, boolean barrier, Slots slots) {
            super(language, slots.layout.build()); this.shape = shape; this.barrier = barrier; this.slots = slots;
            call = DirectCallNode.create(target); call.forceInlining();
        }
        @Override public String getName() { return "tuple ownership consumer"; }
        @Override public Object execute(VirtualFrame frame) {
            long value = (Long) frame.getArguments()[0];
            var result = Calls.direct(call, new Object[]{0L, value, frame.getArguments()[1]});
            if (barrier && Barrier.observe()) CompilerDirectives.transferToInterpreterAndInvalidate();
            if (CompilerDirectives.inInterpreter() && result instanceof HandoffStorage) Barrier.materialized++;
            shape.consume(frame, result, slots.fields, 0);
            try {
                if (frame.getLong(slots.fields[0]) != value + 17L) throw new IllegalStateException("Check failed.");
                return frame.getObject(slots.fields[1]);
            } catch (FrameSlotTypeException failure) { return TupleCompletionTest.<RuntimeException, Object>rethrow(failure); }
        }
    }
    private static final class DispatchConsumer extends RootNode {
        private final Slots slots;
        @Child private TupleDispatch dispatch;
        DispatchConsumer(Language language, TupleShape shape) { this(language, shape, new Slots()); }
        DispatchConsumer(Language language, TupleShape shape, Slots slots) {
            super(language, slots.layout.build()); this.slots = slots;
            dispatch = new TupleDispatch(new TupleDestination(shape) {
                @Override public void consume(VirtualFrame frame, Node node, Object result) { shape.consume(frame, result, slots.fields, 0); }
            }, new Metrics(false), 1, false);
        }
        @Override public Object execute(VirtualFrame frame) {
            dispatch.execute(frame, (Closure) frame.getArguments()[0], new Object[]{frame.getArguments()[1]});
            try {
                if (frame.getLong(slots.fields[0]) != (Long) frame.getArguments()[2]) throw new IllegalStateException("Check failed.");
                return frame.getObject(slots.fields[1]);
            } catch (FrameSlotTypeException failure) { return TupleCompletionTest.<RuntimeException, Object>rethrow(failure); }
        }
    }
    private static final class Barrier {
        static volatile boolean requested;
        static volatile long observations;
        static long materialized;
        @CompilerDirectives.TruffleBoundary static boolean observe() { observations++; return requested; }
    }
    private static void released(Language language) {
        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
        assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
    }
    @Test void ownedResultsKeepDynamicWidthsAndExactFieldsInCompiledCode() throws Exception {
        withLanguage(language -> {
            var leaves = new ArrayList<CoreRepresentation>();
            var kinds = List.of(CoreKind.LONG, CoreKind.LONG, CoreKind.FLOAT, CoreKind.DOUBLE, CoreKind.ADDRESS, CoreKind.OBJECT);
            var reps = List.of("Word8Rep", "IntRep", "FloatRep", "DoubleRep", "AddrRep", "BoxedRep (Just Lifted)");
            for (int i = 0; i < reps.size(); i++)
                leaves.add(new CoreRepresentation(kinds.get(i), true, true, List.of(reps.get(i)), null, null, null, null, null));
            var mixed = shape(language, leaves);
            var shapes = List.of(shape(language, List.of()), mixed, shape(language));
            var address = ManagedAddress.fromByteArray(new byte[]{11}); var reference = new Object();
            Object[] values = {255, Long.MIN_VALUE, Float.intBitsToFloat(0x7fc01234), Double.longBitsToDouble(0x7ff8000000001234L), address, reference};
            var sources = new ArrayList<HandoffStorage>();
            for (var current : shapes) {
                var layout = current.getLayout(); var source = layout.create();
                if (current == mixed) layout.copyIn(source, values);
                else if (current.getWidth() != 0) layout.copyIn(source, new Object[]{17L, reference});
                sources.add(source);
            }
            var copier = new RootNode(language) {
                int dynamicEntries;
                int compiledCopies;
                @Override public Object execute(VirtualFrame frame) {
                    var current = (TupleShape) frame.getArguments()[0];
                    if (CompilerDirectives.inCompiledCode() && !CompilerDirectives.isPartialEvaluationConstant(current.getWidth())) dynamicEntries++;
                    // The existing pooled-release loop requires a fixed layout;
                    // this control isolates the genuinely dynamic fresh carrier.
                    var owned = TupleResults.ownedTupleResult((HandoffStorage) frame.getArguments()[1], current);
                    if (CompilerDirectives.inCompiledCode()) compiledCopies++;
                    return owned;
                }
            };
            var target = copier.getCallTarget();
            target.getClass().getMethod("ensureInitialized").invoke(target);
            ThreadInventoryCoreEvidence.install(List.of(target));
            for (int n = 0; n < shapes.size(); n++) {
                var current = shapes.get(n); var layout = current.getLayout(); var source = sources.get(n);
                int entries = copier.dynamicEntries, copies = copier.compiledCopies;
                var owned = (HandoffStorage) target.call(current, source);
                assertEquals(entries + 1, copier.dynamicEntries, "The compiled copy must accept a genuinely dynamic width");
                assertEquals(copies + 1, copier.compiledCopies); assertNotSame(source, owned); assertSame(layout, owned.getLayout());
                for (int i = 0; i < current.getWidth(); i++) {
                    if (layout.isInt(i)) assertEquals(layout.getInt(source, i), layout.getInt(owned, i));
                    else if (layout.isLong(i)) assertEquals(layout.getLong(source, i), layout.getLong(owned, i));
                    else if (layout.isFloat(i)) assertEquals(Float.floatToRawIntBits(layout.getFloat(source, i)), Float.floatToRawIntBits(layout.getFloat(owned, i)));
                    else if (layout.isDouble(i)) assertEquals(Double.doubleToRawLongBits(layout.getDouble(source, i)), Double.doubleToRawLongBits(layout.getDouble(owned, i)));
                    else assertSame(layout.getObject(source, i), layout.getObject(owned, i));
                }
                released(language);
            }
        });
    }
    @Test void invalidBytecodeConsumerRootFailsBeforeResultConsumptionOrLocalWrites() throws Exception {
        withLanguage(language -> {
            var shape = shape(language); var locals = new ArrayList<LocalAccessor>();
            var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                b.beginRoot();
                for (int i = 0; i < 2; i++) locals.add(LocalAccessor.constantOf(b.createLocal("tuple field " + i, null)));
                b.beginReturn(); b.emitLoadConstant(0L); b.endReturn(); b.endRoot();
            }).getNode(0);
            var destination = new BytecodeTupleSlots(shape, locals.toArray(LocalAccessor[]::new));
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[]{0L}, root.getFrameDescriptor());
            var nonBytecodeRoot = new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Invalid consumer root reached guest code"); }
            };
            var state = language.getHandoffState().get();
            var input = state.getArguments().acquire(shape.getLayout()); var inputMarker = new Object();
            shape.getLayout().setObject(input, 1, inputMarker);
            for (var node : List.of(new Node() {}, nonBytecodeRoot)) {
                var marker = new Object();
                locals.get(0).setLong(root.getBytecodeNode(), frame, 11L); locals.get(1).setObject(root.getBytecodeNode(), frame, marker);
                var output = state.getResults().acquire(shape.getLayout()); var outputMarker = new Object();
                shape.getLayout().setLong(output, 0, 23L); shape.getLayout().setObject(output, 1, outputMarker);
                var token = state.getResults().complete(output); long generation = output.getGeneration();
                var fresh = shape.getLayout().create(); var freshMarker = new Object();
                shape.getLayout().setLong(fresh, 0, 37L); shape.getLayout().setObject(fresh, 1, freshMarker);
                Class<? extends RuntimeException> type = node == nonBytecodeRoot ? ClassCastException.class : NullPointerException.class;
                // Root admission precedes token/pool lookup and even malformed-carrier validation.
                for (var result : Arrays.asList(token, fresh, null)) {
                    var failure = assertThrows(type, () -> destination.consume(frame, node, result));
                    if (node != nonBytecodeRoot) assertEquals("null cannot be cast to non-null type thc.runtime.BytecodeRoot", failure.getMessage());
                    assertEquals(11L, locals.get(0).getLong(root.getBytecodeNode(), frame));
                    assertSame(marker, locals.get(1).getObject(root.getBytecodeNode(), frame));
                    assertSame(output, state.getResults().completed()); assertTrue(output.getLive());
                    assertEquals(generation, output.getGeneration()); assertEquals(generation, output.getCompletedGeneration());
                    assertEquals(1, state.getResults().getDepth()); assertSame(outputMarker, shape.getLayout().getObject(output, 1));
                    assertEquals(1, state.getArguments().getDepth()); assertTrue(input.getLive());
                    assertSame(inputMarker, shape.getLayout().getObject(input, 1)); assertNull(state.getPending());
                }
                destination.consume(frame, root, token);
                assertEquals(23L, locals.get(0).getLong(root.getBytecodeNode(), frame));
                assertSame(outputMarker, locals.get(1).getObject(root.getBytecodeNode(), frame)); released(language);
                destination.consume(frame, root, fresh);
                assertEquals(37L, locals.get(0).getLong(root.getBytecodeNode(), frame));
                assertSame(freshMarker, locals.get(1).getObject(root.getBytecodeNode(), frame)); released(language);
            }
            state.getArguments().release(input, shape.getLayout());
            assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        });
    }
    @Test void asyncTupleCarrierStaysVirtualUntilItsCompiledCaptureOwnsIt() throws Exception {
        withLanguage(language -> {
            var shape = shape(language);
            var producer = new Producer(language, shape, new Slots(), null, null, true).getCallTarget();
            var slots = new Slots();
            var consumer = new RootNode(language, slots.layout.build()) {
                @Child private DirectCallNode call = DirectCallNode.create(producer);
                { call.forceInlining(); }
                int compiledCaptures;
                @Override public Object execute(VirtualFrame frame) {
                    var result = Calls.direct(call, new Object[]{0L, frame.getArguments()[0], frame.getArguments()[1]});
                    if (CompilerDirectives.inCompiledCode()) CompilerDirectives.ensureVirtualizedHere(result);
                    if (Boolean.TRUE.equals(frame.getArguments()[2])) {
                        if (CompilerDirectives.inCompiledCode()) compiledCaptures++;
                        // Interpreter tuple completion owns a pool loan; a compiled
                        // inline carrier can become the captured value directly.
                        return result == TupleComplete.INSTANCE ? TupleResults.ownedTupleResult(result, shape) : result;
                    }
                    shape.consume(frame, result, slots.fields, 0);
                    try { return frame.getObject(slots.fields[1]); }
                    catch (FrameSlotTypeException failure) { return TupleCompletionTest.<RuntimeException, Object>rethrow(failure); }
                }
            };
            var pointer = new Object();
            for (int i = 0; i < 20; i++) {
                assertSame(pointer, consumer.getCallTarget().call(5L, pointer, false));
                assertTrue(consumer.getCallTarget().call(5L, pointer, true) instanceof HandoffStorage); released(language);
            }
            compile(consumer.getCallTarget()); int before = consumer.compiledCaptures;
            var captured = (HandoffStorage) consumer.getCallTarget().call(4097L, pointer, true);
            assertEquals(before + 1, consumer.compiledCaptures); assertSame(shape.getLayout(), captured.getLayout());
            assertEquals(4114L, shape.getLayout().getLong(captured, 0)); assertSame(pointer, shape.getLayout().getObject(captured, 1));
            assertSame(pointer, consumer.getCallTarget().call(8193L, pointer, false));
            assertTrue(valid(consumer.getCallTarget())); released(language);
        });
    }
    @Test void deoptimizationMaterializesAFreshPointerCarrierWithoutInventingALoan() throws Exception {
        withLanguage(language -> {
            var shape = shape(language); var producer = new Producer(language, shape).getCallTarget();
            var consumer = new Consumer(language, shape, producer, true).getCallTarget(); var pointer = new Object();
            Barrier.requested = false;
            for (int i = 0; i < 20; i++) { assertSame(pointer, consumer.call((long) i, pointer)); released(language); }
            compile(consumer);
            assertSame(pointer, consumer.call(4097L, pointer)); assertTrue(valid(consumer)); released(language);
            long before = Barrier.materialized; Barrier.requested = true;
            try { assertSame(pointer, consumer.call(8193L, pointer)); } finally { Barrier.requested = false; }
            assertEquals(before + 1, Barrier.materialized, "The post-call observer must force actual carrier materialization"); released(language);
        });
    }
    @Test void freshAndResidualBranchesNormalizeBeforeTheirResultJoin() throws Exception {
        withLanguage(language -> {
            var shape = shape(language); var residual = new Producer(language, shape).getCallTarget();
            var branching = new Producer(language, shape, new Slots(), residual, null, false).getCallTarget();
            var consumer = new Consumer(language, shape, branching, false).getCallTarget(); var pointer = new Object();
            for (int i = 0; i < 20; i++) assertSame(pointer, consumer.call((long) i, pointer));
            compile(residual); compile(consumer);
            assertSame(pointer, consumer.call(4097L, pointer)); assertTrue(valid(consumer));
            // Exercise the previously cold residual branch, then compile both warmed arms.
            assertSame(pointer, consumer.call(-4097L, pointer)); released(language);
            for (int i = 0; i < 10; i++) assertSame(pointer, consumer.call(i % 2 == 0 ? -8193L : 8193L, pointer));
            compile(consumer); long allocations = language.getHandoffState().get().getResults().getAllocations();
            for (long value : List.of(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
                assertSame(pointer, consumer.call(value, pointer)); assertTrue(valid(consumer)); released(language);
            }
            assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations());
        });
    }
    private static void checkAll(List<Closure> closures, RootCallTarget consumer, Object pointer, Language language) {
        for (int index = 0; index < closures.size(); index++) {
            long expected = 100L * index + 17L + (index % 2 == 0 ? 0L : 31L * index);
            assertSame(pointer, consumer.call(closures.get(index), pointer, expected)); released(language);
        }
    }
    @Test void polymorphicTupleCallsKeepPapPrefixesAndCapturedHeadersThroughGenericDispatch() throws Exception {
        withLanguage(language -> {
            var shape = shape(language); var captured = new CaptureLayout(language, new boolean[]{true}, new boolean[]{true});
            var producers = new ArrayList<RootCallTarget>();
            for (int index = 0; index < 5; index++) producers.add(new Producer(language, shape, new Slots(), null, index % 2 == 0 ? null : captured, false).getCallTarget());
            var closures = new ArrayList<Closure>();
            for (int index = 0; index < producers.size(); index++) {
                var environment = index % 2 == 0 ? null : captured.captureValues(new Object[]{31L * index});
                closures.add(new Closure(environment, 2, producers.get(index)).pap(new Object[]{100L * index}));
            }
            var consumer = new DispatchConsumer(language, shape).getCallTarget(); var pointer = new Object();
            for (int i = 0; i < 10; i++) checkAll(closures, consumer, pointer, language);
            for (var producer : producers) compile(producer); compile(consumer);
            long allocations = language.getHandoffState().get().getResults().getAllocations();
            for (int i = 0; i < 10; i++) checkAll(closures, consumer, pointer, language);
            assertTrue(valid(consumer)); assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations());
        });
    }
}
