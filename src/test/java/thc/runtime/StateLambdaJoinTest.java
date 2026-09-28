// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

class StateLambdaJoinTest {
    private static <T> T single(List<T> values) {
        if (values.isEmpty()) throw new java.util.NoSuchElementException("List is empty.");
        if (values.size() != 1) throw new IllegalArgumentException("List has more than one element.");
        return values.getFirst();
    }
    private final Map<String, Object> integral = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private Map<String, Object> parameter(String id, Map<String, Object> rep) { return Map.of("id", id, "name", id, "lifted", false, "rep", rep); }
    private List<Object> variable(String id) { return variable(id, integral); }
    private List<Object> variable(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private List<Object> literal(long n) { return List.of("lit", "int", Long.toString(n), Map.of("rep", integral)); }
    private List<Object> lambda(String id, Map<String, Object> rep, List<Object> body) {
        return List.of("lam", List.of(parameter(id, rep)), body, Map.of("rep", closure, "resultRep", integral));
    }
    @SafeVarargs private final List<Object> app(List<Object> fn, List<Object>... args) {
        return List.of("app", fn, Arrays.asList(args), Collections.nCopies(args.length, false), false, false, Map.of("rep", integral));
    }
    private List<Object> plus(List<Object> a, List<Object> b) { return app(List.of("prim", "+#"), a, b); }
    private List<Object> jump() { return app(variable("finish", closure), variable("input")); }
    private List<Object> stateApplication() { return app(lambda("state", state, jump()), List.of("void", Map.of("rep", state))); }
    private Map<String, Object> binding(String id, List<Object> expr) {
        return Map.of("id", id, "name", id, "lifted", true, "arity", 1, "rep", closure, "expr", expr);
    }
    private Map<String, Object> module(List<Object> body) {
        var finish = new LinkedHashMap<>(binding("finish", lambda("value", integral, plus(variable("value"), literal(17)))));
        finish.put("joinValueArity", 1); finish.put("joinResultRep", integral);
        List<Object> region = List.of("let", false, List.of(finish), body, Map.of("rep", integral));
        return Map.of("bindings", List.of(binding("entry", lambda("input", integral, region))), "instrument", true);
    }
    @Test void ordinaryClosuresAndNonTailStateCallsStillReject() {
        var ordinary = app(lambda("value", integral, jump()), literal(0));
        var nonTail = plus(stateApplication(), literal(1));
        var effects = new ArrayList<>(stateApplication());
        effects.set(2, List.of(app(List.of("prim", "noDuplicate#"), List.of("void", Map.of("rep", state)))));
        for (var body : List.of(ordinary, nonTail, effects)) for (var backend : List.of("ast", "bytecode"))
            try (var context = Main.executionContext()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var failure = assertThrows(RuntimeFault.class, () -> {
                        if (backend.equals("ast")) new Program(language, module(body)); else new BytecodeProgram(language, module(body));
                    });
                    assertTrue(java.util.Objects.toString(failure.getMessage(), "").contains("Local join"));
                } finally { context.leave(); }
            }
    }
    @Test void stateBindingRetainsTheOriginalLexicalScopeAndMetadata() {
        var original = new ArrayList<>(stateApplication());
        Map<String, Object> metadata = Map.of("rep", integral, "source", "state-source", "sourceNotes", List.of("state-source"));
        original.set(6, metadata);
        var reduced = java.util.Objects.requireNonNull(CoreStateApplications.inline(original));
        assertEquals("case", reduced.get(0)); assertEquals("state", reduced.get(2));
        var expected = new LinkedHashMap<>(metadata); expected.put("binder", parameter("state", state));
        assertEquals(expected, reduced.get(4));
        var alternatives = (List<?>) reduced.get(3);
        assertEquals(jump(), ((List<?>) single(alternatives)).get(3));
        assertNull(CoreStateApplications.inline(app(lambda("value", integral, jump()), literal(0))));
    }
}
