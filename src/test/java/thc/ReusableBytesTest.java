// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

class ReusableBytesTest {
    private static Map<String,Object> proof(String kind, String rep) {
        return map("kind", kind, "evaluated", true, "primReps", rep == null ? list() : list(rep));
    }
    private static final Map<String,Object> INT = proof("long", "IntRep"), WORD = proof("long", "WordRep"),
        BYTE = proof("long", "Word8Rep"), ADDRESS = proof("address", "AddrRep"), VOID = proof("void", null),
        FLOAT = proof("float", "FloatRep"), DOUBLE = proof("double", "DoubleRep"),
        BYTES = proof("object", "BoxedRep (Just Unlifted)"), DATA = proof("data", "BoxedRep (Just Lifted)"),
        CLOSURE = proof("closure", "BoxedRep (Just Lifted)"),
        PAIR = map("kind", "unknown", "evaluated", true, "primReps", list("BoxedRep (Just Unlifted)"),
            "aggregate", "unboxed-tuple", "components", list(VOID, BYTES));
    private static Map<String,Object> parameter(String id, Map<String,Object> proof) {
        return map("id", id, "name", id, "lifted", proof == DATA || proof == CLOSURE, "rep", proof);
    }
    private static List<Object> variable(String id, Map<String,Object> proof) { return list("var", id, map("rep", proof)); }
    private static List<Object> integer(long value) { return list("lit", "int", Long.toString(value), map("rep", INT)); }
    private static List<Object> state() { return list("void", map("rep", VOID)); }
    private static List<Object> literal(String hex) { return list("lit", "string-bytes", hex, map("rep", ADDRESS)); }
    @SafeVarargs private static List<Object> primitive(String name, Map<String,Object> proof, List<Object>... args) {
        return list("app", list("prim", name), Arrays.asList(args), Collections.nCopies(args.length, false), false, false, map("rep", proof));
    }
    private static Map<String,Object> function(String id, List<Map<String,Object>> args, List<Object> body, Map<String,Object> result) {
        return map("id", id, "name", id, "lifted", true, "arity", args.size(), "rep", CLOSURE,
            "expr", list("lam", args, body, map("resultRep", result)));
    }
    private static List<Object> tupleCase(List<Object> value, String id, List<Object> body, Map<String,Object> result) {
        return tupleCase(value, id, BYTES, body, result);
    }
    private static Map<String,Object> pair(Map<String,Object> payload) {
        return map("kind", "unknown", "evaluated", true, "primReps", payload.get("primReps"),
            "aggregate", "unboxed-tuple", "components", list(VOID, payload));
    }
    private static List<Object> tupleCase(List<Object> value, String id, Map<String,Object> payload, List<Object> body, Map<String,Object> result) {
        return list("case", value, id + "Pair", list(list("data", "pair", list(id + "State", id), body,
            map("binders", list(parameter(id + "State", VOID), parameter(id, payload))))),
            map("binder", parameter(id + "Pair", pair(payload)), "rep", result));
    }
    private static List<Object> effect(List<Object> value, String id, List<Object> body, Map<String,Object> result) {
        return list("case", value, id, list(list("default", null, list(), body)), map("binder", parameter(id, VOID), "rep", result));
    }
    private static List<Object> byteValue(List<Object> value) {
        return primitive("wordToWord8#", BYTE, primitive("int2Word#", WORD, value));
    }
    private static List<Object> readByte(List<Object> bytes, long offset) {
        return primitive("word2Int#", INT, primitive("word8ToWord#", WORD, primitive("indexWord8Array#", BYTE, bytes, integer(offset))));
    }
    private static List<Object> buffer(List<Object> seed) {
        List<Object> a = variable("a", BYTES), frozen = variable("frozen", BYTES), b = variable("b", BYTES);
        List<Object> body = tupleCase(primitive("unsafeFreezeByteArray#", PAIR, b, state()), "result", variable("result", BYTES), BYTES);
        body = effect(primitive("copyByteArray#", VOID, frozen, integer(0), b, integer(1), integer(2), state()), "copied", body, BYTES);
        body = effect(primitive("writeWord8Array#", VOID, b, integer(0), byteValue(integer(7)), state()), "prefix", body, BYTES);
        body = tupleCase(primitive("newByteArray#", PAIR, integer(3), state()), "b", body, BYTES);
        body = tupleCase(primitive("unsafeFreezeByteArray#", PAIR, a, state()), "frozen", body, BYTES);
        body = effect(primitive("writeWord8Array#", VOID, a, integer(0), byteValue(primitive("+#", INT, seed, integer(1))), state()), "lastWrite", body, BYTES);
        body = effect(primitive("writeWord8Array#", VOID, a, integer(1), byteValue(integer(255)), state()), "highByte", body, BYTES);
        body = effect(primitive("writeWord8Array#", VOID, a, integer(0), byteValue(seed), state()), "firstWrite", body, BYTES);
        return tupleCase(primitive("newByteArray#", PAIR, integer(2), state()), "a", body, BYTES);
    }
    private static List<Object> lifecycle() {
        var original = variable("original", BYTES); var grown = variable("grown", BYTES);
        var snapshot = variable("snapshot", BYTES);
        var frozenSnapshot = variable("frozenSnapshot", BYTES); var frozenGrown = variable("frozenGrown", BYTES);
        var body = primitive("+#", INT, variable("word", INT), primitive("+#", INT, variable("size", INT),
            primitive("+#", INT, primitive("sizeofByteArray#", INT, frozenSnapshot),
                primitive("+#", INT, primitive("float2Int#", INT, variable("single", FLOAT)),
                    primitive("+#", INT, primitive("double2Int#", INT, variable("double", DOUBLE)),
                        primitive("compareByteArrays#", INT, frozenSnapshot, integer(0), frozenGrown, integer(0), integer(8)))))));
        body = tupleCase(primitive("unsafeFreezeByteArray#", PAIR, grown, state()), "frozenGrown", body, INT);
        body = tupleCase(primitive("readDoubleArray#", pair(DOUBLE), grown, integer(2), state()), "double", DOUBLE, body, INT);
        body = tupleCase(primitive("readFloatArray#", pair(FLOAT), grown, integer(2), state()), "single", FLOAT, body, INT);
        body = tupleCase(primitive("readIntArray#", pair(INT), grown, integer(0), state()), "word", INT, body, INT);
        body = tupleCase(primitive("getSizeofMutableByteArray#", pair(INT), grown, state()), "size", INT, body, INT);
        body = effect(primitive("shrinkMutableByteArray#", VOID, grown, integer(24), state()), "shrunk", body, INT);
        body = effect(primitive("copyMutableByteArrayNonOverlapping#", VOID, grown, integer(24), grown, integer(0), integer(8), state()), "copiedBack", body, INT);
        body = effect(primitive("copyMutableByteArray#", VOID, grown, integer(0), grown, integer(24), integer(8), state()), "copiedOut", body, INT);
        body = effect(primitive("writeDoubleArray#", VOID, grown, integer(2), list("lit", "double", "4.5", map("rep", DOUBLE)), state()), "doubleWritten", body, INT);
        body = effect(primitive("writeFloatArray#", VOID, grown, integer(2), list("lit", "float", "1.5", map("rep", FLOAT)), state()), "singleWritten", body, INT);
        body = tupleCase(primitive("resizeMutableByteArray#", PAIR, original, integer(32), state()), "grown", body, INT);
        // GHC forbids accessing the old array after resize, whether or not it moved.
        body = tupleCase(primitive("unsafeFreezeByteArray#", PAIR, snapshot, state()), "frozenSnapshot", body, INT);
        body = effect(primitive("copyMutableByteArray#", VOID, original, integer(0), snapshot, integer(0), integer(16), state()), "snapshotCopied", body, INT);
        body = tupleCase(primitive("newByteArray#", PAIR, integer(16), state()), "snapshot", body, INT);
        body = effect(primitive("writeIntArray#", VOID, original, integer(0), variable("seed", INT), state()), "wordWritten", body, INT);
        body = effect(primitive("setByteArray#", VOID, original, integer(0), integer(16), integer(170), state()), "filled", body, INT);
        return tupleCase(primitive("newByteArray#", PAIR, integer(16), state()), "original", body, INT);
    }
    private static Map<String,Object> module() {
        var field = parameter("bytes", BYTES);
        var boxed = list("case", buffer(integer(40)), "built", list(list("default", null, list(),
            list("app", list("con", "Box", 1), list(variable("built", BYTES)), list(false), false, false, map("rep", DATA)))),
            map("binder", parameter("built", BYTES), "rep", DATA));
        var bytes = variable("bytes", BYTES);
        var score = primitive("+#", INT, primitive("sizeofByteArray#", INT, bytes),
            primitive("+#", INT, readByte(bytes, 0), primitive("+#", INT,
                primitive("*#", INT, integer(257), readByte(bytes, 1)), primitive("*#", INT, integer(65537), readByte(bytes, 2)))));
        var reader = list("case", variable("shared", DATA), "box", list(list("data", "Box", list("bytes"), score,
            map("binders", list(field)))), map("binder", parameter("box", DATA), "rep", INT));
        return map("schema", 1, "ghc", "9.14.1", "module", "ReusableBytes", "instrument", true,
            "constructors", list(map("id", "pair", "name", "pair", "kind", "unboxed-tuple", "arity", 2),
                map("id", "Box", "name", "Box", "kind", "boxed", "arity", 1, "tag", 1,
                    "strictFields", list(false), "fieldLifted", list(false), "fieldReps", list(BYTES.get("primReps")), "fieldTypes", list(BYTES))),
            "bindings", list(
                map("id", "text", "name", "text", "lifted", false, "rep", ADDRESS, "expr", literal("ff800041")),
                function("literal", list(parameter("offset", INT)), primitive("indexCharOffAddr#", WORD,
                    primitive("plusAddr#", ADDRESS, variable("text", ADDRESS), variable("offset", INT)), integer(0)), WORD),
                function("inline", list(parameter("offset", INT)), primitive("indexCharOffAddr#", WORD, literal("ff800041"), variable("offset", INT)), WORD),
                function("make", list(parameter("seed", INT)), buffer(variable("seed", INT)), BYTES),
                function("lifecycle", list(parameter("seed", INT)), lifecycle(), INT),
                map("id", "shared", "name", "shared", "lifted", true, "rep", DATA, "expr", boxed),
                function("read", list(parameter("unused", INT)), reader, INT)));
    }
    private static Object call(Program program, String name, long argument) throws Exception {
        var closure = (Closure)program.entryValue(name);
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", OptimizedCallTarget.class).invoke(runtime, closure.target);
        return Calls.target(closure.target, new Object[]{0L, closure.environment, argument});
    }
    @SuppressWarnings("unchecked") private void check(boolean compiled) throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", Boolean.toString(compiled))
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module(), List.of("literal", "inline", "make", "lifecycle", "read")); }
                finally { preparation.leave(); }
            }
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            for (var target : (List<OptimizedCallTarget>)field.get(code)) {
                assertFalse(target.wasExecuted());
                if (compiled) { assertTrue(target.prepareForAOT()); target.compile(true); assertFalse(target.wasExecuted()); }
            }
            if (compiled) code.requireInstalledCode();
            String previous = System.getProperty("thc.requireCompiledCode");
            if (compiled) System.setProperty("thc.requireCompiledCode", "true");
            try {
                Object previousBuffer = null;
                for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var program = code.newInstance(language); var sibling = code.newInstance(language);
                        var caf = (Thunk)program.entryValue("shared"); var other = (Thunk)sibling.entryValue("shared");
                        assertEquals(0, caf.getState()); assertEquals(0, other.getState());
                        for (var name : List.of("literal", "inline")) {
                            long[] expected = {255, 128, 0, 65, 0};
                            for (int i = 0; i < expected.length; i++) assertEquals(expected[i], call(program, name, i));
                        }
                        Object first = call(program, "make", 10), second = call(program, "make", 19);
                        assertNotSame(first, second); assertNotSame(first, previousBuffer);
                        assertEquals(3, ManagedByteArray.sizeGuest(first));
                        assertEquals(7, ManagedByteArray.readGuest(first, 0, true));
                        assertEquals(11, ManagedByteArray.readGuest(first, 1, true));
                        assertEquals(255, ManagedByteArray.readGuest(first, 2, true));
                        assertEquals(20, ManagedByteArray.readGuest(second, 1, true));
                        // Snapshot size 16 + shrunken size 24 + truncated floats 1 and 4;
                        // the copied integer is unchanged and the prefix comparison is equal.
                        assertEquals(55L, call(program, "lifecycle", 10));
                        assertEquals(26L, call(sibling, "lifecycle", -19));
                        assertEquals(16722482L, call(program, "read", 0));
                        assertEquals(2, caf.getState()); assertEquals(0, other.getState());
                        assertEquals(1L, program.diagnostics().get("thunkEvaluations"));
                        assertEquals(16722482L, call(sibling, "read", 0));
                        var a = (DataValue)caf.getValue(); var b = (DataValue)other.getValue();
                        assertNotSame(a.getLayout().read(a, 0), b.getLayout().read(b, 0));
                        assertEquals(16722482L, call(program, "read", 0));
                        assertEquals(1L, program.diagnostics().get("thunkEvaluations"));
                        assertEquals(0, program.diagnostics().get("loweredRootCount"));
                        if (compiled) { assertTrue(((Number)program.diagnostics().get("compiledEntries")).longValue() > 0); code.requireInstalledCode(); }
                        var handoff = language.getHandoffState().get();
                        assertNull(handoff.getPending()); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                        previousBuffer = first;
                    } finally { context.leave(); }
                }
            } finally { if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous); }
        }
    }
    @Test void immutableLiteralsAndByteCafsHaveFreshLoadState() throws Exception { check(false); }
    @Test void firstCompiledByteConstructionNeedsNoTraining() throws Exception { check(true); }
    @Test void preparedNarrowAddressReadsUseRuntimeAllocation() {
        var names = List.of("indexWord8OffAddr#", "indexInt8OffAddr#", "indexWord16OffAddr#", "indexInt16OffAddr#");
        var representations = List.of("Word8Rep", "Int8Rep", "Word16Rep", "Int16Rep");
        var bindings = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < names.size(); i++) {
            var result = proof("long", representations.get(i));
            bindings.add(function(names.get(i), list(parameter("address", ADDRESS), parameter("offset", INT)),
                primitive(names.get(i), result, variable("address", ADDRESS), variable("offset", INT)), result));
        }
        var input = with(module(), "bindings", bindings);
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), input, names); }
                finally { preparation.leave(); }
            }
            for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).allowNativeAccess(true).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var program = code.newInstance(TruffleLanguage.LanguageReference.create(Language.class).get(null));
                    var allocations = Language.currentState().getNativeAllocations();
                    var address = allocations.malloc(4);
                    address.writeWord8(0, 255);
                    boolean little = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN;
                    address.writeWord8(2, little ? 1 : 128);
                    address.writeWord8(3, little ? 128 : 1);
                    int[] expected = {255, -1, 32769, -32767};
                    var readers = new ArrayList<Closure>();
                    for (int i = 0; i < names.size(); i++) {
                        var reader = (Closure)program.entryValue(names.get(i)); readers.add(reader);
                        assertEquals(Integer.valueOf(expected[i]), ScalarTestCalls.callScalarTestTarget(reader.target,
                            new Object[]{0L, reader.environment, address, i < 2 ? 0L : 1L}), names.get(i));
                    }
                    allocations.free(address);
                    for (var reader : readers) assertThrows(RuntimeFault.class, () -> ScalarTestCalls.callScalarTestTarget(reader.target,
                        new Object[]{0L, reader.environment, address, 0L}));
                } finally { context.leave(); }
            }
        }
    }
    @Test void computedAndNativeAddressesAreResolvedAtInstanceInitialization() {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var raw = map("id", "address", "name", "address", "lifted", false, "rep", ADDRESS,
                    "expr", list("lit", "null-addr", "0", map("rep", ADDRESS)));
                var nullCode = Program.prepareCode(language, with(module(), "bindings", list(raw)), List.of("address"));
                assertSame(ManagedAddress.nullAddress(), nullCode.newInstance(language).entryValue("address"));
                raw.put("expr", list("lit", "data-addr", "unapproved", map("rep", ADDRESS)));
                var missingData = Program.prepareCode(language, with(module(), "bindings", list(raw)), List.of("address"));
                assertThrows(RuntimeFault.class, () -> missingData.newInstance(language));
                raw.put("expr", list("lit", "function-addr", "unapproved", map("rep", ADDRESS)));
                var functionCode = Program.prepareCode(language, with(module(), "bindings", list(raw)), List.of("address"));
                assertThrows(RuntimeFault.class, () -> functionCode.newInstance(language));
                var computed = map("id", "address", "name", "address", "lifted", false, "rep", ADDRESS,
                    "expr", primitive("plusAddr#", ADDRESS, literal("41"), integer(0)));
                var computedCode = Program.prepareCode(language, with(module(), "bindings", list(computed)), List.of("address"));
                var first = computedCode.newInstance(language); var second = computedCode.newInstance(language);
                var left = (ManagedAddress)first.entryValue("address"); var right = (ManagedAddress)second.entryValue("address");
                assertEquals(65L, left.readWord8(0)); assertEquals(65L, right.readWord8(0));
            } finally { context.leave(); }
        }
    }
}
