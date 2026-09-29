// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.ToLongBiFunction;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
final class CompactLibraryFixture {
    private final String directory, compiledEntry;
    private final List<String> entries;
    private final ToLongBiFunction<String, Long> expected;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    CompactLibraryFixture(String directory, List<String> entries, ToLongBiFunction<String, Long> expected, String compiledEntry) {
        this.directory = directory; this.entries = entries; this.expected = expected; this.compiledEntry = compiledEntry;
    }
    private Map<String, Object> read(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath(), StandardCharsets.UTF_8)); }
    private record Input(String name, long value) {}
    void run() throws Exception {
        var manifest = read(directory + "/manifest.json"); assertEquals(entries, manifest.get("entries"));
        for (String kind : List.of("inputHashes", "artifactHashes")) for (var hash : ((Map<String, String>) manifest.get(kind)).entrySet())
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), "Stale compact fixture: " + hash.getKey());
        var rows = new ArrayList<List<String>>(); for (String line : Files.readAllLines(new File(root, directory + "/oracle.tsv").toPath(), StandardCharsets.UTF_8)) rows.add(Arrays.asList(line.split("\t", -1)));
        var wanted = new ArrayList<Input>(); for (String name : entries) for (long input : new long[] {-31L, 0L, 17L, 4097L}) wanted.add(new Input(name, input));
        var actual = new ArrayList<Input>(); for (var row : rows) actual.add(new Input(row.getFirst(), Long.parseLong(row.get(1)))); assertEquals(wanted, actual);
        for (var row : rows) assertEquals(expected.applyAsLong(row.getFirst(), Long.parseLong(row.get(1))), Long.parseLong(row.get(2)));
        var stages = (Map<String, List<String>>) manifest.get("stages"); assertEquals(Set.of("pre", "post"), stages.keySet());
        for (String stage : stages.keySet()) for (var selection : read(directory + "/" + stage + "/original-selection.json").entrySet()) {
            String[] origin = ((String) selection.getValue()).split("!/", 2);
            try (ZipFile zip = new ZipFile(new File(root, origin[0])); var input = zip.getInputStream(zip.getEntry(origin[1]))) {
                assertArrayEquals(input.readAllBytes(), Files.readAllBytes(new File(root, selection.getKey()).toPath()), "Original library module changed: " + selection.getKey());
            }
        }
        var targetLayout = Objects.requireNonNull(CoreCbdFixtures.visitModules(new File(root, directory + "/installed/packages.json").getPath(), (module, path) -> {}).getTargetLayout());
        for (var stage : stages.entrySet()) for (String backend : List.of("ast", "bytecode")) {
            var modules = new ArrayList<Map<String, Object>>(); for (String path : stage.getValue()) modules.add(read(path));
            var module = new LinkedHashMap<>(CoreModules.merge(modules)); module.put("targetLayout", targetLayout);
            boolean retained = false; for (var binding : (List<Map<String, Object>>) module.get("bindings")) if (((String) binding.get("id")).contains(":GHC.Compact.")) retained = true;
            assertTrue(retained, "Original library retained");
            try (Context context = Context.newBuilder("thc", "llvm").allowCreateThread(true).allowNativeAccess(true).allowIO(IOAccess.ALL).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (String entry : entries) {
                        var linked = new LinkedHashMap<>(CoreModules.reachable(module, entry, true)); linked.put("instrument", true);
                        boolean async = entry.equals("interruptedPlain") || entry.equals("interruptedSharing");
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, async) : new BytecodeProgram(language, linked, async);
                        var function = context.asValue(new EntryValue(program, entry, 1));
                        for (long input : new long[] {-31L, 0L, 17L, 4097L}) assertEquals(expected.applyAsLong(entry, input), function.execute(input).asLong(), stage.getKey() + "/" + backend + "/" + entry + "/" + input);
                        if (entry.equals(compiledEntry)) {
                            assertTrue(function.invokeMember("compile").asBoolean()); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(expected.applyAsLong(entry, 43L), function.execute(43L).asLong()); assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                        }
                        var pools = language.getHandoffState().get(); assertEquals(0, pools.getArguments().getDepth()); assertEquals(0, pools.getResults().getDepth());
                        assertEquals(0, pools.getArguments().retainedReferences()); assertEquals(0, pools.getResults().retainedReferences()); assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                    }
                } finally { context.leave(); }
            }
        }
    }
}
