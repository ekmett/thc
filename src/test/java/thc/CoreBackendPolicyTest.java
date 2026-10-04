// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreBackendPolicyTest {
    @TempDir Path directory;
    @AfterEach void releaseMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private final Map<String,Object> integer = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String,Object> data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String,Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String,Object> state = map("kind", "void", "primReps", list(), "evaluated", true);
    private Object variable(String id, Map<String,Object> rep) { return list("var", id, map("rep", rep)); }
    private Map<String,Object> function(String id, Object body) {
        return map("id", id, "name", id.substring(id.lastIndexOf('.') + 1), "type", "Int# -> Int#", "lifted", true, "arity", 1, "rep", closure,
            "expr", list("lam", list(map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false, "rep", integer)),
                body, map("rep", closure, "resultRep", integer)));
    }
    private Path fixture(String backend) throws Exception {
        String other = backend.equals("ast") ? "bytecode" : "ast";
        Object boxed = list("app", list("con", "unit:M.Box", 1, map("rep", closure)), list(list("lit", "int", "17", map("rep", integer))),
            list(false), true, true, map("rep", with(data, "evaluated", true)));
        Object trace = list("app", list("prim", "traceEvent#", map()), list(list("lit", "string-bytes", "636166",
            map("rep", map("kind", "address", "primReps", list("AddrRep"), "evaluated", true))), list("void", map("rep", state))),
            list(false, false), false, false, map("rep", state));
        var caf = map("id", "unit:M.value", "name", "value", "type", "Box", "lifted", true, "arity", 0, "rep", data,
            "expr", list("case", trace, "traced", list(list("default", null, list(), boxed, map("binders", list()))),
                map("rep", data, "binder", map("id", "traced", "lifted", false, "rep", state))));
        Object read = list("case", variable("unit:M.value", data), "boxed",
            list(list("data", "unit:M.Box", list("payload"), variable("payload", integer),
                map("binders", list(map("id", "payload", "name", "payload", "type", "Int#", "lifted", false, "coercion", false, "rep", integer))))),
            map("rep", integer, "binder", map("id", "boxed", "lifted", true, "rep", data)));
        var alias = map("id", "unit:M.alias", "name", "alias", "type", "Int# -> Int#", "lifted", true, "arity", 1,
            "rep", closure, "expr", variable("unit:M.read", closure));
        var entry = function("unit:M.entry", list("app", variable("unit:M.alias", closure), list(variable("x", integer)),
            list(false), false, false, map("rep", integer)));
        var model = map("schema", 1, "ghc", "9.14.1", "unit", "unit", "module", "M", "boundary", "optimized-Core-after-Tidy-before-CorePrep",
            "backendPolicy", map("default", backend, "bindings", map("unit:M.read", other)),
            "bindings", list(entry, alias, function("unit:M.read", read), caf),
            "constructors", list(map("id", "unit:M.Box", "name", "Box", "kind", "boxed", "arity", 1, "tag", 1,
                "fieldReps", list(list("IntRep")), "fieldTypes", list(integer), "strictFields", list(false), "fieldLifted", list(false))));
        return CoreCbdTestSupport.writeModel(directory.resolve(backend + ".cbd"), model);
    }
    @Test void mixedClosureAliasesAndCafsShareTheirOriginalCells() throws Exception {
        for (String backend : List.of("ast", "bytecode")) {
            var file = fixture(backend); var output = new ByteArrayOutputStream();
            try (var context = Context.newBuilder("thc").err(output).build()) {
                var entry = context.eval("thc", request(List.of(file.toString()), "unit:M.entry", backend, false, false, false));
                context.enter();
                try {
                    Language.currentState().getRuntimeTrace().control(500, 1);
                    var program = Language.currentState(null).getCoreUnitPrograms().getFirst();
                    var target = program.entryValue("unit:M.read");
                    var alias = (Thunk) program.entryValue("unit:M.alias");
                    assertEquals(0, alias.getState());
                    var caf = (Thunk) program.entryValue("unit:M.value");
                    assertEquals(0, caf.getState());
                    assertEquals(17L, entry.execute(0).asLong());
                    assertEquals(17L, entry.execute(1).asLong());
                    assertSame(alias, program.entryValue("unit:M.alias")); assertEquals(2, alias.getState());
                    assertSame(target, alias.getValue(), "Evaluating an alias must retain the original cross-backend closure");
                    assertSame(caf, program.entryValue("unit:M.value")); assertEquals(2, caf.getState());
                    assertEquals(Map.of("unit:M.alias", 1L, "unit:M.value", 1L), program.diagnostics().get("thunkEvaluationsByLabel"));
                    assertEquals("mixed", program.diagnostics().get("backend")); assertTrue(program.getHasBytecode());
                    assertEquals("[thc trace event] caf\n", output.toString(StandardCharsets.UTF_8));
                } finally { context.leave(); }
            }
        }
    }
    @Test void exportedAnnotationsChooseTheSameBackendsAcrossCoreCapturePaths() {
        for (String artifact : List.of("pre/BackendAnnotations.cbd", "post/BackendAnnotations.cbd", "interface.cbd")) {
            var path = Path.of("build/backend-annotations").resolve(artifact);
            assertTrue(java.nio.file.Files.isRegularFile(path), "Prepare backend-annotations first: " + path);
            for (String fallback : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                var entry = context.eval("thc", request(List.of(path.toString()), "main:BackendAnnotations.entry", fallback, false, false, false));
                assertEquals("ast", document(entry.getMember("diagnostics").asString()).get("backend"), artifact);
                assertEquals(62L, entry.execute(10L).asLong(), artifact);
                assertEquals(64L, entry.execute(11L).asLong(), artifact);
                assertEquals("mixed", document(entry.getMember("diagnostics").asString()).get("backend"), artifact);
                assertTrue(entry.hasMember("bytecode"), artifact);
            }
        }
    }
    @Test void reusableAstPreparationIgnoresUnreachableBytecodeRoots() throws Exception {
        var model = map("schema", 1, "ghc", "9.14.1", "unit", "unit", "module", "M",
            "boundary", "optimized-Core-after-Tidy-before-CorePrep", "constructors", list(),
            "backendPolicy", map("default", "ast", "bindings", map("unit:M.unused", "bytecode")),
            "bindings", list(function("unit:M.entry", variable("x", integer)),
                function("unit:M.unused", variable("x", integer))));
        var path = CoreCbdTestSupport.writeModel(directory.resolve("reusable.cbd"), model);
        try (var context = Main.executionContext(false)) {
            var entry = context.eval("thc", NativeCache.request(List.of(path.toString()), "unit:M.entry"));
            assertEquals(73L, entry.execute(73L).asLong());
            assertEquals("ast", document(entry.getMember("diagnostics").asString()).get("backend"));
        }
    }
}
