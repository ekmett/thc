// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.RootCallTarget;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class SumInputNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/sum-input");
    private Map<String, Object> json(File file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.toString());
    }
    @TestFactory public List<DynamicTest> originalSumInputsInline() throws Exception { return nativeCases(true); }
    @TestFactory public List<DynamicTest> originalSumInputsResidual() throws Exception { return nativeCases(false); }
    private List<DynamicTest> nativeCases(boolean inlining) throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (String group : List.of("inputHashes", "artifactHashes"))
            for (var hash : ((Map<String, String>) manifest.get(group)).entrySet()) {
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath())));
                assertEquals(hash.getValue(), actual, "Stale sum input fixture " + hash.getKey());
            }
        var rows = new LinkedHashMap<String, List<String[]>>();
        int rowCount = 0;
        for (String line : Files.readAllLines(new File(directory, "oracle.tsv").toPath(), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t", -1);
            rows.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(row); rowCount++;
        }
        assertEquals(9, rows.size()); assertEquals(333, rowCount);
        var tests = new ArrayList<DynamicTest>();
        for (String stage : List.of("pre", "post")) {
            assertEquals(true, json(new File(directory, stage + "/audit.json")).get("accepted"));
            var source = json(new File(directory, stage + "/core/SumInputAudit.json"));
            for (var group : rows.entrySet()) {
                String entry = group.getKey(); var cases = group.getValue();
                String name = "main:SumInputAudit." + entry;
                var linked = new LinkedHashMap<>(CoreModules.reachable(source, name, true)); linked.put("instrument", true);
                for (String backend : List.of("ast", "bytecode"))
                    tests.add(DynamicTest.dynamicTest(stage + "/" + backend + "/" + entry + "/inlining=" + inlining, () -> {
                        try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true)
                                .option("compiler.Inlining", Boolean.toString(inlining))
                                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                                .option("engine.CompilationFailureAction", "Throw").build()) {
                            context.initialize("thc"); context.enter();
                            try {
                                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                                var callable = context.asValue(new EntryValue(program, name, 1));
                                for (String[] row : cases) assertEquals(Long.parseLong(row[2]), callable.execute(Long.parseLong(row[1])).asLong(), row[1]);
                                assertTrue(callable.invokeMember("compile").asBoolean());
                                for (String[] row : cases.reversed()) {
                                    long before = (Long) program.diagnostics().get("compiledEntries");
                                    assertEquals(Long.parseLong(row[2]), callable.execute(Long.parseLong(row[1])).asLong(), row[1]);
                                    assertTrue((Long) program.diagnostics().get("compiledEntries") > before);
                                    valid(program.entryTarget(name)); valid(program.hostEntryTarget(1));
                                }
                                var handoff = language.getHandoffState().get();
                                assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().getDepth());
                                assertEquals(0, handoff.getResults().retainedReferences()); assertEquals(0, handoff.getArguments().retainedReferences());
                            } finally { context.leave(); }
                        }
                    }));
            }
        }
        return tests;
    }
}
