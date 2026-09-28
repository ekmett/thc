// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticTupleTest {
    private final Map<String, Object> integral = Map.of("kind", "long", "evaluated", true, "primReps", List.of("IntRep"));
    private final Map<String, Object> state = Map.of("kind", "void", "evaluated", true, "primReps", List.of());
    private final Map<String, Object> reference = Map.of("kind", "data", "evaluated", false, "primReps", List.of("BoxedRep (Just Lifted)"));
    private final Map<String, Object> closure = Map.of("kind", "closure", "evaluated", true, "primReps", List.of("BoxedRep (Just Lifted)"));
    private Map<String, Object> binder(String id, Map<String, Object> rep) { return binder(id, rep, false); }
    private Map<String, Object> binder(String id, Map<String, Object> rep, boolean lifted) { return Map.of("id", id, "name", id, "lifted", lifted, "rep", rep); }
    private List<Object> integer(long n) { return List.of("lit", "int", Long.toString(n), Map.of("rep", integral)); }
    private List<Object> apply(List<Object> fn, List<List<Object>> operands, Map<String, Object> rep) {
        var flags = new ArrayList<Boolean>();
        for (int i = 0; i < operands.size(); i++) flags.add(operands.size() == 3 && i == 2);
        return List.of("app", fn, operands, flags, false, false, Map.of("rep", rep));
    }
    private Map<String, Object> binding(String id, List<Object> body, Map<String, Object> result) {
        var binding = new LinkedHashMap<>(binder(id, closure, true));
        binding.put("expr", List.of("lam", List.of(binder("n", integral)), body, Map.of("rep", closure, "resultRep", result)));
        return binding;
    }
    private Map<String, Object> module(boolean empty, boolean diagnostic) {
        var components = empty ? List.of() : List.of(state, integral, reference);
        Map<String, Object> tuple = Map.of("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple",
            "primReps", empty ? List.of() : List.of("IntRep", "BoxedRep (Just Lifted)"), "components", components);
        var good = apply(List.of("con", "Tuple", components.size()), empty ? List.of() : List.of(
            List.of("void", Map.of("rep", state)), integer(42), List.of("con", "Box", 0, Map.of("rep", reference))), tuple);
        List<Object> cold = List.of("case", List.of("unsupported", "cold tuple"), "unavailable",
            List.of(Arrays.asList("default", null, List.of(), good)), Map.of("rep", tuple, "binder", binder("unavailable", tuple)));
        List<Object> branch = List.of("case", List.of("var", "n", Map.of("rep", integral)), "nCase", List.of(
            List.of("lit", List.of("int", "0"), List.of(), good), Arrays.asList("default", null, List.of(), cold)),
            Map.of("rep", tuple, "binder", binder("nCase", integral)));
        List<Object> consume = List.of("case", apply(List.of("var", "producer", Map.of("rep", closure)),
            List.of(List.of("var", "n", Map.of("rep", integral))), tuple), "result",
            List.of(Arrays.asList("default", null, List.of(), integer(9))), Map.of("rep", integral, "binder", binder("result", tuple)));
        return Map.of("instrument", true, "diagnosticUnsupported", diagnostic, "constructors", List.of(
            Map.of("id", "Tuple", "name", "Tuple", "kind", "unboxed-tuple", "arity", components.size()),
            Map.of("id", "Box", "name", "Box", "kind", "boxed", "arity", 0, "fieldReps", List.of(), "strictFields", List.of(), "fieldLifted", List.of())),
            "bindings", List.of(binding("producer", branch, tuple), binding("entry", consume, integral)));
    }
    private ExecutableProgram program(Language language, Map<String, Object> input, String backend) {
        return backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input);
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private Object call(ExecutableProgram program, long n) { return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{n}}); }
    private long traps(ExecutableProgram program) { return ((Number) program.diagnostics().get("unsupportedTraps")).longValue(); }
    @Test void coldUnsupportedTupleLoadsOnlyInDiagnosticModeAndTrapsWhenDemanded() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean empty : List.of(false, true)) for (boolean inlining : List.of(false, true)) {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", Boolean.toString(inlining)).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    assertThrows(UnsupportedCore.class, () -> program(language, module(empty, false), backend));
                    var p = program(language, module(empty, true), backend);
                    for (int repeat = 0; repeat < 10; repeat++) assertEquals(9L, call(p, 0));
                    assertEquals(0L, traps(p));
                    var target = p.entryTarget("entry");
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                    long before = ((Number) p.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(9L, call(p, 0)); valid(target);
                    assertTrue(((Number) p.diagnostics().get("compiledEntries")).longValue() > before);
                    var results = language.getHandoffState().get().getResults();
                    if (!inlining) assertTrue(results.getAllocations() > 0);
                    var failure = assertThrows(RuntimeFault.class, () -> call(p, 1));
                    assertEquals("Diagnostic unsupported path reached: Unsupported Core node unsupported", failure.getMessage());
                    assertEquals(1L, traps(p));
                    assertEquals(0, results.getDepth()); assertEquals(0, results.retainedReferences());
                    assertEquals(9L, call(p, 0));
                    assertEquals(0, results.getDepth()); assertEquals(0, results.retainedReferences());
                } finally { context.leave(); }
            }
        }
    }
}
