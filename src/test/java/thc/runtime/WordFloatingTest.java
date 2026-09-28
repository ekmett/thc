// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.file.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class WordFloatingTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot")), directory = root.resolve("build/word-floating");
    private final BigInteger one = BigInteger.ONE;
    private BigInteger power(int bit) { return one.shiftLeft(bit); }
    private BigInteger unsigned(long value) { return new BigInteger(Long.toUnsignedString(value)); }
    private final List<String> names = list("wordFloat", "wordDouble");
    private Map<String, Object> json(String path) throws Exception { return object(Json.parse(Files.readString(directory.resolve(path)))); }
    private Map<String, Object> module(String stage) throws Exception { return json(stage + "-core/WordFloatingAudit.json"); }
    private record Row(long input, int floatBits, long doubleBits) {}
    private Map<String, Integer> operationIds(String source) {
        var result = new LinkedHashMap<String, Integer>();
        var matcher = Pattern.compile("(?m)^    private static final int ([A-Z][A-Z0-9_]*) = ([0-9]+);[ \\t]*\\r?$").matcher(source);
        while (matcher.find()) result.put(matcher.group(1), Integer.parseInt(matcher.group(2))); return result;
    }
    @Test void integerInventoryDoesNotParseFloatingPrefixesOrNestedConstants() {
        assertEquals(map("FLOAT_ADD", 0, "WORD_FLOAT", 58), operationIds("    private static final int FLOAT_ADD = 0;\n    private static final double LN2 = 0.6931471805599453;\n"
            + "    private static final double SCALE = 1e3;\n        private static final int NESTED = 0;\n    private static final int WORD_FLOAT = 58;\r\n"));
    }
    @Test void floatingOperationIdsRemainDistinctAcrossMergedFamilies() throws Exception {
        var ids = operationIds(Files.readString(root.resolve("src/main/java/thc/runtime/FloatingPrimitives.java")));
        assertTrue(ids.keySet().containsAll(list("WORD_FLOAT", "WORD_DOUBLE", "FLOAT_ABS", "FLOAT_EXP")));
        assertEquals(ids.size(), new HashSet<>(ids.values()).size(), "Floating operation IDs must not alias another family");
    }
    // Independent integer quotient/remainder model: never use a floating cast.
    private long roundedBits(long value, int precision) {
        var n = unsigned(value); if (n.signum() == 0) return 0;
        int exponent = n.bitLength() - 1, shift = exponent + 1 - precision;
        BigInteger significand;
        if (shift <= 0) significand = n.shiftLeft(-shift);
        else {
            var qr = n.divideAndRemainder(power(shift)); var halfway = power(shift - 1);
            significand = qr[1].compareTo(halfway) > 0 || qr[1].equals(halfway) && qr[0].testBit(0) ? qr[0].add(one) : qr[0];
        }
        if (significand.bitLength() > precision) { significand = significand.shiftRight(1); exponent++; }
        int bias = precision == 24 ? 127 : 1023;
        return ((long) (exponent + bias) << (precision - 1)) | significand.clearBit(precision - 1).longValue();
    }
    private List<Long> domain() {
        var values = new TreeSet<>(list(BigInteger.ZERO, one, BigInteger.TWO, BigInteger.valueOf(3), power(64).subtract(one)));
        for (int bit = 1; bit <= 63; bit++) for (int delta = -1; delta <= 1; delta++) values.add(power(bit).add(BigInteger.valueOf(delta)));
        for (int precision : list(24, 53)) for (int bit = precision; bit <= 63; bit++) for (int odd : list(1, 3, 5)) for (int delta = -1; delta <= 1; delta++)
            values.add(power(bit).add(power(bit - precision).multiply(BigInteger.valueOf(odd))).add(BigInteger.valueOf(delta)));
        var result = new ArrayList<Long>(); for (var value : values) if (value.signum() >= 0 && value.compareTo(power(64)) < 0) result.add(value.longValue()); return result;
    }
    private List<Row> rows() throws Exception {
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(directory.resolve("oracle.tsv"))) {
            var fields = line.split("\t", -1); assertEquals(3, fields.length); rows.add(new Row(Long.parseUnsignedLong(fields[0]), (int) Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        assertEquals(domain(), rows.stream().map(Row::input).toList(), "Native domain must be complete, ordered and duplicate-free");
        for (var row : rows) {
            assertEquals((int) roundedBits(row.input(), 24), row.floatBits(), "native Float " + unsigned(row.input()));
            assertEquals(roundedBits(row.input(), 53), row.doubleBits(), "native Double " + unsigned(row.input()));
        }
        return rows;
    }
    @Test void unsignedRoundingMatchesIndependentIntegerModel() {
        var random = new Random(9141); var inputs = new ArrayList<>(domain()); for (int i = 0; i < 10000; i++) inputs.add(random.nextLong());
        for (long value : inputs) {
            assertEquals((int) roundedBits(value, 24), Float.floatToRawIntBits(WordFloatingConversions.toFloat(value)), "Float " + unsigned(value));
            assertEquals(roundedBits(value, 53), Double.doubleToRawLongBits(WordFloatingConversions.toDouble(value)), "Double " + unsigned(value));
        }
        // Mutation controls: signed conversion and Double->Float both give
        // wrong answers on this corpus, including positive signed-range inputs.
        assertNotEquals(roundedBits(-1, 53), Double.doubleToRawLongBits((double) -1L));
        for (int bit : list(54, 62, 63)) {
            long value = power(bit).add(power(bit - 24)).add(one).longValue();
            assertNotEquals((int) roundedBits(value, 24), Float.floatToRawIntBits((float) WordFloatingConversions.toDouble(value)));
        }
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private ExecutableProgram program(Language language, Map<String, Object> input, String backend) {
        return backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input);
    }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    @Test void genuineConversionsMatchNativeWithInlining() throws Exception { nativeValues(true); }
    @Test void genuineConversionsMatchNativeAcrossResidualCalls() throws Exception { nativeValues(false); }
    private void nativeValues(boolean inlining) throws Exception {
        provenanceAndAuthenticUnfoldedPrimopsRemainExact(); var rows = rows();
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var name : names) {
                    var linked = CoreModules.reachable(module(stage), name); var program = program(language, linked, backend); var host = program.hostEntryTarget(1); var entry = program.entryValue(name);
                    var targets = objects(linked.get("bindings")).stream().map(binding -> program.entryTarget((String) binding.get("id"))).toList();
                    CheckedConsumer<Row> check = row -> {
                        var result = Calls.target(host, new Object[]{entry, new Object[]{row.input()}}); var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining + "/" + unsigned(row.input());
                        if (name.equals("wordFloat")) { assertTrue(result instanceof Float, label); assertEquals(row.floatBits(), Float.floatToRawIntBits((Float) result), label); }
                        else { assertTrue(result instanceof Double, label); assertEquals(row.doubleBits(), Double.doubleToRawLongBits((Double) result), label); }
                    };
                    for (var row : rows) check.accept(row);
                    for (var target : targets.reversed()) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "initial installation"); }
                    // Start with top-bit-set max, then replay all rows twice.
                    var compiledRows = new ArrayList<>(list(rows.getLast())); compiledRows.addAll(rows); compiledRows.addAll(rows.reversed());
                    for (var row : compiledRows) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check.accept(row);
                        assertEquals(before + 2, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                        for (var target : targets) valid(target, stage + "/" + backend + "/" + name + " first/subsequent installed invocation");
                    }
                    assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                }
            } finally { context.leave(); }
        }
    }
    private Map<String, Object> binding(Map<String, Object> module, String name) {
        var bindings = objects(module.get("bindings")).stream().filter(item -> name.equals(item.get("name"))).toList(); assertEquals(1, bindings.size()); return bindings.getFirst();
    }
    @Test void provenanceAndAuthenticUnfoldedPrimopsRemainExact() throws Exception {
        var manifest = json("manifest.json"); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(64L, manifest.get("wordBits")); assertEquals(false, manifest.get("installedArtifactsHashed"));
        assertEquals(names, manifest.get("entries")); assertEquals(list("pre", "post"), manifest.get("stages")); assertEquals((long) rows().size(), manifest.get("nativeRows"));
        var sources = object(manifest.get("inputHashes"));
        var required = new HashSet<>(list("thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/WordFloatingFixtures.hs",
            "compiler/test-fixtures/WordFloatingAudit.hs", "compiler/test-fixtures/WordFloatingNative.hs", "scripts/audit-core.py", "scripts/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json", "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py"));
        try (var files = Files.list(root.resolve("compiler/THC"))) { files.filter(path -> path.getFileName().toString().endsWith(".hs")).forEach(path -> required.add(root.relativize(path).toString())); }
        try (var files = Files.list(root.resolve("scripts"))) { files.filter(path -> path.getFileName().toString().startsWith("core_") && path.getFileName().toString().endsWith(".py")).forEach(path -> required.add(root.relativize(path).toString())); }
        assertEquals(required, sources.keySet()); var artifacts = object(manifest.get("artifactHashes")); var expectedArtifacts = new HashSet<>(list("build/word-floating/oracle.tsv"));
        for (var stage : list("pre", "post")) expectedArtifacts.addAll(list("build/word-floating/" + stage + "-core/WordFloatingAudit.json", "build/word-floating/" + stage + "-audit.json"));
        assertEquals(expectedArtifacts, artifacts.keySet()); var hashes = new LinkedHashMap<>(sources); hashes.putAll(artifacts);
        for (var hash : hashes.entrySet()) assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(hash.getKey())))), "Stale fixture " + hash.getKey());
        for (var stage : list("pre", "post")) {
            var audit = json(stage + "-audit.json"); assertEquals(true, audit.get("accepted")); assertEquals(list(), audit.get("issues")); assertEquals(list(), audit.get("missingGlobals"));
            assertEquals(Set.of("word2Float#", "word2Double#"), new HashSet<>(objects(audit.get("primitives")).stream().map(item -> item.get("name")).toList()));
            for (var name : names) {
                var lambda = expression(binding(module(stage), name).get("expr")); var parameters = objects(lambda.get(1)); assertEquals(1, parameters.size()); var parameter = parameters.getFirst();
                assertEquals(list("WordRep"), object(parameter.get("rep")).get("primReps")); var calls = primitiveCalls(lambda); assertEquals(1, calls.size()); var call = calls.getFirst();
                assertEquals(name.equals("wordFloat") ? "word2Float#" : "word2Double#", expression(call.get(1)).get(1));
                var arguments = expression(call.get(2)); assertEquals(1, arguments.size()); var argument = expression(arguments.getFirst());
                assertEquals(list("var", parameter.get("id")), argument.subList(0, 2)); // not constant-folded
                assertEquals(list("WordRep"), object(object(argument.get(2)).get("rep")).get("primReps"));
                var result = list(name.equals("wordFloat") ? "FloatRep" : "DoubleRep");
                assertEquals(result, object(object(call.get(6)).get("rep")).get("primReps")); assertEquals(result, object(object(lambda.get(3)).get("resultRep")).get("primReps"));
            }
        }
    }
    private List<List<Object>> primitiveCalls(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> values) {
            if (values.size() > 1 && "app".equals(values.getFirst()) && values.get(1) instanceof List<?> head && !head.isEmpty() && "prim".equals(head.getFirst())) result.add(expression(values));
            for (var item : values) result.addAll(primitiveCalls(item));
        } else if (value instanceof Map<?, ?> map) for (var item : map.values()) result.addAll(primitiveCalls(item));
        return result;
    }
    @Test void conflictingFloatingResultAndUnaryArityAreRejected() throws Exception {
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var name : names) for (var variant : list("result", "arity")) {
                    var linked = CoreModules.reachable(module(stage), name); var lambda = expression(binding(linked, name).get("expr")); var calls = primitiveCalls(lambda);
                    assertEquals(1, calls.size()); var app = calls.getFirst();
                    if (variant.equals("result")) {
                        var other = name.equals("wordFloat") ? "double" : "float";
                        object(app.get(6)).put("rep", map("kind", other, "primReps", list(other.equals("float") ? "FloatRep" : "DoubleRep"), "evaluated", true));
                    } else {
                        expression(app.get(2)).clear(); expression(app.get(3)).clear(); object(app.get(6)).put("callDemand", map("arity", 0, "strictArgs", list()));
                    }
                    assertThrows(RuntimeFault.class, () -> program(language, linked, backend));
                }
            } finally { context.leave(); }
        }
    }
}
