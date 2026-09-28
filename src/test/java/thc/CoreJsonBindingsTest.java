// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

@SuppressWarnings("unchecked")
class CoreJsonBindingsTest {
    private final Map<String, Object> formal = map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false);
    private List<Object> lambda() { return lambda(List.of("var", "x"), Map.of()); }
    private List<Object> lambda(Object body) { return lambda(body, Map.of()); }
    private List<Object> lambda(Object body, Map<String, Object> metadata) { return list("lam", List.of(formal), body, metadata); }
    private Map<String, Object> binding(String id) { return binding(id, lambda(), Map.of()); }
    private Map<String, Object> binding(String id, Object body, Map<String, Object> extra) {
        var result = map("id", id, "name", id, "type", "Synthetic", "lifted", true, "arity", 1, "expr", body);
        result.putAll(extra);
        return result;
    }
    private CoreJsonIndex source(Object value) { return CoreJsonIndex.fromBytes(Json.stringify(value).getBytes(StandardCharsets.UTF_8)); }
    @FunctionalInterface private interface Action { void run(Language language, String backend, boolean async) throws Exception; }
    private void bothBackends(Action action) throws Exception {
        for (String backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true})
            try (var context = Main.executionContext(false)) {
                context.initialize("thc"); context.enter();
                try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null), backend, async); }
                finally { context.leave(); }
            }
    }
    private ExecutableProgram program(Language language, String backend, boolean async, List<Map<String, Object>> bindings) {
        var module = map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.JsonBinding", "sourceNotesEnabled", false,
            "instrument", true, "bindings", bindings, "constructors", List.of());
        return backend.equals("ast") ? new Program(language, module, async, false) : new BytecodeProgram(language, module, async);
    }
    private long count(ExecutableProgram program, String name) { return ((Number) program.diagnostics().get(name)).longValue(); }
    private Object invoke(ExecutableProgram program, Object value, Object... arguments) {
        return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{value, arguments});
    }
    @Test void jsonProjectionDoesNotLowerUnusedBodiesAtEitherSize() throws Exception {
        bothBackends((language, backend, async) -> {
            for (int size : new int[]{32, 64}) {
                var models = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < size; i++) models.add(binding("f" + i));
                try (var input = source(models)) {
                    var adapter = new CoreJsonBindings(false);
                    var bindings = adapter.bindings(input.getRoot());
                    var program = program(language, backend, async, bindings);
                    var before = adapter.statistics();
                    assertEquals(size, before.getBindingHeaders()); assertEquals(0L, before.getBodyMaterializations());
                    assertEquals(0L, before.getExpressionViews());
                    assertEquals(size * 2L, before.getSummaryExpressionsVisited(), "the policy scan is real eager work");
                    assertEquals(0L, count(program, "initializedBindingCount")); assertEquals(0L, count(program, "loweredRootCount"));
                    assertEquals(0L, count(program, "hostEntryRootCount"));
                    var first = program.entryValue("f0");
                    assertEquals(1L, adapter.statistics().getBodyMaterializations()); assertEquals(1L, count(program, "initializedBindingCount"));
                    assertEquals(1L, count(program, "loweredRootCount"));
                    long afterFirst = input.statistics().getDecodedSpanCount();
                    assertSame(first, program.entryValue("f0")); assertEquals(afterFirst, input.statistics().getDecodedSpanCount());
                    var second = program.entryValue("f" + (size - 1));
                    assertEquals(2L, adapter.statistics().getBodyMaterializations()); assertEquals(2L, count(program, "loweredRootCount"));
                    assertEquals(Long.MIN_VALUE, invoke(program, first, Long.MIN_VALUE));
                    assertEquals(Long.MAX_VALUE, invoke(program, second, Long.MAX_VALUE));
                    assertEquals(2L, adapter.statistics().getBodyMaterializations());
                }
            }
        });
    }
    @Test void diagnosticsAreSkippedWithoutConcealingRequiredProofFields() throws Exception {
        String huge = "unused ".repeat(32768);
        var foreign = map("schema", 1, "target", map("symbol", "f"), "unexpected", true);
        var metadata = map("entryStrict", List.of(false), "entryStrictSource", huge, "source", huge, "sourceNotes", List.of(huge),
            "foreignCall", foreign, "exceptionPayload", map("schema", 1, "type", "T", "unexpected", true));
        try (var input = source(binding("f", lambda(List.of("var", "x"), metadata), map("info", map("strictness", huge), "entryStrictSource", huge, "source", huge)))) {
            var projected = new CoreJsonBindings(false).binding(input.getRoot());
            assertFalse(projected.containsKey("info")); assertFalse(projected.containsKey("entryStrictSource")); assertFalse(projected.containsKey("source"));
            var body = (CoreBindingBody) projected.get("expr");
            var meta = (Map<String, Object>) body.get(3);
            assertEquals(Set.of("entryStrict", "foreignCall", "exceptionPayload"), meta.keySet());
            assertEquals(Set.of("schema", "target", "unexpected"), ((Map<?, ?>) meta.get("foreignCall")).keySet());
            assertEquals(Set.of("schema", "type", "unexpected"), ((Map<?, ?>) meta.get("exceptionPayload")).keySet());
            assertEquals(0L, body.decodeAttempts());
            assertTrue(input.statistics().getDecodedByteCount() < 512, "neither huge diagnostic values nor the complete body should be parsed");
            assertEquals(huge, ((Map<?, ?>) input.validateDocument()).get("source"), "inspection retains original JSON");
        }
    }
    private Map<String, Object> reps(boolean evaluated) { return map("kind", "long", "primReps", List.of("IntRep"), "evaluated", evaluated); }
    @Test void canonicalConsumedStringsAndVectorsDoNotMergeOccurrenceEvidence() throws Exception {
        try (var input = source(List.of(binding("a", lambda(), map("rep", reps(false), "entryStrict", List.of(false))),
                binding("b", lambda(), map("rep", reps(true), "entryStrict", List.of(false)))))) {
            var bindings = new CoreJsonBindings(false).bindings(input.getRoot());
            assertSame(bindings.get(0).get("type"), bindings.get(1).get("type"));
            assertSame(bindings.get(0).get("entryStrict"), bindings.get(1).get("entryStrict"));
            var first = (Map<?, ?>) bindings.get(0).get("rep"); var second = (Map<?, ?>) bindings.get(1).get("rep");
            assertNotSame(first, second); assertSame(first.get("kind"), second.get("kind")); assertSame(first.get("primReps"), second.get("primReps"));
            assertEquals(false, first.get("evaluated")); assertEquals(true, second.get("evaluated"));
            assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) first.get("primReps")).add("WordRep"));
        }
    }
    @Test void delimitedSummaryTraversesBodiesButNotInspectionMetadata() throws Exception {
        var fake = map("info", List.of("prim", "control0#"), "sourceNotes", List.of("prim", "prompt#"));
        var nested = list("case", List.of("var", "x"), "s", List.of(list("default", null, List.of(),
            list("let", false, List.of(binding("inner", List.of("prim", "control0#"), Map.of())), List.of("var", "inner")))));
        try (var input = source(List.of(binding("plain", lambda(), fake), binding("control", lambda(nested), Map.of())))) {
            var bindings = new CoreJsonBindings(false).bindings(input.getRoot());
            assertFalse(((CoreBindingBody) bindings.get(0).get("expr")).getHeader().getContainsDelimitedControl());
            assertTrue(((CoreBindingBody) bindings.get(1).get("expr")).getHeader().getContainsDelimitedControl());
            assertEquals(0L, ((CoreBindingBody) bindings.get(1).get("expr")).decodeAttempts());
        }
    }
    @Test void malformedColdBodyFailsOnlyOnDemandAndMemoizesItsError() throws Exception {
        bothBackends((language, backend, async) -> {
            var raw = Json.stringify(List.of(binding("good"), binding("bad",
                lambda(list("var", "x", map("rep", map("kind", "INVALID_NUMBER")))), Map.of()))).replace("\"INVALID_NUMBER\"", "1e999");
            try (var input = CoreJsonIndex.fromBytes(raw.getBytes(StandardCharsets.UTF_8))) {
                var adapter = new CoreJsonBindings(false);
                var program = program(language, backend, async, adapter.bindings(input.getRoot()));
                assertEquals(0L, adapter.statistics().getBodyMaterializations());
                assertEquals(19L, invoke(program, program.entryValue("good"), 19L));
                var failure = assertThrows(Exception.class, () -> program.entryValue("bad"));
                long decoded = input.statistics().getDecodedSpanCount();
                assertSame(failure, assertThrows(Exception.class, () -> program.entryValue("bad")));
                assertEquals(decoded, input.statistics().getDecodedSpanCount());
                assertEquals(2L, adapter.statistics().getBodyMaterializations()); assertEquals(1L, count(program, "initializedBindingCount"));
            }
        });
    }
    @Test void exactKeysDuplicateKeysAndDisabledOptimizationProofsStillReject() throws Exception {
        var app = list("app", List.of("prim", "raise#"), List.of(List.of("var", "x")), List.of(true), false, false,
            map("exceptionPayload", map("schema", 1, "type", CoreExceptionPayload.TYPE, "extra", 0),
                "callDemand", map("arity", 1, "strictArgs", List.of("not Boolean"))));
        try (var input = source(binding("f", lambda(app), Map.of()))) {
            var outer = (CoreBindingBody) new CoreJsonBindings(false).binding(input.getRoot()).get("expr");
            var body = (List<Object>) outer.get(2);
            assertThrows(RuntimeFault.class, () -> CoreExceptionPayload.validate(body));
            assertThrows(RuntimeFault.class, () -> CoreCallDemands.lowerApplication(body, false));
        }
        try (var input = CoreJsonIndex.fromBytes("{\"id\":\"a\",\"id\":\"b\",\"expr\":[\"void\"]}".getBytes(StandardCharsets.UTF_8))) {
            var projected = new CoreJsonBindings(false).binding(input.getRoot());
            var failure = assertThrows(IllegalArgumentException.class, () -> projected.get("id"));
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> projected.get("id")));
        }
    }
    @Test void closeKeepsDecodedScalarsButPreventsUnpreparedBodyAdmission() {
        var input = source(binding("f", lambda(), map("source", "source-id")));
        var projected = new CoreJsonBindings(true).binding(input.getRoot()); var name = projected.get("name");
        assertEquals("source-id", projected.get("source"));
        var body = (CoreBindingBody) projected.get("expr"); input.close();
        assertSame(name, projected.get("name"));
        var failure = assertThrows(IllegalStateException.class, body::materialize);
        assertSame(failure, assertThrows(IllegalStateException.class, body::materialize));
        assertEquals("lam", body.get(0), "owned shallow header survives source closure");
        assertEquals(1L, body.decodeAttempts());
    }
}
