// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Narrow unsigned words compute in Int carriers and expose unsigned public Long values. */
class NarrowWordPrimitiveTest {
    private Map<String, Object> integer(String rep) { return map("kind", "long", "primReps", list(rep), "evaluated", true); }
    private String request(String backend, List<Object> body, int arity, boolean diagnostic, String inputRep, String resultRep) {
        var parameters = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < arity; index++) parameters.add(map("id", "x" + index, "name", "x" + index, "type", inputRep.substring(0, inputRep.length() - 3) + "#", "lifted", false, "coercion", false, "rep", integer(inputRep)));
        var entry = map("id", "entry", "name", "entry", "lifted", true, "arity", arity, "expr", list("lam", parameters, body, map("resultRep", integer(resultRep))));
        return Json.INSTANCE.stringify(map("entry", "entry", "backend", backend, "instrument", true, "diagnosticUnsupported", diagnostic,
            "modules", list(map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.NarrowWord", "constructors", list(), "bindings", list(entry)))));
    }
    private List<Object> primitive(String name, int supplied, String inputRep) {
        var args = new ArrayList<List<Object>>(); for (int index = 0; index < supplied; index++) args.add(list("var", "x" + index, map("rep", integer(inputRep))));
        return list("app", list("prim", name), args, Collections.nCopies(supplied, false));
    }
    private BigInteger mask(int width) { return BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE); }
    private List<Long> inputs(int width) { long maximum = mask(width).longValue(); return list(0L, 1L, 17L, maximum / 2, maximum / 2 + 1, maximum - 1, maximum); }
    private long arithmetic(long input, int width) {
        var modulus = BigInteger.ONE.shiftLeft(width); var a = BigInteger.valueOf(input).mod(modulus); var b = BigInteger.valueOf(input / 257 + 11).mod(modulus);
        var wrapped = a.add(BigInteger.valueOf(17)).multiply(b.add(BigInteger.valueOf(3))).subtract(BigInteger.valueOf(29)).mod(modulus);
        int flags = (a.compareTo(b) < 0 ? 1 : 0) + (a.equals(b) ? 2 : 0) + (wrapped.compareTo(a) <= 0 ? 4 : 0);
        return wrapped.add(modulus.add(BigInteger.ONE).multiply(BigInteger.valueOf(flags))).longValue();
    }
    private BigInteger weighted(List<List<BigInteger>> items, long multiplier, long[] weights) {
        var acc = BigInteger.ZERO;
        for (var fields : items) {
            var sum = BigInteger.ZERO; for (int i = 0; i < fields.size(); i++) sum = sum.add(fields.get(i).multiply(BigInteger.valueOf(weights[i])));
            acc = acc.multiply(BigInteger.valueOf(multiplier)).add(sum);
        }
        return acc;
    }
    private long records(long input) {
        var value = BigInteger.valueOf(input); var samples = new ArrayList<List<BigInteger>>();
        for (int i = 0; i < 8; i++) {
            samples.add(list(value.mod(BigInteger.ONE.shiftLeft(8)), value.add(BigInteger.valueOf(129)).mod(BigInteger.ONE.shiftLeft(16)),
                value.multiply(BigInteger.valueOf(65537)).add(BigInteger.ONE).mod(BigInteger.ONE.shiftLeft(32))));
            value = BigInteger.valueOf(value.multiply(BigInteger.valueOf(257)).add(BigInteger.valueOf(129)).longValue());
        }
        return weighted(samples, 17, new long[]{3, 5, 7}).add(weighted(samples.reversed(), 33, new long[]{1, 11, 13})).longValue();
    }
    @Test void ordinaryNativeExamplesAgreeWithIndependentUnsignedArithmeticModel() throws Exception {
        var rows = Files.readAllLines(Path.of(System.getProperty("thc.projectRoot"), "build/corpus/oracle.tsv")).stream().map(line -> line.split("\t", -1)).filter(row -> row[0].startsWith("narrow-words/")).toList();
        var names = new HashSet<String>(); for (var row : rows) names.add(row[0].substring(row[0].indexOf('/') + 1));
        assertEquals(Set.of("word8Arithmetic", "word16Arithmetic", "word32Arithmetic", "narrowWordRecordChecksum"), names);
        for (var row : rows) {
            long input = Long.parseLong(row[1]); long expected = switch (row[0].substring(row[0].indexOf('/') + 1)) {
                case "word8Arithmetic" -> arithmetic(input, 8); case "word16Arithmetic" -> arithmetic(input, 16); case "word32Arithmetic" -> arithmetic(input, 32); default -> records(input);
            };
            assertEquals(expected, Long.parseLong(row[2]), "Independent model " + row[0] + "(" + input + ")");
        }
    }
    @Test void conversionsTruncateAndZeroExtendAllThreeWidths() {
        var raw = list(0L, 1L, -1L, 127L, 128L, 255L, 256L, -129L, 32767L, 32768L, 65535L, 65536L, -32769L, 2147483647L, 2147483648L, 4294967295L, 4294967296L, -2147483649L, Long.MIN_VALUE, Long.MAX_VALUE);
        for (String backend : list("ast", "bytecode")) try (var context = MainKt.executionContext(false)) {
            for (int width : new int[]{8, 16, 32}) for (String name : list("wordToWord" + width + "#", "word" + width + "ToWord#")) {
                var values = name.startsWith("wordTo") ? raw : inputs(width); var inputRep = name.startsWith("wordTo") ? "WordRep" : "Word" + width + "Rep";
                var resultRep = name.startsWith("wordTo") ? "Word" + width + "Rep" : "WordRep";
                var fn = context.eval("thc", request(backend, primitive(name, 1, inputRep), 1, false, inputRep, resultRep));
                for (int pass = 0; pass < 2; pass++) { if (pass == 1) assertTrue(fn.invokeMember("compile").asBoolean()); for (long value : values)
                    assertEquals(BigInteger.valueOf(value).and(mask(width)).longValue(), fn.execute(value).asLong(), backend + " " + name + "(" + value + "), pass " + pass); }
            }
        }
    }
    @Test void wrappingArithmeticMatchesIndependentBigIntegerResultsIncludingWord32ProductOverflow() {
        for (String backend : list("ast", "bytecode")) try (var context = MainKt.executionContext(false)) {
            for (int width : new int[]{8, 16, 32}) for (String operation : list("plus", "sub", "times")) {
                var name = operation + "Word" + width + "#"; var inputRep = "Word" + width + "Rep"; var fn = context.eval("thc", request(backend, primitive(name, 2, inputRep), 2, false, inputRep, inputRep));
                for (int pass = 0; pass < 2; pass++) {
                    if (pass == 1) assertTrue(fn.invokeMember("compile").asBoolean());
                    for (long left : inputs(width)) for (long right : inputs(width)) {
                        var x = BigInteger.valueOf(left); var y = BigInteger.valueOf(right);
                        var mathematical = switch (operation) { case "plus" -> x.add(y); case "sub" -> x.subtract(y); default -> x.multiply(y); };
                        assertEquals(mathematical.and(mask(width)).longValue(), fn.execute(left, right).asLong(), backend + " " + name + "(" + left + ", " + right + "), pass " + pass);
                    }
                }
            }
        }
    }
    @Test void comparisonsKeepUnsignedOrderAcrossEachWidthsSignBitAndEquality() {
        for (String backend : list("ast", "bytecode")) try (var context = MainKt.executionContext(false)) {
            for (int width : new int[]{8, 16, 32}) for (String operation : list("lt", "le")) {
                var name = operation + "Word" + width + "#"; var inputRep = "Word" + width + "Rep"; var fn = context.eval("thc", request(backend, primitive(name, 2, inputRep), 2, false, inputRep, "IntRep"));
                for (int pass = 0; pass < 2; pass++) {
                    if (pass == 1) assertTrue(fn.invokeMember("compile").asBoolean()); var values = inputs(width);
                    for (int i = 0; i < values.size(); i++) for (int j = 0; j < values.size(); j++) {
                        long left = values.get(i), right = values.get(j), expected = i < j || operation.equals("le") && i == j ? 1L : 0L;
                        assertEquals(expected, fn.execute(left, right).asLong(), backend + " " + name + "(" + left + ", " + right + ")");
                    }
                }
            }
        }
    }
    private List<Object> literalBody(int width, String inputRep, String text, boolean alternative) {
        return !alternative ? list("lit", "word" + width, text) : list("case", list("var", "x0", map("rep", integer(inputRep))), "scrutinee", list(
            list("lit", list("word" + width, text), list(), list("lit", "int", "99")), list("default", null, list(), list("lit", "int", "17"))));
    }
    @Test void narrowLiteralsAndCaseAlternativesEnforceCanonicalUnsignedRangesEvenInDiagnosticMode() {
        for (String backend : list("ast", "bytecode")) for (boolean diagnostic : list(false, true)) try (var context = MainKt.executionContext(false)) {
            for (int width : new int[]{8, 16, 32}) {
                long maximum = mask(width).longValue(); var inputRep = "Word" + width + "Rep";
                for (boolean alternative : list(false, true)) {
                    for (String text : list("0", Long.toString(maximum))) {
                        var fn = context.eval("thc", request(backend, literalBody(width, inputRep, text, alternative), 1, diagnostic, inputRep, alternative ? "IntRep" : inputRep));
                        assertEquals(alternative ? 99L : Long.parseLong(text), fn.execute(Long.parseLong(text)).asLong()); if (alternative) assertEquals(17L, fn.execute(Long.parseLong(text) ^ 1L).asLong());
                    }
                    for (String text : list("-1", Long.toString(maximum + 1), "+1", "01", "-0", "1.0", " 1", "", "18446744073709551616")) {
                        var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(backend, literalBody(width, inputRep, text, alternative), 1, diagnostic, inputRep, alternative ? "IntRep" : inputRep)));
                        assertTrue(Objects.toString(error.getMessage(), "").contains("Invalid word" + width + " literal"), error.getMessage());
                    }
                }
            }
        }
    }
    @Test void allNewPrimitivesRejectWrongAritiesAtLoad() {
        for (String backend : list("ast", "bytecode")) for (boolean diagnostic : list(false, true)) try (var context = MainKt.executionContext(false)) {
            for (int width : new int[]{8, 16, 32}) {
                var names = new LinkedHashMap<String, Integer>(); names.put("wordToWord" + width + "#", 1); names.put("word" + width + "ToWord#", 1);
                for (String operation : list("plus", "sub", "times", "lt", "le")) names.put(operation + "Word" + width + "#", 2);
                for (var item : names.entrySet()) for (int supplied : new int[]{item.getValue() - 1, item.getValue() + 1}) {
                    var name = item.getKey(); var inputRep = name.startsWith("wordTo") ? "WordRep" : "Word" + width + "Rep";
                    var resultRep = name.startsWith("wordTo") ? "Word" + width + "Rep" : name.startsWith("word") ? "WordRep" : name.startsWith("lt") || name.startsWith("le") ? "IntRep" : inputRep;
                    var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(backend, primitive(name, supplied, inputRep), supplied, diagnostic, inputRep, resultRep)));
                    assertTrue(Objects.toString(error.getMessage(), "").contains("Primitive arity mismatch: " + name), error.getMessage());
                }
            }
        }
    }
}
