// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import jdk.incubator.vector.*;
import static org.junit.jupiter.api.Assertions.*;

/** Bytecode transport controls; these do not replace exported-Core/native SIMD evidence. */
@SuppressWarnings("unchecked")
class BytecodeVectorTransportTest {
    private static Map<String, Object> m(Object... pairs) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
    private List<Object> l(Object... values) { return Arrays.asList(values); }
    private Map<String, Object> scalar(String kind, String rep) { return m("kind", kind, "primReps", List.of(rep), "evaluated", true); }
    private final Map<String, Object> integer = scalar("long", "IntRep"), word = scalar("long", "WordRep"), closure = scalar("closure", "BoxedRep (Just Lifted)");
    private Map<String, Object> tuple(List<Map<String, Object>> fields) {
        var reps = new ArrayList<String>(); for (var field : fields) reps.addAll((List<String>) field.get("primReps"));
        return m("kind", "unknown", "aggregate", "unboxed-tuple", "components", fields, "primReps", reps, "evaluated", true);
    }
    private static final class Family {
        final String name, element, laneName;
        final int lanes;
        final Map<String, Object> lane, vector;
        Family(String name, String element, int lanes) {
            this.name = name; this.element = element; this.lanes = lanes; laneName = element.substring(0, element.length() - "ElemRep".length());
            lane = m("kind", switch (laneName) { case "Float" -> "float"; case "Double" -> "double"; default -> "long"; }, "primReps", List.of(laneName + "Rep"), "evaluated", true);
            vector = m("kind", "vector", "primReps", List.of("VecRep " + lanes + " " + element), "vector", m("lanes", lanes, "element", element), "evaluated", true);
        }
    }
    private List<Family> families() throws Exception {
        var table = (Map<String, Object>) Json.parse(Files.readString(new File(System.getProperty("thc.projectRoot"), "bin/simd-families.json").toPath()));
        var result = new ArrayList<Family>(); for (var record : (List<Map<String, Object>>) table.get("families"))
            result.add(new Family((String) record.get("name"), (String) record.get("element"), ((Number) record.get("lanes")).intValue()));
        assertEquals(30, result.size()); return result;
    }
    private Family family(String name) throws Exception {
        var result = new ArrayList<Family>(); for (var family : families()) if (family.name.equals(name)) result.add(family); assertEquals(1, result.size()); return result.getFirst();
    }
    private List<Object> variable(String id) { return variable(id, integer); }
    private List<Object> variable(String id, Map<String, Object> proof) { return l("var", id, m("rep", proof)); }
    private List<Object> number(long value) { return l("lit", "int", Long.toString(value), m("rep", integer)); }
    private Map<String, Object> parameter(String id) { return parameter(id, integer); }
    private Map<String, Object> parameter(String id, Map<String, Object> proof) { return m("id", id, "name", id, "lifted", proof.equals(closure), "rep", proof); }
    private List<Object> application(List<Object> fn, List<List<Object>> args, Map<String, Object> result) {
        return l("app", fn, args, Collections.nCopies(args.size(), false), false, false, m("rep", result));
    }
    private List<Object> call(String id, List<List<Object>> args, Map<String, Object> result) { return application(variable(id, closure), args, result); }
    private List<Object> primitive(String id, List<List<Object>> args) { return primitive(id, args, integer); }
    private List<Object> primitive(String id, List<List<Object>> args, Map<String, Object> result) { return application(l("prim", id), args, result); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { return l("lam", args, body, m("rep", closure, "resultRep", result, "entryStrict", Collections.nCopies(args.size(), false))); }
    private Map<String, Object> binding(String id, List<Object> body) { return m("id", id, "name", id, "lifted", true, "rep", closure, "expr", body); }
    private List<Object> vectorLet(String id, Map<String, Object> proof, List<Object> rhs, List<Object> body) { return vectorLet(id, proof, rhs, body, false, false); }
    private List<Object> vectorLet(String id, Map<String, Object> proof, List<Object> rhs, List<Object> body, boolean recursive, boolean lifted) {
        return l("let", recursive, List.of(m("id", id, "name", id, "lifted", lifted, "rep", proof, "expr", rhs)), body);
    }
    private List<Object> localLets(Map<String, Object> vector, List<Object> payload) {
        return vectorLet("x", vector, call("identity", List.of(payload), vector), vectorLet("x", vector, call("identity", List.of(variable("x", vector)), vector),
            vectorLet("copied", vector, variable("x", vector), variable("copied", vector))));
    }
    private List<Object> choice(List<Object> condition, List<Object> yes, List<Object> no, Map<String, Object> result) {
        return l("case", condition, "condition", l(l("lit", l("int", "1"), List.of(), yes), l("default", null, List.of(), no)), m("rep", result, "binder", parameter("condition")));
    }
    private List<Object> laneValue(Family family, List<Object> value) {
        var operation = switch (family.laneName) { case "Float" -> "int2Float#"; case "Double" -> "int2Double#"; default -> (family.laneName.startsWith("Word") ? "wordTo" : "intTo") + family.laneName + "#"; };
        return primitive(operation, List.of(family.laneName.startsWith("Word") ? primitive("int2Word#", List.of(value), word) : value), family.lane);
    }
    private List<Object> lanes(Family family, List<Object> input) {
        var values = new ArrayList<List<Object>>(); for (int index = 0; index < family.lanes; index++) values.add(laneValue(family, primitive("+#", List.of(input, number(index)))));
        var fields = tuple(Collections.nCopies(family.lanes, family.lane));
        return primitive("pack" + family.name + "#", List.of(application(l("con", "T" + family.lanes, family.lanes), values, fields)), family.vector);
    }
    private Map<String, Object> function(String id, String next, Map<String, Object> vector) {
        return binding(id, lambda(List.of(parameter("v", vector), parameter("n")), choice(primitive("<=#", List.of(variable("n"), number(0))), variable("v", vector),
            call(next, List.of(variable("v", vector), primitive("-#", List.of(variable("n"), number(1)))), vector), vector), vector));
    }
    private Map<String, Object> entry(String name, List<Object> body, Map<String, Object> vector) { return binding(name, lambda(List.of(parameter("x")), body, vector)); }
    private Map<String, Object> fixture(Family family) {
        var vector = family.vector; var payload = lanes(family, variable("x")); var pair = tuple(List.of(vector, integer));
        var pairValue = application(l("con", "T2", 2), List.of(payload, number(19)), pair);
        var pairResult = l("case", call("pairIdentity", List.of(pairValue), pair), "whole",
            l(l("data", "T2", l("projected", "unused"), variable("projected", vector), m("binders", List.of(parameter("projected", vector), parameter("unused"))))), m("rep", vector, "binder", parameter("whole", pair)));
        var capturedPair = l("case", variable("p", pair), "capturedWhole",
            l(l("data", "T2", l("projected", "unused"), variable("projected", vector), m("binders", List.of(parameter("projected", vector), parameter("unused"))))), m("rep", vector, "binder", parameter("capturedWhole", pair)));
        var join = new LinkedHashMap<>(binding("swap", lambda(List.of(parameter("left", vector), parameter("right", vector), parameter("n")),
            choice(primitive("<=#", List.of(variable("n"), number(0))), variable("left", vector),
                call("swap", List.of(variable("right", vector), variable("left", vector), primitive("-#", List.of(variable("n"), number(1)))), vector), vector), vector)));
        join.put("joinValueArity", 3); join.put("joinResultRep", vector);
        var joinValue = l("let", true, List.of(join), call("swap", List.of(lanes(family, number(91)), payload, number(101)), vector), m("rep", vector));
        var constructors = new ArrayList<Map<String, Object>>();
        for (int arity : new LinkedHashSet<>(List.of(2, family.lanes))) constructors.add(m("id", "T" + arity, "name", "T" + arity, "kind", "unboxed-tuple", "arity", arity));
        return m("instrument", true, "constructors", constructors, "bindings", List.of(
            binding("identity", lambda(List.of(parameter("v", vector)), variable("v", vector), vector)),
            binding("worker", lambda(List.of(parameter("v", vector), parameter("ignored")), variable("v", vector), vector)),
            binding("pairIdentity", lambda(List.of(parameter("p", pair)), variable("p", pair), pair)),
            binding("capturePair", lambda(List.of(parameter("p", pair)), lambda(List.of(parameter("ignored")), capturedPair, vector), closure)),
            binding("capturedPrefix", lambda(List.of(parameter("x")), call("capturePair", List.of(pairValue), closure), closure)),
            binding("make", lambda(List.of(parameter("outer")), lambda(List.of(parameter("v", vector)), choice(primitive("==#", List.of(variable("outer"), number(19))),
                variable("v", vector), lanes(family, number(0)), vector), vector), closure)),
            function("self", "self", vector), function("mutualA", "mutualB", vector), function("mutualB", "mutualA", vector),
            binding("prefix", lambda(List.of(parameter("x")), call("worker", List.of(payload), closure), closure)),
            entry("exact", call("identity", List.of(payload), vector), vector),
            entry("pap", application(call("worker", List.of(payload), closure), List.of(number(19)), vector), vector),
            entry("roundTrip", call("identity", List.of(call("identity", List.of(payload), vector)), vector), vector),
            entry("arithmetic", primitive("plus" + family.name + "#", List.of(call("identity", List.of(payload), vector),
                primitive("broadcast" + family.name + "#", List.of(laneValue(family, number(0))), vector)), vector), vector),
            entry("local", application(lambda(List.of(parameter("v", vector)), variable("v", vector), vector), List.of(payload), vector), vector),
            entry("localLet", localLets(vector, payload), vector),
            entry("over", call("make", List.of(number(19), payload), vector), vector),
            entry("selfTail", call("self", List.of(payload, number(100)), vector), vector),
            entry("mutualTail", call("mutualA", List.of(payload, number(100)), vector), vector),
            entry("joinSwap", joinValue, vector), entry("tupleField", pairResult, vector),
            entry("tupleCapture", call("capturePair", List.of(pairValue, number(23)), vector), vector)));
    }
    private Context context(boolean inlining) { return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception { withLanguage(true, action); }
    private void withLanguage(boolean inlining, Action action) throws Exception {
        try (var context = context(inlining)) { context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); } }
    }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertNull(state.getPending());
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    private void check(ExecutableProgram program, Language language, Family family, String name, long input) {
        var root = (GuestRoot) program.entryTarget(name).getRootNode(); var shape = Objects.requireNonNull(root.getTupleResult());
        assertEquals(1, shape.getWidth()); assertTrue(shape.getProof().isVector()); assertFalse(shape.getProof().isTuple());
        var result = TupleResults.ownedTupleResult(Calls.target(root.getCallTarget(), new Object[]{0L, input}), shape);
        var raw = new VectorLayout(shape.getProof()).require(shape.getLayout().getObject(result, 0));
        for (int lane = 0; lane < family.lanes; lane++) {
            long value = input + lane; var label = family.name + "/" + name + "/" + input + "/lane=" + lane;
            if (family.laneName.equals("Float")) assertEquals(Float.floatToRawIntBits((float) value), Float.floatToRawIntBits(((FloatVector) raw).lane(lane)), label);
            else if (family.laneName.equals("Double")) assertEquals(Double.doubleToRawLongBits((double) value), Double.doubleToRawLongBits(((DoubleVector) raw).lane(lane)), label);
            else {
                long expected = switch (family.laneName) { case "Int8" -> (byte) value; case "Word8" -> value & 255L; case "Int16" -> (short) value; case "Word16" -> value & 65535L; case "Int32" -> (int) value; case "Word32" -> value & 0xffff_ffffL; default -> value; };
                long signed = switch (raw) { case ByteVector v -> v.lane(lane); case ShortVector v -> v.lane(lane); case IntVector v -> v.lane(lane); case LongVector v -> v.lane(lane); default -> throw new IllegalStateException("Expected integral raw vector"); };
                long actual = switch (family.laneName) { case "Word8" -> signed & 255L; case "Word16" -> signed & 65535L; case "Word32" -> signed & 0xffff_ffffL; default -> signed; };
                assertEquals(expected, actual, label);
            }
        }
        released(language);
    }
    private final List<String> paths = List.of("exact", "pap", "roundTrip", "arithmetic", "local", "localLet", "over", "selfTail", "mutualTail", "joinSwap", "tupleField");
    private final Map<String, Long> entryCounts = Map.of("exact", 2L, "pap", 2L, "roundTrip", 3L, "arithmetic", 2L, "local", 2L, "localLet", 3L, "over", 3L, "joinSwap", 1L, "tupleField", 2L);
    private final List<Long> values = List.of(Long.MIN_VALUE, -129L, -1L, 0L, 127L, Long.MAX_VALUE);
    @Test void allExistingFamiliesCarryExactLanesThroughCallsAndJoins() throws Exception {
        withLanguage(language -> {
            for (var family : families()) {
                var program = new BytecodeProgram(language, fixture(family)); for (var name : paths) for (long value : values) check(program, language, family, name, value);
                var prefix = (Closure) Calls.target(program.entryTarget("prefix"), new Object[]{0L, -1L});
                assertEquals(1, prefix.suppliedCount); assertEquals(1, prefix.arity); assertEquals(0, prefix.supplied.length); assertNotNull(prefix.typedSupplied);
                var input = Objects.requireNonNull(((GuestRoot) program.entryTarget("worker").getRootNode()).getTypedInput());
                assertEquals(2, input.getLogical().getLogicalArity()); assertEquals(2, input.getLogical().getPhysicalArity());
                assertEquals(1, prefix.typedSupplied.getLayout().getReps().size()); assertTrue(prefix.typedSupplied.getLayout().isObject(0));
                new VectorLayout(CoreRepresentations.parse(family.vector)).require(prefix.typedSupplied.getLayout().getObject(prefix.typedSupplied, 0)); released(language);
            }
        });
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); var result = new ArrayList<RootCallTarget>(); visit(entry, seen, result); return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return; var root = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(root);
        if (root instanceof BytecodeRoot body) for (var instruction : body.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget callee && callee.getRootNode() instanceof GuestRoot) visit(callee, seen, result);
        result.add(target);
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.toString()); }
    private Object saved(Closure captured, TupleShape shape) {
        var result = TupleResults.ownedTupleResult(Calls.target(captured.target, new Object[]{0L, captured.environment, 31L}), shape);
        return new VectorLayout(shape.getProof()).require(shape.getLayout().getObject(result, 0));
    }
    @Test void tupleCapturesKeepAllVectorSpeciesAndOwnedLifetimeOnBothBackends() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean inlining : List.of(true, false)) withLanguage(inlining, language -> {
            for (var family : families()) {
                var data = fixture(family); ExecutableProgram program = backend.equals("ast") ? new Program(language, data) : new BytecodeProgram(language, data);
                for (long value : values) check(program, language, family, "tupleCapture", value);
                var captured = (Closure) Calls.target(program.entryTarget("capturedPrefix"), new Object[]{0L, -129L}); var environment = Objects.requireNonNull(captured.environment);
                var vectors = new ArrayList<Integer>(); for (int i = 0; i < environment.getLayout().getStorageSize(); i++) if (environment.getLayout().isVector(i)) vectors.add(i);
                assertEquals(1, vectors.size()); new VectorLayout(CoreRepresentations.parse(family.vector)).require(environment.getLayout().inspect(environment, vectors.getFirst()));
                assertEquals(0, ClosureInspection.image(captured).getPointers().length, "Raw vectors are not guest references");
                var shape = Objects.requireNonNull(((GuestRoot) captured.target.getRootNode()).getTupleResult()); var original = saved(captured, shape);
                Calls.target(program.entryTarget("capturedPrefix"), new Object[]{0L, 99L}); assertEquals(original, saved(captured, shape), backend + "/" + family.name + " escaped capture");
                if (Set.of("Int32X4", "Word8X16", "FloatX4", "DoubleX2").contains(family.name)) {
                    var target = program.entryTarget("tupleCapture"); var active = activeTargets(target);
                    for (var installed : active) { installed.getClass().getMethod("compile", boolean.class).invoke(installed, true); valid(installed); }
                    for (long value : values.reversed()) {
                        long before = (Long) program.diagnostics().get("compiledEntries"); check(program, language, family, "tupleCapture", value);
                        assertEquals(3L, (Long) program.diagnostics().get("compiledEntries") - before); assertEquals(active, activeTargets(target)); for (var installed : active) valid(installed);
                    }
                }
                released(language);
            }
        });
    }
    @Test void compiledCallsRemainInstalledFromTheFirstEntryWithAndWithoutInlining() throws Exception {
        for (boolean inlining : List.of(true, false)) withLanguage(inlining, language -> {
            for (var family : families()) if (Set.of("Int32X4", "Word8X16", "FloatX4", "DoubleX2").contains(family.name)) {
                var program = new BytecodeProgram(language, fixture(family));
                for (var name : paths) {
                    for (long value : values) check(program, language, family, name, value); var entry = program.entryTarget(name); var active = activeTargets(entry);
                    for (var target : active) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
                    for (long value : values.reversed()) {
                        long before = (Long) program.diagnostics().get("compiledEntries"); check(program, language, family, name, value); long entered = (Long) program.diagnostics().get("compiledEntries") - before;
                        var expected = entryCounts.get(name); if (expected != null) assertEquals(expected.longValue(), entered, family.name + "/" + name + " exact compiled entries");
                        else assertTrue(entered > 0, family.name + "/" + name + " compiled tail entry");
                        assertEquals(active, activeTargets(entry), family.name + "/" + name + " active targets"); for (var target : active) valid(target);
                    }
                }
            }
        });
    }
    @Test void sameWidthVectorAndTupleInputsRemainLogicallyDistinct() throws Exception {
        withLanguage(language -> {
            var family = family("Int32X4"); var program = new BytecodeProgram(language, fixture(family)); var closureValue = (Closure) program.entryValue("identity");
            var wrong = ArgumentLayout.fromProofs(List.of(CoreRepresentations.parse(tuple(Collections.nCopies(family.lanes, family.lane)))));
            assertThrows(RuntimeFault.class, () -> ArgumentLayout.validate(closureValue, wrong, 0, 1));
            var unsigned = family("Word32X4"); var otherVector = ArgumentLayout.fromProofs(List.of(CoreRepresentations.parse(unsigned.vector)));
            assertThrows(RuntimeFault.class, () -> ArgumentLayout.validate(closureValue, otherVector, 0, 1)); released(language);
        });
    }
    private Map<String, Object> addBinding(Map<String, Object> original, Map<String, Object> binding) {
        var result = new LinkedHashMap<>(original); var bindings = new ArrayList<>((List<Map<String, Object>>) original.get("bindings")); bindings.add(binding); result.put("bindings", bindings); return result;
    }
    private void checkBits(long value, RootCallTarget entry, TupleShape shape, Family family, boolean floating, Language language) {
        var result = TupleResults.ownedTupleResult(Calls.target(entry, new Object[]{0L, value}), shape); var raw = shape.getLayout().getObject(result, 0);
        for (int index = 0; index < family.lanes; index++) {
            if (floating) assertEquals((int) value, Float.floatToRawIntBits(((FloatVector) raw).lane(index)));
            else assertEquals(value, Double.doubleToRawLongBits(((DoubleVector) raw).lane(index)));
        }
        released(language);
    }
    @Test void vectorLetsPreserveFloatingBitsFromTheFirstCompiledEntry() throws Exception {
        for (boolean inlining : List.of(true, false)) withLanguage(inlining, language -> {
            for (var family : families()) if (Set.of("FloatX4", "DoubleX2").contains(family.name)) {
                boolean floating = family.laneName.equals("Float"); var bitsProof = scalar("long", floating ? "Word32Rep" : "Word64Rep");
                var bits = primitive(floating ? "wordToWord32#" : "wordToWord64#", List.of(primitive("int2Word#", List.of(variable("x")), word)), bitsProof);
                var lane = primitive(floating ? "castWord32ToFloat#" : "castWord64ToDouble#", List.of(bits), family.lane);
                var body = localLets(family.vector, primitive("broadcast" + family.name + "#", List.of(lane), family.vector));
                var module = addBinding(fixture(family), binding("bitsLet", lambda(List.of(parameter("x")), body, family.vector))); var program = new BytecodeProgram(language, module);
                var inputs = floating ? List.of(0L, 0x80000000L, 1L, 0x7fc01234L, 0x7f800000L, 0xff800000L) :
                    List.of(0L, Long.MIN_VALUE, 1L, 0x7ff8000000001234L, 0x7ff0000000000000L, -4503599627370496L);
                var entry = program.entryTarget("bitsLet"); var shape = Objects.requireNonNull(((BytecodeRoot) entry.getRootNode()).getTupleResult());
                for (long value : inputs) checkBits(value, entry, shape, family, floating, language); var active = activeTargets(entry);
                for (var target : active) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
                for (long value : inputs.reversed()) {
                    long before = (Long) program.diagnostics().get("compiledEntries"); checkBits(value, entry, shape, family, floating, language);
                    assertEquals(3L, (Long) program.diagnostics().get("compiledEntries") - before); assertEquals(active, activeTargets(entry)); for (var target : active) valid(target);
                }
            }
        });
    }
    private void reject(Language language, Family family, List<Object> body, Map<String, Object> result) {
        var module = addBinding(fixture(family), binding("badLet", lambda(List.of(parameter("x")), body, result)));
        assertThrows(RuntimeFault.class, () -> new BytecodeProgram(language, module).entryTarget("badLet")); released(language);
    }
    @Test void vectorLetsRejectRecursiveLiftedAndMismatchedValues() throws Exception {
        withLanguage(language -> {
            var family = family("Int32X4"); var payload = lanes(family, variable("x"));
            reject(language, family, vectorLet("v", family.vector, payload, variable("v", family.vector), true, false), family.vector);
            reject(language, family, vectorLet("v", family.vector, payload, variable("v", family.vector), false, true), family.vector);
            var other = family("Word32X4"); reject(language, family, vectorLet("v", family.vector, lanes(other, variable("x")), variable("v", family.vector)), family.vector);
            reject(language, family, vectorLet("v", family.vector, number(0), variable("v", family.vector)), family.vector);
            // Ordinary closures retaining a lexical vector now have owned lane storage; the genuine capturedCase Core path is proved by SimdCallNativeTest.
            var fields = tuple(Collections.nCopies(family.lanes, family.lane)); var values = new ArrayList<List<Object>>(); for (int i = 0; i < family.lanes; i++) values.add(laneValue(family, number(i)));
            reject(language, family, vectorLet("v", fields, application(l("con", "T" + family.lanes, family.lanes), values, fields), variable("v", fields)), fields);
        });
    }
}
