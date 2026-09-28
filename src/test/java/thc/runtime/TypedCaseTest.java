// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

class TypedCaseTest {
    private final Map<String, Object> wide = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private Map<String, Object> reference(String kind) { return map("kind", kind, "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false); }
    private final Map<String, Object> data = reference("data"), closure = reference("closure");
    private final Map<String, Object> address = map("kind", "address", "primReps", list("AddrRep"), "evaluated", true);
    private Map<String, Object> param(String id) { return param(id, null); }
    private Map<String, Object> param(String id, Map<String, Object> rep) {
        var result = map("id", id, "name", id, "lifted", rep != wide && rep != address, "coercion", false); if (rep != null) result.put("rep", rep); return result;
    }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> number(long n) { return list("lit", "int", Long.toString(n)); }
    private Map<String, Object> binding(String id, List<Object> body) { return map("id", id, "name", id, "lifted", true, "expr", body); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body) { return lambda(args, body, wide); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { var metadata = map("rep", closure); if (result != null) metadata.put("resultRep", result); return list("lam", args, body, metadata); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return list("app", list("prim", name), list(args), Collections.nCopies(args.length, false)); }
    private List<Object> box(List<Object> value) { return list("app", list("con", "Box", 1), list(value), list(false), true, true); }
    private List<Object> arm(long value, List<Object> body) { return list("lit", list("int", Long.toString(value)), list(), body); }
    private List<Object> otherwise(List<Object> body) { return list("default", null, list(), body); }
    private List<Object> dataArm(String id, List<String> fields, List<Object> body) { return list("data", id, fields, body); }
    private List<Object> caseOf(List<Object> scrutinee, String binder, Map<String, Object> proof, List<List<Object>> alternatives) { return caseOf(scrutinee, binder, proof, alternatives, wide); }
    private List<Object> caseOf(List<Object> scrutinee, String binder, Map<String, Object> proof, List<List<Object>> alternatives, Map<String, Object> result) {
        var metadata = map(); if (proof != null) metadata.put("binder", param(binder, proof)); if (result != null) metadata.put("rep", result);
        return list("case", scrutinee, binder, alternatives, metadata);
    }
    private void compile(RootCallTarget target) throws Exception { var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, type.getMethod("isValidLastTier").invoke(target)); }
    private Object call(ExecutableProgram p, String id, Object... args) { return Calls.target(p.hostEntryTarget(args.length), new Object[]{p.entryValue(id), args}); }
    @FunctionalInterface private interface CaseAction { void accept(boolean enabled, String backend, ExecutableProgram program) throws Exception; }
    private void each(CaseAction action) throws Exception {
        var previous = System.getProperty(CaseCategories.TYPED_CASES_PROPERTY);
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                System.setProperty(CaseCategories.TYPED_CASES_PROPERTY, Boolean.toString(enabled));
                try (var context = Main.executionContext(false)) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        for (var backend : list("ast", "bytecode")) {
                            var module = map("instrument", true, "bindings", fixtures(), "constructors", list(
                                map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", list(list("IntRep")), "fieldLifted", list(false), "strictFields", list(false)),
                                map("id", "End", "name", "End", "arity", 0, "kind", "boxed", "fieldReps", list(), "fieldLifted", list(), "strictFields", list()),
                                map("id", "Other", "name", "Other", "arity", 0, "kind", "boxed", "fieldReps", list(), "fieldLifted", list(), "strictFields", list())));
                            action.accept(enabled, backend, backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module));
                        }
                    } finally { context.leave(); }
                }
            }
        } finally { if (previous == null) System.clearProperty(CaseCategories.TYPED_CASES_PROPERTY); else System.setProperty(CaseCategories.TYPED_CASES_PROPERTY, previous); }
    }
    private Map<String, Object> identity(String id, Map<String, Object> rep) { return binding(id, lambda(list(param("input")), caseOf(variable("input"), "whole", rep, list(otherwise(variable("whole"))), rep), rep)); }
    private List<Map<String, Object>> fixtures() {
        var select = primitive("+#", caseOf(variable("input"), "whole", data, list(
            dataArm("Box", list("payload"), caseOf(variable("payload"), "n", wide, list(arm(-1, caseOf(number(9), "unmatched", wide, list())), arm(Long.MIN_VALUE, primitive("+#", variable("n"), number(3))), otherwise(primitive("+#", variable("n"), number(7)))))),
            dataArm("End", list(), number(99)), otherwise(number(-17)))), number(1));
        return list(binding("make", lambda(list(param("n", wide)), box(variable("n")), data)), binding("end", list("con", "End", 0)), binding("other", list("con", "Other", 0)), binding("select", lambda(list(param("input")), select)),
            binding("partial", lambda(list(param("input")), caseOf(variable("input"), "whole", data, list(dataArm("Box", list("n"), variable("n")))))),
            binding("generic", lambda(list(param("input")), caseOf(variable("input"), "whole", null, list(arm(0, number(11)), dataArm("End", list(), number(12)), otherwise(number(13)))))),
            binding("nullLiteral", lambda(list(param("input")), caseOf(variable("input"), "whole", null, list(list("lit", list("null-addr", "0"), list(), number(21)), otherwise(number(22)))))),
            binding("plus", lambda(list(param("n", wide)), primitive("+#", variable("n"), number(5)))), identity("dataIdentity", data), identity("functionIdentity", closure), identity("addressIdentity", address));
    }
    private Object selected(ExecutableProgram p, long value) { return call(p, "select", call(p, "make", value)); }
    @Test void typedCasesKeepWidePrimitiveResultsColdArmsAndGeneralCaseFallback() throws Exception {
        each((enabled, backend, p) -> {
            for (int i = 0; i < 30; i++) assertEquals(10L, selected(p, 2)); compile(p.entryTarget("select"));
            for (long n : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_017L, -3_000_000_017L}) assertEquals(n == Long.MIN_VALUE ? n + 4 : n + 8, selected(p, n), enabled + "/" + backend + "/" + n);
            assertEquals(100L, call(p, "select", call(p, "end"))); assertEquals(-16L, call(p, "select", call(p, "other")), "DATA is not proof of a particular constructor family");
            assertEquals(11L, call(p, "generic", 0L)); assertEquals(12L, call(p, "generic", call(p, "end"))); assertEquals(13L, call(p, "generic", 5L)); assertEquals(13L, call(p, "generic", call(p, "other")));
            var failure = assertThrows(RuntimeFault.class, () -> call(p, "partial", call(p, "end"))); assertTrue(Objects.toString(failure.getMessage(), "").contains("Non-exhaustive Core case")); assertEquals(0L, p.diagnostics().get("blackholes"));
            for (int i = 0; i < 2; i++) { var failureArm = assertThrows(RuntimeFault.class, () -> selected(p, -1)); assertTrue(Objects.toString(failureArm.getMessage(), "").contains("Non-exhaustive Core case")); }
            assertEquals(0L, p.diagnostics().get("blackholes"));
            if (backend.equals("ast")) {
                var names = new HashSet<String>(); p.entryTarget("select").getRootNode().accept(node -> { names.add(node.getClass().getSimpleName()); return true; });
                assertEquals(enabled, names.contains("DataCase")); assertEquals(enabled, names.contains("LongCase"));
            } else assertEquals(enabled, ((BytecodeProgram) p).bytecodeDump().contains("MatchDataValue"));
        });
    }
    private record Expected(String entry, long value) {}
    @Test void genericLiteralCasesDoNotInvokeHostEqualityOnColdObjectCarriers() throws Exception {
        each((enabled, backend, p) -> {
            var hostile = new Object() {
                @Override public boolean equals(Object other) { throw new IllegalStateException("Literal matching invoked arbitrary host equality"); }
                @Override public int hashCode() { return 0; }
            }; var ordinary = new Object();
            for (int i = 0; i < 30; i++) {
                assertEquals(11L, call(p, "generic", 0L)); assertEquals(13L, call(p, "generic", ordinary)); assertEquals(21L, call(p, "nullLiteral", ManagedAddress.nullAddress())); assertEquals(22L, call(p, "nullLiteral", ordinary));
            }
            for (var pair : list(new Expected("generic", 13L), new Expected("nullLiteral", 22L))) {
                compile(p.entryTarget(pair.entry())); long before = ((Number) p.diagnostics().get("compiledEntries")).longValue();
                assertEquals(pair.value(), call(p, pair.entry(), hostile), enabled + "/" + backend + "/" + pair.entry() + " first compiled call");
                assertTrue(((Number) p.diagnostics().get("compiledEntries")).longValue() > before, enabled + "/" + backend + "/" + pair.entry() + " must enter installed guest code");
                for (var wrong : list(0, (short) 0, (byte) 0, 0.0f, 0.0, false, BigInteger.ZERO)) assertEquals(pair.value(), call(p, pair.entry(), wrong), enabled + "/" + backend + "/" + pair.entry() + "/" + wrong);
            }
            assertEquals(11L, call(p, "generic", 0L), "Object-widened Long still matches numerically"); assertEquals(21L, call(p, "nullLiteral", ManagedAddress.nullAddress()), "Null address keeps identity matching");
            assertEquals(22L, call(p, "nullLiteral", ManagedAddress.fromHex("00")), "Byte contents are not address identity");
        });
    }
    private ExecutableProgram load(Language language, String backend, List<Object> body, Map<String, Object> input) {
        var module = map("bindings", list(binding("select", lambda(list(param("input", input)), body, null))));
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    @Test void declaredCaseResultsRejectKnownColdContradictionsAndKeepUnknownFallback() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var backend : list("ast", "bytecode")) {
                    // A known Long cannot inhabit the boxed result even in an unselected arm.
                    var contradictory = caseOf(number(0), "scrutinee", wide, list(arm(0, variable("input")), otherwise(number(1))), data);
                    assertThrows(RuntimeFault.class, () -> load(language, backend, contradictory, data), backend);
                    // Missing metadata keeps generic transport; the sibling cannot refine it.
                    var unknown = caseOf(number(0), "scrutinee", wide, list(arm(0, variable("input")), otherwise(number(1))), null);
                    var program = load(language, backend, unknown, null); var sentinel = new Object();
                    assertSame(sentinel, call(program, "select", sentinel), backend); assertEquals(42L, call(program, "select", 42L), backend);
                }
            } finally { context.leave(); }
        }
    }
    @Test void singletonLiteralKeepsItsFirstInstalledMatchAndStillRejectsMismatch() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var body = caseOf(variable("input"), "scrutinee", wide, list(arm(42, number(7))));
                var program = load(language, "ast", body, wide);
                var target = program.entryTarget("select");
                compile(target);
                var runtime = com.oracle.truffle.api.Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                    .invoke(runtime, target);
                assertEquals(7L, Calls.target(target, new Object[]{0L, 42L}));
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertEquals("Non-exhaustive Core case", assertThrows(RuntimeFault.class,
                    () -> Calls.target(target, new Object[]{0L, 43L})).getMessage());
            } finally { context.leave(); }
        }
    }
    @Test void singletonConstructorKeepsFirstInstalledMatchAndRejectsMismatch() throws Exception {
        each((enabled, backend, unused) -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            var exactData = new LinkedHashMap<>(data); exactData.put("evaluated", true);
            var alternative = list("data", "Box", list("payload"), variable("payload"),
                map("binders", list(param("payload", wide))));
            var body = caseOf(variable("input"), "whole", exactData, list(alternative));
            var function = list("lam", list(param("input", exactData)), body,
                map("rep", closure, "resultRep", wide, "entryStrict", list(true)));
            var module = map("bindings", list(binding("partial", function)),
                "constructors", list(
                    map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", list(list("IntRep")), "fieldLifted", list(false), "strictFields", list(false)),
                    map("id", "Other", "name", "Other", "arity", 0, "kind", "boxed", "fieldReps", list(), "fieldLifted", list(), "strictFields", list())));
            ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
            var layout = program.constructorLayout("Box");
            var value = layout.allocate();
            layout.initializeLong(value, 0, 42L);
            var mismatch = program.constructorLayout("Other").allocate();
            var target = program.entryTarget("partial");
            compile(target);
            var runtime = com.oracle.truffle.api.Truffle.getRuntime();
            runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                .invoke(runtime, target);
            assertEquals(42L, Calls.target(target, new Object[]{0L, value}));
            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), enabled + "/" + backend);
            assertEquals("Non-exhaustive Core case", assertThrows(RuntimeFault.class,
                () -> Calls.target(target, new Object[]{0L, mismatch})).getMessage());
        });
    }
    @Test void clonedSingletonCasesKeepTheirColdInstalledMatchAndRejectColdMismatch() throws Exception {
        each((enabled, backend, unused) -> {
            if (!backend.equals("ast")) return; // Alternative is the AST case node.
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            for (boolean constructor : new boolean[]{false, true}) for (boolean matching : new boolean[]{true, false}) {
                var input = new LinkedHashMap<>(constructor ? data : wide);
                input.put("evaluated", true);
                var alternative = constructor
                    ? list("data", "Box", list("payload"), variable("payload"), map("binders", list(param("payload", wide))))
                    : arm(42, number(7));
                var body = caseOf(variable("input"), "whole", input, list(alternative));
                var function = list("lam", list(param("input", input)), body,
                    map("rep", closure, "resultRep", wide, "entryStrict", list(true)));
                var program = new Program(language, map("instrument", true, "bindings", list(binding("partial", function)),
                    "constructors", list(
                        map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", list(list("IntRep")), "fieldLifted", list(false), "strictFields", list(false)),
                        map("id", "Other", "name", "Other", "arity", 0, "kind", "boxed", "fieldReps", list(), "fieldLifted", list(), "strictFields", list()))));
                Object match = 42L, mismatch = 43L;
                if (constructor) {
                    var layout = program.constructorLayout("Box");
                    var value = layout.allocate(); layout.initializeLong(value, 0, 42L);
                    match = value; mismatch = program.constructorLayout("Other").allocate();
                }
                var original = program.entryTarget("partial");
                var root = NodeUtil.cloneNode(original.getRootNode());
                var target = root.getCallTarget();
                var originalArm = NodeUtil.findAllNodeInstances(original.getRootNode(), Alternative.class).getFirst();
                var clonedArm = NodeUtil.findAllNodeInstances(root, Alternative.class).getFirst();
                assertNotSame(originalArm, clonedArm, "exercise a real deep node clone");
                assertEquals(0L, program.diagnostics().get("compiledEntries"), "no guest training before the first clone");
                compile(target);
                var runtime = com.oracle.truffle.api.Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                    .invoke(runtime, target);
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                Object argument = matching ? match : mismatch;
                if (matching) {
                    assertEquals(constructor ? 42L : 7L, Calls.target(target, new Object[]{0L, argument}));
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                } else {
                    assertEquals("Non-exhaustive Core case", assertThrows(RuntimeFault.class,
                        () -> Calls.target(target, new Object[]{0L, argument})).getMessage());
                }
                assertSame(target, root.getCallTarget());
                assertEquals(before + 1, program.diagnostics().get("compiledEntries"), "the first call must enter installed code");
            }
        });
    }
    @Test void defaultOnlyCasesForwardConcreteReferencesAndForceSharedScrutineeOnce() throws Exception {
        each((enabled, backend, p) -> {
            var value = call(p, "make", Long.MIN_VALUE); var function = p.entryValue("plus"); var literal = ManagedAddress.fromHex("41ff");
            for (int i = 0; i < 30; i++) { assertSame(value, call(p, "dataIdentity", value)); assertSame(function, call(p, "functionIdentity", function)); assertSame(literal, call(p, "addressIdentity", literal)); }
            for (var id : list("dataIdentity", "functionIdentity", "addressIdentity")) compile(p.entryTarget(id));
            assertSame(value, call(p, "dataIdentity", value)); assertSame(function, call(p, "functionIdentity", function)); assertSame(literal, call(p, "addressIdentity", literal)); int[] evaluations = {0};
            var target = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { evaluations[0]++; return value; } }.getCallTarget(); var thunk = new Thunk(target, null);
            for (int i = 0; i < 2; i++) assertSame(value, call(p, "dataIdentity", thunk)); assertEquals(1, evaluations[0]); assertEquals(2, thunk.getState()); assertNull(thunk.getTarget());
            var failed = new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { evaluations[0]++; throw new RuntimeFault("case scrutinee failed"); } }.getCallTarget(), null);
            for (int i = 0; i < 2; i++) assertEquals("case scrutinee failed", assertThrows(RuntimeFault.class, () -> call(p, "dataIdentity", failed)).getMessage());
            assertEquals(2, evaluations[0], "Default-only case still evaluates and memoizes a failing scrutinee");
        });
    }
}
