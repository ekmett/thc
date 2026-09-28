// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class FusedFloatingTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/fused-floating");
    private static final BigInteger ONE = BigInteger.ONE;
    private record Format(int width) {
        int fraction() { return width == 32 ? 23 : 52; }
        int bias() { return width == 32 ? 127 : 1023; }
        long sign() { return 1L << (width - 1); }
        long unit() { return (long) bias() << fraction(); }
        long infinity() { return (2L * bias() + 1) << fraction(); }
        long fractionMask() { return (1L << fraction()) - 1; }
        long magnitude(long x) { return x & (sign() - 1); }
        boolean nan(long x) { return magnitude(x) > infinity(); }
        List<String> names() {
            var names = new ArrayList<String>();
            for (var suffix : list("Add", "Sub", "NegAdd", "NegSub")) names.add("fused" + (width == 32 ? "Float" : "Double") + suffix);
            return names;
        }
    }
    private static final List<Format> FORMATS = list(new Format(32), new Format(64));
    private record Input(long x, long y, long z) {}
    private record Row(String name, Input input, long bits) {}
    private static List<Input> domain(Format f) {
        var values = new TreeSet<Input>((a, b) -> {
            int order = Long.compareUnsigned(a.x, b.x); if (order != 0) return order;
            order = Long.compareUnsigned(a.y, b.y); return order != 0 ? order : Long.compareUnsigned(a.z, b.z);
        });
        var edges = new ArrayList<Long>();
        for (long bits : new long[]{0L, 1L, (1L << f.fraction()) - 1, 1L << f.fraction(), f.unit() - 2, f.unit() - 1,
                f.unit(), f.unit() + 1, f.unit() + 2, f.infinity() - 1, f.infinity(), f.infinity() + 1,
                f.infinity() + (1L << (f.fraction() - 1)) + 123}) {
            edges.add(bits); edges.add(bits ^ f.sign());
        }
        for (long x : edges) for (long y : edges) for (long z : new long[]{0L, f.sign()}) values.add(new Input(x, y, z));
        for (long x : edges) for (long z : new long[]{f.unit(), f.unit() ^ f.sign()}) {
            values.add(new Input(x, f.unit(), z)); values.add(new Input(f.unit(), x, z)); values.add(new Input(f.unit(), z, x));
        }
        var cancellations = list(new Input(f.unit() + 1, f.unit() - 2, f.unit() ^ f.sign()),
            new Input(f.infinity() - 1, f.unit() + (1L << f.fraction()), (f.infinity() - 1) ^ f.sign()),
            new Input(1, f.unit() - (1L << f.fraction()), 1),
            new Input(1L << f.fraction(), f.unit() - 1, (1L << f.fraction()) ^ f.sign()),
            new Input(f.unit() + (1L << (f.fraction() - 1)), f.unit() + 3, 1));
        for (var v : cancellations) for (long sx : new long[]{0L, f.sign()}) for (long sy : new long[]{0L, f.sign()})
            for (long sz : new long[]{0L, f.sign()}) values.add(new Input(v.x ^ sx, v.y ^ sy, v.z ^ sz));
        return new ArrayList<>(values);
    }
    private record Decoded(BigInteger coefficient, int exponent) {}
    private static Decoded decode(Format f, long bits) {
        int exponent = (int) (f.magnitude(bits) >>> f.fraction());
        var coefficient = BigInteger.valueOf(bits & f.fractionMask());
        if (exponent != 0) coefficient = coefficient.add(ONE.shiftLeft(f.fraction()));
        if ((bits & f.sign()) != 0L) coefficient = coefficient.negate();
        return new Decoded(coefficient, Math.max(exponent, 1) - f.bias() - f.fraction());
    }
    // Exact signed integer * power-of-two arithmetic, then one nearest/even
    // quotient rounding. No floating arithmetic or Math.fma in this model.
    private static Long model(Format f, int operation, Input input) {
        long x = input.x ^ (operation >= 2 ? f.sign() : 0L), y = input.y,
            z = input.z ^ ((operation & 1) != 0 ? f.sign() : 0L);
        long ax = f.magnitude(x), ay = f.magnitude(y), az = f.magnitude(z), negativeProduct = (x ^ y) & f.sign();
        if (f.nan(x) || f.nan(y) || f.nan(z) || ax == f.infinity() && ay == 0L || ay == f.infinity() && ax == 0L) return null;
        if (ax == f.infinity() || ay == f.infinity()) {
            if (az == f.infinity() && negativeProduct != (z & f.sign())) return null;
            return negativeProduct | f.infinity();
        }
        if (az == f.infinity()) return z;
        var dx = decode(f, x); var dy = decode(f, y); var dz = decode(f, z);
        int base = Math.min(dx.exponent + dy.exponent, dz.exponent);
        var sum = dx.coefficient.multiply(dy.coefficient).shiftLeft(dx.exponent + dy.exponent - base)
            .add(dz.coefficient.shiftLeft(dz.exponent - base));
        if (sum.signum() == 0) return (ax == 0L || ay == 0L) && az == 0L && negativeProduct != 0L && (z & f.sign()) != 0L ? f.sign() : 0L;
        long sign = sum.signum() < 0 ? f.sign() : 0L;
        var magnitude = sum.abs();
        int step = Math.max(magnitude.bitLength() - 1 + base - f.fraction(), 1 - f.bias() - f.fraction());
        int shift = step - base;
        BigInteger rounded;
        if (shift <= 0) rounded = magnitude.shiftLeft(-shift);
        else {
            var divisor = ONE.shiftLeft(shift); var qr = magnitude.divideAndRemainder(divisor);
            int order = qr[1].shiftLeft(1).compareTo(divisor);
            rounded = order > 0 || order == 0 && qr[0].testBit(0) ? qr[0].add(ONE) : qr[0];
        }
        if (rounded.bitLength() > f.fraction() + 1) { rounded = rounded.shiftRight(1); step++; }
        if (rounded.bitLength() <= f.fraction()) return sign | rounded.longValue();
        int exponent = step + f.fraction() + f.bias();
        return sign | (exponent >= 2 * f.bias() + 1 ? f.infinity() : ((long) exponent << f.fraction()) | (rounded.longValue() & f.fractionMask()));
    }
    private static long observed(Format f, int operation, Input input) {
        if (f.width == 32) {
            float x = Float.intBitsToFloat((int) input.x), y = Float.intBitsToFloat((int) input.y), z = Float.intBitsToFloat((int) input.z);
            return Integer.toUnsignedLong(Float.floatToRawIntBits(Math.fma(operation >= 2 ? -x : x, y, (operation & 1) != 0 ? -z : z)));
        }
        double x = Double.longBitsToDouble(input.x), y = Double.longBitsToDouble(input.y), z = Double.longBitsToDouble(input.z);
        return Double.doubleToRawLongBits(Math.fma(operation >= 2 ? -x : x, y, (operation & 1) != 0 ? -z : z));
    }
    private static void matches(Format f, Long expected, long actual, String label) {
        if (expected == null) assertTrue(f.nan(actual), label + " must be NaN (payload/sign unspecified)");
        else assertEquals(expected.longValue(), actual, label);
    }
    @Test void exactIntegerModelCoversSingleRoundingSignedZeroAndNonFiniteRules() {
        var random = new Random(9141);
        for (var f : FORMATS) {
            assertEquals(1538, domain(f).size());
            long mask = f.width == 32 ? 0xffff_ffffL : -1L;
            var inputs = new ArrayList<>(domain(f));
            for (int i = 0; i < 4096; i++) inputs.add(new Input(random.nextLong() & mask, random.nextLong() & mask, random.nextLong() & mask));
            for (var input : inputs) for (int op = 0; op <= 3; op++) matches(f, model(f, op, input), observed(f, op, input), f.width + "/" + op + "/" + input);
            var cancellation = new Input(f.unit() + 1, f.unit() - 2, f.unit() ^ f.sign());
            assertNotEquals(0L, model(f, 0, cancellation));
            long separate = f.width == 32 ? Float.floatToRawIntBits(Float.intBitsToFloat((int) cancellation.x) * Float.intBitsToFloat((int) cancellation.y) - 1f)
                : Double.doubleToRawLongBits(Double.longBitsToDouble(cancellation.x) * Double.longBitsToDouble(cancellation.y) - 1.0);
            assertEquals(0L, separate, "separately rounded multiplication loses the nonzero fused residue");
            assertEquals(0L, model(f, 2, new Input(0, 0, 0)));
            assertNotEquals(f.sign(), model(f, 2, new Input(0, 0, 0)), "negating a rounded result would produce wrong -0");
        }
        var f = FORMATS.getFirst(); var input = new Input(0x3fc00000, 0x3f800003, 1);
        float widened = (float) Math.fma((double) Float.intBitsToFloat((int) input.x), (double) Float.intBitsToFloat((int) input.y), (double) Float.intBitsToFloat(1));
        assertNotEquals(model(f, 0, input), (long) Float.floatToRawIntBits(widened), "Float FMA must not pass through Double rounding");
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException(); }
    private static Map<String, Object> json(File file) throws Exception { return object(Json.parse(Files.readString(file.toPath()))); }
    private Map<String, Object> module(String stage) throws Exception { return json(new File(directory, stage + "-core/FloatingAudit.json")); }
    private void provenance() throws Exception { provenance(json(new File(directory, "manifest.json"))); }
    private void provenance(Map<String, Object> manifest) throws Exception {
        require(manifest.keySet().equals(Set.of("schema", "ghc", "ghcInfo", "installedArtifactsHashed", "nativeFlags", "entries", "stages", "nativeRows", "inputHashes", "artifactHashes")));
        var inputs = new HashSet<>(list("test/fixtures/compiler/FloatingAudit.hs", "test/fixtures/compiler/FloatingAuditNative.hs", "thc.cabal",
            "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/FusedFloatingFixtures.hs",
            "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py", "bin/audit-core.py",
            "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json"));
        for (var file : Objects.requireNonNull(new File(root, "src/compiler/THC").listFiles())) if (file.getName().endsWith(".hs")) inputs.add("src/compiler/THC/" + file.getName());
        for (var file : Objects.requireNonNull(new File(root, "bin").listFiles())) if (file.getName().startsWith("core_") && file.getName().endsWith(".py")) inputs.add("bin/" + file.getName());
        var artifacts = new HashSet<>(list("build/fused-floating/oracle.tsv"));
        for (var stage : list("pre", "post")) { artifacts.add("build/fused-floating/" + stage + "-core/FloatingAudit.json"); artifacts.add("build/fused-floating/" + stage + "-audit.json"); }
        require(object(manifest.get("inputHashes")).keySet().equals(inputs)); require(object(manifest.get("artifactHashes")).keySet().equals(artifacts));
        assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(false, manifest.get("installedArtifactsHashed"));
        assertEquals(list("-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint"), manifest.get("nativeFlags"));
        var names = new ArrayList<String>(); long nativeRows = 0;
        for (var f : FORMATS) { names.addAll(f.names()); nativeRows += domain(f).size() * 4L; }
        assertEquals(names, manifest.get("entries")); assertEquals(list("pre", "post"), manifest.get("stages")); assertEquals(nativeRows, manifest.get("nativeRows"));
        for (var kind : list("inputHashes", "artifactHashes")) for (var hash : object(manifest.get(kind)).entrySet()) {
            var file = new File(root, hash.getKey()).getCanonicalFile(); require(file.toPath().startsWith(root.getCanonicalFile().toPath()));
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))), "Stale " + hash.getKey());
        }
        for (var stage : list("pre", "post")) assertEquals(true, json(new File(directory, stage + "-audit.json")).get("accepted"));
    }
    private List<Row> rows() throws Exception { return rows(Files.readAllLines(new File(directory, "oracle.tsv").toPath())); }
    private static List<Row> rows(List<String> lines) {
        var rows = new ArrayList<Row>();
        for (var line : lines) {
            var fields = line.split("\t", -1); require(fields.length == 5); var bits = new long[4];
            for (int i = 0; i < 4; i++) { require(fields[i + 1].matches("0|[1-9][0-9]*")); bits[i] = Long.parseUnsignedLong(fields[i + 1]); }
            rows.add(new Row(fields[0], new Input(bits[0], bits[1], bits[2]), bits[3]));
        }
        record Key(String name, Input input) {}
        var expected = new ArrayList<Key>();
        for (var f : FORMATS) for (var input : domain(f)) for (var name : f.names()) expected.add(new Key(name, input));
        if (!rows.stream().map(row -> new Key(row.name, row.input)).toList().equals(expected))
            throw new IllegalArgumentException("Missing, duplicate, reordered or invented native inputs");
        for (var f : FORMATS) for (var row : rows) if (f.names().contains(row.name)) {
            if (f.width == 32) require(row.bits >>> 32 == 0L);
            matches(f, model(f, f.names().indexOf(row.name), row.input), row.bits, "native/" + row.name + "/" + row.input);
        }
        return rows;
    }
    private static List<String> replaceFirst(List<String> text, String first) {
        var result = new ArrayList<>(text); result.set(0, first); return result;
    }
    @Test void nativeCorpusRejectsMissingDuplicateCorruptAndOutOfRangeRows() throws Exception {
        provenance(); rows(); var manifest = json(new File(directory, "manifest.json"));
        for (var kind : list("inputHashes", "artifactHashes")) for (var path : object(manifest.get(kind)).keySet())
            assertThrows(IllegalArgumentException.class, () -> provenance(with(manifest, kind, without(object(manifest.get(kind)), path))));
        var text = Files.readAllLines(new File(directory, "oracle.tsv").toPath());
        var duplicate = new ArrayList<>(text); duplicate.add(text.getFirst());
        var prefix = text.getFirst().substring(0, text.getFirst().lastIndexOf('\t'));
        for (var bad : list(text.subList(1, text.size()), duplicate, text.reversed(), replaceFirst(text, text.getFirst() + "\t0"),
                replaceFirst(text, prefix + "\t18446744073709551616"))) assertThrows(IllegalArgumentException.class, () -> rows(bad));
        assertThrows(AssertionError.class, () -> rows(replaceFirst(text, prefix + "\t1")));
    }
    private static List<List<Object>> calls(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> values) {
            if (!values.isEmpty() && "app".equals(values.getFirst()) && values.size() > 1 && values.get(1) instanceof List<?> head && !head.isEmpty() && "prim".equals(head.getFirst())) result.add(expression(values));
            for (var item : values) result.addAll(calls(item));
        } else if (value instanceof Map<?, ?> fields) for (var item : fields.values()) result.addAll(calls(item));
        return result;
    }
    private static Map<String, Object> worker(Map<String, Object> source, String name) {
        var matches = objects(source.get("bindings")).stream().filter(binding -> (name + "Worker").equals(binding.get("name"))).toList();
        assertEquals(1, matches.size()); return matches.getFirst();
    }
    @Test void genuineCoreRetainsEveryFusedTernaryProofAndRejectsCorruption() throws Exception {
        provenance();
        for (var stage : list("pre", "post")) for (var f : FORMATS) for (int operation = 0; operation < 4; operation++) {
            var name = f.names().get(operation); var source = CoreModules.reachable(module(stage), name, true);
            assertEquals(2, objects(source.get("bindings")).size()); var worker = worker(source, name);
            var primop = list("fmadd", "fmsub", "fnmadd", "fnmsub").get(operation) + (f.width == 32 ? "Float#" : "Double#");
            var calls = calls(worker.get("expr")); assertEquals(1, calls.size()); var call = calls.getFirst();
            assertEquals(primop, expression(call.get(1)).get(1)); assertEquals(list(false, false, false), call.get(3));
            var proof = list(f.width == 32 ? "FloatRep" : "DoubleRep");
            var lambda = expression(worker.get("expr"));
            assertEquals(list(proof, proof, proof), objects(lambda.get(1)).stream().map(arg -> object(arg.get("rep")).get("primReps")).toList());
            assertEquals(proof, object(object(call.get(6)).get("rep")).get("primReps"));
            for (var backend : list("ast", "bytecode")) try (var context = context(false)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (int corruption = 0; corruption <= 3; corruption++) {
                        var bad = object(Json.parse(Json.stringify(source))); var targetCalls = calls(worker(bad, name).get("expr"));
                        assertEquals(1, targetCalls.size()); var target = targetCalls.getFirst();
                        switch (corruption) {
                            case 0 -> { var args = expression(target.get(2)); target.set(2, new ArrayList<>(args.subList(0, args.size() - 1))); }
                            case 1 -> object(object(target.get(6)).get("rep")).put("primReps", list(f.width == 32 ? "DoubleRep" : "FloatRep"));
                            case 2 -> object(object(target.get(6)).get("rep")).put("primReps", list("IntRep"));
                            case 3 -> object(expression(expression(target.get(2)).getFirst()).get(2)).put("rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true));
                        }
                        assertThrows(RuntimeFault.class, () -> program(language, bad, backend), stage + "/" + backend + "/" + name + "/corruption=" + corruption);
                    }
                } finally { context.leave(); }
            }
        }
    }
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private static ExecutableProgram program(Language language, Map<String, Object> source, String backend) {
        return backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
    }
    private static void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    @Test void nativeFusedResultsRemainCompiledWithInlining() throws Exception { nativeResults(true); }
    @Test void nativeFusedResultsRemainCompiledAcrossResidualCalls() throws Exception { nativeResults(false); }
    private void nativeResults(boolean inlining) throws Exception {
        provenance(); var rows = rows();
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var f : FORMATS) for (var name : f.names()) {
                    var source = with(CoreModules.reachable(module(stage), name, true), "instrument", true);
                    var p = program(language, source, backend); var target = p.entryTarget(name);
                    var targets = objects(source.get("bindings")).stream().map(binding -> p.entryTarget((String) binding.get("id"))).toList();
                    assertEquals(2, targets.size()); var selected = rows.stream().filter(row -> row.name.equals(name)).toList();
                    CheckedBiConsumer<Row, Boolean> check = (row, compiled) -> {
                        long before = ((Number) p.diagnostics().get("compiledEntries")).longValue();
                        long value = (Long) Calls.target(target, new Object[]{0L, row.input.x, row.input.y, row.input.z});
                        matches(f, f.nan(row.bits) ? null : row.bits, value, stage + "/" + backend + "/" + name + "/" + row.input);
                        if (compiled) {
                            assertEquals(before + 2, ((Number) p.diagnostics().get("compiledEntries")).longValue());
                            for (var active : targets) valid(active);
                        }
                        var state = language.getHandoffState().get();
                        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
                        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
                    };
                    for (var row : selected) check.accept(row, false);
                    for (var active : targets.reversed()) { active.getClass().getMethod("compile", boolean.class).invoke(active, true); valid(active); }
                    for (var row : selected.reversed()) check.accept(row, true); // First installed call checked; no settling.
                    for (var row : selected) check.accept(row, true);
                    assertEquals(0L, ((Number) p.diagnostics().get("unsupportedTraps")).longValue());
                }
            } finally { context.leave(); }
        }
    }
}
