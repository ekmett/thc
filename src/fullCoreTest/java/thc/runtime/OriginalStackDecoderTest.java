// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full unchanged installed decoder/formatter; native and managed frame counts differ. */
@SuppressWarnings("unchecked")
public class OriginalStackDecoderTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String directory = "build/original-stack-decoder";
    private final List<String> entries = List.of("captureNamed", "observeSnapshot");
    private Map<String, Object> json(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath(), StandardCharsets.UTF_8)); }
    private Map<String, Object> manifest() throws Exception {
        var value = json(directory + "/manifest.json"); assertEquals("thc-original-stack-decoder-fixture", value.get("format"));
        assertEquals(1L, value.get("schema")); assertEquals("9.14.1", value.get("ghc")); assertEquals(entries, value.get("entries"));
        var inputs = (Map<String, String>) value.get("inputHashes"); assertTrue(inputs.keySet().containsAll(List.of("t/fixtures/compiler/OriginalStackDecoder.hs",
            "t/fixtures/compiler/OriginalStackDecoderNative.hs", "t/haskell-fixtures/InstalledCoreFixtures.hs", "t/haskell-fixtures/StackDecoderFixtures.hs", "bin/core-capabilities.json")));
        for (String group : List.of("inputHashes", "artifactHashes")) {
            var records = (Map<String, String>) value.get(group); assertFalse(records.isEmpty());
            for (var hash : records.entrySet()) {
                String path = hash.getKey(); assertFalse(new File(path).isAbsolute());
                for (String component : path.split("/", -1)) assertFalse(Set.of("", ".", "..").contains(component));
                var file = new File(root, path).getCanonicalFile(); assertTrue(file.toPath().startsWith(root.getCanonicalFile().toPath()));
                if (group.equals("artifactHashes")) assertTrue(path.startsWith(directory + "/"));
                assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))), "Stale original stack fixture: " + path);
            }
        }
        var audits = (List<String>) value.get("audits"); var expectedAudits = new ArrayList<String>();
        for (String stage : List.of("pre", "post")) for (String entry : entries) expectedAudits.add(directory + "/" + stage + "/" + entry + "-audit.json"); assertEquals(expectedAudits, audits);
        for (String path : audits) { var audit = json(path); assertEquals(true, audit.get("accepted"), path); assertEquals(List.of(), audit.get("missingGlobals"), path); }
        return value;
    }
    private Context context(boolean inlining) { return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private boolean valid(RootCallTarget target) throws Exception { return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private long observe(RootCallTarget target, Object snapshot, long probe) { return (Long) Calls.target(target, new Object[] {0L, snapshot, probe}); }
    private Object capture(RootCallTarget target) { return Calls.target(target, new Object[] {0L, 1L}); }
    private List<Long> inspect(RootCallTarget target, Object snapshot, List<Long> nativeRows, String label) {
        long count = observe(target, snapshot, -1); assertTrue(count > 0, label);
        assertEquals(nativeRows.get(1).longValue(), observe(target, snapshot, -2), label + " repeated decode");
        assertEquals(count, observe(target, snapshot, -3), label + " provenance for each authentic guest frame");
        assertEquals(count, observe(target, snapshot, -4), label + " original formatter renders each managed IPE");
        long length = observe(target, snapshot, -5); assertTrue(length >= 1 && length <= 100000, label);
        long namedSources = observe(target, snapshot, -6); assertTrue(namedSources >= 1 && namedSources <= count, label + " original formatter includes captureLeaf and its source in one frame");
        return List.of(count, length, namedSources);
    }
    @Test public void originalDecoderAndFormatterConsumeCapturedFramesBeforeAndAfterCompilation() throws Exception {
        var manifest = manifest(); var nativeRows = new ArrayList<Long>();
        for (String token : Files.readString(new File(root, (String) manifest.get("nativeOutput")).toPath(), StandardCharsets.UTF_8).trim().split(" ", -1)) nativeRows.add(Long.parseLong(token));
        assertEquals(6, nativeRows.size()); assertTrue(nativeRows.getFirst() > 0); assertEquals(1L, nativeRows.get(1)); assertEquals(1L, nativeRows.get(5));
        assertTrue(nativeRows.get(2) >= 0 && nativeRows.get(2) <= nativeRows.getFirst()); assertTrue(nativeRows.get(3) >= 0 && nativeRows.get(3) <= nativeRows.getFirst()); assertTrue(nativeRows.get(4) >= 0);
        var originalsText = new StringBuilder(); var targetLayout = CoreCbdFixtures.appendModules(originalsText, new File(root, (String) manifest.get("packageManifest")).getPath());
        assertNotNull(targetLayout, "Original selected-toolchain target layout required");
        var originals = (List<Map<String, Object>>) Json.parse("[" + originalsText + "]"); var stages = (Map<String, String>) manifest.get("stages"); assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stage : stages.entrySet()) for (String backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[] {false, true}) try (Context context = context(inlining)) {
            var modules = new ArrayList<>(originals); modules.add(json(stage.getValue())); var combined = new LinkedHashMap<>(CoreModules.merge(modules)); combined.put("targetLayout", targetLayout);
            // Link both real fixture roots without allowing unavailable
            // globals, replacing originals or discarding their cold branches.
            var distinctBindings = new LinkedHashMap<Object, Map<String, Object>>();
            for (String entry : entries) for (var binding : (List<Map<String, Object>>) CoreModules.reachable(combined, entry, true).get("bindings")) distinctBindings.putIfAbsent(binding.get("id"), binding);
            var bindings = new ArrayList<>(distinctBindings.values()); var module = new LinkedHashMap<>(combined); module.put("bindings", bindings); module.put("instrument", true);
            boolean original = false; for (var binding : bindings) if (((String) binding.get("id")).startsWith("ghc-internal:GHC.Internal.Stack.Decode.")) original = true; assertTrue(original);
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                var capture = program.entryTarget("captureNamed"); var observe = program.entryTarget("observeSnapshot"); String label = stage.getKey() + "/" + backend + "/inlining=" + inlining;
                var snapshot = capture(capture); var observation = inspect(observe, snapshot, nativeRows, label);
                // Warm each entry, then assert its very first installed entry;
                // no settling loop after installation is allowed.
                for (int i = 0; i < 3; i++) { observe(observe, snapshot, -2); capture(capture); }
                for (var target : List.of(capture, observe)) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target), label + " installed " + target.getRootNode().getName()); }
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var fresh = capture(capture); assertTrue(valid(capture), label + " first installed capture");
                assertEquals(nativeRows.get(1).longValue(), observe(observe, snapshot, -2), label + " first installed original decode"); assertTrue(valid(observe), label + " first installed decode");
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() >= before + 2, label);
                assertEquals(observation, inspect(observe, snapshot, nativeRows, label), label + " retained detached snapshot"); inspect(observe, fresh, nativeRows, label);
                assertEquals(0L, program.diagnostics().get("unsupportedTraps"), label); var loans = language.getHandoffState().get();
                assertEquals(0, loans.getArguments().getDepth()); assertEquals(0, loans.getResults().getDepth()); assertEquals(0, loans.getArguments().retainedReferences()); assertEquals(0, loans.getResults().retainedReferences());
            } finally { context.leave(); }
        }
    }
}
