// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.nio.file.Files;
import java.util.UUID;
import com.oracle.truffle.api.TruffleLanguage;
import thc.runtime.*;
import org.junit.jupiter.api.io.TempDir;
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
    @TempDir Path temporary;
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));

    private long compiled(Value function) {
        return ((Number) ((Map<String, Object>) Json.INSTANCE.parse(function.getMember("diagnostics").asString())).get("compiledEntries")).longValue();
    }
    @Test void realCorePreservesTypedLongArgumentsAndResults() throws Exception {
        var path = root.resolve("build/corpus/groups/int64-conversions/core/Int64Conversions.cbd");
        var module = CoreCbdFixtures.read(path);
        var bindings = (List<Map<String, Object>>) module.get("bindings");
        for (var conversion : List.of(List.of("toInt64", "IntRep", "Int64Rep"), List.of("fromInt64", "Int64Rep", "IntRep"))) {
            String name = conversion.get(0), argumentRep = conversion.get(1), resultRep = conversion.get(2);
            var selected = bindings.stream().filter(b -> ("main:Int64Conversions." + name).equals(b.get("id"))).toList();
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
            for (String backend : List.of("ast", "bytecode")) try (Context context = Main.executionContext(false)) {
                var function = context.eval("thc", CoreModules.request(List.of(path.toString()), "main:Int64Conversions." + name, true, false, backend));
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

    private Map<String, Object> model(List<?> body) {
        var parameter = Map.of("id", "x", "type", "Int64#", "lifted", false, "coercion", false,
            "rep", Map.of("kind", "long", "primReps", List.of("Int64Rep"), "evaluated", true));
        var entry = Map.of("id", "entry", "name", "entry", "arity", 1, "lifted", true, "expr", List.of("lam", List.of(parameter), body, Map.of()));
        var module = Map.of("schema", 1, "ghc", "9.14.1", "unit", "main", "boundary", "test-model", "module", "Synthetic.Int64", "constructors", List.of(), "bindings", List.of(entry));
        return module;
    }
    private String request(String backend, boolean diagnostic, List<?> body) throws Exception {
        var path = CoreCbdFixtures.write(temporary.resolve(UUID.randomUUID() + ".cbd"), model(body));
        return CoreModules.request(List.of(path.toString()), "entry", true, diagnostic, backend);
    }
    // Lexical spellings do not exist in CBD integer records. Check malformed
    // synthetic internal models directly, retaining the runtime validation contract.
    private void validateModel(Context context, String backend, boolean diagnostic, List<?> body) {
        var module = new java.util.LinkedHashMap<>(model(body)); module.put("diagnosticUnsupported", diagnostic);
        context.initialize("thc"); context.enter();
        try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            if (backend.equals("ast")) new Program(language, module); else new BytecodeProgram(language, module);
        } finally { context.leave(); }
    }
    private List<?> body(String value, boolean alternative) {
        return !alternative ? List.of("lit", "int64", value, Map.of()) : List.of("case", List.of("var", "x", Map.of()), "scrutinee", List.of(
            List.of("lit", List.of("int64", value), List.of(), List.of("lit", "int", "1", Map.of()), Map.of("binders", List.of())),
            Arrays.asList("default", null, List.of(), List.of("lit", "int", "0", Map.of()), Map.of("binders", List.of()))), Map.of());
    }
    @Test void int64LiteralsAndAlternativesRejectNoncanonicalOrOutOfRangeValues() throws Exception {
        for (String backend : List.of("ast", "bytecode")) for (boolean diagnostic : new boolean[] {false, true}) {
            try (Context context = Main.executionContext(false)) {
                for (boolean alternative : new boolean[] {false, true}) {
                    for (long value : new long[] {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
                        var function = context.eval("thc", request(backend, diagnostic, body(Long.toString(value), alternative)));
                        assertEquals(alternative ? 1L : value, function.execute(value).asLong());
                        if (alternative) assertEquals(0L, function.execute(value ^ 1L).asLong());
                    }
                    for (String value : List.of("-9223372036854775809", "9223372036854775808", "", "+1", "01", "-0", " 1", "1.0")) {
                        var error = assertThrows(RuntimeFault.class, () -> validateModel(context, backend, diagnostic, body(value, alternative)));
                        assertTrue(error.getMessage() != null && error.getMessage().contains("Invalid int64 literal"), error.getMessage());
                    }
                }
                for (String primitive : List.of("intToInt64#", "int64ToInt#")) for (int arity : new int[] {0, 2}) {
                    var body = List.of("app", List.of("prim", primitive, Map.of()), Collections.nCopies(arity, List.of("var", "x", Map.of())), Collections.nCopies(arity, false), false, false, Map.of());
                    var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(backend, diagnostic, body)));
                    assertTrue(error.getMessage() != null && error.getMessage().contains("Primitive arity mismatch: " + primitive), error.getMessage());
                }
            }
        }
    }
}
