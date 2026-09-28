// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import jdk.incubator.vector.LongVector;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Ordinary typed transport through closed preparation and fresh program owners. */
class ReusableTypedTest {
    private static final Map<String,Object> INT = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private static final Map<String,Object> CLOSURE = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static final Map<String,Object> VOID = map("kind", "void", "primReps", list(), "evaluated", true);
    private static final Map<String,Object> VECTOR = map("kind", "vector", "primReps", list("VecRep 2 Int64ElemRep"),
        "vector", map("lanes", 2, "element", "Int64ElemRep"), "evaluated", true);
    private static final Map<String,Object> NARROW = map("kind", "long", "primReps", list("Int16Rep"), "evaluated", true);
    private static final Map<String,Object> FLOAT = map("kind", "float", "primReps", list("FloatRep"), "evaluated", true);
    private static final Map<String,Object> DOUBLE = map("kind", "double", "primReps", list("DoubleRep"), "evaluated", true);
    private static final Map<String,Object> ADDRESS = map("kind", "address", "primReps", list("AddrRep"), "evaluated", true);
    private static final Map<String,Object> EMPTY = tuple();
    private static final Map<String,Object> MIXED = tuple(VOID, EMPTY, NARROW, FLOAT, DOUBLE, ADDRESS, VECTOR);
    private static final Map<String,Object> PAIR = tuple(INT, INT);
    private static final Map<String,Object> SUM = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-sum",
        "primReps", list("WordRep", "WordRep", "WordRep"), "alternatives", list(PAIR, INT),
        "tagSlot", 0, "alternativeSlots", list(list(1, 2), list(1)));
    @SafeVarargs private static Map<String,Object> tuple(Map<String,Object>... fields) {
        var reps = new ArrayList<Object>();
        for (var field : fields) reps.addAll((List<?>)field.get("primReps"));
        return map("kind", "unknown", "primReps", reps, "evaluated", true, "aggregate", "unboxed-tuple", "components", Arrays.asList(fields));
    }
    private static Map<String,Object> parameter(String id, Map<String,Object> proof) {
        return map("id", id, "name", id, "lifted", false, "rep", proof);
    }
    private static List<Object> variable(String id, Map<String,Object> proof) { return list("var", id, map("rep", proof)); }
    private static List<Object> number(long n) { return list("lit", "int", Long.toString(n), map("rep", INT)); }
    private static List<Object> call(List<Object> function, List<List<Object>> args, Map<String,Object> result) {
        return list("app", function, args, Collections.nCopies(args.size(), false), false, false, map("rep", result));
    }
    private static List<Object> call(String function, List<List<Object>> args, Map<String,Object> result) {
        return call(list("var", function), args, result);
    }
    private static Map<String,Object> function(String id, List<Map<String,Object>> args, List<Object> body, Map<String,Object> result) {
        return map("id", id, "name", id, "arity", args.size(), "lifted", true,
            "expr", list("lam", args, body, map("resultRep", result)));
    }
    private static List<Object> let(String id, Map<String,Object> proof, List<Object> rhs, List<Object> body, Map<String,Object> result) {
        var binding = parameter(id, proof); binding.put("expr", rhs);
        return list("let", false, list(binding), body, map("rep", result));
    }
    private static Map<String,Object> module() {
        var bindings = new ArrayList<Map<String,Object>>();
        for (var entry : List.of(Map.entry("pair", PAIR), Map.entry("sum", SUM), Map.entry("vector", VECTOR), Map.entry("empty", EMPTY), Map.entry("unit", VOID), Map.entry("mixed", MIXED))) {
            String id = entry.getKey(); var proof = entry.getValue();
            bindings.add(function(id + "Identity", list(parameter("x", proof)), variable("x", proof), proof));
            var join = function("done", list(parameter("answer", proof)), variable("answer", proof), proof);
            join.put("joinValueArity", 1); join.put("joinResultRep", proof);
            var nested = list("lam", list(parameter("ignored", INT)), variable("saved", proof), map("resultRep", proof));
            var captured = call(nested, list(number(0)), proof);
            var body = let("saved", proof, call(id + "Identity", list(variable("x", proof)), proof),
                list("let", false, list(join), call("done", list(captured), proof), map("rep", proof)), proof);
            bindings.add(function(id + "RoundTrip", list(parameter("x", proof)), body, proof));
        }
        bindings.add(function("returnPairFunction", list(parameter("saved", PAIR)),
            list("lam", list(parameter("ignored", INT)), variable("saved", PAIR), map("resultRep", PAIR)), CLOSURE));
        bindings.add(function("emptyToInt", list(parameter("x", EMPTY)), number(8), INT));
        bindings.add(function("emptyToPair", list(parameter("x", EMPTY)), call("makePair", list(number(8)), PAIR), PAIR));
        for (var entry : List.of(Map.entry("Int", INT), Map.entry("Pair", PAIR))) {
            bindings.add(function("nestedEmpty" + entry.getKey(), list(parameter("x", EMPTY)),
                call("emptyTo" + entry.getKey(), list(call("emptyIdentity", list(variable("x", EMPTY)), EMPTY)), entry.getValue()), entry.getValue()));
        }
        var pair = call(list("con", "Pair", 2), list(variable("n", INT), number(7)), PAIR);
        bindings.add(function("makePair", list(parameter("n", INT)), pair, PAIR));
        bindings.add(function("makeSum", list(parameter("n", INT)),
            call(list("con", "Left", 1), list(call("makePair", list(variable("n", INT)), PAIR)), SUM), SUM));
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ReusableTyped", "instrument", true,
            "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2),
                map("id", "Left", "name", "Left", "kind", "unboxed-sum", "arity", 1, "sumArity", 2, "tag", 1)), "bindings", bindings);
    }
    private static Map<String,Object> compositionModule() {
        var input = module();
        var bindings = (List<Map<String,Object>>)input.get("bindings");
        bindings.add(function("applyPair", list(parameter("f", CLOSURE), parameter("x", INT)),
            let("answer", PAIR, call(variable("f", CLOSURE), list(variable("x", INT)), PAIR), variable("answer", PAIR), PAIR), PAIR));
        for (var entry : List.of(Map.entry("sum", SUM), Map.entry("vector", VECTOR), Map.entry("empty", EMPTY), Map.entry("mixed", MIXED))) {
            var proof = entry.getValue();
            bindings.add(function("apply" + entry.getKey(), list(parameter("f", CLOSURE), parameter("x", proof)),
                let("answer", proof, call(variable("f", CLOSURE), list(variable("x", proof)), proof), variable("answer", proof), proof), proof));
        }
        bindings.add(function("forwardPair", list(parameter("f", CLOSURE), parameter("x", INT)),
            call(variable("f", CLOSURE), list(variable("x", INT)), PAIR), PAIR));
        bindings.add(function("selectPair", list(parameter("ignored", INT)), variable("makePair", CLOSURE), CLOSURE));
        bindings.add(function("overapplyPair", list(parameter("f", CLOSURE), parameter("x", INT)),
            call(variable("f", CLOSURE), list(number(0), variable("x", INT)), PAIR), PAIR));
        bindings.add(function("pairPrefix", list(parameter("ignored", INT), parameter("n", INT)),
            call("makePair", list(variable("n", INT)), PAIR), PAIR));
        bindings.add(map("id", "pairCaf", "name", "pairCaf", "lifted", true, "rep", CLOSURE,
            "expr", call("selectPair", list(number(0)), CLOSURE)));
        bindings.add(map("id", "pairAlias", "name", "pairAlias", "lifted", true, "rep", CLOSURE,
            "expr", variable("pairCaf", CLOSURE)));
        bindings.add(map("id", "pairPap", "name", "pairPap", "lifted", true, "rep", CLOSURE,
            "expr", call("pairPrefix", list(number(0)), CLOSURE)));
        return input;
    }
    private static Program.PreparedCode prepare(Engine engine, List<String> entries) {
        try (var context = Context.newBuilder("thc").engine(engine).allowAllAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try { return Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), compositionModule(), entries); }
            finally { context.leave(); }
        }
    }
    private static void pair(org.graalvm.polyglot.Value result, long first) {
        assertEquals(first, result.getArrayElement(0).asLong()); assertEquals(7L, result.getArrayElement(1).asLong());
    }
    private static Engine engine(boolean compiled) {
        return Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", Boolean.toString(compiled))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("compiler.Inlining", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    @SuppressWarnings("unchecked") private static void compileUntouched(Program.PreparedCode code) throws Exception {
        var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        for (var target : (List<RootCallTarget>)field.get(code)) {
            assertEquals(false, type.getMethod("wasExecuted").invoke(target));
            assertEquals(true, type.getMethod("prepareForAOT").invoke(target));
            type.getMethod("compile", boolean.class).invoke(target, true);
            assertEquals(false, type.getMethod("wasExecuted").invoke(target));
            Truffle.getRuntime().getClass().getMethod("bypassedInstalledCode", type).invoke(Truffle.getRuntime(), target);
        }
        code.requireInstalledCode();
    }
    private static void released(Language language) {
        var state = language.getHandoffState().get();
        assertNull(state.getPending()); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
    }
    private void checkForcedSignatures(boolean compiled) throws Exception {
        try (var engine = engine(compiled)) {
            var code = prepare(engine, List.of("pairCaf", "pairAlias", "pairPap"));
            if (compiled) compileUntouched(code);
            for (int instance = 0; instance < 2; instance++) try (var context = Context.newBuilder("thc").engine(engine).allowAllAccess(true).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var program = code.newInstance(language);
                    for (String name : List.of("pairCaf", "pairAlias", "pairPap")) {
                        var entry = new EntryValue(program, name, 1, null, null, language, null, null, false,
                            List.of(CoreRepresentations.parse(INT)), CoreRepresentations.parse(PAIR));
                        pair(context.asValue(entry).execute(41L + instance), 41L + instance);
                        if (compiled) code.requireInstalledCode();
                        released(language);
                    }
                } finally { context.leave(); }
            }
        }
    }
    private void checkComposition(boolean compiled) throws Exception {
        try (var engine = engine(compiled)) {
            var callerCode = prepare(engine, List.of("applyPair", "forwardPair", "overapplyPair", "applysum", "applyvector", "applyempty", "applymixed"));
            var calleeCode = prepare(engine, List.of("makePair", "selectPair", "makeSum", "sumIdentity", "vectorIdentity", "emptyIdentity", "mixedIdentity"));
            if (compiled) { compileUntouched(callerCode); compileUntouched(calleeCode); }
            for (int instance = 0; instance < 2; instance++) try (var context = Context.newBuilder("thc").engine(engine).allowAllAccess(true).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var caller = callerCode.newInstance(language);
                    for (var callee : List.of(calleeCode.newInstance(language), new Program(language, compositionModule()))) {
                        var make = context.asValue(new HostReference(Language.currentState(), callee.entryValue("makePair"), CoreRepresentations.parse(CLOSURE), callee));
                        var select = context.asValue(new HostReference(Language.currentState(), callee.entryValue("selectPair"), CoreRepresentations.parse(CLOSURE), callee));
                        for (String name : List.of("applyPair", "forwardPair")) {
                            pair(context.asValue(new EntryValue(caller, name, 2)).execute(make, 42L), 42L);
                            if (compiled) callerCode.requireInstalledCode();
                        }
                        pair(context.asValue(new EntryValue(caller, "overapplyPair", 2)).execute(select, 43L), 43L);
                        if (compiled) callerCode.requireInstalledCode();
                        for (String kind : List.of("sum", "vector", "empty", "mixed")) {
                            var identity = context.asValue(new HostReference(Language.currentState(), callee.entryValue(kind + "Identity"),
                                CoreRepresentations.parse(CLOSURE), callee));
                            Object x = switch (kind) {
                                case "sum" -> new Object[]{1L, new Object[]{45L, 7L}};
                                case "vector" -> LongVector.fromArray(LongVector.SPECIES_128, new long[]{45L, 7L}, 0);
                                case "empty" -> new Object[0];
                                default -> new Object[]{null, new Object[0], -32768L, 1.25f, -2.5d, null, LongVector.zero(LongVector.SPECIES_128)};
                            };
                            var value = context.asValue(new EntryValue(caller, "apply" + kind, 2)).execute(identity, x);
                            switch (kind) {
                                case "sum" -> { assertEquals(1L, value.getArrayElement(0).asLong()); pair(value.getArrayElement(1), 45L); }
                                case "vector" -> assertEquals(45L, ((LongVector)value.asHostObject()).lane(0));
                                case "empty" -> assertEquals(0, value.getArraySize());
                                default -> {
                                    assertTrue(value.getArrayElement(0).isNull()); assertEquals(0, value.getArrayElement(1).getArraySize());
                                    assertEquals(-32768L, value.getArrayElement(2).asLong()); assertEquals(1.25f, value.getArrayElement(3).asFloat());
                                    assertEquals(-2.5d, value.getArrayElement(4).asDouble()); assertEquals(0L, ((LongVector)value.getArrayElement(6).asHostObject()).lane(0));
                                }
                            }
                            if (compiled) { callerCode.requireInstalledCode(); calleeCode.requireInstalledCode(); }
                            released(language);
                        }
                        if (!compiled) {
                            var wrongResult = context.asValue(new HostReference(Language.currentState(), callee.entryValue("makeSum"),
                                CoreRepresentations.parse(CLOSURE), callee));
                            assertThrows(org.graalvm.polyglot.PolyglotException.class,
                                () -> context.asValue(new EntryValue(caller, "applyPair", 2)).execute(wrongResult, 45L));
                            released(language);
                        }
                        // Force the real tail-transfer protocol to a differently
                        // prepared final root, independent of bloom collisions.
                        var finalClosure = (Closure)callee.entryValue("makePair");
                        var transfer = new GuestRoot(language, new FrameLayout().build()) {
                            @Override public long bloom(com.oracle.truffle.api.frame.VirtualFrame frame) { return 0L; }
                            @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
                                Object x = frame.getArguments()[1];
                                throw new TailCall(finalClosure.target, finalClosure.environment == null ? new Object[]{0L, x} :
                                    new Object[]{0L, finalClosure.environment, x});
                            }
                        };
                        transfer.configureInputProofs(List.of(CoreRepresentations.parse(INT)));
                        transfer.configureTupleResult(new TupleShape(CoreRepresentations.parse(PAIR), language));
                        var bounce = context.asValue(new HostReference(Language.currentState(), new Closure(null, 1, transfer.getCallTarget()),
                            CoreRepresentations.parse(CLOSURE), callee));
                        for (String name : List.of("applyPair", "forwardPair")) {
                            pair(context.asValue(new EntryValue(caller, name, 2)).execute(bounce, 44L), 44L);
                            if (compiled) callerCode.requireInstalledCode();
                        }
                        assertTrue(((Number)caller.diagnostics().get("trampolineIterations")).longValue() >= 2L);
                        if (compiled) { callerCode.requireInstalledCode(); calleeCode.requireInstalledCode(); }
                        assertEquals(0, caller.diagnostics().get("loweredRootCount"));
                        released(language);
                    }
                } finally { context.leave(); }
            }
        }
    }
    @Test void typedHostSignaturesForcePreparedCafsAliasesAndPaps() throws Exception { checkForcedSignatures(false); }
    @Test void independentPreparedAndOrdinaryTypedFunctionsCompose() throws Exception { checkComposition(false); }
    @Test void untouchedTypedCafsAndIndependentFunctionsCompose() throws Exception {
        String before = System.getProperty("thc.requireCompiledCode"); System.setProperty("thc.requireCompiledCode", "true");
        try { checkForcedSignatures(true); checkComposition(true); }
        finally { if (before == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", before); }
    }
    @SuppressWarnings("unchecked") private void check(boolean compiled) throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", Boolean.toString(compiled))
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("compiler.Inlining", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var context = Context.newBuilder("thc").engine(engine).allowAllAccess(true).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var input = module();
                    code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), input,
                        ((List<Map<String,Object>>)input.get("bindings")).stream().map(b -> (String)b.get("id")).toList());
                } finally { context.leave(); }
            }
            var targetsField = Program.PreparedCode.class.getDeclaredField("targets"); targetsField.setAccessible(true);
            var targets = (List<RootCallTarget>)targetsField.get(code);
            var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            if (compiled) for (var target : targets) {
                assertEquals(false, targetClass.getMethod("wasExecuted").invoke(target));
                assertEquals(true, targetClass.getMethod("prepareForAOT").invoke(target));
                targetClass.getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(false, targetClass.getMethod("wasExecuted").invoke(target));
            }
            for (int instance = 0; instance < 2; instance++) try (var context = Context.newBuilder("thc").engine(engine).allowAllAccess(true).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    // Local layouts intentionally use IDs also used by prepared layouts.
                    var localLayout = language.getHandoffLayouts().intern(List.of("DoubleRep"));
                    var local = language.getHandoffState().get().getResults().acquire(localLayout);
                    language.getHandoffState().get().getResults().release(local, localLayout);
                    var program = code.newInstance(language);
                    assertThrows(RuntimeFault.class, () -> new EntryValue(program, "pairRoundTrip", 1, null, null,
                        language, null, null, false, List.of(CoreRepresentations.parse(PAIR)), CoreRepresentations.parse(tuple(INT))));
                    assertThrows(RuntimeFault.class, () -> new EntryValue(program, "vectorRoundTrip", 1, null, null,
                        language, null, null, false, List.of(CoreRepresentations.parse(VECTOR)),
                        CoreRepresentations.parse(map("kind", "vector", "primReps", list("VecRep 4 Int64ElemRep"),
                            "vector", map("lanes", 4, "element", "Int64ElemRep"), "evaluated", true))));
                    for (String entry : List.of("pairRoundTrip", "sumRoundTrip", "vectorRoundTrip", "emptyRoundTrip", "unitRoundTrip", "mixedRoundTrip", "nestedEmptyInt", "nestedEmptyPair", "returnPairFunction", "makeSum")) {
                        if (compiled) for (var target : targets) Truffle.getRuntime().getClass().getMethod("bypassedInstalledCode", targetClass).invoke(Truffle.getRuntime(), target);
                        var fn = context.asValue(new EntryValue(program, entry, 1));
                        var result = switch (entry) {
                            case "pairRoundTrip", "returnPairFunction" -> fn.execute((Object)new Object[]{41L + instance, 7L});
                            case "sumRoundTrip" -> fn.execute((Object)new Object[]{1L, new Object[]{41L + instance, 7L}});
                            case "vectorRoundTrip" -> fn.execute(LongVector.fromArray(LongVector.SPECIES_128, new long[]{41L + instance, 7}, 0));
                            case "emptyRoundTrip", "nestedEmptyInt", "nestedEmptyPair" -> fn.execute((Object)new Object[0]);
                            case "unitRoundTrip" -> fn.execute((Object)null);
                            case "mixedRoundTrip" -> fn.execute((Object)new Object[]{null, new Object[0], -32768L, 1.25f, -2.5d, null,
                                LongVector.fromArray(LongVector.SPECIES_128, new long[]{41L + instance, 7}, 0)});
                            default -> fn.execute(41L + instance);
                        };
                        if (entry.equals("returnPairFunction")) {
                            if (compiled) for (var target : targets) Truffle.getRuntime().getClass().getMethod("bypassedInstalledCode", targetClass).invoke(Truffle.getRuntime(), target);
                            var applied = result.execute(0L);
                            assertEquals(41L + instance, applied.getArrayElement(0).asLong()); assertEquals(7L, applied.getArrayElement(1).asLong());
                        }
                        if (entry.equals("pairRoundTrip")) {
                            assertEquals(41L + instance, result.getArrayElement(0).asLong());
                            assertEquals(7L, result.getArrayElement(1).asLong());
                        }
                        if (entry.equals("nestedEmptyInt")) assertEquals(8L, result.asLong());
                        if (entry.equals("nestedEmptyPair")) { assertEquals(8L, result.getArrayElement(0).asLong()); assertEquals(7L, result.getArrayElement(1).asLong()); }
                        if (entry.equals("mixedRoundTrip")) {
                            assertTrue(result.getArrayElement(0).isNull()); assertEquals(0, result.getArrayElement(1).getArraySize());
                            assertEquals(-32768L, result.getArrayElement(2).asLong()); assertEquals(1.25f, result.getArrayElement(3).asFloat());
                            assertEquals(-2.5d, result.getArrayElement(4).asDouble());
                            assertEquals(41L + instance, ((LongVector)result.getArrayElement(6).asHostObject()).lane(0));
                            // Returned address handles remain owned by this fresh context.
                            var fields = new Object[]{null, new Object[0], -32768L, 1.25f, -2.5d, result.getArrayElement(5),
                                LongVector.fromArray(LongVector.SPECIES_128, new long[]{41L + instance, 7}, 0)};
                            if (compiled) for (var target : targets) Truffle.getRuntime().getClass().getMethod("bypassedInstalledCode", targetClass).invoke(Truffle.getRuntime(), target);
                            assertEquals(-32768L, fn.execute((Object)fields).getArrayElement(2).asLong());
                            assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> fn.execute((Object)new Object[]{null, new Object[0], 32768L, 1.25f, -2.5d, null,
                                LongVector.zero(LongVector.SPECIES_128)}));
                        }
                        if (entry.equals("sumRoundTrip") || entry.equals("makeSum")) {
                            assertEquals(1L, result.getArrayElement(0).asLong());
                            assertEquals(41L + instance, result.getArrayElement(1).getArrayElement(0).asLong());
                            assertEquals(7L, result.getArrayElement(1).getArrayElement(1).asLong());
                        }
                        if (entry.equals("vectorRoundTrip")) {
                            assertEquals(41L + instance, ((LongVector)result.asHostObject()).lane(0));
                            assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> fn.execute(LongVector.zero(LongVector.SPECIES_256)));
                        }
                        if (entry.equals("emptyRoundTrip")) assertEquals(0, result.getArraySize());
                        if (entry.equals("unitRoundTrip")) assertTrue(result.isNull());
                        if (compiled) code.requireInstalledCode();
                    }
                    assertEquals(0, program.diagnostics().get("loweredRootCount"));
                    var state = language.getHandoffState().get();
                    assertNull(state.getPending()); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
                    assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
                } finally { context.leave(); }
            }
        }
    }
    @Test void freshInstancesRetainTypedTransportAndOwnTheirPools() throws Exception { check(false); }
    @Test void untouchedTypedRootsKeepTheirFirstInstalledCall() throws Exception {
        String before = System.getProperty("thc.requireCompiledCode"); System.setProperty("thc.requireCompiledCode", "true");
        try { check(true); }
        finally { if (before == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", before); }
    }
}
