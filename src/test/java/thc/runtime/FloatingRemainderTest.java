// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class FloatingRemainderTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private static final String DIR = "build/floating-remainder";
    private static final List<String> NAMES = names();
    private static List<String> names() {
        var result = new ArrayList<String>();
        for (var type : list("Float", "Double")) for (var name : list("asinh", "acosh", "atanh", "min", "max")) result.add(name + type);
        result.addAll(list("decodeWordsDirect", "decodeWordsCall", "asinhExample")); return result;
    }
    private record Row(String name, long a, long b, List<Long> values) {}
    private static boolean decode(String name) { return name.startsWith("decodeWords"); }
    private static boolean floating(String name) { return name.endsWith("Float"); }
    private static double value(String name, long bits) { return floating(name) ? Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits); }
    private static long bits(String name, double x) { return floating(name) ? Integer.toUnsignedLong(Float.floatToRawIntBits((float) x)) : Double.doubleToRawLongBits(x); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException(); }
    private String read(String path) throws Exception { return Files.readString(new File(root, path).toPath()); }
    private Map<String, Object> json(String path) throws Exception { return object(Json.parse(read(path))); }
    private List<Row> rows(String text) throws Exception {
        var result = new ArrayList<Row>();
        for (var line : text.lines().toList()) if (!line.isEmpty()) {
            var f = line.split(" ", -1); require(f.length == 7);
            result.add(new Row(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]), list(Long.parseLong(f[3]), Long.parseLong(f[4]), Long.parseLong(f[5]), Long.parseLong(f[6]))));
        }
        record Request(String name, long a, long b) {}
        var requests = new ArrayList<Request>();
        for (var line : Files.readAllLines(new File(root, DIR + "/inputs.tsv").toPath())) {
            var f = line.split(" "); requests.add(new Request(f[0], Long.parseLong(f[1]), Long.parseLong(f[2])));
        }
        if (!result.stream().map(row -> new Request(row.name, row.a, row.b)).toList().equals(requests)) throw new IllegalArgumentException("Missing, repeated or reordered native rows");
        require(result.stream().map(Row::name).distinct().toList().equals(NAMES)); require(result.size() > 5000); return result;
    }
    private List<Row> evidence() throws Exception {
        var manifest = json(DIR + "/manifest.json");
        assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(NAMES, manifest.get("entries"));
        var inputs = new HashSet<>(list("t/fixtures/compiler/FloatingRemainderAudit.hs", "t/fixtures/compiler/FloatingRemainderNative.hs",
            "src/examples/THC/InverseHyperbolic.hs", "thc.cabal", "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs",
            "t/haskell-fixtures/FloatingRemainderFixtures.hs", "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py",
            "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json"));
        for (var file : Objects.requireNonNull(new File(root, "src/compiler/THC").listFiles())) if (file.getName().endsWith(".hs")) inputs.add(root.toPath().relativize(file.toPath()).toString());
        for (var file : Objects.requireNonNull(new File(root, "bin").listFiles())) if (file.getName().startsWith("core_") && file.getName().endsWith(".py")) inputs.add(root.toPath().relativize(file.toPath()).toString());
        var commands = new ArrayList<>(list("native-build", "native-oracle"));
        var outputs = new HashSet<>(list(DIR + "/inputs.tsv", DIR + "/oracle.tsv", DIR + "/native/oracle"));
        for (var stage : list("pre", "post")) {
            commands.add(stage + "-export"); for (var name : NAMES) commands.add(stage + "-" + name + "-audit");
            outputs.add(DIR + "/" + stage + "-core/FloatingRemainderAudit.json"); outputs.add(DIR + "/" + stage + "-core/THC.InverseHyperbolic.json");
            for (var name : NAMES) outputs.add(DIR + "/" + stage + "-" + name + "-audit.json");
        }
        for (var command : commands) for (var suffix : list("stdout", "stderr", "command.json")) outputs.add(DIR + "/commands/" + command + "." + suffix);
        for (var kind : list("inputHashes", "artifactHashes")) {
            var hashes = object(manifest.get(kind)); assertEquals(kind.equals("inputHashes") ? inputs : outputs, hashes.keySet(), kind);
            for (var hash : hashes.entrySet()) assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), "Stale floating evidence: " + hash.getKey());
        }
        for (var stage : list("pre", "post")) for (var name : NAMES) {
            var report = json(DIR + "/" + stage + "-" + name + "-audit.json");
            assertEquals(true, report.get("accepted")); assertEquals(list(), report.get("issues")); assertEquals(list(), report.get("missingGlobals"));
            var primitives = objects(report.get("primitives")).stream().map(prim -> prim.get("name")).toList();
            assertTrue(primitives.contains(decode(name) ? "decodeDouble_2Int#" : name.equals("asinhExample") ? "asinhDouble#" : name + "#"));
        }
        var rows = rows(read(DIR + "/oracle.tsv")); assertEquals((long) rows.size(), manifest.get("nativeRows")); return rows;
    }
    /** Independent integer normalization, deliberately not FloatDecodeOp. */
    private static List<Long> words(long raw) {
        var u = new BigInteger(Long.toUnsignedString(raw)); var fraction = u.and(BigInteger.ONE.shiftLeft(52).subtract(BigInteger.ONE));
        int exponent = u.shiftRight(52).and(BigInteger.valueOf(2047)).intValue();
        if (exponent == 0 && fraction.signum() == 0) return list(1L, 0L, 0L, 0L);
        int shift = exponent == 0 ? 53 - fraction.bitLength() : 0; var mantissa = exponent == 0 ? fraction.shiftLeft(shift) : fraction.setBit(52);
        return list(raw < 0 ? -1L : 1L, mantissa.shiftRight(32).longValue(), mantissa.and(new BigInteger("ffffffff", 16)).longValue(), (long) (exponent == 0 ? -1074 - shift : exponent - 1075));
    }
    // Exact binary input, 90-digit decimal sqrt and convergent logarithm series;
    // no production log/log1p is used by this model.
    private final MathContext mc = new MathContext(90);
    private final BigDecimal two = new BigDecimal(2);
    private BigDecimal logTwo;
    private BigDecimal logSeries(BigDecimal x) {
        var z = x.subtract(BigDecimal.ONE).divide(x.add(BigDecimal.ONE), mc); var z2 = z.multiply(z, mc); var term = z; var sum = z;
        for (int n = 1; n <= 110; n++) { term = term.multiply(z2, mc); sum = sum.add(term.divide(new BigDecimal(2 * n + 1), mc), mc); }
        return sum.multiply(two, mc);
    }
    private BigDecimal logarithm(BigDecimal input) {
        int exponent = (int) Math.floor((input.precision() - input.scale() - 1) * 3.321928094887362);
        var x = exponent >= 0 ? input.divide(two.pow(exponent, mc), mc) : input.multiply(two.pow(-exponent, mc), mc);
        while (x.compareTo(BigDecimal.ONE) < 0) { x = x.multiply(two, mc); exponent--; }
        while (x.compareTo(two) >= 0) { x = x.divide(two, mc); exponent++; }
        if (logTwo == null) logTwo = logSeries(two);
        return logSeries(x).add(logTwo.multiply(new BigDecimal(exponent), mc), mc);
    }
    private double mathematical(String name, double x) {
        if (Double.isNaN(x)) return Double.NaN; double a = Math.abs(x);
        if (name.startsWith("asinh")) {
            if (!Double.isFinite(x) || a < 1e-20) return x; var n = new BigDecimal(a);
            return Math.copySign(logarithm(n.add(n.multiply(n, mc).add(BigDecimal.ONE).sqrt(mc), mc)).doubleValue(), x);
        }
        if (name.startsWith("acosh")) {
            if (x < 1) return Double.NaN; if (!Double.isFinite(x)) return x; var n = new BigDecimal(x);
            return logarithm(n.add(n.multiply(n, mc).subtract(BigDecimal.ONE).sqrt(mc), mc)).doubleValue();
        }
        if (a > 1) return Double.NaN; if (a == 1.0) return Math.copySign(Double.POSITIVE_INFINITY, x); if (a < 1e-20) return x;
        var n = new BigDecimal(a); return Math.copySign(logarithm(BigDecimal.ONE.add(n).divide(BigDecimal.ONE.subtract(n), mc)).divide(two, mc).doubleValue(), x);
    }
    private static void close(String name, long expected, long actual, String label) {
        double e = value(name, expected), a = value(name, actual);
        if (Double.isNaN(e)) assertTrue(Double.isNaN(a), label);
        else if (!Double.isFinite(e) || e == 0.0) assertEquals(expected, actual, label);
        else {
            assertTrue(Double.isFinite(a) && Math.copySign(1.0, e) == Math.copySign(1.0, a), label);
            var difference = BigInteger.valueOf(expected).subtract(BigInteger.valueOf(actual)).abs();
            assertTrue(difference.compareTo(BigInteger.valueOf(2)) <= 0, label + ": " + difference + " ULPs, expected=" + e + " actual=" + a);
        }
    }
    private static void selected(Row row, long actual) {
        double x = value(row.name, row.a), y = value(row.name, row.b);
        if (Double.isNaN(x) || Double.isNaN(y) || x == y)
            assertTrue(actual == row.a || actual == row.b || Double.isNaN(value(row.name, actual)) && (Double.isNaN(x) || Double.isNaN(y)), row + ": min/max must select an operand (NaN payload quieting is not portable)");
        else assertEquals((row.name.startsWith("min") ? x < y : x > y) ? row.a : row.b, actual, row.toString());
    }
    @Test void nativeCorpusAndIndependentHighPrecisionModelAgree() throws Exception {
        var corpus = evidence();
        for (var row : corpus) {
            if (decode(row.name)) {
                var expected = words(row.a);
                // Pinned RTS StgPrimFloat.c does not initialize zero's sign.
                if ((row.a & Long.MAX_VALUE) == 0L) assertEquals(expected.subList(1, expected.size()), row.values.subList(1, row.values.size()));
                else assertEquals(expected, row.values, row.toString());
            } else if (row.name.startsWith("min") || row.name.startsWith("max")) selected(row, row.values.getFirst());
            else {
                double x = value(row.name, row.a); long expected = bits(row.name, mathematical(row.name, x));
                close(row.name, expected, row.values.getFirst(), "native/" + row);
                double actual = row.name.startsWith("asinh") ? InverseHyperbolic.asinh(x) : row.name.startsWith("acosh") ? InverseHyperbolic.acosh(x) : InverseHyperbolic.atanh(x);
                close(row.name, expected, bits(row.name, actual), "managed/" + row);
            }
        }
        for (var name : NAMES) if (!decode(name)) {
            var group = corpus.stream().filter(row -> row.name.equals(name)).toList();
            assertTrue(group.stream().anyMatch(row -> value(name, row.a) == 0.0 && row.a != 0L));
            assertTrue(group.stream().anyMatch(row -> Double.isNaN(value(name, row.a))));
            assertTrue(group.stream().anyMatch(row -> value(name, row.a) == Double.POSITIVE_INFINITY));
            assertTrue(group.stream().anyMatch(row -> value(name, row.a) > 0 && value(name, row.a) < (floating(name) ? Float.MIN_NORMAL : Double.MIN_NORMAL)));
        }
        System.out.println("Floating remainder: " + corpus.size() + " native rows/model checks; native zero sign intentionally indeterminate");
    }
    @Test void malformedMissingRepeatedReorderedAndCorruptRowsReject() throws Exception {
        evidence(); var lines = Files.readAllLines(new File(root, DIR + "/oracle.tsv").toPath());
        var repeated = new ArrayList<>(lines); repeated.add(lines.getFirst()); var corrupt = new ArrayList<>(lines); corrupt.set(0, "bad");
        for (var bad : list(lines.subList(1, lines.size()), lines.reversed(), repeated, corrupt)) assertThrows(IllegalArgumentException.class, () -> rows(String.join("\n", bad)));
        var first = rows(String.join("\n", lines)).stream().filter(row -> row.name.equals("asinhDouble") && value(row.name, row.a) == 0.0).findFirst().orElseThrow();
        assertThrows(AssertionError.class, () -> close(first.name, first.values.getFirst(), 1L, "corrupt zero"));
    }
    private static List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> items) { if (!items.isEmpty() && "app".equals(items.getFirst())) result.add(expression(items)); for (var item : items) result.addAll(applications(item)); }
        else if (value instanceof Map<?, ?> fields) for (var item : fields.values()) result.addAll(applications(item));
        return result;
    }
    @Test void fourFieldDecodeRejectsMalformedPhysicalContracts(@TempDir Path temporary) throws Exception {
        evidence(); var name = "decodeWordsDirect";
        for (var mutation : list("valid", "argument", "result-carrier", "result-arity", "partial", "over", "lifted", "bare")) {
            var linked = CoreModules.reachable(json(DIR + "/pre-core/FloatingRemainderAudit.json"), name);
            var matches = applications(linked).stream().filter(app -> app.get(1) instanceof List<?> head && head.size() >= 2 && head.subList(0, 2).equals(list("prim", "decodeDouble_2Int#"))).toList();
            assertEquals(1, matches.size()); var app = matches.getFirst(); var metadata = object(app.get(6)); var proof = object(metadata.get("rep")); var args = expression(app.get(2));
            switch (mutation) {
                case "argument" -> { assertEquals(1, args.size()); CoreRepresentations.metadata(expression(args.getFirst())).put("rep", map("kind", "float", "primReps", list("FloatRep"), "evaluated", true)); }
                case "result-carrier" -> { expression(proof.get("components")).set(1, map("kind", "double", "primReps", list("DoubleRep"), "evaluated", true)); expression(proof.get("primReps")).set(1, "DoubleRep"); }
                case "result-arity" -> { expression(proof.get("components")).removeLast(); expression(proof.get("primReps")).removeLast(); }
                case "partial" -> { args.clear(); expression(app.get(3)).clear(); metadata.remove("callDemand"); }
                case "over" -> { assertEquals(1, args.size()); args.add(args.getFirst()); expression(app.get(3)).add(false); metadata.remove("callDemand"); }
                case "lifted" -> expression(app.get(3)).set(0, true);
                case "bare" -> { var head = new ArrayList<>(expression(app.get(1))); app.clear(); app.addAll(head); }
                default -> { }
            }
            var input = temporary.resolve(mutation + ".json"); Files.writeString(input, Json.stringify(linked)); var report = temporary.resolve(mutation + "-report.json");
            var process = new ProcessBuilder("python3", "bin/audit-core.py", input.toString(), "--entry", name, "--output", report.toString()).directory(root)
                .redirectOutput(temporary.resolve(mutation + ".stdout").toFile()).redirectError(temporary.resolve(mutation + ".stderr").toFile()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); fail("Shared auditor timeout: " + mutation); }
            assertEquals(mutation.equals("valid") ? 0 : 1, process.exitValue(), mutation);
            assertEquals(mutation.equals("valid"), object(Json.parse(Files.readString(report))).get("accepted"), mutation);
            for (var backend : list("ast", "bytecode")) try (var context = context(false)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    if (mutation.equals("valid")) program(language, linked, backend);
                    else assertThrows(RuntimeException.class, () -> program(language, linked, backend), backend + "/" + mutation);
                } finally { context.leave(); }
            }
        }
    }
    private static ExecutableProgram program(Language language, Map<String, Object> linked, String backend) { return backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); }
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private static void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private static void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target); valid(target);
    }
    @Test void allNativeRowsAcrossResidualCalls() throws Exception { nativeResults(false); }
    @Test void allNativeRowsWithInlining() throws Exception { nativeResults(true); }
    private void nativeResults(boolean inlining) throws Exception {
        var corpus = new LinkedHashMap<String, List<Row>>(); for (var row : evidence()) corpus.computeIfAbsent(row.name, ignored -> new ArrayList<>()).add(row);
        for (var stage : list("pre", "post")) {
            var module = CoreModules.merge(list(json(DIR + "/" + stage + "-core/FloatingRemainderAudit.json"), json(DIR + "/" + stage + "-core/THC.InverseHyperbolic.json")));
            for (var backend : list("ast", "bytecode")) for (var name : NAMES) try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var p = program(language, with(CoreModules.reachable(module, name), "instrument", true), backend); var entry = p.entryTarget(name);
                    CheckedBiConsumer<Row, Boolean> check = (row, installed) -> {
                        var expected = decode(name) && (row.a & Long.MAX_VALUE) == 0L ? words(row.a) : row.values;
                        for (int field = 0; field < (decode(name) ? 4 : 1); field++) {
                            long before = ((Number) p.diagnostics().get("compiledEntries")).longValue();
                            var label = stage + "/" + backend + "/" + name + "/" + row.a + "/" + row.b + "/" + field + "/inlining=" + inlining;
                            Object[] arguments = name.equals("asinhExample") ? new Object[]{0L, row.a} : new Object[]{0L, row.a, decode(name) ? (long) field : row.b};
                            long result = (Long) Calls.target(entry, arguments);
                            if (decode(name)) assertEquals(expected.get(field).longValue(), result, label);
                            else if (name.startsWith("min") || name.startsWith("max")) selected(row, result); else close(name, row.values.getFirst(), result, label);
                            if (installed) assertEquals(before + (name.equals("decodeWordsCall") ? 2L : 1L), ((Number) p.diagnostics().get("compiledEntries")).longValue(), label);
                            var h = language.getHandoffState().get(); assertEquals(0, h.getArguments().getDepth()); assertEquals(0, h.getResults().getDepth());
                            assertEquals(0, h.getArguments().retainedReferences()); assertEquals(0, h.getResults().retainedReferences());
                        }
                    };
                    for (var row : corpus.get(name)) check.accept(row, false);
                    var active = activeTargets(entry); assertEquals(name.equals("decodeWordsCall") ? 2 : 1, active.size());
                    for (var target : active) compile(target); long allocations = language.getHandoffState().get().getResults().getAllocations();
                    for (var row : corpus.get(name).reversed()) { check.accept(row, true); assertEquals(active, activeTargets(entry)); for (var target : active) valid(target); }
                    assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations());
                    System.out.println("PASS " + stage + "/" + backend + "/" + name + " inlining=" + inlining + " rows=" + corpus.get(name).size());
                } finally { context.leave(); }
            }
        }
    }
}
