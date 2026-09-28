// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Core proofs and lexical joins remain sound at the public bytecode boundary. */
class BytecodeCoreProofTest {
    private final Map<String, Object> longRep = rep("long", list("IntRep"), true), wordRep = rep("long", list("WordRep"), true),
        dataRep = rep("data", list("BoxedRep (Just Lifted)"), true), closureRep = rep("closure", list("BoxedRep (Just Lifted)"), true), addressRep = rep("address", list("AddrRep"), true);
    private Map<String, Object> rep(String kind, List<String> registers, boolean evaluated) { return map("kind", kind, "primReps", registers, "evaluated", evaluated); }
    private Map<String, Object> meta(Map<String, Object> proof) { return map("rep", proof); }
    private List<Object> variable(String id) { return variable(id, longRep); }
    private List<Object> variable(String id, Map<String, Object> proof) { return list("var", id, meta(proof)); }
    private List<Object> integer(long n) { return list("lit", "int", Long.toString(n), meta(longRep)); }
    private Map<String, Object> binder(String id) { return binder(id, longRep, false); }
    private Map<String, Object> binder(String id, Map<String, Object> proof, boolean lifted) { return map("id", id, "name", id, "type", "Synthetic", "lifted", lifted, "coercion", false, "rep", proof); }
    private List<Object> lambda(List<Map<String, Object>> parameters, List<Object> body) { return lambda(parameters, body, longRep); }
    private List<Object> lambda(List<Map<String, Object>> parameters, List<Object> body, Map<String, Object> result) { return list("lam", parameters, body, map("rep", closureRep, "resultRep", result)); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args) { return apply(fn, args, longRep); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, Map<String, Object> result) { return list("app", fn, args, Collections.nCopies(args.size(), false), false, false, meta(result)); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return apply(list("prim", name), list(args)); }
    private Map<String, Object> binding(String id, List<Object> rhs, boolean lifted, Map<String, Object> proof) { return with(binder(id, proof, lifted), "arity", rhs.getFirst().equals("lam") ? ((List<?>) rhs.get(1)).size() : 0, "expr", rhs); }
    private Map<String, Object> join(String id, List<Map<String, Object>> parameters, List<Object> body) { return join(id, parameters, body, parameters.size(), longRep); }
    private Map<String, Object> join(String id, List<Map<String, Object>> parameters, List<Object> body, int arity, Map<String, Object> result) { return with(binding(id, lambda(parameters, body), true, closureRep), "joinValueArity", arity, "joinResultRep", result); }
    private List<Object> local(List<Map<String, Object>> group, List<Object> body) { return local(group, body, false, longRep); }
    private List<Object> local(List<Map<String, Object>> group, List<Object> body, boolean recursive, Map<String, Object> result) { return list("let", recursive, group, body, meta(result)); }
    private List<Object> caseOf(List<Object> value, String id, List<List<Object>> alternatives, Map<String, Object> inputRep, Map<String, Object> result) { return list("case", value, id, alternatives, map("rep", result, "binder", binder(id, inputRep, inputRep.get("kind").equals("data")))); }
    private List<Object> chooseZero(List<Object> value, List<Object> yes, List<Object> no) { return caseOf(value, "choice", list(list("lit", list("int", "0"), list(), yes), list("default", null, list(), no)), longRep, longRep); }
    private final Map<String, Object> box = map("id", "Box", "name", "Box", "arity", 1, "tag", 1, "kind", "boxed", "strictFields", list(false), "fieldLifted", list(false), "fieldReps", list(list("IntRep")));
    private List<Object> construct(List<Object> n) { return apply(list("con", "Box", 1, meta(closureRep)), list(n), dataRep); }
    private List<Object> unpack(List<Object> value, List<Object> body) { return caseOf(value, "boxValue", list(list("data", "Box", list("field"), body, map("binders", list(binder("field"))))), dataRep, longRep); }
    private String request(List<Object> body) { return request(body, list(), false, list(box)); }
    private String request(List<Object> body, List<Map<String, Object>> extras, boolean diagnostic, List<Map<String, Object>> constructors) {
        var bindings = new ArrayList<>(extras); bindings.add(binding("entry", lambda(list(binder("input")), body), true, closureRep));
        return Json.stringify(map("entry", "entry", "backend", "bytecode", "instrument", true, "diagnosticUnsupported", diagnostic,
            "modules", list(map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.BytecodeProofs", "constructors", constructors, "bindings", bindings))));
    }
    private long count(Value fn, String key) { return ((Number) object(Json.parse(fn.getMember("diagnostics").asString())).get(key)).longValue(); }
    private void compile(Value fn, long input, long expected) {
        for (int i = 0; i < 30; i++) assertEquals(expected, fn.execute(input).asLong());
        assertTrue(fn.invokeMember("compile").asBoolean()); assertEquals(expected, fn.execute(input).asLong()); assertTrue(count(fn, "compiledEntries") > 0);
    }
    @Test void primitiveIdentityCompilesAfterOneOrdinaryInvocation() {
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(variable("input"))); assertEquals(3_000_000_000L, fn.execute(3_000_000_000L).asLong());
            assertFalse(fn.getMember("bytecode").asString().contains("c.Force")); assertTrue(fn.invokeMember("compile").asBoolean()); assertEquals(Long.MIN_VALUE, fn.execute(Long.MIN_VALUE).asLong()); assertTrue(count(fn, "compiledEntries") > 0);
        }
    }
    @Test void diagnosticGlobalCannotRetainTheUnsupportedConstructorWhnfProof() {
        var unsupported = binding("unsupported", list("con", "UnsupportedTuple", 0, meta(dataRep)), true, dataRep);
        var body = chooseZero(variable("input"), integer(42), unpack(variable("unsupported", dataRep), variable("field")));
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(body, list(unsupported), true, list(box, map("id", "UnsupportedTuple", "kind", "unboxed-tuple")))); compile(fn, 0, 42);
            var error = assertThrows(PolyglotException.class, () -> fn.execute(1L)); assertTrue(error.getMessage().contains("Diagnostic unsupported path reached"), error.getMessage()); assertEquals(1L, count(fn, "unsupportedTraps"));
        }
    }
    @Test void provenLongCaseAndLetOmitAdaptiveForcingInstructions() {
        var rhs = chooseZero(variable("input"), integer(Long.MIN_VALUE), primitive("*#", variable("input"), integer(3)));
        var body = local(list(binding("saved", rhs, false, longRep)), primitive("+#", variable("saved"), integer(17)));
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(body)); compile(fn, 0, Long.MIN_VALUE + 17); assertEquals(38L, fn.execute(7L).asLong()); var dump = fn.getMember("bytecode").asString();
            assertFalse(dump.contains("c.ForceValue"), dump); assertFalse(dump.contains("c.ForceLocal"), dump); assertFalse(dump.contains("c.ReadCellIfNeeded"), dump);
        }
    }
    @Test void capturedAddressesRemainObjectsAndCapturedLongsUsePrimitiveReads() {
        var address = list("lit", "string-bytes", "61626300", meta(addressRep));
        var captured = lambda(list(binder("offset")), primitive("+#", primitive("ord#", apply(list("prim", "indexCharOffAddr#"), list(variable("address", addressRep), variable("offset")), wordRep)), variable("saved")));
        var body = local(list(binding("address", address, false, addressRep)), local(list(binding("saved", integer(17), false, longRep)), apply(captured, list(variable("input")))));
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(body)); compile(fn, 0, 114); assertEquals(116L, fn.execute(2L).asLong()); assertTrue(fn.getMember("bytecode").asString().contains("c.CaptureReadLong"));
        }
    }
    @Test void nestedNonrecursiveJoinsBranchToAncestorsWithoutLoopScaffolding() {
        var outer = join("finish", list(binder("answer")), primitive("+#", variable("answer"), integer(17)));
        var inner = apply(variable("finish", closureRep), list(variable("input")));
        for (int index = 0; index < 25; index++) { var name = "step" + index; inner = local(list(join(name, list(binder("unused" + index)), inner)), apply(variable(name, closureRep), list(variable("input")))); }
        var body = local(list(outer), inner);
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(body)); compile(fn, 3_000_000_000L, 3_000_000_017L);
            for (long input : new long[]{Long.MIN_VALUE, -4097L, 0L, Long.MAX_VALUE}) { long before = count(fn, "compiledEntries"); assertEquals(input + 17, fn.execute(input).asLong()); assertTrue(count(fn, "compiledEntries") > before); }
            var dump = fn.getMember("bytecode").asString(); assertFalse(dump.contains("join selector"), dump); assertFalse(dump.contains("c.Apply"), dump);
            assertEquals(26L, count(fn, "localJoinCount")); assertEquals(2L, count(fn, "bytecodeRootCount")); assertEquals(0L, count(fn, "trampolineIterations"));
        }
    }
    @Test void recursiveJoinUsesParallelMovesAndPreservesNonTailContinuation() {
        var body = chooseZero(variable("n"), primitive("+#", primitive("*#", variable("x"), integer(100)), variable("y")),
            apply(variable("loop", closureRep), list(primitive("-#", variable("n"), integer(1)), variable("y"), variable("x"))));
        var region = local(list(join("loop", list(binder("n"), binder("x"), binder("y")), body)), apply(variable("loop", closureRep), list(variable("input"), integer(1), integer(2))), true, longRep);
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(primitive("+#", integer(17), region))); compile(fn, 0, 119); assertEquals(218L, fn.execute(100_001L).asLong()); assertEquals(119L, fn.execute(100_000L).asLong());
            assertEquals(2L, count(fn, "bytecodeRootCount"), "Join must not allocate a guest call target"); assertEquals(1L, count(fn, "localJoinCount")); assertTrue(count(fn, "localJoinTransfers") >= 200_003L);
            assertEquals(0L, count(fn, "tailBounces")); assertEquals(0L, count(fn, "papAllocations")); assertFalse(fn.getMember("bytecode").asString().contains("c.Apply"));
        }
    }
    private List<Object> worker(String other) { return chooseZero(variable("n"), variable("acc"), apply(variable(other, closureRep), list(primitive("-#", variable("n"), integer(1)), primitive("+#", variable("acc"), integer(1))))); }
    @Test void mutuallyRecursiveJoinsStayInsideTheirOwningRoot() {
        var region = local(list(join("left", list(binder("n"), binder("acc")), worker("right")), join("right", list(binder("n"), binder("acc")), worker("left"))), apply(variable("left", closureRep), list(variable("input"), integer(0))), true, longRep);
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(primitive("+#", region, integer(17)))); compile(fn, 0, 17); assertEquals(100_018L, fn.execute(100_001L).asLong());
            assertEquals(2L, count(fn, "bytecodeRootCount")); assertEquals(2L, count(fn, "localJoinCount")); assertEquals(0L, count(fn, "trampolineIterations")); assertFalse(fn.getMember("bytecode").asString().contains("c.Apply"));
        }
    }
    @Test void adjustedJoinArityCanReturnTheRestOfAFlattenedLambda() {
        for (int arity : new int[]{0, 1}) {
            var parameters = arity == 0 ? list(binder("extra")) : list(binder("first"), binder("extra")); var sum = primitive("+#", variable(arity == 0 ? "input" : "first"), variable("extra"));
            var declaration = join("answer", parameters, sum, arity, closureRep); var jump = arity == 0 ? variable("answer", closureRep) : apply(variable("answer", closureRep), list(variable("input")), closureRep);
            var region = local(list(declaration), jump, false, closureRep);
            try (var context = Main.executionContext(false)) {
                var fn = context.eval("thc", request(apply(region, list(integer(17))))); compile(fn, 1, 18); assertEquals(42L, fn.execute(25L).asLong());
                assertEquals(1L, count(fn, "localJoinCount")); assertEquals(3L, count(fn, "bytecodeRootCount"), "Only the returned function needs a new root"); assertEquals(0L, count(fn, "papAllocations"));
            }
        }
    }
    private record Body(List<Object> expression, List<Map<String, Object>> globals) {}
    @Test void evaluatedCoreVarsStillForceOurCafAndRecursiveAliasThunks() {
        var caf = binding("caf", construct(integer(9)), true, dataRep); var fromCaf = unpack(variable("caf", dataRep), primitive("+#", variable("field"), variable("input")));
        var group = list(binding("cell", construct(integer(9)), true, dataRep), binding("alias", variable("cell", dataRep), true, dataRep));
        var fromAlias = local(group, unpack(variable("alias", dataRep), primitive("+#", variable("field"), variable("input"))), true, longRep);
        for (var body : list(new Body(fromCaf, list(caf)), new Body(fromAlias, list()))) try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(body.expression(), body.globals(), false, list(box))); compile(fn, 1, 10); assertEquals(16L, fn.execute(7L).asLong()); assertTrue(count(fn, "thunkEvaluations") > 0); assertEquals(0L, count(fn, "blackholes"));
        }
    }
    @Test void malformedJoinsAreRejectedEvenInDiagnosticMode() {
        var declaration = join("j", list(binder("n")), variable("n"));
        var bad = list(variable("j", closureRep), primitive("+#", apply(variable("j", closureRep), list(variable("input"))), integer(1)),
            apply(variable("j", closureRep), list(variable("input"), integer(2))), lambda(list(binder("later")), apply(variable("j", closureRep), list(variable("later")))));
        for (var body : bad) try (var context = Main.executionContext(false)) {
            var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(local(list(declaration), body), list(), true, list(box)))); assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains("join"), error.getMessage());
        }
    }
    @Test void diagnosticSubstitutionCannotAcquireItsOriginalLongWhnfProof() {
        var unsupported = primitive("notARealPrim#", integer(1)); var body = primitive("+#", chooseZero(variable("input"), integer(41), unsupported), integer(1));
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(body, list(), true, list(box))); compile(fn, 0, 42); var error = assertThrows(PolyglotException.class, () -> fn.execute(1L));
            assertTrue(error.getMessage().contains("Diagnostic unsupported path reached"), error.getMessage()); assertEquals(1L, count(fn, "unsupportedTraps"));
        }
    }
}
