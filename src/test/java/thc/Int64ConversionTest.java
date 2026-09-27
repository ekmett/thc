// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public final class Int64ConversionTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));

    private long compiled(Value function) {
        return ((Number) ((Map<String, Object>) Json.INSTANCE.parse(function.getMember("diagnostics").asString())).get("compiledEntries")).longValue();
    }
    @Test void realCorePreservesTypedLongArgumentsAndResults() throws Exception {
        var module = (Map<String, Object>) Json.INSTANCE.parse(Files.readString(root.resolve(
            "build/corpus/groups/int64-conversions/core/THC.Int64Conversions.json")));
        var bindings = (List<Map<String, Object>>) module.get("bindings");
        for (var conversion : List.of(List.of("toInt64", "IntRep", "Int64Rep"), List.of("fromInt64", "Int64Rep", "IntRep"))) {
            String name = conversion.get(0), argumentRep = conversion.get(1), resultRep = conversion.get(2);
            var selected = bindings.stream().filter(b -> name.equals(b.get("name"))).toList();
            assertEquals(1, selected.size());
            var lambda = (List<Object>) selected.getFirst().get("expr");
            assertEquals("lam", lambda.get(0));
            var parameters = (List<Map<String, Object>>) lambda.get(1);
            assertEquals(1, parameters.size());
            var parameterProof = (Map<String, Object>) parameters.getFirst().get("rep");
            var resultProof = (Map<String, Object>) ((Map<String, Object>) lambda.get(3)).get("resultRep");
            assertEquals("long", parameterProof.get("kind"));
            assertEquals("long", resultProof.get("kind"));
            assertEquals(List.of(argumentRep), parameterProof.get("primReps"));
            assertEquals(List.of(resultRep), resultProof.get("primReps"));
            for (String backend : List.of("ast", "bytecode")) try (Context context = MainKt.executionContext(false)) {
                var function = context.eval("thc", Json.INSTANCE.stringify(Map.of("modules", List.of(module), "entry", name, "backend", backend)));
                List<Long> values = List.of(0L, 1L, -1L, (long) Integer.MIN_VALUE - 1, (long) Integer.MAX_VALUE + 1,
                    Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE);
                for (long value : values) assertEquals(value, function.execute(value).asLong());
                assertTrue(function.invokeMember("compile").asBoolean());
                long before = compiled(function);
                for (long value : values.reversed()) assertEquals(value, function.execute(value).asLong());
                assertTrue(compiled(function) > before, backend + " " + name + " installed code");
            }
        }
    }

    private String request(String backend, boolean diagnostic, List<?> body) {
        var parameter = Map.of("id", "x", "type", "Int64#", "lifted", false, "coercion", false);
        var entry = Map.of("id", "entry", "name", "entry", "arity", 1, "lifted", true, "expr", List.of("lam", List.of(parameter), body));
        var module = Map.of("schema", 1, "ghc", "9.14.1", "module", "Synthetic.Int64", "constructors", List.of(), "bindings", List.of(entry));
        return Json.INSTANCE.stringify(Map.of("entry", "entry", "backend", backend, "diagnosticUnsupported", diagnostic, "modules", List.of(module)));
    }
    private List<?> body(String value, boolean alternative) {
        return !alternative ? List.of("lit", "int64", value) : List.of("case", List.of("var", "x"), "scrutinee", List.of(
            List.of("lit", List.of("int64", value), List.of(), List.of("lit", "int", "1")),
            Arrays.asList("default", null, List.of(), List.of("lit", "int", "0"))));
    }
    @Test void int64LiteralsAndAlternativesRejectNoncanonicalOrOutOfRangeValues() {
        for (String backend : List.of("ast", "bytecode")) for (boolean diagnostic : new boolean[] {false, true}) {
            try (Context context = MainKt.executionContext(false)) {
                for (boolean alternative : new boolean[] {false, true}) {
                    for (long value : new long[] {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
                        var function = context.eval("thc", request(backend, diagnostic, body(Long.toString(value), alternative)));
                        assertEquals(alternative ? 1L : value, function.execute(value).asLong());
                        if (alternative) assertEquals(0L, function.execute(value ^ 1L).asLong());
                    }
                    for (String value : List.of("-9223372036854775809", "9223372036854775808", "", "+1", "01", "-0", " 1", "1.0")) {
                        var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(backend, diagnostic, body(value, alternative))));
                        assertTrue(error.getMessage() != null && error.getMessage().contains("Invalid int64 literal"), error.getMessage());
                    }
                }
                for (String primitive : List.of("intToInt64#", "int64ToInt#")) for (int arity : new int[] {0, 2}) {
                    var body = List.of("app", List.of("prim", primitive), Collections.nCopies(arity, List.of("var", "x")), Collections.nCopies(arity, false));
                    var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(backend, diagnostic, body)));
                    assertTrue(error.getMessage() != null && error.getMessage().contains("Primitive arity mismatch: " + primitive), error.getMessage());
                }
            }
        }
    }
}
