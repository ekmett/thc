// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public final class DiagnosticModeTest {
    private String request(boolean diagnostic) {
        return request(diagnostic, List.of("var", "Missing.libraryBody"));
    }
    private String request(boolean diagnostic, List<?> unsupported) {
        var argument = Map.of("id", "n", "name", "n", "lifted", false);
        var body = List.of("case", List.of("var", "n"), "caseN", List.of(
            List.of("lit", List.of("int", "0"), List.of(), List.of("lit", "int", "41")),
            Arrays.asList("default", null, List.of(), unsupported)));
        var binding = Map.of("id", "entry", "name", "entry", "arity", 1,
            "lifted", true, "expr", List.of("lam", List.of(argument), body));
        var module = Map.of("schema", 1, "ghc", "9.14.1", "module", "DiagnosticTest",
            "constructors", List.of(), "bindings", List.of(binding));
        return Json.INSTANCE.stringify(Map.of("modules", List.of(module), "entry", "entry",
            "diagnosticUnsupported", diagnostic));
    }
    @Test void normalExecutionStillRejectsUnavailableCodeAtLoad() {
        try (Context context = Main.executionContext(false)) {
            var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(false)));
            assertTrue(error.getMessage() != null && error.getMessage().contains("Unresolved external binding"));
        }
    }
    @Test void diagnosticBranchesCompileAndUnavailablePathsTrapExplicitly() {
        try (Context context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(true));
            assertEquals(41L, fn.execute(0L).asLong());
            assertTrue(fn.invokeMember("compile").asBoolean());
            assertEquals(41L, fn.execute(0L).asLong());
            var before = (Map<?, ?>) Json.INSTANCE.parse(fn.getMember("diagnostics").asString());
            assertEquals("diagnostic-traps", before.get("unsupportedPolicy"));
            assertEquals(0L, before.get("unsupportedTraps"));
            assertFalse(((List<?>) before.get("deferredUnsupported")).isEmpty());
            var error = assertThrows(PolyglotException.class, () -> fn.execute(1L));
            assertTrue(error.getMessage() != null && error.getMessage().contains(
                "Diagnostic unsupported path reached: Unresolved external binding Missing.libraryBody"));
            var after = (Map<?, ?>) Json.INSTANCE.parse(fn.getMember("diagnostics").asString());
            assertEquals(1L, after.get("unsupportedTraps"));
        }
    }
    @Test void diagnosticPrimitiveGapsRemainExplicitAndMalformedCoreStillRejects() {
        var primitive = List.of("app", List.of("prim", "futurePrim#"),
            List.of(List.of("var", "n")), List.of(false));
        try (Context context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(true, primitive));
            assertEquals(41L, fn.execute(0L).asLong());
            var error = assertThrows(PolyglotException.class, () -> fn.execute(1L));
            assertTrue(error.getMessage() != null && error.getMessage().contains("Unsupported primitive futurePrim#"));
            var malformed = List.of("app", List.of("prim", "+#"), List.of(List.of("var", "n")), List.of());
            var bad = assertThrows(PolyglotException.class, () -> context.eval("thc", request(true, malformed)));
            assertTrue(bad.getMessage() != null && bad.getMessage().contains("representation flag count mismatch"));
        }
    }
}
