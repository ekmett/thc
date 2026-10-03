// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class MutableByteArrayTest {
    private Context context() {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .build();
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target, "installed");
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().retainedReferences());
    }
    private final List<ByteArrayOp> operations =
        List.of(ByteArrayOp.SET, ByteArrayOp.COPY_MUTABLE, ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING);
    private record Range(int from, int to, int count) {}
    private long count(ExecutableProgram p) {
        return ((Number) p.diagnostics().get("compiledEntries")).longValue();
    }
    private byte[] bytes(int seed) {
        var result = new byte[8];
        for (int i = 0; i < 8; i++) result[i] = (byte) (seed + 17 * i);
        return result;
    }
    private boolean overlaps(int a, int b, int n) {
        return n > 0 && a < b + n && b < a + n;
    }
    private List<Range> ranges() {
        var result = new ArrayList<Range>();
        for (int a = 0; a <= 8; a++)
            for (int b = 0; b <= 8; b++)
                for (int n = 0; n <= Math.min(8 - a, 8 - b); n++) result.add(new Range(a, b, n));
        return result;
    }
    private long[][] invalidRanges() {
        return new long[][] {{-1, 0}, {Long.MIN_VALUE, 0}, {9, 0}, {1L << 32, 0}, {Long.MAX_VALUE, 0}, {0, -1},
            {0, Long.MIN_VALUE}, {0, Long.MAX_VALUE}, {1, Long.MAX_VALUE}, {8, 1}};
    }
    @Test
    void fullWidthDomainsFillTruncationAndOverlapPoliciesPreserveStorage() {
        for (int start = 0; start <= 8; start++)
            for (int count = 0; count <= 8 - start; count++)
                for (long value :
                    new long[] {Long.MIN_VALUE, Long.MAX_VALUE, -257, -256, -1, 0, 127, 128, 255, 256, 511, 1L << 40}) {
                    var array = bytes(0);
                    var alias = array;
                    var expected = array.clone();
                    for (int i = start; i < start + count; i++) expected[i] = (byte) (value & 255);
                    ManagedByteArray.fill(array, start, count, value);
                    assertSame(alias, array);
                    assertArrayEquals(expected, array);
                }
        for (var range : ranges())
            for (boolean same : new boolean[] {false, true})
                for (boolean nonOverlapping : new boolean[] {false, true}) {
                    var source = bytes(0);
                    var destination = same ? source : bytes(101);
                    var alias = destination;
                    var sourceBefore = source.clone();
                    var before = destination.clone();
                    if (same && nonOverlapping && overlaps(range.from(), range.to(), range.count())) {
                        assertThrows(RuntimeFault.class,
                            ()
                                -> ManagedByteArray.copyMutable(
                                    source, range.from(), destination, range.to(), range.count(), true));
                        assertArrayEquals(before, destination);
                    } else {
                        var expected = before.clone();
                        for (int i = 0; i < range.count(); i++)
                            expected[range.to() + i] = sourceBefore[range.from() + i];
                        ManagedByteArray.copyMutable(
                            source, range.from(), destination, range.to(), range.count(), nonOverlapping);
                        assertSame(alias, destination);
                        assertArrayEquals(expected, destination);
                        if (!same)
                            assertArrayEquals(sourceBefore, source);
                    }
                }
        for (var range : invalidRanges()) {
            long offset = range[0], count = range[1];
            var source = bytes(0);
            var destination = bytes(101);
            var before = destination.clone();
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.fill(destination, offset, count, 255));
            assertArrayEquals(before, destination);
            for (boolean nonOverlapping : new boolean[] {false, true}) {
                assertThrows(RuntimeFault.class,
                    () -> ManagedByteArray.copyMutable(source, offset, destination, 0, count, nonOverlapping));
                assertArrayEquals(before, destination);
                assertThrows(RuntimeFault.class,
                    () -> ManagedByteArray.copyMutable(source, 0, destination, offset, count, nonOverlapping));
                assertArrayEquals(before, destination);
            }
        }
        var array = bytes(0);
        // Existing immutable/mutable copy stays stricter than either new operation.
        for (long n : new long[] {0, 1})
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.copy(array, 0, array, 4, n));
        assertArrayEquals(bytes(0), array);
    }
    private Expr operand(String name, Object value, List<String> events) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                events.add(name);
                return value;
            }
        };
    }
    @Test
    void allOperandsAndStateAreEvaluatedBeforeMutation() {
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        for (var operation : operations)
            for (boolean fail : new boolean[] {false, true}) {
                var source = bytes(0);
                var destination = bytes(101);
                var before = destination.clone();
                var events = new ArrayList<String>();
                var state = new Expr() {
                    @Override
                    public Object execute(VirtualFrame f) {
                        events.add("state");
                        assertArrayEquals(before, destination);
                        if (fail)
                            throw new RuntimeFault("State failed before mutation");
                        return Unit.INSTANCE;
                    }
                };
                var operands = operation == ByteArrayOp.SET
                    ? new Expr[] {operand("destination", destination, events), operand("offset", 1L, events),
                          operand("count", 3L, events), operand("value", 511L, events), state}
                    : new Expr[] {operand("source", source, events), operand("sourceOffset", 0L, events),
                          operand("destination", destination, events), operand("destinationOffset", 1L, events),
                          operand("count", 3L, events), state};
                var expression = ByteArrayOp.expression(operation, CoreRepresentation.UNKNOWN, operands);
                if (fail) {
                    assertThrows(RuntimeFault.class, () -> expression.execute(frame));
                    assertArrayEquals(before, destination);
                } else {
                    assertSame(Unit.INSTANCE, expression.execute(frame));
                    var expected = before.clone();
                    for (int i = 0; i <= 2; i++)
                        expected[i + 1] = operation == ByteArrayOp.SET ? (byte) (511 & 255) : source[i];
                    assertArrayEquals(expected, destination);
                }
                assertEquals(operation == ByteArrayOp.SET
                        ? List.of("destination", "offset", "count", "value", "state")
                        : List.of("source", "sourceOffset", "destination", "destinationOffset", "count", "state"),
                    events);
            }
    }
    private Map<String, Object> synthetic(ByteArrayOp operation) {
        var array = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var number = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var proofs = operation == ByteArrayOp.SET ? List.of(array, number, number, number, state)
                                                  : List.of(array, number, array, number, number, state);
        var params = new ArrayList<Map<String, Object>>();
        var args = new ArrayList<Object>();
        for (int i = 0; i < proofs.size(); i++) {
            params.add(Map.of("id", "p" + i, "lifted", false, "rep", proofs.get(i)));
            args.add(List.of("var", "p" + i, Map.of("rep", proofs.get(i))));
        }
        var app = List.of("app", List.of("prim", operation.getPrimitive()), args,
            Collections.nCopies(params.size(), false), false, false, Map.of("rep", state));
        var body = List.of("case", app, "state",
            List.of(Arrays.asList("default", null, List.of(), List.of("lit", "int", "17", Map.of("rep", number)))),
            Map.of("rep", number, "binder", Map.of("id", "state", "lifted", false, "rep", state)));
        return Map.of("instrument", true, "constructors", List.of(), "bindings",
            List.of(Map.of("id", "mutate", "name", "mutate", "arity", params.size(), "lifted", true, "rep", closure,
                "expr", List.of("lam", params, body, Map.of("rep", closure, "resultRep", number)))));
    }
    private Object call(RootCallTarget entry, ByteArrayOp op, Object source, long from, Object destination, long to,
        long count, long value, Object state) {
        return Calls.target(entry,
            op == ByteArrayOp.SET ? new Object[] {0L, destination, to, count, value, state}
                                  : new Object[] {0L, source, from, destination, to, count, state});
    }
    private Object call(
        RootCallTarget entry, ByteArrayOp op, Object source, long from, Object destination, long to, long count) {
        return call(entry, op, source, from, destination, to, count, 511, Unit.INSTANCE);
    }
    private void positive(boolean compiled, ByteArrayOp op, ExecutableProgram p, RootCallTarget entry,
        Language language, String backend) throws Exception {
        boolean firstCompiledCall = compiled;
        for (var range : ranges())
            for (boolean same : new boolean[] {false, true}) {
                if (op == ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING && same
                    && overlaps(range.from(), range.to(), range.count()))
                    continue;
                var source = bytes(0);
                var destination = same ? source : bytes(101);
                var sourceBefore = source.clone();
                var expected = destination.clone();
                for (int i = 0; i < range.count(); i++)
                    expected[range.to() + i] =
                        op == ByteArrayOp.SET ? (byte) (511 & 255) : sourceBefore[range.from() + i];
                long before = firstCompiledCall ? count(p) : 0L;
                assertEquals(17L, call(entry, op, source, range.from(), destination, range.to(), range.count()));
                assertArrayEquals(expected, destination);
                if (!same)
                    assertArrayEquals(sourceBefore, source);
                if (firstCompiledCall) {
                    long after = count(p);
                    assertTrue(after > before, backend + "/" + op + " first installed call: compiledEntries "
                        + before + " -> " + after + ", isValidLastTier="
                        + entry.getClass().getMethod("isValidLastTier").invoke(entry));
                    valid(entry, backend + "/" + op);
                    firstCompiledCall = false;
                }
                released(language);
            }
    }
    @Test
    void compiledTypedBackendsMutateExactBytesAndGuardStateBoundsAndOverlap() throws Exception {
        for (var backend : List.of("ast", "bytecode"))
            for (var operation : operations) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var p = program(language, synthetic(operation), backend);
                        var entry = p.entryTarget("mutate");
                        positive(false, operation, p, entry, language, backend);
                        compile(entry);
                        positive(true, operation, p, entry, language, backend);
                        for (var range : invalidRanges()) {
                            long offset = range[0], length = range[1];
                            var source = bytes(0);
                            var destination = bytes(101);
                            var before = destination.clone();
                            assertThrows(RuntimeFault.class,
                                () -> call(entry, operation, source, 0, destination, offset, length));
                            assertArrayEquals(before, destination);
                            if (operation != ByteArrayOp.SET) {
                                assertThrows(RuntimeFault.class,
                                    () -> call(entry, operation, source, offset, destination, 0, length));
                                assertArrayEquals(before, destination);
                            }
                        }
                        var source = bytes(0);
                        var destination = bytes(101);
                        var before = destination.clone();
                        var failure = assertThrows(RuntimeFault.class,
                            ()
                                -> call(entry, operation, source, Long.MAX_VALUE, destination, Long.MAX_VALUE,
                                    Long.MAX_VALUE, 511, 17L));
                        assertTrue(Objects.toString(failure.getMessage(), "").contains("zero-width scalar carrier"),
                            failure.getMessage());
                        assertArrayEquals(before, destination);
                        if (operation == ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING)
                            for (var range : List.of(new Range(0, 1, 7), new Range(1, 0, 7), new Range(0, 0, 8))) {
                                var array = bytes(0);
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> call(
                                            entry, operation, array, range.from(), array, range.to(), range.count()));
                                assertArrayEquals(bytes(0), array);
                            }
                        for (var bad : List.of(new Object(), new Object[] {1})) {
                            assertThrows(RuntimeFault.class, () -> call(entry, operation, source, 0, bad, 0, 0));
                            if (operation != ByteArrayOp.SET)
                                assertThrows(
                                    RuntimeFault.class, () -> call(entry, operation, bad, 0, destination, 0, 0));
                        }
                        released(language);
                    } finally {
                        context.leave();
                    }
                }
    }
}
