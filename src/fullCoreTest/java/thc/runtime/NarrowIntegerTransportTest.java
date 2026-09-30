// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
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
public class NarrowIntegerTransportTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/narrow-integer-transport");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    private Map<String, Object> evidence() throws Exception {
        var manifest = json(new File(directory, "manifest.json")); assertEquals(true, manifest.get("strictAccepted"));
        for (String group : List.of("inputHashes", "artifactHashes")) for (var hash : ((Map<String, String>) manifest.get(group)).entrySet())
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), "Stale narrow-integer input " + hash.getKey());
        return manifest;
    }
    private Map<String, List<List<String>>> rows() throws Exception {
        var result = new LinkedHashMap<String, List<List<String>>>();
        for (String line : Files.readAllLines(new File(directory, "oracle.tsv").toPath(), StandardCharsets.UTF_8)) {
            var row = Arrays.asList(line.split("\t", -1)); result.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }
    private List<Map<String, Object>> modules(String stage) throws Exception {
        var files = new File(directory, stage + "/core").listFiles(); assertNotNull(files); var result = new ArrayList<Map<String, Object>>();
        for (File file : files) if (file.getName().endsWith(".cbd")) result.add(CoreCbdFixtures.read(file.toPath())); return result;
    }
    @FunctionalInterface private interface Action { void run(Context context, Language language) throws Exception; }
    private void entered(boolean inline, Action action) throws Exception {
        try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inline))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(context, TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private ExecutableProgram program(Language language, String stage, String backend, String entry) throws Exception {
        var linked = CoreModules.reachable(CoreModules.merge(modules(stage)), "main:NarrowIntegerTransport." + entry, true);
        return backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.toString()); }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().retainedReferences()); assertEquals(0, state.getArguments().retainedReferences());
    }
    private void observe(List<String> row, Value callable, ExecutableProgram program, Language language) {
        assertEquals(Long.parseLong(row.get(2)), callable.execute(Long.parseLong(row.get(1))).asLong(), row.toString());
        assertEquals(0L, program.diagnostics().get("blackholes")); released(language);
    }
    @TestFactory public List<DynamicTest> genuineActiveValuesMatchNativeOnFirstInstalledCall() throws Exception {
        var manifest = evidence(); var rows = rows(); assertEquals(manifest.get("entries"), new ArrayList<>(rows.keySet()));
        int count = 0; for (var cases : rows.values()) count += cases.size(); assertEquals(460, count); var tests = new ArrayList<DynamicTest>();
        for (String stage : List.of("pre", "post")) {
            assertEquals(true, json(new File(directory, stage + "/audit.json")).get("accepted"));
            for (String backend : List.of("ast", "bytecode")) for (var group : rows.entrySet()) for (boolean inline : new boolean[] {true, false}) {
                String entry = group.getKey(); var cases = group.getValue();
                tests.add(DynamicTest.dynamicTest(stage + "/" + backend + "/" + entry + "/inline=" + inline, () -> entered(inline, (context, language) -> {
                    var program = program(language, stage, backend, entry); String name = "main:NarrowIntegerTransport." + entry;
                    var callable = context.asValue(new EntryValue(program, name, 1)); for (var row : cases) observe(row, callable, program, language);
                    assertTrue(callable.invokeMember("compile").asBoolean());
                    for (var row : cases.reversed()) {
                        long before = (Long) program.diagnostics().get("compiledEntries"); observe(row, callable, program, language);
                        assertTrue((Long) program.diagnostics().get("compiledEntries") > before); valid(program.entryTarget(name)); valid(program.hostEntryTarget(1));
                    }
                })));
            }
        }
        return tests;
    }
    @TestFactory public List<DynamicTest> genuinePublicNarrowInputsAndOutputsKeepSignednessAndRejectOutOfRange() throws Exception {
        evidence(); var rows = rows(); var tests = new ArrayList<DynamicTest>();
        for (String stage : List.of("pre", "post")) for (String backend : List.of("ast", "bytecode")) for (var integer : NarrowInteger.values())
            tests.add(DynamicTest.dynamicTest(stage + "/" + backend + "/public/" + integer.getRep(), () -> entered(true, (context, language) -> {
                String entry = "public" + integer.getRep().substring(0, integer.getRep().length() - 3); var program = program(language, stage, backend, entry);
                String name = "main:NarrowIntegerTransport." + entry; var callable = context.asValue(new EntryValue(program, name, 1));
                long minimum = integer.getUnsigned() ? 0L : -(1L << (integer.getBits() - 1));
                long maximum = integer.getUnsigned() ? (1L << integer.getBits()) - 1 : (1L << (integer.getBits() - 1)) - 1;
                var cases = new ArrayList<List<String>>(); for (var row : Objects.requireNonNull(rows.get(entry + "Case"))) {
                    long input = Long.parseLong(row.get(1)); if (input >= minimum && input <= maximum) cases.add(row);
                }
                assertFalse(cases.isEmpty()); for (var row : cases) assertEquals(Long.parseLong(row.get(2)), callable.execute(Long.parseLong(row.get(1))).asLong());
                assertTrue(callable.invokeMember("compile").asBoolean());
                for (var row : cases.reversed()) {
                    long before = (Long) program.diagnostics().get("compiledEntries"); assertEquals(Long.parseLong(row.get(2)), callable.execute(Long.parseLong(row.get(1))).asLong());
                    assertTrue((Long) program.diagnostics().get("compiledEntries") > before); valid(program.entryTarget(name)); valid(program.hostEntryTarget(1)); released(language);
                }
                for (long bad : new long[] {minimum - 1, maximum + 1}) { assertThrows(PolyglotException.class, () -> callable.execute(bad)); released(language); }
            })));
        return tests;
    }
}
