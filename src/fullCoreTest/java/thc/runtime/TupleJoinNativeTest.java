// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class TupleJoinNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/tuple-join-input");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    @TestFactory public List<DynamicTest> typedJoinMovesMatchNativeWithBottomingCases() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (String group : List.of("inputHashes", "artifactHashes")) for (var hash : ((Map<String, String>) manifest.get(group)).entrySet())
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), "Stale tuple join fixture " + hash.getKey());
        var allRows = new LinkedHashMap<String, List<List<String>>>(); int count = 0;
        for (String line : Files.readAllLines(new File(directory, "oracle.tsv").toPath(), StandardCharsets.UTF_8)) {
            var row = Arrays.asList(line.split("\t", -1)); allRows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row); count++;
        }
        assertEquals(6, allRows.size()); assertEquals(222, count); var entries = (List<String>) manifest.get("entries");
        var rows = new LinkedHashMap<String, List<List<String>>>(); for (var entry : allRows.entrySet()) if (entries.contains(entry.getKey())) rows.put(entry.getKey(), entry.getValue());
        assertEquals(new LinkedHashSet<>(entries), rows.keySet()); var originals = new ArrayList<Map<String, Object>>(); Object layout = null;
        if (Boolean.TRUE.equals(manifest.get("originalLibrary"))) {
            assertTrue(entries.contains("originalRoundTo"));
            layout = CoreCbdFixtures.visitModules(new File(root, (String) manifest.get("packageManifest")).getPath(), (module, ignored) -> originals.add(module)).getTargetLayout(); assertNotNull(layout);
            Map<String, Object> roundTo = null;
            for (var module : originals) if (module.get("bindings") instanceof List<?> bindings) for (var raw : bindings) {
                var binding = (Map<String, Object>) raw;
                if ("ghc-internal:GHC.Internal.Float.$wroundTo".equals(binding.get("id"))) { assertNull(roundTo); roundTo = binding; }
            }
            assertNotNull(roundTo); assertTrue(roundTo.toString().contains("joinValueArity"), "Original installed roundTo retains its local join");
        } else assertFalse(entries.contains("originalRoundTo"));
        var tests = new ArrayList<DynamicTest>();
        for (String stage : List.of("pre", "post")) {
            assertEquals(true, json(new File(directory, stage + "/audit.json")).get("accepted")); var modules = new ArrayList<>(originals);
            modules.add(CoreCbdFixtures.read(new File(directory, stage + "/core/TupleJoinInputAudit.cbd").toPath())); var combined = new LinkedHashMap<>(CoreModules.merge(modules));
            if (layout != null) combined.put("targetLayout", layout);
            for (var group : rows.entrySet()) {
                String entry = group.getKey(), name = "main:TupleJoinInputAudit." + entry; var cases = group.getValue();
                var linked = new LinkedHashMap<>(CoreModules.reachable(combined, name, true)); linked.put("instrument", true);
                for (String backend : List.of("ast", "bytecode")) tests.add(DynamicTest.dynamicTest(stage + "/" + backend + "/" + entry, () -> {
                    try (Context context = Context.newBuilder("thc", "llvm").allowNativeAccess(true).allowIO(IOAccess.ALL).allowCreateThread(true).allowExperimentalOptions(true)
                        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                        context.initialize("thc"); context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                            var callable = context.asValue(new EntryValue(program, name, 1));
                            for (var row : cases) assertEquals(Long.parseLong(row.get(2)), callable.execute(Long.parseLong(row.get(1))).asLong(), row.get(1));
                            assertTrue(callable.invokeMember("compile").asBoolean());
                            for (var row : cases.reversed()) {
                                long before = (Long) program.diagnostics().get("compiledEntries");
                                assertEquals(Long.parseLong(row.get(2)), callable.execute(Long.parseLong(row.get(1))).asLong(), row.get(1));
                                assertTrue((Long) program.diagnostics().get("compiledEntries") > before);
                            }
                            var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().getDepth());
                            assertEquals(0, handoff.getResults().retainedReferences()); assertEquals(0, handoff.getArguments().retainedReferences());
                        } finally { context.leave(); }
                    }
                }));
            }
        }
        return tests;
    }
}
