// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.MainKt;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

/** Strict fields can exclude thunks without evaluating their lazy neighbors. */
class ReferenceFieldTest {
    private final List<String> boxed = list("BoxedRep (Just Lifted)");
    private Map<String, Object> proof(String kind, boolean evaluated) { return proof(kind, evaluated, boxed); }
    private Map<String, Object> proof(String kind, boolean evaluated, List<String> reps) { return map("kind", kind, "evaluated", evaluated, "primReps", reps); }
    private List<Object> variable(String id) { return list("var", id); }
    private Map<String, Object> parameter(String id) { return map("id", id, "name", id, "lifted", false, "type", "Int#", "coercion", false); }
    private List<Object> lambda(String id, List<Object> body) { return list("lam", list(parameter(id)), body); }
    private Map<String, Object> binding(String id, List<Object> body) { return map("id", id, "name", id, "lifted", true, "type", "Synthetic", "arity", body.getFirst().equals("lam") ? 1 : 0, "expr", body); }
    private List<Object> apply(String id, List<List<Object>> args, boolean lifted) { return list("app", list("con", id, args.size()), args, Collections.nCopies(args.size(), lifted)); }
    private Map<String, Object> constructor(String id, List<Map<String, Object>> proofs, List<Boolean> strict) {
        return map("id", id, "name", id, "kind", "boxed", "arity", proofs.size(), "tag", 1, "fieldTypes", proofs,
            "fieldReps", proofs.stream().map(p -> p.get("primReps")).toList(), "strictFields", strict,
            "fieldLifted", proofs.stream().map(p -> !p.get("kind").equals("long")).toList());
    }
    private Map<String, Object> record() { return constructor("Record", list(proof("data", true), proof("data", false), proof("closure", true), proof("closure", false), proof("object", true), proof("object", false)), list(true, false, true, false, true, false)); }
    private Map<String, Object> module(boolean typed) {
        var box = apply("Box", list(variable("input")), false);
        var built = apply("Record", list(variable("shared"), variable("bottom"), variable("identity"), variable("bottom"), variable("shared"), variable("bottom")), true);
        List<Object> body = list("let", false, list(binding("shared", box)), built);
        var constructors = list(record(), constructor("Box", list(proof("long", true, list("IntRep"))), list(false)));
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ReferenceFields", "instrument", true,
            "constructors", typed ? constructors : constructors.stream().map(c -> without(c, "fieldTypes")).toList(),
            "bindings", list(binding("bottom", variable("bottom")), binding("identity", lambda("x", variable("x"))), binding("entry", lambda("input", body))));
    }
    private DataValue run(ExecutableProgram program, long n) { return (DataValue) Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{n}}); }
    private void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        type.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    private void check(ExecutableProgram program, boolean typed, String backend, long n) {
        var result = run(program, n); var first = (DataValue) result.getLayout().read(result, 0);
        assertEquals(n, first.getLayout().readLong(first, 0), backend);
        assertSame(first, result.getLayout().read(result, 4), "Shared strict field identity survives");
        assertSame(program.entryValue("identity"), result.getLayout().read(result, 2));
        for (int i : new int[]{1, 3, 5}) {
            var lazy = (Thunk) result.getLayout().read(result, i);
            assertEquals(0, lazy.getState(), "A field's type must not force its lazy value");
        }
        var expected = list(typed ? DataValue.class : Object.class, Object.class, typed ? Closure.class : Object.class, Object.class, Object.class, Object.class);
        var fields = Arrays.asList(result.getClass().getDeclaredFields()); fields.sort(Comparator.comparing(java.lang.reflect.Field::getName));
        assertEquals(expected, fields.stream().map(java.lang.reflect.Field::getType).toList(), backend);
    }
    @Test void strictDataAndClosureFieldsArePreciseWhileLazyAndPolymorphicFieldsRemainGeneric() throws Exception {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean typed : new boolean[]{true, false}) for (var backend : list("ast", "bytecode")) {
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module(typed)) : new BytecodeProgram(language, module(typed));
                    for (int i = 0; i < 30; i++) check(program, typed, backend, i);
                    compile(program.entryTarget("entry")); long before = (Long) program.diagnostics().get("compiledEntries");
                    for (long n : new long[]{3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE, 0L}) check(program, typed, backend, n);
                    assertTrue((Long) program.diagnostics().get("compiledEntries") > before, backend);
                    assertEquals(0L, program.diagnostics().get("blackholes"), backend);
                }
            } finally { context.leave(); }
        }
    }
    @Test void constructorProofsMustAlignWithBothStorageAndWorkerEvaluation() {
        var good = record(); var proofs = objects(good.get("fieldTypes"));
        assertEquals(list(DataValue.class, null, Closure.class, null, null, null), Arrays.asList(new CoreFields(good).getReferenceTypes()));
        var evaluated = new ArrayList<>(proofs); evaluated.set(1, with(proofs.get(1), "evaluated", true));
        var wrongRep = new ArrayList<>(proofs); wrongRep.set(0, proof("long", true, list("IntRep")));
        var missing = new ArrayList<>(proofs); missing.set(0, null);
        for (var bad : list(with(good, "fieldTypes", proofs.subList(0, proofs.size() - 1)), with(good, "fieldTypes", evaluated),
                with(good, "fieldTypes", wrongRep), with(good, "fieldTypes", missing), with(good, "fieldLifted", list(false, true, true, true, true, true)),
                with(good, "fieldLifted", list("true", true, true, true, true, true)), with(good, "arity", 6.5), with(good, "arity", -1)))
            assertThrows(RuntimeFault.class, () -> new CoreFields(bad));
        for (var type : new CoreFields(without(good, "fieldTypes")).getReferenceTypes()) assertNull(type);
    }
    @Test void typedConstructorPapKeepsItsStrictPrefixLazyUntilSaturation() throws Exception {
        List<Object> partial = list("app", list("con", "Record", 6), list(variable("shared")), list(true));
        List<Object> body = list("let", false, list(binding("shared", apply("Box", list(variable("input")), false))), partial);
        var original = module(true); var bindings = new ArrayList<>(objects(original.get("bindings")));
        bindings.set(bindings.size() - 1, binding("entry", lambda("input", body))); var data = with(original, "bindings", bindings);
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var backend : list("ast", "bytecode")) {
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, data) : new BytecodeProgram(language, data);
                    var bottom = (Thunk) program.entryValue("bottom"); var identity = program.entryValue("identity");
                    for (int index = 0; index < 30; index++) {
                        long n = 3_000_000_017L + index;
                        var pap = (Closure) Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{n}});
                        var prefix = (Thunk) pap.supplied[0]; assertEquals(0, prefix.getState(), backend);
                        var result = (DataValue) Calls.target(program.hostEntryTarget(5), new Object[]{pap, new Object[]{bottom, identity, bottom, identity, bottom}});
                        assertEquals(2, prefix.getState(), backend); assertSame(prefix.getValue(), result.getLayout().read(result, 0), backend);
                        var value = (DataValue) prefix.getValue(); assertEquals(n, value.getLayout().readLong(value, 0), backend); assertEquals(0, bottom.getState(), backend);
                    }
                    assertEquals(0L, program.diagnostics().get("blackholes"), backend);
                }
            } finally { context.leave(); }
        }
    }
}
