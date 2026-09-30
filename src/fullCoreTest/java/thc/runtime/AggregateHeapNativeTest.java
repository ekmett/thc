// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import thc.CoreModules;
import thc.CoreCbdFixtures;
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
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class AggregateHeapNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/aggregate-heap");
    private Map<String, Object> json(File file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }
    @TestFactory public List<DynamicTest> genuineConstructorFieldsMatchNativeAndFirstCompiledEntry() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        assertEquals(true, manifest.get("strictAccepted"));
        for (String group : List.of("inputHashes", "artifactHashes"))
            for (var hash : ((Map<String, String>) manifest.get(group)).entrySet()) {
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath())));
                assertEquals(hash.getValue(), actual, "Stale aggregate heap fixture " + hash.getKey());
            }
        var rows = new LinkedHashMap<String, List<String[]>>();
        int rowCount = 0;
        for (String line : Files.readAllLines(new File(directory, "oracle.tsv").toPath(), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t", -1);
            rows.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(row); rowCount++;
        }
        assertEquals(9, rows.size()); assertEquals(117, rowCount);
        var tests = new ArrayList<DynamicTest>();
        for (String stage : List.of("pre", "post")) {
            assertEquals(true, json(new File(directory, stage + "/audit.json")).get("accepted"));
            var modules = new ArrayList<Map<String, Object>>();
            for (File file : Objects.requireNonNull(new File(directory, stage + "/core").listFiles()))
                if (file.getName().endsWith(".cbd")) modules.add(CoreCbdFixtures.read(file.toPath()));
            for (var group : rows.entrySet()) for (String backend : List.of("ast", "bytecode")) {
                String entry = group.getKey();
                var cases = group.getValue();
                tests.add(DynamicTest.dynamicTest(stage + "/" + backend + "/" + entry, () -> {
                    try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true)
                            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                            .option("engine.CompilationFailureAction", "Throw").build()) {
                        context.initialize("thc"); context.enter();
                        try {
                            Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            String name = "main:AggregateHeapFields." + entry;
                            var linked = CoreModules.reachable(CoreModules.merge(modules), name, true);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                            var callable = context.asValue(new EntryValue(program, name, 1));
                            for (String[] row : cases) assertEquals(Long.parseLong(row[2]), callable.execute(Long.parseLong(row[1])).asLong(), row[1]);
                            assertTrue(callable.invokeMember("compile").asBoolean());
                            for (String[] row : cases.reversed()) {
                                long before = (Long) program.diagnostics().get("compiledEntries");
                                assertEquals(Long.parseLong(row[2]), callable.execute(Long.parseLong(row[1])).asLong(), row[1]);
                                assertTrue((Long) program.diagnostics().get("compiledEntries") > before);
                            }
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                        } finally { context.leave(); }
                    }
                }));
            }
        }
        return tests;
    }
}
