// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public final class Int16LiteralTest {
    private Map<String, Object> integer(String rep) {
        return Map.of("kind", "long", "primReps", List.of(rep), "evaluated", true);
    }
    private String request(String backend, List<?> body, boolean diagnostic, String resultRep, String inputRep) {
        var parameter = Map.of("id", "x", "name", "x", "type", inputRep.substring(0, inputRep.length() - 3) + "#",
            "lifted", false, "coercion", false, "rep", integer(inputRep));
        var entry = Map.of("id", "entry", "name", "entry", "lifted", true, "arity", 1,
            "expr", List.of("lam", List.of(parameter), body, Map.of("resultRep", integer(resultRep))));
        var module = Map.of("schema", 1, "ghc", "9.14.1", "module", "Synthetic.Int16Literal",
            "constructors", List.of(), "bindings", List.of(entry));
        return Json.INSTANCE.stringify(Map.of("entry", "entry", "backend", backend,
            "diagnosticUnsupported", diagnostic, "modules", List.of(module)));
    }
    private String request(String backend, List<?> body, boolean diagnostic, String resultRep) {
        return request(backend, body, diagnostic, resultRep, "IntRep");
    }
    private List<?> body(String text, boolean alternative) {
        return !alternative ? List.of("lit", "int16", text)
            : List.of("case", List.of("var", "x", Map.of("rep", integer("Int16Rep"))), "scrutinee", List.of(
                List.of("lit", List.of("int16", text), List.of(), List.of("lit", "int", "99")),
                Arrays.asList("default", null, List.of(), List.of("lit", "int", "17"))));
    }

    @Test void canonicalSigned16LiteralsAndCaseAlternativesEnforceRangeInBothLoadModes() {
        for (String backend : List.of("ast", "bytecode")) for (boolean diagnostic : new boolean[] {false, true}) {
            try (Context context = MainKt.executionContext(false)) {
                for (boolean alternative : new boolean[] {false, true}) {
                    for (String text : List.of("-32768", "-1", "0", "1", "32767")) {
                        var fn = context.eval("thc", request(backend, body(text, alternative), diagnostic,
                            alternative ? "IntRep" : "Int16Rep", "Int16Rep"));
                        assertEquals(alternative ? 99L : Long.parseLong(text), fn.execute(Long.parseLong(text)).asLong());
                        if (alternative) assertEquals(17L, fn.execute(Long.parseLong(text) ^ 1L).asLong());
                    }
                    for (String text : List.of("-32769", "32768", "", "+1", "01", "-0", " 1", "1.0", "18446744073709551616")) {
                        var error = assertThrows(PolyglotException.class, () -> context.eval("thc",
                            request(backend, body(text, alternative), diagnostic, alternative ? "IntRep" : "Int16Rep", "Int16Rep")));
                        assertTrue(error.getMessage() != null && error.getMessage().contains("Invalid int16 literal"), error.getMessage());
                    }
                }
            }
        }
    }

    @Test void intCarrierMetadataAliasesPreserveLiteralValuesButOtherCarriersFail() {
        for (String backend : List.of("ast", "bytecode")) for (boolean diagnostic : new boolean[] {false, true}) {
            try (Context context = MainKt.executionContext(false)) {
                for (var literal : List.of(List.of("int16", "-32768"), List.of("word16", "65535"))) {
                    String kind = literal.get(0), value = literal.get(1);
                    String resultRep = kind.equals("int16") ? "Int16Rep" : "Word16Rep";
                    for (String rep : List.of("Int16Rep", "Word16Rep", "Int8Rep", "Word8Rep", "Int32Rep", "Word32Rep", "IntRep", "WordRep", "Int64Rep", "Word64Rep")) {
                        for (String carrier : List.of("long", "unknown")) {
                            var metadata = Map.of("rep", Map.of("kind", carrier, "primReps", List.of(rep), "evaluated", true));
                            var body = List.of("lit", kind, value, metadata);
                            if (carrier.equals("long") && Set.of("Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep").contains(rep)) {
                                assertEquals(Long.parseLong(value), context.eval("thc", request(backend, body, diagnostic, resultRep)).execute(0L).asLong());
                            } else {
                                var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(backend, body, diagnostic, resultRep)));
                                assertTrue(error.getMessage() != null && error.getMessage().contains("literal requires a scalar Int carrier"), error.getMessage());
                            }
                        }
                    }
                }
            }
        }
    }

    private Map<String, Object> unknown(Object evaluated) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "unknown");
        result.put("primReps", null);
        if (evaluated != null) result.put("evaluated", evaluated);
        return result;
    }

    @Test void intrinsicNarrowLiteralsRefineUnconstrainedButNotMalformedProofs() {
        for (String backend : List.of("ast", "bytecode")) for (boolean diagnostic : new boolean[] {false, true}) {
            try (Context context = MainKt.executionContext(false)) {
                for (String kind : List.of("int16", "word16")) {
                    String resultRep = kind.equals("int16") ? "Int16Rep" : "Word16Rep";
                    for (boolean evaluated : new boolean[] {false, true}) for (boolean nullRegisters : new boolean[] {false, true}) {
                        var proof = unknown(evaluated);
                        if (!nullRegisters) proof.remove("primReps");
                        assertEquals(1L, context.eval("thc", request(backend,
                            List.of("lit", kind, "1", Map.of("rep", proof)), diagnostic, resultRep)).execute(0L).asLong());
                        for (var conversion : List.of(List.of("int16ToInt#", "IntRep"), List.of("word16ToWord#", "WordRep"))) {
                            String operation = conversion.get(0), result = conversion.get(1);
                            var body = List.of("app", List.of("prim", operation), List.of(List.of("lit", kind, "1", Map.of("rep", proof))),
                                List.of(false), false, false, Map.of("rep", integer(result)));
                            assertEquals(1L, context.eval("thc", request(backend, body, diagnostic, result)).execute(0L).asLong());
                        }
                    }
                    List<Object> malformed = List.of(List.of(), "unknown", unknown(null),
                        Map.of("kind", "float", "primReps", List.of("FloatRep"), "evaluated", true),
                        Map.of("kind", "double", "primReps", List.of("DoubleRep"), "evaluated", true),
                        Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true),
                        unknown("false"),
                        Map.of("kind", "unknown", "primReps", List.of(), "evaluated", false),
                        Map.of("kind", "unknown", "primReps", List.of(), "evaluated", false,
                            "aggregate", "unboxed-tuple", "components", List.of()),
                        Map.of("kind", "unknown", "primReps", List.of("WordRep"), "evaluated", true,
                            "aggregate", "unboxed-sum", "tagSlot", 0, "alternativeSlots", List.of(List.of(), List.of()),
                            "alternatives", List.of(Map.of("kind", "void", "primReps", List.of(), "evaluated", true),
                                Map.of("kind", "void", "primReps", List.of(), "evaluated", true))));
                    for (Object proof : malformed) assertThrows(PolyglotException.class, () -> context.eval("thc",
                        request(backend, List.of("lit", kind, "1", Map.of("rep", proof)), diagnostic, resultRep)),
                        backend + "/" + kind + "/diagnostic=" + diagnostic + "/proof=" + proof);
                }
            }
        }
    }
}
