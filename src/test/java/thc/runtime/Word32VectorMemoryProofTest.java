// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.incubator.vector.IntVector;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class Word32VectorMemoryProofTest {
    // These fixtures use 128-bit vectors and ByteArray#, not wider vectors or Addr#.
    private final List<VectorMemoryOp> operations = operations();
    private List<VectorMemoryOp> operations() {
        var result = new ArrayList<VectorMemoryOp>();
        for (var operation : VectorMemoryOp.values())
            if (operation.getFamily() == VectorMemoryFamily.WORD32 && operation.getVectorBytes() == 16 && !operation.isAddress()) result.add(operation);
        return result;
    }
    private Map<String, Object> m(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private List<Object> l(Object... values) { return new ArrayList<>(Arrays.asList(values)); }
    private Map<String, Object> scalar(String kind, String rep) {
        return m("kind", kind, "primReps", rep == null ? List.of() : List.of(rep), "evaluated", true);
    }
    private final Map<String, Object> state = scalar("void", null), integer = scalar("long", "IntRep"),
        lane = scalar("long", "Word32Rep"), word = scalar("long", "WordRep"), array = scalar("object", "BoxedRep (Just Unlifted)"), closure = scalar("closure", "BoxedRep (Just Lifted)");
    private final Map<String, Object> vector = m("kind", "vector", "primReps", List.of("VecRep 4 Word32ElemRep"),
        "evaluated", true, "vector", m("lanes", 4, "element", "Word32ElemRep"));
    private final Map<String, Object> signedVector = signedVector();
    private Map<String, Object> signedVector() {
        var result = new LinkedHashMap<>(vector);
        result.put("primReps", List.of("VecRep 4 Int32ElemRep")); result.put("vector", m("lanes", 4, "element", "Int32ElemRep")); return result;
    }
    private final Map<String, Object> unpacked = m("kind", "unknown", "primReps", Collections.nCopies(4, "Word32Rep"),
        "evaluated", true, "aggregate", "unboxed-tuple", "components", Collections.nCopies(4, lane));
    private final int[] written = {Integer.MIN_VALUE, 0x01234567, -1, Integer.MAX_VALUE};
    private final long[] weights = {3, 5, 7, 11};
    private Map<String, Object> readResult(boolean evaluated) {
        return m("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of("VecRep 4 Word32ElemRep"), "vector", vector.get("vector"),
            "components", List.of(state, vector), "evaluated", evaluated);
    }
    private Object copy(Object value) {
        if (value instanceof Map<?, ?> values) {
            var result = new LinkedHashMap<String, Object>();
            for (var entry : values.entrySet()) result.put((String) entry.getKey(), copy(entry.getValue())); return result;
        }
        if (value instanceof List<?> values) {
            var result = new ArrayList<Object>(); for (var item : values) result.add(copy(item)); return result;
        }
        return value;
    }
    private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private List<Object> list(Object value) { return (List<Object>) value; }
    private Map<String, Object> binder(String id, Map<String, Object> proof) {
        return m("id", id, "name", id, "lifted", false, "coercion", false, "rep", copy(proof));
    }
    private List<Object> variable(String id, Map<String, Object> proof) { return l("var", id, m("rep", copy(proof))); }
    private List<Object> literal(long value) { return literal(value, "int", integer); }
    private List<Object> literal(long value, String kind, Map<String, Object> proof) { return l("lit", kind, Long.toString(value), m("rep", copy(proof))); }
    private List<Object> call(String name, List<List<Object>> arguments, Map<String, Object> proof) {
        return l("app", List.of("prim", name, m("rep", closure)), new ArrayList<>(arguments),
            new ArrayList<>(Collections.nCopies(arguments.size(), false)), false, false, m("rep", copy(proof)));
    }
    private List<Object> checksumExpression() {
        var result = literal(0);
        for (int i = 0; i <= 3; i++) {
            var asWord = call("word32ToWord#", List.of(variable("lane" + i, lane)), word);
            var asInt = call("word2Int#", List.of(asWord), integer);
            var weighted = call("*#", List.of(asInt, literal(weights[i])), integer);
            result = call("+#", List.of(result, weighted), integer);
        }
        return result;
    }
    private List<Object> consume(List<Object> value) {
        var names = new ArrayList<String>(); var binders = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 4; i++) { names.add("lane" + i); binders.add(binder("lane" + i, lane)); }
        return l("case", call("unpackWord32X4#", List.of(value), unpacked), "lanes",
            l(l("data", "Tuple4", names, checksumExpression(), m("binders", binders))),
            m("rep", copy(integer), "binder", binder("lanes", unpacked)));
    }
    private List<Object> packed() {
        var fields = new ArrayList<List<Object>>(); for (var value : written) fields.add(literal(value & 0xffff_ffffL, "word32", lane));
        var tuple = l("app", List.of("con", "Tuple4", 4), fields, Collections.nCopies(4, false), false, true, m("rep", copy(unpacked)));
        return call("packWord32X4#", List.of(tuple), vector);
    }
    private record Fixture(Map<String, Object> module, List<Object> app, List<Object> body, List<Map<String, Object>> parameters) {}
    private Fixture fixture(VectorMemoryOp operation) {
        var parameters = List.of(binder("array", array), binder("offset", integer), binder("state", state));
        var operands = List.of(variable("array", array), variable("offset", integer));
        List<Object> app;
        if (operation.isRead()) {
            var args = new ArrayList<>(operands); args.add(variable("state", state)); app = call(operation.getPrimitive(), args, readResult(false));
        } else if (operation.isWrite()) {
            var args = new ArrayList<>(operands); args.add(packed()); args.add(variable("state", state)); app = call(operation.getPrimitive(), args, state);
        } else app = call(operation.getPrimitive(), operands, vector);
        List<Object> body;
        if (operation.isRead())
            body = l("case", app, "whole", l(l("data", "Tuple2", l("nextState", "vector"), consume(variable("vector", vector)),
                m("binders", l(binder("nextState", state), binder("vector", vector))))),
                m("rep", copy(integer), "binder", binder("whole", readResult(true))));
        else if (operation.isWrite())
            body = l("case", app, "afterWrite", l(l("default", null, List.of(),
                consume(call(operation.getScalarOffset() ? "indexWord32ArrayAsWord32X4#" : "indexWord32X4Array#", operands, vector)),
                m("binders", List.of()))), m("rep", copy(integer), "binder", binder("afterWrite", state)));
        else body = consume(app);
        var module = m("schema", 1, "ghc", "9.14.1", "instrument", true,
            "constructors", l(m("id", "Tuple2", "kind", "unboxed-tuple", "arity", 2), m("id", "Tuple4", "kind", "unboxed-tuple", "arity", 4)),
            "bindings", l(m("id", "root", "name", "root", "arity", 3, "lifted", true, "rep", copy(closure),
                "expr", l("lam", parameters, body, m("rep", copy(closure), "resultRep", copy(integer))))));
        return new Fixture(module, app, body, parameters);
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception { withLanguage(false, action); }
    private void withLanguage(boolean coldTransition, Action action) throws Exception {
        var builder = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw");
        if (coldTransition) builder.option("engine.SingleTierCompilationThreshold", "10000000");
        try (var context = builder.build()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private ExecutableProgram program(Language language, String backend, Fixture fixture, boolean diagnostic) {
        var module = new LinkedHashMap<>(fixture.module); module.put("diagnosticUnsupported", diagnostic);
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private Object invoke(ExecutableProgram program, byte[] bytes, long index) { return invoke(program, bytes, index, thc.runtime.Unit.INSTANCE); }
    private Object invoke(ExecutableProgram program, byte[] bytes, long index, Object token) {
        return Calls.target(program.hostEntryTarget(3), new Object[]{program.entryValue("root"), new Object[]{bytes, index, token}});
    }
    private int shift(int octet) { return 8 * (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? octet : 3 - octet); }
    private long expected(byte[] bytes, int offset) {
        long result = 0;
        for (int lane = 0; lane <= 3; lane++) {
            int value = 0; for (int octet = 0; octet <= 3; octet++) value |= (bytes[offset + lane * 4 + octet] & 255) << shift(octet);
            result += (value & 0xffff_ffffL) * weights[lane];
        }
        return result;
    }
    private void storeModel(byte[] bytes, int offset) {
        for (int lane = 0; lane <= 3; lane++) for (int octet = 0; octet <= 3; octet++) bytes[offset + lane * 4 + octet] = (byte) (written[lane] >>> shift(octet));
    }
    private byte[] bytes(int size, int multiplier, int bias) {
        var result = new byte[size]; for (int i = 0; i < size; i++) result[i] = (byte) (i * multiplier + bias); return result;
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    @Test void allSixExactContractsExecuteOnBothBackendsInBothLoadModes() throws Exception {
        assertEquals(CoreKind.VECTOR, CoreVectors.proofWord32.getKind());
        assertEquals(CoreVector.WORD32X4, CoreVectors.proofWord32.getVector());
        boolean allProofs = true;
        for (var operation : operations) if (!operation.getVectorProof().equals(CoreVectors.proofWord32)) { allProofs = false; break; }
        assertTrue(allProofs);
        var names = new LinkedHashSet<String>(); for (var op : operations) names.add(op.getPrimitive());
        assertEquals(Set.of("indexWord32X4Array#", "indexWord32ArrayAsWord32X4#", "readWord32X4Array#",
            "readWord32ArrayAsWord32X4#", "writeWord32X4Array#", "writeWord32ArrayAsWord32X4#"), names);
        for (var backend : List.of("ast", "bytecode")) for (boolean diagnostic : List.of(false, true)) withLanguage(language -> {
            for (var operation : operations) for (var indexRep : List.of("IntRep", "WordRep")) {
                // The binder shares a Long carrier; the operation keeps its exact index proof.
                var f = fixture(operation); f.parameters.get(1).put("rep", copy(scalar("long", indexRep)));
                var p = program(language, backend, f, diagnostic);
                for (long index = 0; index <= (operation.getScalarOffset() ? 3 : 1); index++) {
                    var bytes = bytes(40, 47, 129); var expectedBytes = bytes.clone();
                    int offset = (int) (index * (operation.getScalarOffset() ? 4 : 16));
                    if (operation.isWrite()) storeModel(expectedBytes, offset);
                    assertEquals(expected(expectedBytes, offset), invoke(p, bytes, index), backend + "/" + diagnostic + "/" + operation.getPrimitive() + "/" + indexRep + "/" + index);
                    assertArrayEquals(expectedBytes, bytes); released(language);
                }
                assertEquals(0L, ((Number) p.diagnostics().get("unsupportedTraps")).longValue());
            }
        });
    }
    private RootCallTarget active(RootCallTarget host, RootCallTarget original, String label) {
        var calls = new ArrayList<DirectCallNode>();
        for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)) if (call.getCallTarget() == original) calls.add(call);
        assertEquals(1, calls.size(), label);
        return (RootCallTarget) calls.getFirst().getCurrentCallTarget();
    }
    private boolean valid(RootCallTarget target) throws Exception { return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private long count(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private void reject(Language language, String backend, boolean diagnostic, Fixture fixture, String label) {
        var bytes = bytes(40, 13, 97); var before = bytes.clone();
        assertThrows(RuntimeFault.class, () -> invoke(program(language, backend, fixture, diagnostic), bytes, 0), label);
        assertArrayEquals(before, bytes, "rejected before effects " + label); released(language);
    }
    @Test void allOperationsRejectWrongArgumentsFlagsArityAndResultProofs() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean diagnostic : List.of(false, true)) withLanguage(language -> {
            for (var operation : operations) {
                var mutations = new ArrayList<>(List.of("array-levity", "array-unknown", "index-signedness", "index-unknown", "lexical-array", "lexical-index-float", "lexical-index-double", "partial", "over", "result"));
                if (operation.isRead() || operation.isWrite()) mutations.addAll(List.of("state", "state-unknown"));
                for (var mutation : mutations) {
                    var f = fixture(operation); var args = list(f.app.get(2)); var flags = list(f.app.get(3));
                    switch (mutation) {
                        case "array-levity" -> map(list(args.get(0)).get(2)).put("rep", copy(scalar("object", "BoxedRep (Just Lifted)")));
                        case "array-unknown" -> map(map(list(args.get(0)).get(2)).get("rep")).put("kind", "unknown");
                        case "index-signedness" -> map(list(args.get(1)).get(2)).put("rep", copy(scalar("long", "WordRep")));
                        case "index-unknown" -> map(map(list(args.get(1)).get(2)).get("rep")).put("kind", "unknown");
                        case "lexical-array" -> f.parameters.get(0).put("rep", copy(scalar("object", "BoxedRep (Just Lifted)")));
                        case "lexical-index-float" -> f.parameters.get(1).put("rep", copy(scalar("float", "FloatRep")));
                        case "lexical-index-double" -> f.parameters.get(1).put("rep", copy(scalar("double", "DoubleRep")));
                        case "partial" -> { args.removeLast(); flags.removeLast(); }
                        case "over" -> { args.add(copy(args.getFirst())); flags.add(false); }
                        case "result" -> map(f.app.get(6)).remove("rep");
                        case "state" -> map(list(args.getLast()).get(2)).put("rep", copy(integer));
                        case "state-unknown" -> map(map(list(args.getLast()).get(2)).get("rep")).put("kind", "unknown");
                    }
                    reject(language, backend, diagnostic, f, backend + "/" + diagnostic + "/" + operation + "/" + mutation);
                }
                int arguments = list(fixture(operation).app.get(2)).size();
                for (int index = 0; index < arguments; index++) for (var flag : l(true, null, 0L, "false")) {
                    var f = fixture(operation); list(f.app.get(3)).set(index, flag);
                    reject(language, backend, diagnostic, f, backend + "/" + diagnostic + "/" + operation + "/flag[" + index + "]=" + flag);
                }
                if (!operation.isRead()) for (var count : l(4.0, 4.5, true, "4", null)) {
                    var f = fixture(operation);
                    var proof = operation.isWrite() ? map(map(list(list(f.app.get(2)).get(2)).get(6)).get("rep")) : map(map(f.app.get(6)).get("rep"));
                    map(proof.get("vector")).put("lanes", count);
                    reject(language, backend, diagnostic, f, backend + "/" + diagnostic + "/" + operation + "/direct lanes=" + count);
                }
                if (operation.isWrite()) {
                    var f = fixture(operation); map(list(list(f.app.get(2)).get(2)).get(6)).put("rep", copy(signedVector));
                    reject(language, backend, diagnostic, f, backend + "/" + diagnostic + "/" + operation + "/signed vector");
                    var sameCarrier = fixture(operation);
                    var pack = list(list(sameCarrier.app.get(2)).get(2));
                    var tuple = list(list(pack.get(2)).get(0));
                    var firstLane = list(list(tuple.get(2)).get(0));
                    // The word32 tag supplies unsigned semantics, not this duplicate Long annotation.
                    map(firstLane.get(3)).put("rep", copy(scalar("long", "Int32Rep")));
                    var p = program(language, backend, sameCarrier, diagnostic);
                    for (long index = 0; index <= (operation.getScalarOffset() ? 3 : 1); index++) {
                        var bytes = bytes(40, 47, 129); var expectedBytes = bytes.clone();
                        int offset = (int) (index * (operation.getScalarOffset() ? 4 : 16)); storeModel(expectedBytes, offset);
                        assertEquals(expected(expectedBytes, offset), invoke(p, bytes, index), backend + "/" + diagnostic + "/" + operation + "/signed lane annotation/" + index);
                        assertArrayEquals(expectedBytes, bytes); released(language);
                    }
                    for (var kind : List.of("float", "double")) {
                        var rep = kind.equals("float") ? "FloatRep" : "DoubleRep";
                        var wrongLiteral = fixture(operation);
                        var wrongPack = list(list(wrongLiteral.app.get(2)).get(2));
                        var wrongTuple = list(list(wrongPack.get(2)).get(0));
                        var wrongLane = list(list(wrongTuple.get(2)).get(0));
                        map(wrongLane.get(3)).put("rep", copy(scalar(kind, rep)));
                        reject(language, backend, diagnostic, wrongLiteral, backend + "/" + diagnostic + "/" + operation + "/" + kind + " lane literal carrier");
                    }
                }
            }
        });
    }
    @Test void immediateReadCasesRejectEscapesWrongShapesAndPatternMetadata() throws Exception {
        var mutations = List.of("whole-escape", "missing-vector", "signed-result", "width", "components", "component-state", "component-unknown",
            "default", "multiple", "constructor", "arity", "duplicate-pattern", "whole-pattern", "pattern-order",
            "pattern-state", "pattern-signed", "pattern-unknown", "whole-levity", "pattern-levity", "whole-coercion", "pattern-coercion",
            "missing-whole-coercion", "missing-pattern-coercion", "whole-id", "whole-unevaluated", "pattern-unevaluated", "outer-result");
        for (var backend : List.of("ast", "bytecode")) for (boolean diagnostic : List.of(false, true)) withLanguage(language -> {
            for (var operation : operations) if (operation.isRead()) for (var mutation : mutations) {
                var f = fixture(operation); var alternative = list(list(f.body.get(3)).get(0));
                var whole = map(map(f.body.get(4)).get("binder")); var records = list(map(alternative.get(4)).get("binders"));
                var result = map(map(f.app.get(6)).get("rep"));
                switch (mutation) {
                    case "whole-escape" -> alternative.set(3, l("var", "whole"));
                    case "missing-vector" -> result.remove("vector");
                    case "signed-result" -> { result.put("vector", signedVector.get("vector")); result.put("primReps", signedVector.get("primReps")); }
                    case "width" -> map(result.get("vector")).put("lanes", 2);
                    case "components" -> Collections.reverse(list(result.get("components")));
                    case "component-state" -> list(result.get("components")).set(0, copy(integer));
                    case "component-unknown" -> map(list(result.get("components")).get(1)).put("kind", "unknown");
                    case "default" -> alternative.set(0, "default");
                    case "multiple" -> list(f.body.get(3)).add(copy(alternative));
                    case "constructor" -> list(f.module.get("constructors")).remove(0);
                    case "arity" -> map(list(f.module.get("constructors")).get(0)).put("arity", 4);
                    case "duplicate-pattern" -> list(alternative.get(2)).set(1, "nextState");
                    case "whole-pattern" -> list(alternative.get(2)).set(1, "whole");
                    case "pattern-order" -> Collections.reverse(records);
                    case "pattern-state" -> map(records.get(0)).put("rep", copy(integer));
                    case "pattern-signed" -> map(records.get(1)).put("rep", copy(signedVector));
                    case "pattern-unknown" -> map(map(records.get(1)).get("rep")).put("kind", "unknown");
                    case "whole-levity" -> whole.put("lifted", true);
                    case "pattern-levity" -> map(records.get(1)).put("lifted", true);
                    case "whole-coercion" -> whole.put("coercion", true);
                    case "pattern-coercion" -> map(records.get(1)).put("coercion", true);
                    case "missing-whole-coercion" -> whole.remove("coercion");
                    case "missing-pattern-coercion" -> map(records.get(0)).remove("coercion");
                    case "whole-id" -> whole.put("id", "other");
                    case "whole-unevaluated" -> map(whole.get("rep")).put("evaluated", false);
                    case "pattern-unevaluated" -> map(map(records.get(1)).get("rep")).put("evaluated", false);
                    case "outer-result" -> map(f.body.get(4)).put("rep", copy(scalar("double", "DoubleRep")));
                }
                reject(language, backend, diagnostic, f, backend + "/" + diagnostic + "/" + operation + "/" + mutation);
            }
            for (var operation : operations) if (operation.isRead()) {
                for (var site : List.of("producer", "whole", "producer-component", "whole-component", "pattern"))
                    for (var count : l(4.0, 4.5, true, "4", null)) {
                        var f = fixture(operation);
                        var producer = map(map(f.app.get(6)).get("rep"));
                        var whole = map(map(map(f.body.get(4)).get("binder")).get("rep"));
                        var alternative = list(list(f.body.get(3)).get(0));
                        var shape = switch (site) {
                            case "producer" -> producer;
                            case "whole" -> whole;
                            case "producer-component" -> map(list(producer.get("components")).get(1));
                            case "whole-component" -> map(list(whole.get("components")).get(1));
                            default -> map(map(list(map(alternative.get(4)).get("binders")).get(1)).get("rep"));
                        };
                        map(shape.get("vector")).put("lanes", count);
                        reject(language, backend, diagnostic, f, backend + "/" + diagnostic + "/" + operation + "/" + site + "/lanes=" + count);
                    }
                for (var arity : l(2.0, 2.5, true, "2", null)) {
                    var f = fixture(operation); map(list(f.module.get("constructors")).get(0)).put("arity", arity);
                    reject(language, backend, diagnostic, f, backend + "/" + diagnostic + "/" + operation + "/arity=" + arity);
                }
            }
        });
    }
    @Test void stateExpressionsRunBeforeReadOrWriteAndFailurePrecedesVectorBounds() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean diagnostic : List.of(false, true)) withLanguage(language -> {
            for (var operation : operations) if (!operation.isIndex()) for (boolean failure : List.of(false, true)) {
                var f = fixture(operation);
                long stride = operation.getScalarOffset() ? 4L : 16L;
                var effectIndex = failure ? literal(Long.MAX_VALUE) : call("*#", List.of(variable("offset", integer), literal(stride)), integer);
                var args = list(f.app.get(2));
                args.set(args.size() - 1, call("writeWord8Array#", List.of(variable("array", array), effectIndex,
                    literal(93, "word8", scalar("long", "Word8Rep")), variable("state", state)), state));
                var p = program(language, backend, f, diagnostic);
                var bytes = bytes(40, 37, 161); var expectedBytes = bytes.clone();
                if (failure) {
                    var error = assertThrows(RuntimeFault.class, () -> invoke(p, bytes, Long.MAX_VALUE));
                    assertTrue(Objects.toString(error.getMessage(), "").contains("ByteArray# index outside"), "State must fail before vector bounds: " + error);
                } else {
                    int offset = (int) stride; expectedBytes[offset] = 93;
                    if (operation.isWrite()) storeModel(expectedBytes, offset);
                    assertEquals(expected(expectedBytes, offset), invoke(p, bytes, 1));
                }
                assertArrayEquals(expectedBytes, bytes); released(language);
            }
            for (var operation : operations) if (!operation.isIndex()) {
                var p = program(language, backend, fixture(operation), diagnostic);
                for (long index : List.of(0L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                    var bytes = bytes(40, 0, 37); var before = bytes.clone();
                    var error = assertThrows(RuntimeFault.class, () -> invoke(p, bytes, index, 0L));
                    assertTrue(Objects.toString(error.getMessage(), "").contains("zero-width"), "invalid State must precede bounds: " + error);
                    assertArrayEquals(before, bytes); released(language);
                }
                for (long index : List.of(-1L, 10L, 1L << 32, Long.MAX_VALUE)) {
                    var bytes = bytes(40, 0, 37); var before = bytes.clone();
                    assertThrows(RuntimeFault.class, () -> invoke(p, bytes, index));
                    assertArrayEquals(before, bytes); released(language);
                }
            }
        });
    }
    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
    private String failureDiagnostic(String label, RootCallTarget target, RootCallTarget host, RootCallTarget original) {
        try {
            return label + " exact compiled guest entry; targetValid=" + valid(target) +
                "; sameTarget=" + (target == active(host, original, label + " selected guest call")) + "; hostValid=" + valid(host);
        } catch (Exception failure) { return Word32VectorMemoryProofTest.<String, RuntimeException>rethrow(failure); }
    }
    private record Invalid(int size, long index) {}
    private void validCall(ExecutableProgram p, Language language, VectorMemoryOp operation, int stride, String label, String phase) {
        var bytes = bytes(40, 47, 129); var expectedBytes = bytes.clone();
        if (operation.isWrite()) storeModel(expectedBytes, stride);
        assertEquals(expected(expectedBytes, stride), invoke(p, bytes, 1), label + "/" + phase);
        assertArrayEquals(expectedBytes, bytes, label + "/" + phase + " backing bytes"); released(language);
    }
    @Test void installedGuestBoundsFailuresDeoptimizeWithoutEffectsAndRecover() throws Exception {
        int[] transitions = {0};
        for (var backend : List.of("ast", "bytecode")) for (var operation : operations) {
            int stride = operation.getScalarOffset() ? 4 : 16;
            var invalid = List.of(new Invalid(0, 0), new Invalid(15, 0), new Invalid(40, -1), new Invalid(40, Long.MIN_VALUE), new Invalid(40, Long.MAX_VALUE),
                new Invalid(40, 1L << 32), new Invalid(40, (long) Integer.MAX_VALUE + 1), new Invalid(40, (40L - 16) / stride + 1));
            for (var bad : invalid) withLanguage(true, language -> {
                var label = backend + "/" + operation.getPrimitive() + "/size=" + bad.size + "/index=" + bad.index;
                var p = program(language, backend, fixture(operation), false);
                var host = p.hostEntryTarget(3); var original = p.entryTarget("root");
                // Fixed profiling only: fresh storage on every invocation, then one
                // compilation. Keep the host bridge interpreted so the guest owns
                // the compiled bounds failure and its invalidation.
                for (int i = 0; i < 40; i++) validCall(p, language, operation, stride, label, "warm/" + i);
                var target = active(host, original, label + " selected guest call");
                assertEquals(0L, count(p), label + " no automatic compiled entries");
                assertFalse(valid(target), label + " no automatic installation");
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                // Restore the shared entry stub without executing a settling guest call.
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                assertTrue(valid(target), label + " installed guest");
                long before = count(p); validCall(p, language, operation, stride, label, "installed");
                assertEquals(before + 1, count(p), () -> failureDiagnostic(label, target, host, original));
                assertSame(target, active(host, original, label + " selected guest call"), label + " active installed identity");
                assertTrue(valid(target), label + " installed immediately before invalid input");
                assertFalse(valid(host), label + " host bridge remains interpreted");
                var bytes = bytes(bad.size, 19, 83); var unchanged = bytes.clone();
                var sentinel = new Object(); Object[] published = {sentinel};
                var error = assertThrows(RuntimeFault.class, () -> published[0] = invoke(p, bytes, bad.index), label);
                assertEquals("Vector ByteArray# range outside its backing storage", error.getMessage(), label);
                assertSame(sentinel, published[0], label + " no failed result publication");
                assertArrayEquals(unchanged, bytes, label + " no partial memory effects"); released(language);
                assertSame(target, active(host, original, label + " selected guest call"), label + " same target after failure");
                assertFalse(valid(target), label + " bounds failure invalidates installed guest");
                long afterFailure = count(p); validCall(p, language, operation, stride, label, "recovery");
                assertEquals(afterFailure, count(p), label + " recovery without recompilation");
                assertSame(target, active(host, original, label + " selected guest call"), label + " recovery target identity");
                assertFalse(valid(target), label + " no recovery recompilation");
                for (var counter : List.of("unsupportedTraps", "blackholes"))
                    assertEquals(0L, ((Number) p.diagnostics().get(counter)).longValue(), label + "/" + counter);
                transitions[0]++;
            });
        }
        assertEquals(96, transitions[0]);
    }
    private Expr operand(String name, List<String> events, Supplier<Object> action) {
        return new Expr() {
            @Override public Object execute(VirtualFrame frame) { events.add(name); return action.get(); }
        };
    }
    @Test void directAstOperandsPreserveStateOrderAndDoNotPublishFailedLoads() {
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        for (var operation : operations) if (!operation.isIndex()) {
            var bytes = bytes(32, 0, 53); var before = bytes.clone(); var events = new ArrayList<String>();
            var failure = new RuntimeFault("state marker");
            var arguments = new ArrayList<Expr>(List.of(operand("array", events, () -> bytes), operand("index", events, () -> Long.MAX_VALUE)));
            if (operation.isWrite()) arguments.add(operand("vector", events,
                () -> IntVector.broadcast(IntVector.SPECIES_128, 1).withLane(1, 2).withLane(2, 3).withLane(3, 4)));
            arguments.add(operand("state", events, () -> { throw failure; }));
            var expression = new VectorByteArrayExpression(operation, arguments.toArray(Expr[]::new));
            var sentinel = new Object(); Object[] published = {sentinel};
            assertSame(failure, assertThrows(RuntimeFault.class, () -> published[0] = expression.execute(frame)));
            assertSame(sentinel, published[0]); assertArrayEquals(before, bytes);
            assertEquals(operation.isWrite() ? List.of("array", "index", "vector", "state") : List.of("array", "index", "state"), events);
        }
    }
}
