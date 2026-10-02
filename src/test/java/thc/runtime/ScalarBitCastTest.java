// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RawBitCasts.rawBitCastPrimitive;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.runtime.ScalarValueTestSupport.*;

class ScalarBitCastTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private static final List<String> NAMES = list("floatRoundtrip", "floatField", "floatCaptured", "floatDecode", "floatEncode",
        "doubleRoundtrip", "doubleField", "doubleCaptured", "doubleDecode", "doubleEncode");
    private record Signature(String input, String output) {}
    private static final Map<String, Signature> SIGNATURES = new LinkedHashMap<>();
    static {
        SIGNATURES.put("castFloatToWord32#", new Signature("FloatRep", "Word32Rep"));
        SIGNATURES.put("castWord32ToFloat#", new Signature("Word32Rep", "FloatRep"));
        SIGNATURES.put("castDoubleToWord64#", new Signature("DoubleRep", "Word64Rep"));
        SIGNATURES.put("castWord64ToDouble#", new Signature("Word64Rep", "DoubleRep"));
    }
    private static Context context() { return context(true); }
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private static ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private static void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private static void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "installed"); }
    private static long count(ExecutableProgram p) { return ((Number) p.diagnostics().get("compiledEntries")).longValue(); }
    private static void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    private static long model(String name, long raw) { return name.startsWith("float") ? raw & 0xffffffffL : raw; }
    private static Set<Long> patterns(int width) {
        int fraction = width == 32 ? 23 : 52;
        long exponent = ((1L << (width == 32 ? 8 : 11)) - 1) << fraction;
        var edges = list(0L, 1L, (1L << fraction) - 1, 1L << fraction, exponent, exponent - 1);
        var result = new LinkedHashSet<>(edges); result.add(width == 32 ? 0xffffffffL : -1L);
        for (long sign : new long[]{0, 1L << (width - 1)}) {
            for (long edge : edges) result.add(sign | edge);
            for (long quiet : new long[]{0, 1L << (fraction - 1)}) {
                var payloads = new ArrayList<Long>();
                for (long payload = 0; payload <= 256; payload++) payloads.add(payload);
                for (int bit = 0; bit < fraction - 1; bit++) payloads.add(1L << bit);
                for (int bit = 1; bit < fraction; bit++) payloads.add((1L << bit) - 1);
                for (long payload : payloads) result.add(sign | exponent | quiet | payload);
            }
            for (int bit = 0; bit < width; bit++) result.add(sign | (1L << bit));
        }
        return result;
    }
    private static List<Long> inputs(int width) {
        var result = new TreeSet<>(patterns(width));
        if (width == 32) result.addAll(list(Long.MIN_VALUE, Long.MAX_VALUE, -1L, -(1L << 32), 1L << 32,
            (1L << 48) | 0x7f800001L, -((1L << 40) | 0x123456L)));
        return new ArrayList<>(result);
    }
    private Map<String, Object> json(String path) throws Exception { return object(Json.parse(Files.readString(new File(root, path).toPath()))); }
    private Map<String, Object> cbd(String path) throws Exception { return thc.CoreCbdFixtures.read(new File(root, path).toPath()); }
    private String entryId(String name) { return "main:ScalarBitCastAudit." + name; }
    private Map<String, Object> evidence() throws Exception { return json("build/scalar-bitcasts/manifest.json"); }
    private void verifyEvidence(Map<String, Object> manifest) throws Exception {
        String prefix = "build/scalar-bitcasts";
        assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(NAMES, manifest.get("entries")); assertEquals(13555L, manifest.get("nativeRows"));
        assertEquals(map("pre", prefix + "/pre-core/ScalarBitCastAudit.cbd", "post", prefix + "/post-core/ScalarBitCastAudit.cbd"), manifest.get("stages"));
        assertEquals(map("32", inputs(32), "64", inputs(64)), manifest.get("inputsByWidth"));
        var arities = new LinkedHashMap<String, Long>(); for (var name : SIGNATURES.keySet()) arities.put(name, 1L);
        assertEquals(arities, manifest.get("bitcastPrimitiveArities"));
        var keys = new HashSet<String>(); for (var stage : list("pre", "post")) for (var name : NAMES) keys.add(stage + "/" + name);
        assertEquals(keys, object(manifest.get("audits")).keySet());
        assertTrue(object(manifest.get("inputHashes")).keySet().containsAll(list("bin/export-core.ps1", "bin/windows-common.ps1")), "Native exporter provenance");
        for (var section : list("inputHashes", "artifactHashes")) {
            var hashes = object(manifest.get(section)); assertFalse(hashes.isEmpty(), section);
            for (var hash : hashes.entrySet()) assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(root.toPath().resolve(hash.getKey())))), "Stale fixture " + hash.getKey());
        }
        for (var stage : list("pre", "post")) for (var name : NAMES) {
            var report = json(prefix + "/" + stage + "-" + name + "-audit.json");
            assertEquals(true, report.get("accepted")); assertEquals(list(), report.get("issues")); assertEquals(list(), report.get("missingGlobals"));
        }
    }
    private record IeeeClass(long sign, String kind) {}
    @Test void integerCorpusRetainsEveryIeeeClassSignAndPayloadBit() {
        assertEquals(1211, inputs(32).size()); assertEquals(1500, inputs(64).size());
        for (int width : new int[]{32, 64}) {
            int fraction = width == 32 ? 23 : 52; long maximumExponent = width == 32 ? 255L : 2047L, exponent = maximumExponent << fraction;
            var values = patterns(width); var classes = new HashSet<IeeeClass>();
            for (long bits : values) {
                long mantissa = bits & ((1L << fraction) - 1), exp = (bits >>> fraction) & maximumExponent;
                String kind = exp == maximumExponent && mantissa != 0 ? (mantissa >>> (fraction - 1) == 0 ? "signalling-nan" : "quiet-nan")
                    : exp == maximumExponent ? "infinity" : exp == 0 ? (mantissa == 0 ? "zero" : "subnormal") : "normal";
                classes.add(new IeeeClass((bits >>> (width - 1)) & 1L, kind));
            }
            var expected = new HashSet<IeeeClass>(); for (long sign : new long[]{0, 1}) for (var kind : list("zero", "subnormal", "normal", "infinity", "quiet-nan", "signalling-nan")) expected.add(new IeeeClass(sign, kind));
            assertEquals(expected, classes);
            for (long sign : new long[]{0, 1L << (width - 1)}) for (int bit = 0; bit < fraction; bit++) assertTrue(values.contains(sign | exponent | (1L << bit)));
            for (long raw : inputs(width)) assertEquals(width == 32 ? Integer.toUnsignedLong((int) raw) : raw, model(width == 32 ? "floatDecode" : "doubleDecode", raw));
        }
        assertEquals(0xffffffffL, model("floatDecode", -1)); assertEquals(Long.MIN_VALUE, model("doubleDecode", Long.MIN_VALUE));
    }
    @Test void nativeRawBitsWithInlining() throws Exception { nativeBits(true); }
    @Test void nativeRawBitsAcrossResidualCalls() throws Exception { nativeBits(false); }
    private void nativeBits(boolean inlining) throws Exception {
        var manifest = evidence(); verifyEvidence(manifest); var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var line : Files.readAllLines(new File(root, "build/scalar-bitcasts/oracle.tsv").toPath())) {
            var row = Arrays.asList(line.split("\t")); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row);
        }
        assertEquals(new HashSet<>(NAMES), rows.keySet()); assertEquals(((Number) manifest.get("nativeRows")).intValue(), rows.values().stream().mapToInt(List::size).sum());
        for (var stage : object(manifest.get("stages")).entrySet()) {
            var module = cbd((String) stage.getValue());
            for (var name : NAMES) for (var backend : list("ast", "bytecode")) try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var p = program(language, with(CoreModules.reachable(module, entryId(name)), "instrument", true), backend);
                    var host = p.hostEntryTarget(1); var function = context.asValue(new EntryValue(p, entryId(name), 1));
                    var label = stage.getKey() + "/" + backend + "/" + name + "/inline=" + inlining;
                    var cases = rows.get(name);
                    assertEquals(expression(object(manifest.get("inputsByWidth")).get(name.startsWith("float") ? "32" : "64")).stream().map(value -> ((Number) value).longValue()).toList(), cases.stream().map(row -> Long.parseLong(row.get(1))).toList());
                    CheckedConsumer<List<String>> check = row -> {
                        long input = Long.parseLong(row.get(1)), expected = Long.parseLong(row.get(2));
                        assertEquals(model(name, input), expected, "native " + label + "/" + input); assertEquals(expected, function.execute(input).asLong(), label + "/" + input);
                    };
                    for (var row : cases) check.accept(row);
                    for (var target : activeTargets(host)) if (target != host)
                        assertDoesNotThrow(() -> compile(target), label + " guest installation");
                    assertTrue(function.invokeMember("compile").asBoolean(), label + " host installation");
                    for (var row : cases.reversed()) {
                        long before = count(p); check.accept(row);
                        assertTrue(count(p) > before, label + "/" + row.get(1) + " must enter compiled guest code");
                        released(language);
                    }
                    assertEquals(0L, ((Number) p.diagnostics().get("blackholes")).longValue()); assertEquals(0L, ((Number) p.diagnostics().get("unsupportedTraps")).longValue());
                } finally { context.leave(); }
            }
        }
    }
    private static Map<String, Object> proof(String rep) {
        return map("kind", switch (rep) { case "FloatRep" -> "float"; case "DoubleRep" -> "double"; default -> "long"; }, "primReps", list(rep), "evaluated", true);
    }
    private static Map<String, Object> synthetic(String name) {
        var signature = SIGNATURES.get(name); var closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var app = list("app", list("prim", name, map("rep", closure)), list(list("var", "x", map("rep", proof(signature.input())))), list(false), false, false, map("rep", proof(signature.output())));
        var body = list("lam", list(map("id", "x", "lifted", false, "rep", proof(signature.input()))), app, map("rep", closure, "resultRep", proof(signature.output())));
        var binding = map("id", "entry", "name", "entry", "arity", 1, "lifted", true, "rep", closure, "expr", body);
        return object(Json.parse(Json.stringify(map("schema", 1, "ghc", "9.14.1", "unit", "main", "module", "ScalarBitCastModel",
            "boundary", "optimized-Core-before-Tidy", "instrument", true, "constructors", list(), "bindings", list(binding)))));
    }
    private static List<Object> lambda(Map<String, Object> module) { var bindings = objects(module.get("bindings")); assertEquals(1, bindings.size()); return expression(bindings.getFirst().get("expr")); }
    private static void mutate(List<Object> lam, String mutation) {
        var app = expression(lam.get(2)); var arguments = expression(app.get(2));
        switch (mutation) {
            case "valid" -> { }
            case "argument" -> object(expression(arguments.getFirst()).get(2)).put("rep", proof("IntRep"));
            case "result" -> object(app.get(6)).put("rep", proof("WordRep"));
            case "lexical" -> objects(lam.get(1)).getFirst().put("rep", proof("WordRep"));
            case "partial" -> { arguments.clear(); expression(app.get(3)).clear(); }
            case "over" -> { assertEquals(1, arguments.size()); arguments.add(arguments.getFirst()); expression(app.get(3)).add(false); }
            case "bare" -> lam.set(2, list("prim", expression(app.get(1)).get(1), map("rep", object(lam.get(3)).get("rep"))));
            case "tuple" -> object(expression(arguments.getFirst()).get(2)).put("rep", map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", list(), "components", list(), "evaluated", true));
            case "sum" -> object(expression(arguments.getFirst()).get(2)).put("rep", map("kind", "unknown", "aggregate", "unboxed-sum", "primReps", list("WordRep", "WordRep"), "alternatives", list(proof("IntRep"), proof("IntRep")), "tagSlot", 0, "alternativeSlots", list(list(1), list(1)), "evaluated", true));
            default -> throw new AssertionError(mutation);
        }
    }
    @Test void pinnedSignaturesAndSharedAuditorPositiveNegativeControls(@TempDir Path directory) throws Exception {
        var table = object(json("src/main/resources/thc/scalar-primop-signatures.json").get("primitives"));
        // Producer checks this subset with Aeson: complete capability bounds can
        // exceed the Core reader's signed Long range.
        var capabilities = object(evidence().get("bitcastPrimitiveArities"));
        for (var entry : SIGNATURES.entrySet()) {
            var name = entry.getKey(); var signature = entry.getValue(); assertEquals(map("arguments", list(signature.input()), "result", signature.output()), table.get(name)); assertEquals(1L, capabilities.get(name));
            for (var mutation : list("valid", "argument", "result", "lexical", "partial", "over", "bare")) {
                var module = synthetic(name); mutate(lambda(module), mutation); var label = name.substring(0, name.length() - 1) + "-" + mutation;
                var source = thc.CoreCbdFixtures.write(directory.resolve(label + ".cbd"), without(module, "instrument")); var report = directory.resolve(label + "-report.json");
                var process = new ProcessBuilder(System.getenv().getOrDefault("THC_PYTHON", "python3"), "bin/audit-core.py", source.toString(), "--entry", "entry", "--output", report.toString()).directory(root)
                    .redirectOutput(directory.resolve(label + ".stdout").toFile()).redirectError(directory.resolve(label + ".stderr").toFile()).start();
                if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); fail("Shared bitcast auditor timed out: " + label); }
                assertEquals(mutation.equals("valid") ? 0 : 1, process.exitValue(), label); var actual = object(Json.parse(Files.readString(report)));
                assertEquals(mutation.equals("valid"), actual.get("accepted"), label);
                if (mutation.equals("valid")) { assertEquals(list(), actual.get("issues")); assertEquals(list(), actual.get("missingGlobals")); }
                else assertFalse(expression(actual.get("issues")).isEmpty(), label);
            }
        }
    }
    @Test void lexicalMetadataArityAndScalarFrontiersAreChecked() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var name : SIGNATURES.keySet()) for (var mutation : list("argument", "result", "lexical", "partial", "over", "bare", "tuple", "sum")) {
                    var module = synthetic(name); mutate(lambda(module), mutation); var signature = SIGNATURES.get(name);
                    boolean sameCarrier = switch (mutation) {
                        case "argument", "lexical" -> Set.of("IntRep", "WordRep", "Int64Rep", "Word64Rep").contains(signature.input());
                        case "result" -> Set.of("IntRep", "WordRep", "Int64Rep", "Word64Rep").contains(signature.output()); default -> false;
                    };
                    if (sameCarrier) {
                        var p = program(language, module, backend); var target = p.entryTarget("entry"); var host = p.hostEntryTarget(1);
                        long bits = name.contains("32") ? 0xff800123L : 0xfff0000000000123L;
                        Object input = switch (signature.input()) { case "FloatRep" -> Float.intBitsToFloat((int) bits); case "DoubleRep" -> Double.longBitsToDouble(bits); default -> bits; };
                        CheckedRunnable check = () -> {
                            var result = Calls.target(host, new Object[]{p.entryValue("entry"), new Object[]{input}});
                            long actual = switch (signature.output()) { case "FloatRep" -> Float.floatToRawIntBits((Float) result) & 0xffffffffL; case "DoubleRep" -> Double.doubleToRawLongBits((Double) result); default -> (Long) result; };
                            assertEquals(bits, actual, backend + "/" + name + "/" + mutation);
                        };
                        check.run(); compile(target); long before = count(p); check.run(); assertTrue(count(p) > before, backend + "/" + name + "/" + mutation + " first installed entry");
                        released(language);
                    } else assertThrows(RuntimeException.class, () -> program(language, module, backend), backend + "/" + name + "/" + mutation);
                }
            } finally { context.leave(); }
        }
    }
    @Test void typedNodesKeepRawBitsAndNeverUseBoxedOperandExecution() throws Exception {
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build()); long fbits = 0xff800123L, dbits = 0xfff0000000000123L;
        for (var name : SIGNATURES.keySet()) {
            var operand = new Expr() {
                @Override public Object execute(VirtualFrame f) { throw new IllegalStateException("bitcast operand was boxed"); }
                @Override public int executeInt(VirtualFrame f) { return (int) fbits; }
                @Override public long executeLong(VirtualFrame f) { return dbits; }
                @Override public float executeFloat(VirtualFrame f) { return Float.intBitsToFloat((int) fbits); }
                @Override public double executeDouble(VirtualFrame f) { return Double.longBitsToDouble(dbits); }
            };
            var node = Objects.requireNonNull(rawBitCastPrimitive(name, new Expr[]{operand}));
            long actual = switch (name) { case "castWord32ToFloat#" -> Float.floatToRawIntBits(node.executeFloat(frame)) & 0xffffffffL;
                case "castWord64ToDouble#" -> Double.doubleToRawLongBits(node.executeDouble(frame)); case "castFloatToWord32#" -> Integer.toUnsignedLong(node.executeInt(frame)); default -> node.executeLong(frame); };
            assertEquals(name.contains("32") ? fbits : dbits, actual, name);
        }
    }
    @Test void everyFloatNanEncodingAndSelectedDoubleNanPayloadsRemainExact() throws Exception {
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        class Source extends Expr {
            long input;
            @Override public Object execute(VirtualFrame f) { throw new IllegalStateException("bitcast operand was boxed"); }
            @Override public int executeInt(VirtualFrame f) { return (int) input; }
            @Override public long executeLong(VirtualFrame f) { return input; }
        }
        var floatSource = new Source(); var doubleSource = new Source();
        var floatValue = Objects.requireNonNull(rawBitCastPrimitive("castWord32ToFloat#", new Expr[]{floatSource}));
        var floatRoundTrip = Objects.requireNonNull(rawBitCastPrimitive("castFloatToWord32#", new Expr[]{floatValue}));
        var doubleValue = Objects.requireNonNull(rawBitCastPrimitive("castWord64ToDouble#", new Expr[]{doubleSource}));
        var doubleRoundTrip = Objects.requireNonNull(rawBitCastPrimitive("castDoubleToWord64#", new Expr[]{doubleValue}));
        for (long sign : new long[]{0, 0x80000000L}) for (int payload = 1; payload < (1 << 23); payload++) {
            long bits = sign | 0x7f800000L | payload; floatSource.input = bits;
            if (Integer.toUnsignedLong(floatRoundTrip.executeInt(frame)) != bits) fail("Float NaN changed: " + Long.toString(bits, 16));
        }
        for (long sign : new long[]{0, Long.MIN_VALUE}) for (long quiet : new long[]{0, 1L << 51}) for (int payload = 1; payload <= 65535; payload++) {
            long bits = sign | 0x7ff0000000000000L | quiet | payload; doubleSource.input = bits;
            if (doubleRoundTrip.executeLong(frame) != bits) fail("Double NaN changed: " + Long.toUnsignedString(bits, 16));
        }
    }
    private static Object typedInput(String name, long bits) {
        return switch (name) { case "castFloatToWord32#" -> Float.intBitsToFloat((int) bits); case "castDoubleToWord64#" -> Double.longBitsToDouble(bits); case "castWord32ToFloat#" -> (int) bits; default -> bits; };
    }
    private static long invoke(RootCallTarget target, String name, long bits) {
        var value = callScalarTestTarget(target, new Object[]{0L, typedInput(name, bits)});
        return switch (name) { case "castWord32ToFloat#" -> Float.floatToRawIntBits((Float) value) & 0xffffffffL; case "castWord64ToDouble#" -> Double.doubleToRawLongBits((Double) value); case "castFloatToWord32#" -> Integer.toUnsignedLong((Integer) value); default -> (Long) value; };
    }
    @Test void directTypedCallsPreserveBitsOnFirstCompiledCallAndRejectWrongCarriers() throws Exception {
        long[] fbits = {0, 0x80000000L, 1, 0x007fffff, 0x7f800000, 0xff800000L, 0x7f800001, 0xffc12345L};
        long[] dbits = {0, Long.MIN_VALUE, 1, 0x000fffffffffffffL, 0x7ff0000000000000L, 0xfff0000000000000L, 0x7ff0000000000001L, 0xfff8000000001234L};
        for (var backend : list("ast", "bytecode")) for (var name : SIGNATURES.keySet()) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var p = program(language, synthetic(name), backend); var target = p.entryTarget("entry"); var bits = name.contains("32") ? fbits : dbits;
                for (long value : bits) assertEquals(value, invoke(target, name, value)); compile(target);
                for (long value : bits) { long before = count(p); assertEquals(value, invoke(target, name, value), backend + "/" + name + "/" + Long.toUnsignedString(value, 16)); assertTrue(count(p) > before, backend + "/" + name + " must enter compiled guest code"); released(language); }
                assertThrows(RuntimeException.class, () -> callScalarTestTarget(target, new Object[]{0L, new Object()}));
                if (name.equals("castWord32ToFloat#")) assertThrows(RuntimeException.class, () -> callScalarTestTarget(target, new Object[]{0L, bits[bits.length - 1]}));
                released(language); assertEquals(bits[bits.length - 1], invoke(target, name, bits[bits.length - 1]));
            } finally { context.leave(); }
        }
    }
}
