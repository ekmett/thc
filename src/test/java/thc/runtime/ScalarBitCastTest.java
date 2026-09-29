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
    private static final Map<String, Long> EXPECTED_CALLS = new LinkedHashMap<>();
    static {
        SIGNATURES.put("castFloatToWord32#", new Signature("FloatRep", "Word32Rep"));
        SIGNATURES.put("castWord32ToFloat#", new Signature("Word32Rep", "FloatRep"));
        SIGNATURES.put("castDoubleToWord64#", new Signature("DoubleRep", "Word64Rep"));
        SIGNATURES.put("castWord64ToDouble#", new Signature("Word64Rep", "DoubleRep"));
        // Original exported lambdas, including immediate runRW State# lambdas;
        // this is deliberately not the lowered entry count.
        for (var name : NAMES) EXPECTED_CALLS.put(name, name.endsWith("Roundtrip") ? 5L : name.endsWith("Field") ? 6L : name.endsWith("Captured") ? 7L : 4L);
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
    private record RetainedCalls(List<String> globals, int callbacks) { long count() { return (long) globals.size() + callbacks; } }
    private static RetainedCalls retainedCalls(Map<String, Object> module, String name, Map<String, Object> shape) {
        var evidence = new ArrayCoreEvidence(module, "main:ScalarBitCastAudit." + name);
        var functions = evidence.getBindings().stream().filter(binding -> "lam".equals(expression(binding.get("expr")).getFirst())).toList();
        var globals = functions.stream().map(binding -> (String) binding.get("id")).toList();
        assertEquals(new HashSet<>(expression(shape.get("globalFunctions"))), new HashSet<>(globals), name + " original global identities");
        var exported = new ArrayList<List<Object>>(); var lowered = new ArrayList<List<Object>>();
        for (var binding : evidence.getBindings()) {
            exported.addAll(evidence.guestLambdas(binding.get("expr")));
            // Independently validates the exact zero-slot formal, literal void
            // argument and flags; never calls the runtime rewriter.
            lowered.addAll(evidence.loweredGuestLambdas(binding.get("expr")));
        }
        assertEquals(EXPECTED_CALLS.get(name), (long) exported.size(), name + " original lambda inventory");
        var eliminated = exported.stream().filter(original -> lowered.stream().noneMatch(lambda -> lambda == original)).toList();
        int stateCount = name.endsWith("Decode") || name.endsWith("Encode") ? 1 : 0;
        assertEquals(stateCount, eliminated.size(), name + " exact State# redex inventory");
        assertEquals(shape.get("stateLambdas"), (long) eliminated.size());
        for (var function : functions) assertTrue(lowered.stream().anyMatch(lambda -> lambda == function.get("expr")), name + " retained global");
        var callbacks = lowered.stream().filter(lambda -> functions.stream().noneMatch(function -> function.get("expr") == lambda)).toList();
        assertEquals(name.endsWith("Captured") ? 1 : 0, callbacks.size(), name + " genuine callback inventory");
        assertEquals(shape.get("nestedCallbacks"), (long) callbacks.size()); return new RetainedCalls(globals, callbacks.size());
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
        assertEquals(EXPECTED_CALLS, manifest.get("expectedGuestCalls"));
        var arities = new LinkedHashMap<String, Long>(); for (var name : SIGNATURES.keySet()) arities.put(name, 1L);
        assertEquals(arities, manifest.get("bitcastPrimitiveArities"));
        var keys = new HashSet<String>(); for (var stage : list("pre", "post")) for (var name : NAMES) keys.add(stage + "/" + name);
        assertEquals(keys, object(manifest.get("audits")).keySet()); assertEquals(keys, object(manifest.get("structure")).keySet());
        var requiredSources = new HashSet<>(list("t/fixtures/compiler/ScalarBitCastAudit.hs", "t/fixtures/compiler/ScalarBitCastNative.hs",
            "thc.cabal", "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/ScalarBitCastFixtures.hs",
            "bin/core-capabilities.json", "bin/audit-core.py", "src/tools/primops/PrimopTools.hs", "src/main/resources/thc/scalar-primop-signatures.json",
            "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"));
        for (var file : Objects.requireNonNull(new File(root, "src/compiler/THC").listFiles())) if (file.getName().endsWith(".hs")) requiredSources.add(root.toPath().relativize(file.toPath()).toString());
        for (var file : Objects.requireNonNull(new File(root, "bin").listFiles())) if (file.getName().startsWith("core_") && file.getName().endsWith(".py")) requiredSources.add(root.toPath().relativize(file.toPath()).toString());
        assertEquals(requiredSources, object(manifest.get("inputHashes")).keySet());
        var commands = new ArrayList<>(list("native-build", "native-oracle"));
        var artifacts = new HashSet<>(list(prefix + "/inputs.tsv", prefix + "/oracle.tsv", prefix + "/native/scalar-bitcast-oracle"));
        for (var stage : list("pre", "post")) {
            commands.add(stage + "-export"); artifacts.add(prefix + "/" + stage + "-core/ScalarBitCastAudit.cbd"); artifacts.add(prefix + "/" + stage + "-core/ScalarBitCastAudit.json"); artifacts.add(prefix + "/" + stage + "-audit.json");
            for (var name : NAMES) { commands.add(stage + "-" + name + "-audit"); artifacts.add(prefix + "/" + stage + "-" + name + "-audit.json"); }
        }
        for (var command : commands) for (var suffix : list("stdout", "stderr", "command.json")) artifacts.add(prefix + "/commands/" + command + "." + suffix);
        assertEquals(artifacts, object(manifest.get("artifactHashes")).keySet());
        for (var kind : list("inputHashes", "artifactHashes")) for (var hash : object(manifest.get(kind)).entrySet())
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), "Stale bitcast fixture: " + hash.getKey());
        for (var stage : list("pre", "post")) for (var name : NAMES) {
            var report = json(prefix + "/" + stage + "-" + name + "-audit.json");
            assertEquals(true, report.get("accepted")); assertEquals(list(), report.get("issues")); assertEquals(list(), report.get("missingGlobals"));
            var shape = object(object(manifest.get("structure")).get(stage + "/" + name)); assertEquals(EXPECTED_CALLS.get(name), shape.get("guestCalls"));
        }
    }
    @Test void evidenceFailsClosedOnMissingHashesStagesCountsAndCorruption() throws Exception {
        var good = evidence(); verifyEvidence(good);
        for (var mutation : map("nativeRows", 13554L, "stages", map("pre", "other"), "inputsByWidth", map("32", list(), "64", inputs(64)),
            "expectedGuestCalls", with(object((Object) EXPECTED_CALLS), "floatRoundtrip", 4L), "bitcastPrimitiveArities", map()).entrySet())
            assertThrows(AssertionError.class, () -> verifyEvidence(with(good, mutation.getKey(), mutation.getValue())), mutation.getKey());
        for (var field : list("inputHashes", "artifactHashes")) {
            var hashes = object(good.get(field));
            for (var path : hashes.keySet()) assertThrows(AssertionError.class, () -> verifyEvidence(with(good, field, without(hashes, path))), field + "/" + path);
            assertThrows(AssertionError.class, () -> verifyEvidence(with(good, field, with(hashes, hashes.keySet().iterator().next(), "0".repeat(64)))));
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
    @Test void loweredPathsKeepGlobalIdentitiesAndCallbacksAndRejectNonStateRedexes() throws Exception {
        var manifest = evidence(); verifyEvidence(manifest);
        for (var stage : object(manifest.get("stages")).entrySet()) {
            var module = cbd((String) stage.getValue()); var shapes = object(manifest.get("structure"));
            for (var name : NAMES) {
                var retained = retainedCalls(module, name, object(shapes.get(stage.getKey() + "/" + name)));
                assertEquals(name.endsWith("Roundtrip") ? 5L : name.endsWith("Field") ? 6L : name.endsWith("Captured") ? 7L : 3L,
                    retained.count(), stage.getKey() + "/" + name + " independently retained roots");
            }
            for (var mutation : list("type", "lifted", "coercion", "formal", "argument", "flags")) {
                var bad = object(Json.parse(Json.stringify(module))); var core = new ArrayCoreEvidence(bad, entryId("floatDecode"));
                var calls = core.nodes(core.getRoot().get("expr")).stream().filter(node -> !node.isEmpty() && node.getFirst().equals("app") &&
                    node.size() > 1 && node.get(1) instanceof List<?> function && !function.isEmpty() && function.getFirst().equals("lam")).toList();
                assertEquals(1, calls.size()); var call = calls.getFirst(); var formals = objects(expression(call.get(1)).get(1)); assertEquals(1, formals.size()); var formal = formals.getFirst();
                switch (mutation) {
                    case "type" -> formal.put("type", "Int#"); case "lifted" -> formal.put("lifted", true); case "coercion" -> formal.put("coercion", true);
                    case "formal" -> formal.put("rep", proof("IntRep")); case "argument" -> call.set(2, list(list("lit", "int", "0", map("rep", proof("IntRep")))));
                    case "flags" -> call.set(3, list(true)); default -> throw new AssertionError(mutation);
                }
                assertThrows(IllegalArgumentException.class, () -> retainedCalls(bad, "floatDecode", object(shapes.get(stage.getKey() + "/floatDecode"))), stage.getKey() + "/" + mutation + " must not silently remove an arbitrary lambda");
            }
        }
    }
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
                    var entry = p.entryTarget(entryId(name)); var host = p.hostEntryTarget(1); var function = context.asValue(new EntryValue(p, entryId(name), 1));
                    var label = stage.getKey() + "/" + backend + "/" + name + "/inline=" + inlining;
                    var retained = retainedCalls(module, name, object(object(manifest.get("structure")).get(stage.getKey() + "/" + name))); var cases = rows.get(name);
                    assertEquals(expression(object(manifest.get("inputsByWidth")).get(name.startsWith("float") ? "32" : "64")).stream().map(value -> ((Number) value).longValue()).toList(), cases.stream().map(row -> Long.parseLong(row.get(1))).toList());
                    CheckedConsumer<List<String>> check = row -> {
                        long input = Long.parseLong(row.get(1)), expected = Long.parseLong(row.get(2));
                        assertEquals(model(name, input), expected, "native " + label + "/" + input); assertEquals(expected, function.execute(input).asLong(), label + "/" + input);
                    };
                    for (var row : cases) check.accept(row);
                    var active = activeTargets(host); assertTrue(active.size() > 1, label + " actual guest call target");
                    assertEquals(retained.count() + 1, (long) active.size(), label + " retained roots plus separate host root");
                    var globals = retained.globals().stream().map(p::entryTarget).toList();
                    for (var target : globals) assertTrue(active.stream().anyMatch(value -> value == target), label + " original global " + target.getRootNode().getName());
                    assertEquals(retained.callbacks(), active.stream().filter(target -> target != host && globals.stream().noneMatch(value -> value == target)).count(), label + " retain genuine callbacks, not the in-frame State# redex");
                    for (var target : active) if (target != host) compile(target);
                    assertTrue(function.invokeMember("compile").asBoolean(), label + " host installation");
                    for (var row : cases.reversed()) {
                        long before = count(p); check.accept(row); assertEquals(before + retained.count(), count(p), label + "/" + row.get(1) + " exact retained guest entries");
                        assertEquals(active, activeTargets(host), label + " active target identities"); valid(entry, label + " original");
                        for (var target : active) valid(target, label + " active"); released(language);
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
                var process = new ProcessBuilder("python3", "bin/audit-core.py", source.toString(), "--entry", "entry", "--output", report.toString()).directory(root)
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
                        check.run(); compile(target); long before = count(p); check.run(); assertEquals(before + 1, count(p), backend + "/" + name + "/" + mutation + " first installed entry");
                        assertSame(target, p.entryTarget("entry")); valid(target, backend + "/" + name + "/" + mutation); released(language);
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
    @Test void directTypedCallsPreserveBitsWithExactlyOneCompiledEntryAndRejectWrongCarriers() throws Exception {
        long[] fbits = {0, 0x80000000L, 1, 0x007fffff, 0x7f800000, 0xff800000L, 0x7f800001, 0xffc12345L};
        long[] dbits = {0, Long.MIN_VALUE, 1, 0x000fffffffffffffL, 0x7ff0000000000000L, 0xfff0000000000000L, 0x7ff0000000000001L, 0xfff8000000001234L};
        for (var backend : list("ast", "bytecode")) for (var name : SIGNATURES.keySet()) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var p = program(language, synthetic(name), backend); var target = p.entryTarget("entry"); var bits = name.contains("32") ? fbits : dbits;
                for (long value : bits) assertEquals(value, invoke(target, name, value)); compile(target);
                for (long value : bits) { long before = count(p); assertEquals(value, invoke(target, name, value), backend + "/" + name + "/" + Long.toUnsignedString(value, 16)); assertEquals(before + 1, count(p)); valid(target, backend + "/" + name); released(language); }
                assertThrows(RuntimeException.class, () -> callScalarTestTarget(target, new Object[]{0L, new Object()}));
                if (name.equals("castWord32ToFloat#")) assertThrows(RuntimeException.class, () -> callScalarTestTarget(target, new Object[]{0L, bits[bits.length - 1]}));
                released(language); assertEquals(bits[bits.length - 1], invoke(target, name, bits[bits.length - 1]));
            } finally { context.leave(); }
        }
    }
}
