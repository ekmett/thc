// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import thc.*;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class FourWayAggregateNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/fourway-aggregate");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.toString()); }
    private void observe(List<String> row, Value callable, Language language) {
        var result = callable.execute(Long.parseLong(row.get(2)), new BigInteger(row.get(3))).asBigInteger();
        assertEquals(row.get(4), result.toString(), String.join("/", row)); var handoff = language.getHandoffState().get();
        assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().getDepth());
        assertEquals(0, handoff.getResults().retainedReferences()); assertEquals(0, handoff.getArguments().retainedReferences());
    }
    @TestFactory public List<DynamicTest> originalAndNestedAggregatesMatchNativeOnFirstCompiledEntry() throws Exception {
        var manifest = FourWayEvidence.verify(root); var rows = new LinkedHashMap<String, List<List<String>>>(); int count = 0;
        for (String line : Files.readAllLines(new File(directory, "oracle.tsv").toPath(), StandardCharsets.UTF_8)) {
            var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row); count++;
        }
        assertEquals(manifest.get("entries"), new ArrayList<>(rows.keySet())); assertEquals(16, rows.size()); assertEquals(1536, count);
        var tests = new ArrayList<DynamicTest>();
        for (String stage : List.of("pre", "post")) {
            assertEquals(true, json(new File(directory, stage + "/audit.json")).get("accepted"));
            var files = new File(directory, stage + "/core").listFiles(); assertNotNull(files); var modules = new ArrayList<Map<String, Object>>();
            for (File file : files) if (file.getName().endsWith(".cbd")) modules.add(CoreCbdFixtures.read(file.toPath()));
            for (var group : rows.entrySet()) for (String backend : List.of("ast", "bytecode")) {
                String entry = group.getKey(); var cases = group.getValue(); String lower = entry.toLowerCase(Locale.ROOT);
                // Residual edges additionally exercise durable captures/PAPs without inlining.
                var inlineModes = lower.contains("residual") || lower.contains("capture") ? List.of(true, false) : List.of(true);
                for (boolean inline : inlineModes) tests.add(DynamicTest.dynamicTest(stage + "/" + backend + "/" + entry + "/inline=" + inline, () -> {
                    try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inline))
                        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                        context.initialize("thc"); context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); String name = "main:FourWayAggregateFields." + entry;
                            var linked = CoreModules.reachable(CoreModules.merge(modules), name, true);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                            var callable = context.asValue(new EntryValue(program, name, 2)); for (var row : cases) observe(row, callable, language);
                            // Public compile restores the shared entry prerequisite without
                            // executing a settling guest call. The next observation is checked.
                            assertTrue(callable.invokeMember("compile").asBoolean());
                            for (var row : cases.reversed()) {
                                long before = (Long) program.diagnostics().get("compiledEntries"); observe(row, callable, language);
                                assertTrue((Long) program.diagnostics().get("compiledEntries") > before); valid(program.entryTarget(name)); valid(program.hostEntryTarget(2));
                            }
                        } finally { context.leave(); }
                    }
                }));
            }
        }
        return tests;
    }
}
