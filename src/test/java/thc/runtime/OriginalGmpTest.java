// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.CoreModules;
import thc.ForeignExceptionFixtureSupport;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

/** Genuine installed declarations and native observations; no replacement FFI. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
public class OriginalGmpTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-gmp";
    private Object json(String path) throws Exception { return Json.parse(Files.readString(new File(root, path).toPath())); }
    private Map<String, Object> module(String stage) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var name : List.of("OriginalGmpAudit", "THC.InterfaceClosure")) modules.add((Map<String, Object>) json(prefix + "/" + stage + "/core/" + name + ".json"));
        return CoreModules.merge(modules);
    }
    private List<Map<String, Object>> rows() throws Exception {
        var manifest = (Map<String, Object>) json(prefix + "/manifest.json"); assertEquals(true, manifest.get("strictAccepted"));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalGmpAudit.hs", "t/fixtures/compiler/OriginalGmpNative.hs", "t/haskell-fixtures/OriginalGmpFixtures.hs", "bin/core_original_foreign.py"), null);
        var required = new LinkedHashSet<String>(); required.add(prefix + "/oracle.json");
        for (var stage : List.of("pre", "post")) { required.add(prefix + "/" + stage + "/core/OriginalGmpAudit.json"); required.add(prefix + "/" + stage + "/core/THC.InterfaceClosure.json"); }
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), required, prefix + "/");
        var rows = (List<Map<String, Object>>) json(prefix + "/oracle.json"); assertEquals(464, rows.size());
        var expectedSymbols = new LinkedHashSet<String>(); for (var op : GmpForeignOp.values()) expectedSymbols.add(op.getSymbol());
        var observedSymbols = new LinkedHashSet<>(); var counts = new LinkedHashMap<String, Integer>();
        for (var row : rows) { observedSymbols.add(row.get("symbol")); counts.merge((String) row.get("entry"), 1, Integer::sum); }
        assertEquals(expectedSymbols, observedSymbols);
        assertEquals(Map.ofEntries(Map.entry("originalAdd", 15), Map.entry("originalSub", 15), Map.entry("originalAddWord", 8),
            Map.entry("originalMulWord", 8), Map.entry("originalCmp", 5), Map.entry("originalMul", 4), Map.entry("originalDivWord", 12),
            Map.entry("originalModWord", 5), Map.entry("originalQuotRem", 8), Map.entry("originalQuot", 4), Map.entry("originalRem", 8),
            Map.entry("originalRShift", 48), Map.entry("originalRShiftNegative", 24), Map.entry("originalGetDouble", 84),
            Map.entry("originalEncodeDouble", 48), Map.entry("originalGcdWords", 7), Map.entry("originalGcdWord", 8),
            Map.entry("originalGcd", 24), Map.entry("originalLShift", 63), Map.entry("originalAnd", 15), Map.entry("originalAndNot", 15),
            Map.entry("originalOr", 15), Map.entry("originalXor", 15), Map.entry("originalPopCount", 6)), counts);
        for (var stage : List.of("pre", "post")) for (var entry : counts.keySet()) {
            var audit = (Map<String, Object>) json(prefix + "/" + stage + "/" + entry + ".audit.json");
            assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
        }
        return rows;
    }
    private byte[] bytes(Object value) {
        var words = (List<Long>) value; var buffer = ByteBuffer.allocate(words.size() * 8).order(ByteOrder.nativeOrder());
        for (long word : words) buffer.putLong(word); return buffer.array();
    }
    private ManagedAllocation nativeBytes(Object value) {
        var bytes = bytes(value);
        var allocation = ManagedAllocation.nativeMutable(bytes.length, 8);
        allocation.copyBytesIn(bytes, 0, 0, bytes.length);
        return allocation;
    }
    private byte[] bytes(ManagedAllocation allocation) { return allocation.copyBytesOut(0, allocation.getSize()); }
    private static final class Observation {
        final ManagedAllocation left, right, output, remainder;
        final Object[] arguments;
        Observation(Map<String, Object> row, Function<Object, ManagedAllocation> convert) {
            left = convert.apply(row.get("leftBefore")); right = convert.apply(row.get("rightBefore"));
            output = switch ((String) row.get("alias")) {
                case "output-left" -> left; case "output-right" -> right;
                case "remainder-left" -> row.get("entry").equals("originalRem") ? left : convert.apply(row.get("outputBefore"));
                case null, default -> convert.apply(row.get("outputBefore"));
            };
            remainder = "remainder-left".equals(row.get("alias")) && "originalQuotRem".equals(row.get("entry")) ? left : convert.apply(row.get("remainderBefore"));
            arguments = switch ((String) row.get("entry")) {
                case "originalAdd", "originalSub", "originalMul", "originalQuot", "originalRem", "originalGcd" -> new Object[]{output, left, row.get("leftCount"), right, row.get("rightCount")};
                case "originalAddWord", "originalMulWord", "originalRShift", "originalRShiftNegative", "originalLShift" -> new Object[]{output, left, row.get("leftCount"), row.get("word")};
                case "originalGetDouble" -> new Object[]{left, row.get("word"), row.get("fractional")};
                case "originalEncodeDouble", "originalGcdWords" -> new Object[]{row.get("word"), row.get("fractional")};
                case "originalAnd", "originalAndNot", "originalOr", "originalXor" -> new Object[]{output, left, right, row.get("leftCount")};
                case "originalPopCount" -> new Object[]{left, row.get("leftCount")};
                case "originalCmp" -> new Object[]{left, right, row.get("leftCount")};
                case "originalDivWord" -> new Object[]{output, row.get("fractional"), left, row.get("leftCount"), row.get("word")};
                case "originalModWord", "originalGcdWord" -> new Object[]{left, row.get("leftCount"), row.get("word")};
                case "originalQuotRem" -> new Object[]{output, remainder, row.get("fractional"), left, row.get("leftCount"), right, row.get("rightCount")};
                default -> throw new IllegalStateException("Unknown original GMP consumer");
            };
        }
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>()); var result = new ArrayList<RootCallTarget>();
        visit(entry, seen, result); return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return; var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget callee && callee.getRootNode() instanceof GuestRoot) visit(callee, seen, result);
        result.add(target);
    }
    private Context context() { return context(true, true); }
    private Context context(boolean nativeAccess, boolean inline) { return Context.newBuilder("thc").allowNativeAccess(nativeAccess).allowIO(IOAccess.ALL).allowExperimentalOptions(true)
        .option("thc.ByteArrayStorage", nativeAccess ? "native" : "heap")
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("compiler.Inlining", Boolean.toString(inline)).option("engine.CompilationFailureAction", "Throw").build(); }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> source) throws Exception { source = ForeignExceptionFixtureSupport.nativeModules(List.of(source)); return backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source); }
    @Test public void originalCorpusAndExportProvenanceRemainExact() throws Exception { rows(); }
    @Test @Tag("foreign-exceptions-full-core") public void genuineOriginalCallsMatchAllNativeBuffersOnFirstCompiledEntries() throws Exception {
        var rows = rows();
        for (var stage : List.of("pre", "post")) {
            var source = module(stage); var calls = OriginalStdioChecks.foreignCalls(source); assertEquals(24, calls.size());
            var observed = new LinkedHashSet<String>();
            for (var call : calls) {
                var descriptor = (Map<?, ?>) ((Map<?, ?>) call.get(6)).get("foreignCall");
                observed.add((String) ((Map<?, ?>) descriptor.get("target")).get("symbol"));
            }
            assertEquals(new LinkedHashSet<>(Arrays.stream(GmpForeignOp.values()).map(GmpForeignOp::getSymbol).toList()), observed);
            for (var backend : List.of("ast", "bytecode")) for (boolean inline : new boolean[]{false, true}) try (var context = context(true, inline)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var grouped = new LinkedHashMap<String, List<Map<String, Object>>>();
                    for (var row : rows) grouped.computeIfAbsent((String) row.get("entry"), _ -> new ArrayList<>()).add(row);
                    for (var group : grouped.entrySet()) {
                        var name = group.getKey(); var examples = group.getValue(); var linked = new LinkedHashMap<>(CoreModules.reachable(source, name)); linked.put("instrument", true);
                        var program = load(language, backend, linked); var value = program.entryValue(name);
                        var host = program.hostEntryTarget(new Observation(examples.getFirst(), this::nativeBytes).arguments.length);
                        class Runner {
                            RootCallTarget entry = program.entryTarget(name); List<RootCallTarget> active = List.of();
                            void exercise(boolean compiled) throws Exception {
                                for (int index = 0; index < examples.size(); index++) {
                                    var row = examples.get(index); var observation = new Observation(row, OriginalGmpTest.this::nativeBytes); var label = stage + "/" + backend + "/inline=" + inline + "/" + name + "/" + index;
                                    assertArrayEquals(bytes(row.get("outputBefore")), bytes(observation.output), label + " output alias initialization");
                                    assertArrayEquals(bytes(row.get("remainderBefore")), bytes(observation.remainder), label + " remainder alias initialization");
                                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                    // The source-pure cmp/mod consumers are genuine global
                                    // aliases. The ordinary host dispatcher forces their CAF
                                    // and applies the resulting closure, retaining captures.
                                    var returned = Calls.target(host, new Object[]{value, observation.arguments});
                                    assertEquals(row.get("result"), returned instanceof Double number ? (Object) Double.doubleToRawLongBits(number) : returned, label);
                                    assertArrayEquals(bytes(row.get("leftAfter")), bytes(observation.left), label + " left"); assertArrayEquals(bytes(row.get("rightAfter")), bytes(observation.right), label + " right");
                                    assertArrayEquals(bytes(row.get("outputAfter")), bytes(observation.output), label + " output"); assertArrayEquals(bytes(row.get("remainderAfter")), bytes(observation.remainder), label + " remainder");
                                    if (compiled) {
                                        assertEquals(before + active.size(), ((Number) program.diagnostics().get("compiledEntries")).longValue(), label + " compiled entries");
                                        assertEquals(active, targets(entry), label + " target retention"); for (var target : active) valid(target);
                                    }
                                    var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                                }
                            }
                        }
                        var runner = new Runner(); runner.exercise(false); runner.entry = program.entryTarget(name); runner.active = targets(runner.entry); assertFalse(runner.active.isEmpty());
                        for (var target : runner.active) {
                            try { target.getClass().getMethod("compile", boolean.class).invoke(target, true); }
                            catch (Exception failure) { throw new AssertionError(stage + "/" + backend + "/inline=" + inline + "/" + name + ": " + target.getRootNode().getName(), failure); }
                            valid(target);
                        }
                        runner.exercise(true);
                    }
                } finally { context.leave(); }
            }
        }
    }
    @Test @Tag("foreign-exceptions-full-core") public void malformedGenuineMetadataFailsBothLoadersBeforeExecution() throws Exception {
        rows(); var source = module("pre"); var entries = new LinkedHashSet<String>();
        for (var row : (List<Map<String, Object>>) json(prefix + "/oracle.json")) entries.add((String) row.get("entry"));
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var entry : entries) {
                    var linked = CoreModules.reachable(source, entry);
                    for (int variant = 2; variant < 5; variant++) {
                        var bad = (Map<String, Object>) Json.parse(Json.stringify(linked)); var calls = OriginalStdioChecks.foreignCalls(bad);
                        if (calls.size() != 1) throw new IllegalArgumentException("Expected one call");
                        var call = calls.getFirst(); var metadata = (Map<String, Object>) call.get(6); var descriptor = (Map<String, Object>) metadata.get("foreignCall");
                        switch (variant) {
                            case 2 -> ((List<Object>) call.get(3)).set(0, true);
                            case 3 -> ((List<Object>) call.get(1)).set(1, ((List<Map<String, Object>>) linked.get("bindings")).stream()
                                .filter(binding -> entry.equals(binding.get("name"))).findFirst().orElseThrow().get("id"));
                            case 4 -> { var argument = ((List<List<Object>>) call.get(2)).get(0); ((Map<String, Object>) CoreRepresentations.metadata(argument)).put("rep", Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true)); }
                        }
                        assertThrows(RuntimeFault.class, () -> load(language, backend, bad), backend + "/" + entry + "/" + variant);
                    }
                }
            } finally { context.leave(); }
        }
    }
    @Test @Tag("foreign-exceptions-full-core") public void originalEntryPermissionAndArrayCarriersNeverStoreToGuestBuffersOnRejection() throws Exception {
        rows(); var source = CoreModules.reachable(module("pre"), "originalAddWord");
        for (var backend : List.of("ast", "bytecode")) for (boolean nativeAccess : new boolean[]{false, true}) try (var context = context(nativeAccess, true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var output = new byte[24]; Arrays.fill(output, (byte) 0x5a); var before = output.clone(); var input = new byte[24]; Arrays.fill(input, (byte) 0x33);
                if (!nativeAccess) {
                    assertThrows(RuntimeFault.class, () -> callScalarTestTarget(load(language, backend, source).entryTarget("originalAddWord"),
                        new Object[]{0L, output, input, 1L, 1L}));
                } else {
                    var entry = load(language, backend, source).entryTarget("originalAddWord");
                    assertThrows(RuntimeFault.class, () -> callScalarTestTarget(entry, new Object[]{0L, output, ManagedAddress.fromByteArray(input), 1L, 1L}));
                }
                assertArrayEquals(before, output);
            } finally { context.leave(); }
        }
    }
}
