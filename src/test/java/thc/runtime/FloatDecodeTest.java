// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class FloatDecodeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private static final String DIRECTORY = "build/float-decode";
    private static final List<String> NAMES = list("floatDirect", "floatCall", "floatExponent", "doubleDirect", "doubleCall", "doubleExponent", "floatExampleExponent", "doubleExampleExponent");
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private static ExecutableProgram program(Language language, Map<String, Object> module, String backend) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private String read(String path) throws Exception { return Files.readString(new File(root, path).toPath()); }
    private Map<String, Object> json(String path) throws Exception { return object(Json.parse(read(path))); }
    private Map<String, Object> originalModule() throws Exception { return json(DIRECTORY + "/original/GHC.Internal.Bignum.Integer.json"); }
    private Map<String, Object> module(String stage) throws Exception {
        return CoreModules.merge(list(json(DIRECTORY + "/" + stage + "-core/FloatDecodeAudit.json"), originalModule(), json(DIRECTORY + "/" + stage + "-core/THC.FloatDecode.json")));
    }
    private static Set<String> reachableIds(String name) {
        var ids = new HashSet<>(list("main:" + (name.contains("Example") ? "THC.FloatDecode" : "FloatDecodeAudit") + "." + name));
        switch (name) {
            case "floatCall" -> ids.add("main:FloatDecodeAudit.floatWorker");
            case "doubleCall" -> ids.add("main:FloatDecodeAudit.doubleWorker");
            case "doubleExponent", "doubleExampleExponent" -> ids.add("ghc-internal:GHC.Internal.Bignum.Integer.$wintegerFromInt64#");
            default -> { }
        }
        return ids;
    }
    private static long count(ExecutableProgram p) { return ((Number) p.diagnostics().get("compiledEntries")).longValue(); }
    private static void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private static void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target); valid(target);
    }
    private static void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    private static List<Long> inputs(int width) {
        int p = width == 32 ? 23 : 52, e = width == 32 ? 8 : 11; long top = (1L << e) - 1, bias = (1L << (e - 1)) - 1;
        var magnitudes = new LinkedHashSet<Long>();
        for (long code = 0; code <= top; code++) magnitudes.add(code << p);
        for (long code : new long[]{0, 1, bias - 1, bias, bias + 1, top - 1, top}) for (long fraction : new long[]{1L, (1L << p) - 1, 1L << (p - 1)}) magnitudes.add((code << p) | fraction);
        for (int bit = 0; bit < p; bit++) for (long delta = -1; delta <= 1; delta++) magnitudes.add((1L << bit) + delta);
        long state = 0xdec0deL;
        for (int i = 0; i < 128; i++) { state = state * 6364136223846793005L + 1442695040888963407L; magnitudes.add(state & (width == 32 ? 0x7fffffffL : Long.MAX_VALUE)); }
        var result = new TreeSet<>(magnitudes); for (long magnitude : magnitudes) result.add(magnitude | (1L << (width - 1))); return new ArrayList<>(result);
    }
    /** Independent unbounded dyadic model: no JVM floating arithmetic, runtime
     * decoder or leading-zero intrinsic supplies the expected fields. */
    private static List<Long> mathematical(String name, long raw) {
        int width = name.startsWith("float") ? 32 : 64, p = width == 32 ? 23 : 52, exponentBits = width == 32 ? 8 : 11;
        var bits = BigInteger.valueOf(raw).mod(BigInteger.ONE.shiftLeft(width));
        int code = bits.shiftRight(p).and(BigInteger.ONE.shiftLeft(exponentBits).subtract(BigInteger.ONE)).intValue();
        var fraction = bits.and(BigInteger.ONE.shiftLeft(p).subtract(BigInteger.ONE)); var magnitude = code == 0 ? fraction : fraction.setBit(p);
        int exponent = (code == 0 ? 1 : code) - ((1 << (exponentBits - 1)) - 1) - p;
        if (magnitude.signum() == 0) exponent = 0;
        else { int shift = p + 1 - magnitude.bitLength(); magnitude = magnitude.shiftLeft(shift); exponent -= shift; }
        var mantissa = bits.testBit(width - 1) ? magnitude.negate() : magnitude;
        if (name.endsWith("Exponent")) { long publicExponent = mantissa.signum() == 0 ? 0L : (long) exponent + p + 1; return list(publicExponent, publicExponent); }
        return list(mantissa.longValueExact(), (long) exponent);
    }
    private record Row(String name, long bits, List<Long> fields) {}
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException(); }
    private static List<Row> rows(String text) {
        var result = new ArrayList<Row>();
        for (var line : text.lines().toList()) if (!line.isEmpty()) {
            var fields = line.split("\t", -1); require(fields.length == 4);
            result.add(new Row(fields[0], Long.parseLong(fields[1]), list(Long.parseLong(fields[2]), Long.parseLong(fields[3]))));
        }
        record Key(String name, long bits) {}
        var expected = new ArrayList<Key>(); for (var name : NAMES) for (long bits : inputs(name.startsWith("float") ? 32 : 64)) expected.add(new Key(name, bits));
        require(result.stream().map(row -> new Key(row.name, row.bits)).toList().equals(expected));
        for (var row : result) if (!mathematical(row.name, row.bits).equals(row.fields)) throw new IllegalArgumentException("Native/model mismatch: " + row);
        return result;
    }
    private void verifyEvidence(Map<String, Object> manifest) throws Exception {
        assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(NAMES, manifest.get("entries"));
        assertEquals(inputs(32), manifest.get("floatInputs")); assertEquals(inputs(64), manifest.get("doubleInputs")); assertEquals(4L * (inputs(32).size() + inputs(64).size()), manifest.get("nativeRows"));
        var sources = new HashSet<>(list("test/fixtures/compiler/FloatDecodeAudit.hs", "test/fixtures/compiler/FloatDecodeNative.hs", "src/examples/THC/FloatDecode.hs", "thc.cabal",
            "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/FloatDecodeFixtures.hs", "bin/core-capabilities.json",
            "bin/audit-core.py", "src/main/resources/thc/scalar-primop-signatures.json", "bin/build-compiler.sh", "bin/export-core.sh", "bin/export-boot.py", "bin/toolchain.sh", "bin/plugin.py",
            "nih/pinned/ghc-9.14.1/libraries/ghc-internal/include/WordSize.h", "nih/pinned/ghc-9.14.1/libraries/ghc-internal/LICENSE"));
        for (var name : list("BigNat", "Integer", "Natural")) for (var suffix : list(".hs", ".hs-boot")) sources.add("nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Bignum/" + name + suffix);
        for (var file : Objects.requireNonNull(new File(root, "src/compiler/THC").listFiles())) if (file.getName().endsWith(".hs")) sources.add(root.toPath().relativize(file.toPath()).toString());
        for (var file : Objects.requireNonNull(new File(root, "bin").listFiles())) if (file.getName().startsWith("core_") && file.getName().endsWith(".py")) sources.add(root.toPath().relativize(file.toPath()).toString());
        var commands = new ArrayList<>(list("native-build", "native-oracle", "boot-export"));
        var artifacts = new HashSet<>(list(DIRECTORY + "/inputs.tsv", DIRECTORY + "/oracle.tsv", DIRECTORY + "/native/oracle", DIRECTORY + "/original/GHC.Internal.Bignum.Integer.json", DIRECTORY + "/original/boot-provenance.json"));
        for (var stage : list("pre", "post")) {
            commands.add(stage + "-export"); artifacts.add(DIRECTORY + "/" + stage + "-core/FloatDecodeAudit.json"); artifacts.add(DIRECTORY + "/" + stage + "-core/THC.FloatDecode.json");
            for (var name : NAMES) { commands.add(stage + "-" + name + "-audit"); artifacts.add(DIRECTORY + "/" + stage + "-" + name + "-audit.json"); }
        }
        for (var command : commands) for (var suffix : list("stdout", "stderr", "command.json")) artifacts.add(DIRECTORY + "/commands/" + command + "." + suffix);
        assertEquals(sources, object(manifest.get("inputHashes")).keySet()); assertEquals(artifacts, object(manifest.get("artifactHashes")).keySet());
        for (var kind : list("inputHashes", "artifactHashes")) for (var hash : object(manifest.get(kind)).entrySet()) assertEquals(hash.getValue(),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), "Stale floating decode fixture: " + hash.getKey());
        for (var stage : list("pre", "post")) for (var name : NAMES) {
            var report = json(DIRECTORY + "/" + stage + "-" + name + "-audit.json");
            assertEquals(true, report.get("accepted")); assertEquals(list(), report.get("issues")); assertEquals(list(), report.get("missingGlobals"));
            assertEquals(reachableIds(name), objects(report.get("reachableBindings")).stream().map(binding -> binding.get("id")).collect(Collectors.toSet()));
            var decode = name.startsWith("float") ? "decodeFloat_Int#" : "decodeDouble_Int64#";
            var primitives = objects(report.get("primitives")).stream().filter(prim -> decode.equals(prim.get("name"))).toList(); assertEquals(1, primitives.size());
            assertEquals(1, expression(primitives.getFirst().get("uses")).size(), stage + "/" + name + " saturated decode");
        }
        assertEquals(139, objects(originalModule().get("bindings")).size(), "Complete pinned original Integer module, not a fabricated worker");
        var provenance = json(DIRECTORY + "/original/boot-provenance.json"); assertEquals("ghc-9.14.1-release", provenance.get("ghcTag")); assertEquals(list(), provenance.get("sourcePatches"));
        var provenanceSources = objects(provenance.get("sources"));
        assertEquals(sources.stream().filter(path -> path.startsWith("vendor/")).collect(Collectors.toSet()), provenanceSources.stream().map(source -> source.get("path")).collect(Collectors.toSet()));
        for (var source : provenanceSources) assertEquals(object(manifest.get("inputHashes")).get(source.get("path")), source.get("sha256"));
    }
    @Test void nativeIeeeCorpusHasExactIndependentFieldsAndProvenance() throws Exception {
        verifyEvidence(json(DIRECTORY + "/manifest.json")); rows(read(DIRECTORY + "/oracle.tsv"));
        for (int width : new int[]{32, 64}) {
            int p = width == 32 ? 23 : 52; var name = width == 32 ? "floatDirect" : "doubleDirect";
            assertEquals(list(0L, 0L), mathematical(name, 0)); assertEquals(list(0L, 0L), mathematical(name, 1L << (width - 1)));
            assertEquals(list(1L << p, width == 32 ? -172L : -1126L), mathematical(name, 1));
            assertEquals(-(1L << p), mathematical(name, (1L << (width - 1)) | 1).getFirst());
        }
    }
    @Test void missingReorderedCorruptRowsAndMissingProvenanceFailClosed() throws Exception {
        var lines = read(DIRECTORY + "/oracle.tsv").lines().filter(line -> !line.isEmpty()).toList();
        var duplicate = new ArrayList<>(lines); duplicate.add(lines.getFirst()); var corrupt = new ArrayList<>(lines);
        corrupt.set(0, lines.getFirst().substring(0, lines.getFirst().lastIndexOf('\t')) + "\t123456");
        for (var bad : list(lines.subList(1, lines.size()), lines.reversed(), duplicate, corrupt)) assertThrows(IllegalArgumentException.class, () -> rows(String.join("\n", bad)));
        var manifest = json(DIRECTORY + "/manifest.json"); assertThrows(AssertionError.class, () -> verifyEvidence(with(manifest, "artifactHashes", map())));
        var hashes = object(manifest.get("inputHashes")); assertThrows(AssertionError.class, () -> verifyEvidence(with(manifest, "inputHashes", with(hashes, hashes.keySet().iterator().next(), "0"))));
    }
    @Test void nativeResultsAcrossResidualCalls() throws Exception { nativeResults(false); }
    @Test void nativeResultsWithInlining() throws Exception { nativeResults(true); }
    @Test void publicDoubleExponentRequiresOriginalIntegerCore(@TempDir Path temporary) throws Exception {
        for (var stage : list("pre", "post")) {
            var report = temporary.resolve(stage + ".json");
            var process = new ProcessBuilder("python3", "bin/audit-core.py", DIRECTORY + "/" + stage + "-core/FloatDecodeAudit.json", "--entry", "doubleExponent", "--output", report.toString())
                .directory(root).redirectOutput(temporary.resolve(stage + ".stdout").toFile()).redirectError(temporary.resolve(stage + ".stderr").toFile()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); fail("Missing-original audit timed out"); }
            assertEquals(1, process.exitValue()); var audit = object(Json.parse(Files.readString(report)));
            assertEquals(false, audit.get("accepted")); assertEquals(list(), audit.get("issues"));
            assertEquals(list("ghc-internal:GHC.Internal.Bignum.Integer.$wintegerFromInt64#"), objects(audit.get("missingGlobals")).stream().map(binding -> binding.get("id")).toList());
        }
    }
    private void nativeResults(boolean inlining) throws Exception {
        verifyEvidence(json(DIRECTORY + "/manifest.json")); var rows = new LinkedHashMap<String, List<Row>>();
        for (var row : rows(read(DIRECTORY + "/oracle.tsv"))) rows.computeIfAbsent(row.name, ignored -> new ArrayList<>()).add(row);
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) for (var name : NAMES) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var p = program(language, with(CoreModules.reachable(module(stage), name), "instrument", true), backend); var entry = p.entryTarget(name);
                boolean example = name.contains("Example"); var host = p.hostEntryTarget(example ? 1 : 2); var value = p.entryValue(name);
                CheckedBiConsumer<Row, Boolean> check = (row, installed) -> {
                    for (int field = 0; field < row.fields.size(); field++) {
                        long before = count(p); var label = stage + "/" + backend + "/" + name + "/" + row.bits + "/" + field + "/inlining=" + inlining;
                        Object[] arguments = example ? new Object[]{row.bits} : new Object[]{row.bits, (long) field};
                        assertEquals(row.fields.get(field), Calls.target(host, new Object[]{value, arguments}), label);
                        if (installed) assertEquals((long) reachableIds(name).size(), count(p) - before, label + " exact first-and-every compiled entry");
                        released(language);
                    }
                };
                for (var row : rows.get(name)) check.accept(row, false);
                var targets = activeTargets(entry); assertEquals(reachableIds(name).size(), targets.size()); for (var target : targets) compile(target);
                long allocations = language.getHandoffState().get().getResults().getAllocations();
                for (var row : rows.get(name)) check.accept(row, true); for (var target : targets) valid(target);
                var active = activeTargets(entry); assertEquals(targets.size(), active.size()); assertTrue(active.stream().allMatch(target -> targets.stream().anyMatch(original -> original == target)));
                assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations()); if (name.endsWith("Direct")) assertEquals(0L, allocations, "Direct decode needs no tuple carrier");
                for (var counter : list("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) p.diagnostics().get(counter)).longValue(), counter);
            } finally { context.leave(); }
        }
    }
    private static List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> items) { if (!items.isEmpty() && "app".equals(items.getFirst())) result.add(expression(items)); for (var item : items) result.addAll(applications(item)); }
        else if (value instanceof Map<?, ?> fields) for (var item : fields.values()) result.addAll(applications(item)); return result;
    }
    @Test void scalarCarriersTupleShapeArityAndSharedAuditorStayChecked(@TempDir Path temporary) throws Exception {
        for (var name : list("floatDirect", "doubleDirect")) for (var mutation : list("valid", "argument", "result-carrier", "result-arity", "partial", "over", "lifted", "bare")) {
            var linked = CoreModules.reachable(module("pre"), name); var primitive = name.startsWith("float") ? "decodeFloat_Int#" : "decodeDouble_Int64#";
            var matches = applications(linked).stream().filter(app -> app.get(1) instanceof List<?> head && head.size() >= 2 && head.subList(0, 2).equals(list("prim", primitive))).toList();
            assertEquals(1, matches.size()); var app = matches.getFirst(); var metadata = object(app.get(6)); var proof = object(metadata.get("rep")); var args = expression(app.get(2));
            switch (mutation) {
                case "argument" -> { assertEquals(1, args.size()); CoreRepresentations.metadata(expression(args.getFirst())).put("rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true)); }
                case "result-carrier" -> { expression(proof.get("components")).set(0, map("kind", "float", "primReps", list("FloatRep"), "evaluated", true)); expression(proof.get("primReps")).set(0, "FloatRep"); }
                case "result-arity" -> { expression(proof.get("components")).removeLast(); expression(proof.get("primReps")).removeLast(); }
                case "partial" -> { args.clear(); expression(app.get(3)).clear(); metadata.remove("callDemand"); }
                case "over" -> { assertEquals(1, args.size()); args.add(args.getFirst()); expression(app.get(3)).add(false); metadata.remove("callDemand"); }
                case "lifted" -> expression(app.get(3)).set(0, true);
                case "bare" -> { var head = new ArrayList<>(expression(app.get(1))); app.clear(); app.addAll(head); }
                default -> { }
            }
            var label = name + "-" + mutation; var input = temporary.resolve(label + ".json"); Files.writeString(input, Json.stringify(linked)); var report = temporary.resolve(label + "-report.json");
            var process = new ProcessBuilder("python3", "bin/audit-core.py", input.toString(), "--entry", name, "--output", report.toString()).directory(root)
                .redirectOutput(temporary.resolve(label + ".stdout").toFile()).redirectError(temporary.resolve(label + ".stderr").toFile()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); fail("Shared auditor timeout: " + label); }
            assertEquals(mutation.equals("valid") ? 0 : 1, process.exitValue(), label); assertEquals(mutation.equals("valid"), object(Json.parse(Files.readString(report))).get("accepted"), label);
            for (var backend : list("ast", "bytecode")) try (var context = context(false)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    if (mutation.equals("valid")) program(language, linked, backend);
                    else for (boolean diagnostic : new boolean[]{false, true}) {
                        if (mutation.equals("bare") && diagnostic) {
                            // Diagnostic mode defers UnsupportedCore; demanding the unavailable tuple still traps.
                            var p = program(language, with(linked, "diagnosticUnsupported", true), backend);
                            var failure = assertThrows(RuntimeFault.class, () -> Calls.target(p.hostEntryTarget(2), new Object[]{p.entryValue(name), new Object[]{0L, 0L}}));
                            assertTrue(failure.getMessage().contains("Unsaturated primitive " + primitive)); assertEquals(1L, ((Number) p.diagnostics().get("unsupportedTraps")).longValue()); released(language);
                        } else assertThrows(RuntimeException.class, () -> program(language, with(linked, "diagnosticUnsupported", diagnostic), backend), backend + "/" + label + "/diagnostic=" + diagnostic);
                    }
                } finally { context.leave(); }
            }
        }
    }
    @Test void typedAstUsesPrimitiveOperandAndWritesLongSlots() throws Exception {
        var descriptor = FrameDescriptor.newBuilder(); var slots = new int[4]; for (int i = 0; i < 4; i++) slots[i] = descriptor.addSlot(FrameSlotKind.Long, null, null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build());
        for (var operation : FloatDecodeOp.values()) {
            var components = new ArrayList<CoreRepresentation>();
            for (int i = 0; i < operation.getFields(); i++) components.add(new CoreRepresentation(CoreKind.LONG, true, true, null, null, null, null, null, null));
            var proof = new CoreRepresentation(CoreKind.UNKNOWN, true, true, null, components, null, null, null, null);
            int[] calls = {0}; var operand = new Expr() {
                @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Boxed operand execution"); }
                @Override public float executeFloat(VirtualFrame frame) { calls[0]++; return -1.5f; }
                @Override public double executeDouble(VirtualFrame frame) { calls[0]++; return -1.5; }
            };
            new FloatDecodeExpression(operation, proof, operand).executeTuple(frame, slots, 0); assertEquals(1, calls[0]);
            var expected = switch (operation) {
                case FLOAT -> list(-12582912L, -23L);
                case DOUBLE -> list(-6755399441055744L, -52L);
                case DOUBLE_WORDS -> list(-1L, 1572864L, 0L, -52L);
            };
            for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i).longValue(), frame.getLong(slots[i]));
        }
    }
}
