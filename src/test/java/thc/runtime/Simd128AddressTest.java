// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class Simd128AddressTest {
    private final List<VectorMemoryOp> operations = new ArrayList<>();
    Simd128AddressTest() {
        var families = Set.of(VectorMemoryFamily.INT8, VectorMemoryFamily.WORD8, VectorMemoryFamily.INT16,
            VectorMemoryFamily.WORD16, VectorMemoryFamily.INT64, VectorMemoryFamily.WORD64);
        for (var op : VectorMemoryOp.values())
            if (op.isAddress() && op.getVectorBytes() == 16 && families.contains(op.getFamily())) operations.add(op);
    }
    private static Map<String, Object> m(Object... pairs) {
        var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result;
    }
    private static List<Object> l(Object... values) { return new ArrayList<>(Arrays.asList(values)); }
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static List<Object> list(Object value) { return (List<Object>) value; }
    private Map<String, Object> scalar(String kind, String rep) { return m("kind", kind, "primReps", rep == null ? List.of() : List.of(rep), "evaluated", true); }
    private final Map<String, Object> state = scalar("void", null), integer = scalar("long", "IntRep"),
        array = scalar("address", "AddrRep"), closure = scalar("closure", "BoxedRep (Just Lifted)");
    private Object copy(Object value) {
        if (value instanceof Map<?, ?> values) {
            var result = new LinkedHashMap<String, Object>(); for (var entry : values.entrySet()) result.put((String) entry.getKey(), copy(entry.getValue())); return result;
        }
        if (value instanceof List<?> values) { var result = new ArrayList<Object>(); for (var item : values) result.add(copy(item)); return result; }
        return value;
    }
    private Map<String, Object> binder(String id, Map<String, Object> proof) { return m("id", id, "name", id, "lifted", false, "coercion", false, "rep", copy(proof)); }
    private List<Object> variable(String id, Map<String, Object> proof) { return l("var", id, m("rep", copy(proof))); }
    private List<Object> literal(long value) { return literal(value, "int", integer); }
    private List<Object> literal(long value, String kind, Map<String, Object> proof) { return l("lit", kind, Long.toString(value), m("rep", copy(proof))); }
    private List<Object> call(String name, List<List<Object>> args, Map<String, Object> proof) {
        return l("app", List.of("prim", name, m("rep", closure)), new ArrayList<>(args), new ArrayList<>(Collections.nCopies(args.size(), false)), false, false, m("rep", copy(proof)));
    }
    private static final class Shape {
        final CoreVector vector;
        final int lanes, bytes, width;
        final String scalar, name;
        final boolean unsigned;
        final Map<String, Object> proof, lane, unpacked;
        final long[] written;
        Shape(VectorMemoryOp operation) {
            vector = Objects.requireNonNull(operation.getVectorProof().getVector());
            lanes = vector.getLanes(); bytes = operation.getVectorBytes(); width = bytes / lanes;
            scalar = vector.getElement().substring(0, vector.getElement().length() - "ElemRep".length());
            name = scalar + "X" + lanes; unsigned = scalar.startsWith("Word");
            proof = m("kind", "vector", "evaluated", true, "primReps", List.of("VecRep " + lanes + " " + vector.getElement()),
                "vector", m("lanes", lanes, "element", vector.getElement()));
            lane = m("kind", "long", "evaluated", true, "primReps", List.of(scalar + "Rep"));
            unpacked = m("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple",
                "primReps", Collections.nCopies(lanes, scalar + "Rep"), "components", Collections.nCopies(lanes, lane));
            written = new long[lanes];
            for (int index = 0; index < lanes; index++) {
                long raw = Long.MIN_VALUE + 0x7fff_ffffL * index - 129;
                written[index] = width == 8 ? raw : unsigned ? raw & ((1L << (width * 8)) - 1) : raw << (64 - width * 8) >> (64 - width * 8);
            }
        }
    }
    private record Fixture(Map<String, Object> module, List<Object> app, List<Object> body) {}
    private List<Object> checksum(Shape shape) {
        var result = literal(0);
        for (int i = 0; i < shape.lanes; i++) {
            var value = variable("lane" + i, shape.lane);
            List<Object> widened;
            if (shape.unsigned) widened = call("word2Int#", List.of(call("word" + shape.width * 8 + "ToWord#", List.of(value), scalar("long", "WordRep"))), integer);
            else widened = call("int" + shape.width * 8 + "ToInt#", List.of(value), integer);
            result = call("+#", List.of(result, call("*#", List.of(widened, literal(2L * i + 1)), integer)), integer);
        }
        return result;
    }
    private List<Object> consume(Shape shape, List<Object> value) {
        var names = new ArrayList<String>(); var binders = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < shape.lanes; i++) { names.add("lane" + i); binders.add(binder("lane" + i, shape.lane)); }
        return l("case", call("unpack" + shape.name + "#", List.of(value), shape.unpacked), "lanes",
            l(l("data", "Lanes", names, checksum(shape), m("binders", binders))), m("rep", copy(integer), "binder", binder("lanes", shape.unpacked)));
    }
    private Map<String, Object> readResult(Shape shape, boolean evaluated) {
        var result = new LinkedHashMap<>(shape.proof); result.putAll(m("kind", "unknown", "aggregate", "unboxed-tuple",
            "components", List.of(state, shape.proof), "evaluated", evaluated)); return result;
    }
    private Fixture fixture(VectorMemoryOp op) {
        var shape = new Shape(op); var values = new ArrayList<List<Object>>();
        for (long value : shape.written) {
            var literal = literal(value, shape.scalar.toLowerCase(Locale.ROOT), shape.lane);
            if (shape.unsigned) literal.set(2, Long.toUnsignedString(value)); values.add(literal);
        }
        var packed = call("pack" + shape.name + "#", List.of(l("app", List.of("con", "Lanes", shape.lanes), values,
            Collections.nCopies(shape.lanes, false), false, true, m("rep", copy(shape.unpacked)))), shape.proof);
        var args = List.of(variable("array", array), variable("offset", integer));
        List<Object> app;
        if (op.isRead()) { var all = new ArrayList<>(args); all.add(variable("state", state)); app = call(op.getPrimitive(), all, readResult(shape, false)); }
        else if (op.isWrite()) { var all = new ArrayList<>(args); all.add(packed); all.add(variable("state", state)); app = call(op.getPrimitive(), all, state); }
        else app = call(op.getPrimitive(), args, shape.proof);
        List<Object> body;
        if (op.isRead()) body = l("case", app, "whole", l(l("data", "StateVector", l("nextState", "vector"),
            consume(shape, variable("vector", shape.proof)), m("binders", l(binder("nextState", state), binder("vector", shape.proof))))),
            m("rep", copy(integer), "binder", binder("whole", readResult(shape, true))));
        else if (op.isWrite()) body = l("case", app, "written", l(l("default", null, List.of(),
            consume(shape, call("index" + (op.getScalarOffset() ? shape.scalar + "OffAddrAs" + shape.name : shape.name + "OffAddr") + "#", args, shape.proof)),
            m("binders", List.of()))), m("rep", copy(integer), "binder", binder("written", state)));
        else body = consume(shape, app);
        var module = m("schema", 1, "ghc", "9.14.1", "instrument", true,
            "constructors", List.of(m("id", "StateVector", "kind", "unboxed-tuple", "arity", 2), m("id", "Lanes", "kind", "unboxed-tuple", "arity", shape.lanes)),
            "bindings", List.of(m("id", "root", "name", "root", "arity", 3, "lifted", true, "rep", closure,
                "expr", List.of("lam", List.of(binder("array", array), binder("offset", integer), binder("state", state)), body, m("rep", closure, "resultRep", integer)))));
        return new Fixture(module, app, body);
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception { withLanguage(false, action); }
    private void withLanguage(boolean inlining, Action action) throws Exception {
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private ExecutableProgram program(Language language, String backend, Fixture f) { return backend.equals("ast") ? new Program(language, f.module) : new BytecodeProgram(language, f.module); }
    private Object invoke(ExecutableProgram p, Object bytes, long index) { return invoke(p, bytes, index, kotlin.Unit.INSTANCE); }
    private Object invoke(ExecutableProgram p, Object bytes, long index, Object token) {
        return Calls.target(p.hostEntryTarget(3), new Object[]{p.entryValue("root"), new Object[]{bytes, index, token}});
    }
    private int shift(int octet, int width) { return 8 * (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? octet : width - octet - 1); }
    private long expected(byte[] bytes, int offset, Shape shape) {
        long result = 0;
        for (int lane = 0; lane < shape.lanes; lane++) {
            long raw = 0; for (int octet = 0; octet < shape.width; octet++) raw |= (bytes[offset + lane * shape.width + octet] & 255L) << shift(octet, shape.width);
            long value = shape.unsigned || shape.width == 8 ? raw : raw << (64 - shape.width * 8) >> (64 - shape.width * 8);
            result += value * (2L * lane + 1);
        }
        return result;
    }
    private void store(byte[] bytes, int offset, Shape shape) {
        for (int lane = 0; lane < shape.lanes; lane++) for (int octet = 0; octet < shape.width; octet++) bytes[offset + lane * shape.width + octet] = (byte) (shape.written[lane] >>> shift(octet, shape.width));
    }
    private byte[] bytes(int size, int multiplier, int bias) {
        var result = new byte[size]; for (int i = 0; i < size; i++) result[i] = (byte) (i * multiplier + bias); return result;
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    @Test void all36OperationsPreserveBytesThroughInteriorAddressesOnBothBackends() throws Exception {
        assertEquals(36, operations.size());
        for (var backend : List.of("ast", "bytecode")) withLanguage(language -> {
            for (var op : operations) {
                var shape = new Shape(op); var p = program(language, backend, fixture(op)); int stride = op.getScalarOffset() ? shape.width : 16;
                for (int size : List.of(16, 17, 31, 32, 33, 48, 49)) for (int offset = 0; offset <= (size - 16) / stride; offset++) for (boolean owned : List.of(false, true)) {
                    var bytes = bytes(size, 47, 129); var model = bytes.clone(); if (op.isWrite()) store(model, offset * stride, shape);
                    Object storage;
                    if (owned) { var owner = ManagedByteArray.allocateGuest(size); owner.copyBytesIn(bytes, 0, 0, size); storage = owner; } else storage = bytes;
                    var address = ManagedAddress.fromGuestByteArray(storage).plus(16);
                    assertEquals(expected(model, offset * stride, shape), invoke(p, address, (long) offset - 16 / stride), backend + "/" + op + "/" + size + "/" + offset + "/" + owned);
                    assertArrayEquals(model, storage instanceof ManagedAllocation owner ? owner.copyBytesOut(0, size) : bytes); released(language);
                }
            }
        });
    }
    @Test void completeRangesTokensAndImmutableStorageAreCheckedBeforeStores() throws Exception {
        for (var backend : List.of("ast", "bytecode")) withLanguage(language -> {
            for (var op : operations) {
                var p = program(language, backend, fixture(op)); int stride = op.getScalarOffset() ? new Shape(op).width : 16;
                for (int size : List.of(0, 1, 15, 16, 17, 31, 48)) {
                    var bytes = bytes(size, 1, 0); var original = bytes.clone(); var address = ManagedAddress.fromByteArray(bytes);
                    var invalid = List.of(-1L, Long.MIN_VALUE, Long.MAX_VALUE, size < 16 ? 0L : (long) (size - 16) / stride + 1);
                    for (long index : invalid) {
                        assertThrows(RuntimeFault.class, () -> invoke(p, address, index)); assertArrayEquals(original, bytes); released(language);
                    }
                }
                var owner = ManagedByteArray.allocateGuest(48); var address = ManagedAddress.fromAllocation(owner); owner.shrink(16);
                assertThrows(RuntimeFault.class, () -> invoke(p, address, 1));
                for (var opaque : List.of(ManagedAddress.nullAddress(), ManagedAddress.unownedNumeric(123L)))
                    assertThrows(RuntimeFault.class, () -> invoke(p, opaque, 0));
                if (!op.isIndex()) {
                    var bytes = bytes(32, 1, 0); var original = bytes.clone();
                    assertThrows(RuntimeFault.class, () -> invoke(p, ManagedAddress.fromByteArray(bytes), 0, 1L)); assertArrayEquals(original, bytes);
                }
                var bytes = bytes(32, 7, 0);
                for (var immutable : List.of(ManagedAddress.fromAllocation(ManagedAllocation.immutable(bytes, 8)),
                    ManagedAddress.fromNativeImageSource(bytes, 0))) {
                    if (op.isWrite()) assertThrows(RuntimeFault.class, () -> invoke(p, immutable, 0));
                    else assertEquals(expected(bytes, 0, new Shape(op)), invoke(p, immutable, 0));
                }
                released(language);
            }
        });
    }
    @Test void pointerCellReadsAndPartialOverwritesRejectWithoutLosingReferences() throws Exception {
        withLanguage(language -> {
            var pointer = ManagedAddress.fromByteArray(new byte[8]); var owner = ManagedAllocation.mutable(40, 8);
            var address = ManagedAddress.fromAllocation(owner);
            address.writeAddressElementIndex(2, pointer); // bytes 16..23
            assertThrows(RuntimeFault.class, () -> address.readVectorBytes(8, 1, 16));
            var value = jdk.incubator.vector.ByteVector.broadcast(jdk.incubator.vector.ByteVector.SPECIES_128, (byte) 0x5a);
            assertThrows(RuntimeFault.class, () -> address.writeVectorBytes(1, 1, value, 16));
            assertSame(pointer, address.readAddressElementIndex(2)); assertEquals(0L, address.readWord8(0));
            address.writeVectorBytes(8, 1, value, 16); // Complete overlap invalidates the reference.
            assertThrows(RuntimeFault.class, () -> address.readAddressElementIndex(2));
            var actual = new ArrayList<Long>(); for (long i = 8; i < 24; i++) actual.add(address.readWord8(i));
            assertEquals(Collections.nCopies(16, 0x5aL), actual);
        });
    }
    @Test void nativeStorageSharesBytesAndRejectsFreedAndForeignContextOwners() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").equals("Linux") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        withLanguage(language -> {
            var registry = Language.currentState().getNativeAllocations();
            for (var backend : List.of("ast", "bytecode")) for (var op : operations) {
                var p = program(language, backend, fixture(op)); var shape = new Shape(op); int stride = op.getScalarOffset() ? shape.width : 16;
                var base = registry.malloc(48);
                try {
                    var bytes = bytes(48, 47, 129); for (int i = 0; i < bytes.length; i++) base.writeWord8(i, bytes[i]);
                    var model = bytes.clone(); if (op.isWrite()) store(model, 0, shape); var alias = base.plus(16);
                    assertEquals(expected(model, 0, shape), invoke(p, alias, -16L / stride));
                    var actual = new byte[48]; for (int i = 0; i < actual.length; i++) actual[i] = (byte) base.readWord8(i); assertArrayEquals(model, actual);
                    assertThrows(RuntimeFault.class, () -> invoke(p, base, Long.MAX_VALUE));
                    withLanguage(other -> assertThrows(RuntimeFault.class, () -> alias.readVectorBytes(0, 1, 16)));
                    released(language);
                } finally { registry.free(base); }
                assertThrows(RuntimeFault.class, () -> invoke(p, base, 0));
            }
            assertEquals(0, registry.liveCount());
        });
    }
    private void rejects(Language language, String backend, VectorMemoryOp op, Consumer<Fixture> change) {
        var f = fixture(op); change.accept(f); assertThrows(RuntimeFault.class, () -> program(language, backend, f), backend + "/" + op);
    }
    @Test void loweringKeepsCarrierAndVectorTupleChecksButAcceptsLongAliases() throws Exception {
        for (var backend : List.of("ast", "bytecode")) withLanguage(language -> {
            for (var op : operations) {
                rejects(language, backend, op, f -> list(f.app.get(2)).set(1, List.of("lit", "double", "0.0", m("rep", scalar("double", "DoubleRep")))));
                rejects(language, backend, op, f -> list(f.app.get(3)).set(0, true));
                rejects(language, backend, op, f -> list(f.app.get(2)).removeLast());
                for (var alias : List.of("Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep"))
                    rejects(language, backend, op, f -> list(list(f.app.get(2)).get(1)).set(2, m("rep", scalar("long", alias))));
                for (var alias : List.of("IntRep", "WordRep", "Int64Rep", "Word64Rep")) {
                    var f = fixture(op); list(list(f.app.get(2)).get(1)).set(2, m("rep", scalar("long", alias)));
                    var p = program(language, backend, f); var bytes = new byte[32]; var model = bytes.clone();
                    if (op.isWrite()) store(model, 0, new Shape(op));
                    assertEquals(expected(model, 0, new Shape(op)), invoke(p, ManagedAddress.fromByteArray(bytes), 0));
                }
                if (op.isRead()) {
                    rejects(language, backend, op, f -> map(f.app.get(6)).put("rep", new Shape(op).proof));
                    rejects(language, backend, op, f -> { var alternatives = list(f.body.get(3)); assertEquals(1, alternatives.size()); alternatives.add(copy(alternatives.getFirst())); });
                } else if (op.isWrite()) rejects(language, backend, op, f -> {
                    VectorMemoryOp other = null; for (var candidate : operations) if (candidate.getFamily() != op.getFamily()) { other = candidate; break; }
                    map(list(list(f.app.get(2)).get(2)).get(6)).put("rep", new Shape(Objects.requireNonNull(other)).proof);
                });
                released(language);
            }
        });
    }
}
