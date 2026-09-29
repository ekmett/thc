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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class SqrtPrimitiveTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private static final List<String> MATH_ENTRIES = mathEntries();
    private static List<String> mathEntries() {
        var result = new ArrayList<String>();
        for (var name : list("fabs", "exp", "expm1", "log", "log1p", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "power")) {
            result.add(name + "Float"); result.add(name + "Double");
        }
        return result;
    }
    private Map<String, Object> json(String path) throws Exception { return object(Json.parse(Files.readString(new File(root, path).toPath()))); }
    @BeforeEach void verifyEvidence() throws Exception {
        var evidence = json("build/sqrt/manifest.json");
        assertEquals(1L, evidence.get("schema")); assertEquals("9.14.1", evidence.get("ghc")); assertEquals(false, evidence.get("installedArtifactsHashed"));
        for (var key : list("inputHashes", "artifactHashes")) for (var hash : object(evidence.get(key)).entrySet()) {
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath())));
            assertEquals(hash.getValue(), actual, "Stale sqrt evidence: " + hash.getKey());
        }
        var required = new ArrayList<>(list("bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json", "src/tools/primops/PrimopTools.hs"));
        for (var file : Objects.requireNonNull(new File(root, "bin").listFiles())) if (file.getName().startsWith("core_") && file.getName().endsWith(".py")) required.add(root.toPath().relativize(file.toPath()).toString());
        assertTrue(object(evidence.get("inputHashes")).keySet().containsAll(required));
        var entries = new ArrayList<>(list("sqrtFloat", "sqrtDouble", "floatCase", "doubleCase")); entries.addAll(MATH_ENTRIES);
        assertEquals(entries, evidence.get("entries")); assertEquals(rows().values().stream().mapToLong(List::size).sum(), evidence.get("nativeRows"));
        for (var stage : list("pre", "post")) assertEquals(true, json("build/sqrt/" + stage + "-audit.json").get("accepted"));
    }
    private Map<String, Object> module(String stage) throws Exception { return thc.CoreCbdFixtures.read(new File(root, "build/sqrt/" + stage + "-core/SqrtAudit.cbd").toPath()); }
    private static String entryId(String name) { return "main:SqrtAudit." + name; }
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private static void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private static void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "initial compilation"); }
    private static ExecutableProgram program(Language language, Map<String, Object> module, String backend) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private Map<String, List<String[]>> rows() throws Exception {
        var result = new LinkedHashMap<String, List<String[]>>();
        for (var line : Files.readAllLines(new File(root, "build/sqrt/oracle.tsv").toPath())) {
            var row = line.split("\t"); result.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }
    private static long count(ExecutableProgram p) { return ((Number) p.diagnostics().get("compiledEntries")).longValue(); }
    private static void call(ExecutableProgram p, String entry, String[] row, String label) {
        Object input = switch (entry) {
            case "sqrtFloat" -> Float.intBitsToFloat((int) Long.parseLong(row[1]));
            case "sqrtDouble" -> Double.longBitsToDouble(Long.parseUnsignedLong(row[1]));
            default -> Long.parseLong(row[1]);
        };
        var result = Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue(entryId(entry)), new Object[]{input}});
        switch (entry) {
            case "sqrtFloat" -> {
                assertTrue(result instanceof Float, label); float expected = Float.intBitsToFloat((int) Long.parseLong(row[2]));
                if (Float.isNaN(expected)) assertTrue(Float.isNaN((Float) result), label);
                else assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits((Float) result), label);
            }
            case "sqrtDouble" -> {
                assertTrue(result instanceof Double, label); double expected = Double.longBitsToDouble(Long.parseUnsignedLong(row[2]));
                if (Double.isNaN(expected)) assertTrue(Double.isNaN((Double) result), label);
                else assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits((Double) result), label);
            }
            default -> assertEquals(Long.parseLong(row[2]), result, label);
        }
    }
    @Test void publicSqrtMatchesNativeWithInlining() throws Exception { nativeResults(true); }
    @Test void publicSqrtMatchesNativeAcrossResidualCalls() throws Exception { nativeResults(false); }
    private void nativeResults(boolean inlining) throws Exception {
        var rows = rows(); rows.keySet().retainAll(Set.of("sqrtFloat", "sqrtDouble", "floatCase", "doubleCase"));
        assertEquals(Set.of("sqrtFloat", "sqrtDouble", "floatCase", "doubleCase"), rows.keySet());
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var entryRows : rows.entrySet()) {
                    var entry = entryRows.getKey(); var selected = entryRows.getValue(); var linked = CoreModules.reachable(module(stage), entryId(entry));
                    var p = program(language, linked, backend); var target = p.entryTarget(entryId(entry));
                    for (var row : selected) call(p, entry, row, backend + "/" + entry + "/interpreter/" + row[1]);
                    for (var binding : objects(linked.get("bindings"))) if (expression(binding.get("expr")).getFirst().equals("lam")) compile(p.entryTarget((String) binding.get("id")));
                    for (var row : selected) {
                        var label = stage + "/" + backend + "/" + entry + "/" + row[1] + "/inlining=" + inlining;
                        long before = count(p); call(p, entry, row, label);
                        assertEquals(before + (entry.endsWith("Case") ? 2 : 1), count(p), label); valid(target, label);
                    }
                    assertEquals(0L, ((Number) p.diagnostics().get("unsupportedTraps")).longValue());
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                }
            } finally { context.leave(); }
        }
    }
    private record Dyadic(BigInteger coefficient, int exponent) {
        Dyadic squared() { return new Dyadic(coefficient.multiply(coefficient), exponent * 2); }
        int compare(Dyadic other) {
            int origin = Math.min(exponent, other.exponent);
            return coefficient.shiftLeft(exponent - origin).compareTo(other.coefficient.shiftLeft(other.exponent - origin));
        }
    }
    private static Dyadic value(long bits, int width) {
        int fractionWidth = width == 32 ? 23 : 52, bias = width == 32 ? 127 : 1023;
        long fraction = bits & ((1L << fractionWidth) - 1);
        int rawExponent = (int) ((bits >>> fractionWidth) & (width == 32 ? 255L : 2047L));
        long coefficient = fraction + (rawExponent == 0 ? 0L : 1L << fractionWidth);
        return new Dyadic(BigInteger.valueOf(coefficient), Math.max(rawExponent, 1) - bias - fractionWidth);
    }
    private static Dyadic midpoint(Dyadic left, Dyadic right) {
        int exponent = Math.min(left.exponent, right.exponent);
        return new Dyadic(left.coefficient.shiftLeft(left.exponent - exponent).add(right.coefficient.shiftLeft(right.exponent - exponent)), exponent - 1);
    }
    // Exact dyadic squares and their midpoint decide nearest-even, independently
    // of every JVM floating sqrt implementation.
    private static Long exactSqrtBits(long bits, int width) {
        long sign = 1L << (width - 1), infinity = width == 32 ? 0x7f800000L : 0x7ff0000000000000L, magnitude = bits & (sign - 1);
        if (magnitude == 0L) return bits;
        if ((bits & sign) != 0L || magnitude > infinity) return null;
        if (magnitude == infinity) return infinity;
        var input = value(bits, width); long low = 0L, high = infinity - 1;
        while (low < high) {
            long mid = low + (high - low + 1) / 2;
            if (value(mid, width).squared().compare(input) <= 0) low = mid; else high = mid - 1;
        }
        var lower = value(low, width); if (lower.squared().compare(input) == 0) return low;
        int comparison = midpoint(lower, value(low + 1, width)).squared().compare(input);
        return comparison > 0 || comparison == 0 && low % 2 == 0L ? low : low + 1;
    }
    @Test void nativeSqrtMatchesIndependentExactRoundingAndIntegerConsumers() throws Exception {
        var rows = rows(); int nanRows = 0;
        for (var entry : list("sqrtFloat", "sqrtDouble")) {
            int width = entry.equals("sqrtFloat") ? 32 : 64; assertEquals(196, rows.get(entry).size(), entry + " native domain");
            for (var row : rows.get(entry)) {
                long bits = Long.parseUnsignedLong(row[1]), nativeBits = Long.parseUnsignedLong(row[2]); var expected = exactSqrtBits(bits, width);
                if (expected == null) {
                    long infinity = width == 32 ? 0x7f800000L : 0x7ff0000000000000L, fraction = width == 32 ? 0x007fffffL : 0x000fffffffffffffL;
                    assertEquals(infinity, nativeBits & infinity, entry + "/" + row[1] + " exponent");
                    assertTrue((nativeBits & fraction) != 0L, entry + "/" + row[1] + " NaN fraction"); nanRows++;
                } else assertEquals(expected.longValue(), nativeBits, entry + "/" + row[1] + " exact bits");
            }
        }
        assertEquals(104, nanRows);
        for (var entry : list("floatCase", "doubleCase")) {
            assertEquals(13, rows.get(entry).size(), entry + " native domain");
            for (var row : rows.get(entry)) assertEquals(new BigInteger(row[1]).sqrt().longValue(), Long.parseLong(row[2]), entry + "/" + row[1]);
        }
    }
    private static List<String> primitiveNames(Object value) {
        var names = new ArrayList<String>();
        if (value instanceof List<?> items) {
            if (!items.isEmpty() && "prim".equals(items.getFirst())) names.add((String) items.get(1));
            for (var item : items) names.addAll(primitiveNames(item));
        } else if (value instanceof Map<?, ?> fields) for (var item : fields.values()) names.addAll(primitiveNames(item));
        return names;
    }
    @Test void exportedScalarMathRetainsExactTypedPrimitiveCalls() throws Exception {
        var primitive = Map.of("powerFloat", "powerFloat#", "powerDouble", "**##");
        for (var stage : list("pre", "post")) {
            var exported = module(stage);
            assertEquals(stage.equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep", exported.get("boundary"));
            var bindings = new LinkedHashMap<String, Map<String, Object>>(); for (var binding : objects(exported.get("bindings"))) bindings.put((String) binding.get("id"), binding);
            var entries = new ArrayList<>(list("sqrtFloat", "sqrtDouble")); entries.addAll(MATH_ENTRIES);
            for (var entry : entries) {
                var lambda = expression(bindings.get(entryId(entry)).get("expr")); var rep = entry.endsWith("Float") ? "FloatRep" : "DoubleRep"; var kind = entry.endsWith("Float") ? "float" : "double";
                assertEquals("lam", lambda.get(0), stage + "/" + entry);
                var inputs = objects(lambda.get(1)); assertEquals(1, inputs.size()); var inputRep = object(inputs.getFirst().get("rep")); var resultRep = object(object(lambda.get(3)).get("resultRep"));
                assertEquals(list(rep), inputRep.get("primReps")); assertEquals(kind, inputRep.get("kind")); assertEquals(true, inputRep.get("evaluated"));
                assertEquals(list(rep), resultRep.get("primReps")); assertEquals(kind, resultRep.get("kind"));
                var expected = primitive.get(entry);
                if (expected == null) expected = entry.startsWith("sqrt") ? entry + "#" : entry.replaceFirst("(Float|Double)$", "") + (entry.endsWith("Float") ? "Float#" : "Double#");
                assertEquals(list(expected), primitiveNames(lambda.get(2)), stage + "/" + entry + " primitive");
            }
        }
    }
    private static void mathCall(ExecutableProgram p, String entry, String[] row, String label) {
        boolean floating = entry.endsWith("Float"); Object input;
        if (floating) input = Float.intBitsToFloat((int) Long.parseLong(row[1])); else input = Double.longBitsToDouble(Long.parseUnsignedLong(row[1]));
        var result = Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue(entryId(entry)), new Object[]{input}});
        if (floating) {
            assertTrue(result instanceof Float, label); float actual = (Float) result, expected = Float.intBitsToFloat((int) Long.parseLong(row[2]));
            if (Float.isNaN(expected)) assertTrue(Float.isNaN(actual), label);
            else if (Float.isInfinite(expected) || expected == 0f) assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), label);
            else assertTrue(Math.abs(actual - expected) <= Math.max(8 * Math.ulp(expected), Math.abs(expected) * 2e-6f), label + ": native=" + expected + " JVM=" + actual);
        } else {
            assertTrue(result instanceof Double, label); double actual = (Double) result, expected = Double.longBitsToDouble(Long.parseUnsignedLong(row[2]));
            if (Double.isNaN(expected)) assertTrue(Double.isNaN(actual), label);
            else if (Double.isInfinite(expected) || expected == 0.0) assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), label);
            else assertTrue(Math.abs(actual - expected) <= Math.max(16 * Math.ulp(expected), Math.abs(expected) * 2e-14), label + ": native=" + expected + " JVM=" + actual);
        }
    }
    @Test void scalarMathMatchesNativeThroughTypedCompiledAstAndBytecode() throws Exception {
        var rows = rows(); assertTrue(rows.keySet().containsAll(MATH_ENTRIES)); assertEquals(402, MATH_ENTRIES.stream().mapToInt(entry -> rows.get(entry).size()).sum());
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var entry : MATH_ENTRIES) {
                    var p = program(language, CoreModules.reachable(module(stage), entryId(entry)), backend); var target = p.entryTarget(entryId(entry)); var selected = rows.get(entry);
                    for (var row : selected) mathCall(p, entry, row, stage + "/" + backend + "/" + entry + "/interpreter/" + row[1]);
                    compile(target); long before = count(p);
                    for (var row : selected) { mathCall(p, entry, row, stage + "/" + backend + "/" + entry + "/compiled/" + row[1]); valid(target, stage + "/" + backend + "/" + entry); }
                    assertEquals(before + selected.size(), count(p), stage + "/" + backend + "/" + entry);
                    assertEquals(0L, ((Number) p.diagnostics().get("unsupportedTraps")).longValue());
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                }
            } finally { context.leave(); }
        }
    }
    @Test void coldNaNsNegativesSubnormalsAndZeroSignsDoNotInvalidateCompiledSqrt() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var entry : list("sqrtFloat", "sqrtDouble")) {
                    var selected = rows().get(entry); var p = program(language, CoreModules.reachable(module("pre"), entryId(entry)), backend);
                    long one = entry.equals("sqrtFloat") ? 0x3f800000L : 0x3ff0000000000000L;
                    var warmRows = selected.stream().filter(row -> row[1].equals(Long.toString(one))).toList(); assertEquals(1, warmRows.size()); var warm = warmRows.getFirst();
                    for (int i = 0; i < 20; i++) call(p, entry, warm, "warm"); var target = p.entryTarget(entryId(entry)); compile(target);
                    for (var row : selected) {
                        long before = count(p); call(p, entry, row, backend + "/" + entry + "/cold/" + row[1]);
                        assertEquals(before + 1, count(p), backend + "/" + entry + "/" + row[1]); valid(target, backend + "/" + entry + "/" + row[1]);
                    }
                }
            } finally { context.leave(); }
        }
    }
    @Test void sqrtRejectsConflictingResultStorageAndWrongArity() throws Exception {
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var entry : list("sqrtFloat", "sqrtDouble")) for (var variant : list("result", "arity")) {
                    var linked = CoreModules.reachable(module(stage), entryId(entry)); var bindings = objects(linked.get("bindings")); assertEquals(1, bindings.size());
                    var lambda = expression(bindings.getFirst().get("expr")); var body = expression(lambda.get(2)); var other = entry.equals("sqrtFloat") ? "double" : "float";
                    var proof = map("kind", other, "primReps", list(other.equals("float") ? "FloatRep" : "DoubleRep"), "evaluated", true);
                    if (variant.equals("arity")) {
                        expression(body.get(2)).clear(); expression(body.get(3)).clear(); object(body.get(6)).put("callDemand", map("arity", 0, "strictArgs", list()));
                    } else { object(lambda.get(3)).put("resultRep", proof); object(body.get(6)).put("rep", proof); }
                    var failure = assertThrows(RuntimeFault.class, () -> program(language, linked, backend));
                    var detail = variant.equals("arity") ? "Primitive arity mismatch: " + entry + "#" : "Conflicting Core representation proofs";
                    assertTrue(Objects.toString(failure.getMessage(), "").contains(detail), failure.getMessage());
                }
            } finally { context.leave(); }
        }
    }
}
