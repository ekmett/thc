// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

@SuppressWarnings("unchecked")
class MutVarTest {
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    @Test
    void managedReferencePreservesAliasesAndDoesNotEvaluateOrCacheContents() throws Exception {
        var first = new Object();
        var second = new Object();
        var reference = new ManagedMutVar(first);
        assertSame(first, reference.getValue());
        var alias = ManagedMutVar.require(reference);
        alias.setValue(second);
        assertSame(second, reference.getValue());
        assertNotSame(reference, new ManagedMutVar(second));
        assertThrows(RuntimeFault.class, () -> ManagedMutVar.require(new Object[] {first}));
        var field = ManagedMutVar.class.getDeclaredField("value");
        assertFalse(java.lang.reflect.Modifier.isFinal(field.getModifiers()));
        assertTrue(
            java.lang.reflect.Modifier.isVolatile(field.getModifiers()), "MutVar writes must publish to other threads");
        assertNull(field.getAnnotation(CompilerDirectives.CompilationFinal.class));
    }
    private RootCallTarget force(Language language, Metrics metrics, boolean async) {
        return new RootNode(language, FrameDescriptor.newBuilder().build()) {
            @Child private Force evaluator = new Force(metrics, async);
            @Override
            public Object execute(VirtualFrame frame) {
                return evaluator.execute(frame, frame.getArguments()[0]);
            }
        }.getCallTarget();
    }
    private Expr constant(Object value) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                return value;
            }
        };
    }
    @Test
    void atomicModifyReturnsOneSharedLazyApplicationAndSelector() {
        try (var context = executionContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var layout =
                    new DataLayout(language, "main:ModifyPair", "ModifyPair", new String[] {"LiftedRep", "IntRep"});
                var calls = new AtomicInteger();
                var old = new Object();
                var replacement = new Object();
                var modifier = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Override
                    public long bloom(VirtualFrame frame) {
                        return (Long) frame.getArguments()[0];
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        assertSame(old, frame.getArguments()[1]);
                        calls.incrementAndGet();
                        return layout.create(new Object[] {replacement, 7L});
                    }
                };
                var metrics = new Metrics(false);
                var force = force(language, metrics, false);
                var cell = new ManagedMutVar(old);
                var function = new Closure(null, 1, modifier.getCallTarget());
                var builder = FrameDescriptor.newBuilder();
                var slots = new int[] {builder.addSlot(FrameSlotKind.Object, "old", null),
                    builder.addSlot(FrameSlotKind.Object, "result", null)};
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
                var invalidState = MutVarOp.expression(MutVarOp.MODIFY2, CoreRepresentation.UNKNOWN,
                    new Expr[] {constant(cell), constant(function), constant(1L)}, language, metrics, false);
                assertThrows(RuntimeFault.class, () -> invalidState.executeTuple(frame, slots, 0));
                assertSame(old, cell.getValue());
                assertEquals(0, calls.get());
                var site = new MutVarModifySite(language, metrics, false);
                var modified = cell.modify(function, site);
                assertSame(old, modified.getOld());
                assertEquals(0, calls.get());
                var selected = cell.getValue();
                assertTrue(selected instanceof Thunk);
                var second = new ManagedMutVar(old);
                var secondModification = second.modify(function, site);
                assertSame(modified.getResult().getTarget(), secondModification.getResult().getTarget(),
                    "atomic updates at one primitive site share the application call target");
                assertSame(((Thunk) selected).getTarget(), ((Thunk) second.getValue()).getTarget(),
                    "atomic updates at one primitive site share the selector call target");
                assertSame(replacement, Calls.target(force, new Object[] {selected}));
                assertEquals(1, calls.get());
                assertSame(layout, ((DataValue) Calls.target(force, new Object[] {modified.getResult()})).getLayout());
                assertEquals(1, calls.get(), "selector and returned record share the modifier application");
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void atomicModifySharesTheModifierFailureWithoutReplayingIt() {
        try (var context = executionContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var calls = new AtomicInteger();
                var payload = new Object();
                var old = new Object();
                var modifier = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Override
                    public long bloom(VirtualFrame frame) {
                        return (Long) frame.getArguments()[0];
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        calls.incrementAndGet();
                        throw new GuestException(payload, this);
                    }
                };
                var metrics = new Metrics(false);
                var force = force(language, metrics, false);
                var cell = new ManagedMutVar(old);
                var modified = cell.modify(
                    new Closure(null, 1, modifier.getCallTarget()), new MutVarModifySite(language, metrics, false));
                assertSame(old, modified.getOld());
                assertEquals(0, calls.get());
                for (var value :
                    List.of(modified.getResult(), cell.getValue(), modified.getResult(), cell.getValue())) {
                    var failure = assertThrows(GuestException.class, () -> Calls.target(force, new Object[] {value}));
                    assertSame(payload, failure.getPayload());
                }
                assertEquals(1, calls.get(), "a failed modifier is memoized by the shared application thunk");
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void atomicModifyDoesNotEnterABottomModifierBeforeReturningOld() {
        try (var context = executionContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var calls = new AtomicInteger();
                var payload = new Object();
                var old = new Object();
                var bottom = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Override
                    public long bloom(VirtualFrame frame) {
                        return (Long) frame.getArguments()[0];
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        calls.incrementAndGet();
                        throw new GuestException(payload, this);
                    }
                };
                var metrics = new Metrics(false);
                var modifier = new Thunk(bottom.getCallTarget(), null);
                var cell = new ManagedMutVar(old);
                var modified = cell.modify(modifier, new MutVarModifySite(language, metrics, false));
                assertSame(old, modified.getOld());
                assertEquals(0, calls.get(), "atomicModifyMutVar2# must not force f before the swap");
                var force = force(language, metrics, false);
                for (var value : List.of(modified.getResult(), cell.getValue())) {
                    var failure = assertThrows(GuestException.class, () -> Calls.target(force, new Object[] {value}));
                    assertSame(payload, failure.getPayload());
                }
                assertEquals(1, calls.get());
            } finally {
                context.leave();
            }
        }
    }
    private Thunk leaf(Language language, Object value) {
        return new Thunk(new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
            @Override
            public long bloom(VirtualFrame frame) {
                return (Long) frame.getArguments()[0];
            }
            @Override
            public Object execute(VirtualFrame frame) {
                return value;
            }
        }.getCallTarget(), null);
    }
    private GuestRoot pausing(Language language, Thunk child, AtomicInteger counter, Object expected) {
        return new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
            @Override
            public long bloom(VirtualFrame frame) {
                return (Long) frame.getArguments()[0];
            }
            @Override
            public Object execute(VirtualFrame frame) {
                if (expected != null)
                    assertSame(expected, frame.getArguments()[1]);
                counter.incrementAndGet();
                CompilerDirectives.transferToInterpreter();
                return new AstCapture(new ThunkSuspended(child), SynchronousMasking.current(this))
                    .append((resumed, input) -> {
                        if (!(input instanceof ChildResume answer))
                            throw new RuntimeFault("Missing child resume");
                        if (answer.getFailure() != null)
                            throw answer.getFailure();
                        return answer.getValue();
                    })
                    .freeze(this, frame.materialize());
            }
        };
    }
    private List<Integer> counts(AtomicInteger[] counts) {
        var result = new ArrayList<Integer>();
        for (var count : counts) result.add(count.get());
        return result;
    }
    private Object step(Context context, RootCallTarget force, Thunk selected) throws Exception {
        var answer = new CompletableFuture<Object>();
        var thread = new Thread(() -> {
            context.enter();
            try {
                answer.complete(Calls.target(force, new Object[] {selected}));
            } catch (ThunkSuspended suspension) {
                answer.complete(suspension);
            } catch (Throwable failure) {
                answer.completeExceptionally(failure);
            } finally {
                context.leave();
            }
        });
        thread.start();
        try {
            return answer.get(10, TimeUnit.SECONDS);
        } finally {
            thread.join(5000);
            assertFalse(thread.isAlive());
        }
    }
    @Test
    void atomicModifyResumesModifierCallAndSelectedFieldWithoutRepeatingTheSwap() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc");
            context.enter();
            final ManagedMutVar cell;
            final Thunk selected;
            final ModifiedMutVar modified;
            final RootCallTarget force;
            var replacement = new Object();
            var counts = new AtomicInteger[] {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(false);
                var old = new Object();
                var layout = new DataLayout(
                    language, "main:PausedModifyPair", "PausedModifyPair", new String[] {"LiftedRep", "IntRep"});
                var field =
                    new Thunk(pausing(language, leaf(language, replacement), counts[2], null).getCallTarget(), null);
                var record = layout.create(new Object[] {field, 7L});
                var body = pausing(language, leaf(language, record), counts[1], old);
                var closure = new Closure(null, 1, body.getCallTarget());
                var modifier =
                    new Thunk(pausing(language, leaf(language, closure), counts[0], null).getCallTarget(), null);
                cell = new ManagedMutVar(old);
                modified = cell.modify(modifier, new MutVarModifySite(language, metrics, true));
                selected = (Thunk) cell.getValue();
                assertSame(old, modified.getOld());
                assertEquals(List.of(0, 0, 0), counts(counts));
                force = force(language, metrics, true);
            } finally {
                context.leave();
            }
            // Each cut changes the Java carrier thread. The selector remains the
            // sole published MutVar value while all three continuations resume.
            for (int round = 0; round <= 2; round++) {
                var result = step(context, force, selected);
                if (!(result instanceof ThunkSuspended suspension))
                    throw new AssertionError("Expected owned selector cut " + round);
                assertSame(selected, suspension.getThunk());
                assertSame(selected, cell.getValue(), "CAS must not replay while the selector is suspended");
                var expected = new ArrayList<Integer>();
                for (int i = 0; i <= 2; i++) expected.add(i <= round ? 1 : 0);
                assertEquals(expected, counts(counts));
            }
            assertSame(replacement, step(context, force, selected));
            assertSame(selected, cell.getValue());
            assertEquals(List.of(1, 1, 1), counts(counts));
            context.enter();
            try {
                var record = (DataValue) Calls.target(force, new Object[] {modified.getResult()});
                assertSame(replacement, Calls.target(force, new Object[] {record.getLayout().readFirstLifted(record)}));
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void concurrentAtomicModifyBuildsOneLazyUpdateChain() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var counter = new DataLayout(language, "main:AtomicCounter", "AtomicCounter", new String[] {"IntRep"});
                var pair =
                    new DataLayout(language, "main:AtomicPair", "AtomicPair", new String[] {"LiftedRep", "IntRep"});
                var metrics = new Metrics(false);
                var modifier = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Child private Force force = new Force(metrics);
                    @Override
                    public long bloom(VirtualFrame frame) {
                        return (Long) frame.getArguments()[0];
                    }
                    @Override
                    public DataValue execute(VirtualFrame frame) {
                        var previous = (DataValue) force.execute(frame, frame.getArguments()[1]);
                        long n = counter.readLong(previous, 0);
                        return pair.create(new Object[] {counter.createLong(n + 1), n});
                    }
                };
                var closure = new Closure(null, 1, modifier.getCallTarget());
                var cell = new ManagedMutVar(counter.createLong(0));
                var site = new MutVarModifySite(language, metrics, false);
                var start = new CountDownLatch(1);
                var workers = Executors.newFixedThreadPool(4);
                try {
                    var futures = new ArrayList<Future<?>>();
                    for (int i = 0; i < 4; i++)
                        futures.add(workers.submit(() -> {
                            start.await();
                            for (int j = 0; j < 8; j++) cell.modify(closure, site);
                            return null;
                        }));
                    start.countDown();
                    for (var future : futures) future.get(10, TimeUnit.SECONDS);
                } finally {
                    workers.shutdownNow();
                    assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
                }
                var force = force(language, metrics, false);
                var last = (DataValue) Calls.target(force, new Object[] {cell.getValue()});
                assertEquals(32L, counter.readLong(last, 0), "each CAS publishes one selector");
            } finally {
                context.leave();
            }
        }
    }
    private CoreRepresentation proof(CoreKind kind, List<String> reps) {
        return new CoreRepresentation(kind, false, true, reps, null, null, null, null, null);
    }
    private CoreRepresentation tuple(List<String> reps, List<CoreRepresentation> fields) {
        return new CoreRepresentation(CoreKind.UNKNOWN, false, true, reps, fields, null, null, null, null);
    }
    @Test
    void exactContractsAcceptLiftedAndUnliftedBoxedPayloadCarriers() {
        var state = proof(CoreKind.VOID, List.of());
        var reference = proof(CoreKind.OBJECT, List.of("BoxedRep (Just Unlifted)"));
        for (var kind : List.of(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT))
            for (var levity : List.of("Lifted", "Unlifted")) {
                var payload = proof(kind, List.of("BoxedRep (Just " + levity + ")"));
                for (var operation : List.of(MutVarOp.NEW, MutVarOp.READ, MutVarOp.SWAP, MutVarOp.WRITE)) {
                    var arguments = switch (operation) {
                        case NEW -> List.of(payload, state);
                        case READ -> List.of(reference, state);
                        case SWAP, WRITE -> List.of(reference, payload, state);
                        default -> throw new IllegalStateException("Separate atomic contract in BoxedCasTest");
                    };
                    var returned = operation == MutVarOp.NEW ? reference : payload;
                    var result = operation.getTuple() ? tuple(returned.getPrimReps(), List.of(state, returned)) : state;
                    var flags = new ArrayList<Boolean>();
                    for (var argument : arguments)
                        flags.add(List.of("BoxedRep (Just Lifted)").equals(argument.getPrimReps()));
                    operation.validate(arguments, flags, result);
                    var scalar = proof(CoreKind.LONG, List.of("IntRep"));
                    if (operation == MutVarOp.READ) {
                        var wrongResult = result.copy(result.getKind(), result.getEvaluated(), result.getPresent(),
                            scalar.getPrimReps(), List.of(state, scalar), result.getVector(), result.getAlternatives(),
                            result.getTagSlot(), result.getAlternativeSlots());
                        assertThrows(RuntimeFault.class, () -> operation.validate(arguments, flags, wrongResult));
                    } else {
                        int payloadIndex = operation == MutVarOp.NEW ? 0 : 1;
                        var wrongArguments = new ArrayList<>(arguments);
                        wrongArguments.set(payloadIndex, scalar);
                        assertThrows(RuntimeFault.class, () -> operation.validate(wrongArguments, flags, result));
                    }
                }
            }
    }
    private static class PublishedValue {
        int sequence;
        int complement;
    }
    private PublishedValue value(int sequence) {
        var value = new PublishedValue();
        value.sequence = sequence;
        value.complement = ~sequence;
        return value;
    }
    @Test
    void concurrentReadersSeeCompletePublishedValuesInWriteOrder() throws Exception {
        var reference = new ManagedMutVar(value(0));
        var done = new Object();
        var start = new CountDownLatch(1);
        var firstRead = new CountDownLatch(2);
        var workers = Executors.newFixedThreadPool(3);
        try {
            var readers = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++)
                readers.add(workers.submit(() -> {
                    start.await();
                    int previous = 0;
                    boolean sawWrite = false;
                    while (true) {
                        if (Thread.currentThread().isInterrupted())
                            throw new InterruptedException("Reader cancelled");
                        var current = reference.getValue();
                        if (current == done)
                            break;
                        var published = (PublishedValue) current;
                        assertEquals(
                            ~published.sequence, published.complement, "Reader observed a partially published value");
                        assertTrue(published.sequence >= previous, "A reader went backward in the write order");
                        previous = published.sequence;
                        if (previous > 0 && !sawWrite) {
                            sawWrite = true;
                            firstRead.countDown();
                        }
                        Thread.onSpinWait();
                    }
                    assertTrue(sawWrite, "Reader missed every published write");
                    return previous;
                }));
            var writer = workers.submit(() -> {
                start.await();
                reference.setValue(value(1));
                assertTrue(firstRead.await(10, TimeUnit.SECONDS), "Readers did not observe the first write");
                for (int sequence = 2; sequence <= 25_000; sequence++) reference.setValue(value(sequence));
                reference.setValue(done);
                return null;
            });
            start.countDown();
            writer.get(10, TimeUnit.SECONDS);
            for (var reader : readers) reader.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "MutVar workers did not terminate");
        }
    }
    @Test
    void atomicModifyRequiresLiftedFunctionAndTwoLiftedResults() {
        var state = proof(CoreKind.VOID, List.of());
        var reference = proof(CoreKind.OBJECT, List.of("BoxedRep (Just Unlifted)"));
        var lifted = List.of("BoxedRep (Just Lifted)");
        var function = proof(CoreKind.CLOSURE, lifted);
        var old = proof(CoreKind.DATA, lifted);
        var record = proof(CoreKind.DATA, lifted);
        var both = new ArrayList<>(lifted);
        both.addAll(lifted);
        var tuple = tuple(both, List.of(state, old, record));
        var args = List.of(reference, function, state);
        MutVarOp.MODIFY2.validate(args, List.of(false, true, false), tuple);
        assertThrows(RuntimeFault.class, () -> MutVarOp.MODIFY2.validate(args, List.of(false, false, false), tuple));
        var wrong = tuple.copy(tuple.getKind(), tuple.getEvaluated(), tuple.getPresent(), tuple.getPrimReps(),
            List.of(state, old), tuple.getVector(), tuple.getAlternatives(), tuple.getTagSlot(),
            tuple.getAlternativeSlots());
        assertThrows(RuntimeFault.class, () -> MutVarOp.MODIFY2.validate(args, List.of(false, true, false), wrong));
    }
    @Test
    void concurrentAtomicExchangesNeitherLoseNorDuplicateReferences() throws Exception {
        var initial = new Object();
        var replacements = new Object[8_000];
        for (int i = 0; i < replacements.length; i++) replacements[i] = new Object();
        var reference = new ManagedMutVar(initial);
        var displaced = new ConcurrentLinkedQueue<Object>();
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Future<?>>();
            for (int worker = 0; worker <= 3; worker++) {
                int first = worker;
                tasks.add(workers.submit(() -> {
                    start.await();
                    for (int index = first; index < replacements.length; index += 4)
                        displaced.add(Objects.requireNonNull(reference.exchange(replacements[index])));
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(replacements.length, displaced.size());
            var counts = new IdentityHashMap<Object, Integer>();
            for (var value : displaced) counts.put(value, counts.getOrDefault(value, 0) + 1);
            var last = Objects.requireNonNull(reference.getValue());
            counts.put(last, counts.getOrDefault(last, 0) + 1);
            assertEquals(replacements.length + 1, counts.size());
            var values = new ArrayList<Object>();
            values.add(initial);
            values.addAll(Arrays.asList(replacements));
            for (var value : values) assertEquals(1, counts.get(value));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "MutVar exchange workers did not terminate");
        }
    }
    private Expr operand(String name, Supplier<Object> action, List<String> events) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                events.add(name);
                return action.get();
            }
        };
    }
    @Test
    void stateFailurePrecedesMutationAndTuplePublication() throws Exception {
        var builder = FrameDescriptor.newBuilder();
        int slot = builder.addSlot(FrameSlotKind.Object, "destination", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
        var events = new ArrayList<String>();
        var original = new Object();
        var replacement = new Object();
        var cell = new ManagedMutVar(original);
        var unknown = CoreRepresentation.UNKNOWN;
        var write = MutVarOp.expression(MutVarOp.WRITE, unknown,
            new Expr[] {operand("cell", () -> cell, events), operand("value", () -> replacement, events),
                operand("state", () -> {
                    assertSame(original, cell.getValue());
                    return Unit.INSTANCE;
                }, events)});
        assertSame(Unit.INSTANCE, write.execute(frame));
        assertSame(replacement, cell.getValue());
        assertEquals(List.of("cell", "value", "state"), events);
        cell.setValue(original);
        var failingWrite = MutVarOp.expression(MutVarOp.WRITE, unknown,
            new Expr[] {operand("cell", () -> cell, events), operand("value", () -> replacement, events),
                operand("state", () -> 1L, events)});
        assertThrows(RuntimeFault.class, () -> failingWrite.execute(frame));
        assertSame(original, cell.getValue());
        frame.setObject(slot, replacement);
        var failingSwap = MutVarOp.expression(MutVarOp.SWAP, unknown,
            new Expr[] {operand("cell", () -> cell, events), operand("value", () -> replacement, events),
                operand("state", () -> 1L, events)});
        assertThrows(RuntimeFault.class, () -> failingSwap.executeTuple(frame, new int[] {slot}, 0));
        assertSame(original, cell.getValue());
        assertSame(replacement, frame.getObject(slot));
        var swap = MutVarOp.expression(MutVarOp.SWAP, unknown,
            new Expr[] {operand("cell", () -> cell, events), operand("value", () -> replacement, events),
                operand("state", () -> Unit.INSTANCE, events)});
        assertNull(swap.executeTuple(frame, new int[] {slot}, 0));
        assertSame(original, frame.getObject(slot));
        assertSame(replacement, cell.getValue());
        for (var operation : List.of(MutVarOp.NEW, MutVarOp.READ)) {
            frame.setObject(slot, replacement);
            var failing = MutVarOp.expression(operation, unknown,
                new Expr[] {operand("value", () -> operation == MutVarOp.READ ? cell : original, events),
                    operand("state", () -> { throw new RuntimeFault("state failed"); }, events)});
            assertThrows(RuntimeFault.class, () -> failing.executeTuple(frame, new int[] {slot}, 0));
            assertSame(replacement, frame.getObject(slot));
        }
    }
    @Test
    void bothBackendsRejectBadStateBeforeWritingAndKeepLiftedContentsLazy() {
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var reference = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var lifted = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var ids = List.of("cell", "value", "state");
        var proofs = List.of(reference, lifted, state);
        var parameters = new ArrayList<Map<String, Object>>();
        var args = new ArrayList<Object>();
        for (int i = 0; i < ids.size(); i++) {
            parameters.add(Map.of("id", ids.get(i), "lifted", ids.get(i).equals("value"), "rep", proofs.get(i)));
            args.add(List.of("var", ids.get(i), Map.of("rep", proofs.get(i))));
        }
        var body = List.of("app", List.of("prim", "writeMutVar#"), args, List.of(false, true, false), false, false,
            Map.of("rep", state));
        Map<String, Object> module = Map.of("bindings",
            List.of(Map.of("id", "write", "name", "write", "lifted", true, "rep", closure, "arity", 3, "expr",
                List.of("lam", parameters, body, Map.of("rep", closure, "resultRep", state)))));
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var p = program(language, module, backend);
                    var original = new Object();
                    var cell = new ManagedMutVar(original);
                    // A lifted value may be an unevaluated thunk. A raw sentinel is
                    // sufficient here: forcing it as a guest value would fail.
                    var replacement = new Object();
                    assertThrows(RuntimeFault.class,
                        ()
                            -> Calls.target(p.hostEntryTarget(3),
                                new Object[] {p.entryValue("write"), new Object[] {cell, replacement, 1L}}),
                        backend);
                    assertSame(original, cell.getValue(), backend + " must check State before mutation");
                    assertSame(Unit.INSTANCE,
                        Calls.target(p.hostEntryTarget(3),
                            new Object[] {p.entryValue("write"), new Object[] {cell, replacement, Unit.INSTANCE}}),
                        backend);
                    assertSame(replacement, cell.getValue(), backend + " must store the lifted operand unchanged");
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                } finally {
                    context.leave();
                }
            }
    }
}
