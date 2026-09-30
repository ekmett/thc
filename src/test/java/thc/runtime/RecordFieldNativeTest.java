// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class RecordFieldNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path directory = root.resolve("build/record-fields");
    private Map<String, Object> json(Path file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(file));
    }

    @Test
    void fieldNamespacesAndCrossModuleAliasesMatchNative() throws Exception {
        var manifest = json(directory.resolve("manifest.json"));
        for (String kind : List.of("inputHashes", "artifactHashes"))
            for (var hash : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                byte[] bytes = Files.readAllBytes(root.resolve(hash.getKey()));
                var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                assertEquals(hash.getValue(), digest, kind + "/" + hash.getKey());
            }
        var rows = Files.readAllLines(directory.resolve("logs/post-native.stdout"))
                       .stream()
                       .map(line -> Arrays.stream(line.split(" ", -1)).map(Long::parseLong).toList())
                       .toList();
        assertEquals(Files.readString(directory.resolve("logs/pre-native.stdout")),
            Files.readString(directory.resolve("logs/post-native.stdout")));
        assertEquals(List.of(-100L, -10L, -1L, 0L, 1L, 10L, 100L), rows.stream().map(row -> row.getFirst()).toList());
        for (String stage : List.of("pre", "post", "installed")) {
            var sources = new ArrayList<Map<String, Object>>();
            for (String name : List.of("RecordFieldLibrary", "RecordFieldClient"))
                sources.add(thc.CoreCbdFixtures.read(directory.resolve(stage + "/" + name + ".cbd")));
            var bindings = sources.stream()
                               .flatMap(source -> ((List<Map<String, Object>>) source.get("bindings")).stream())
                               .toList();
            var ids = bindings.stream().map(binding -> (String) binding.get("id")).toList();
            assertEquals(ids.size(), new HashSet<>(ids).size(), stage + ": distinct GHC identities must not collide");
            for (String field : List.of("$fld:LeftRecord:shared", "$fld:RightRecord:shared", "$fld:Plain:unique"))
                assertTrue(
                    ids.stream().anyMatch(id -> id.endsWith("RecordFieldLibrary." + field)), stage + "/" + field);
            var entries = List.of("fieldAlias", "duplicateFields");
            for (int column = 0; column < entries.size(); column++) {
                String entry = entries.get(column);
                var audit = json(directory.resolve(stage + "/" + entry + "-audit.json"));
                assertEquals(true, audit.get("accepted"));
                assertEquals(List.of(), audit.get("issues"));
                var linked = new LinkedHashMap<>(CoreModules.reachable(CoreModules.merge(sources), "main:RecordFieldClient." + entry, true));
                linked.put("instrument", true);
                for (String backend : List.of("ast", "bytecode"))
                    try (var context = Context.newBuilder("thc")
                             .allowExperimentalOptions(true)
                             .option("engine.BackgroundCompilation", "false")
                             .option("engine.MultiTier", "false")
                             .option("engine.CompilationFailureAction", "Throw")
                             .option("engine.SingleTierCompilationThreshold", "10000000")
                             .build()) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked)
                                                                              : new BytecodeProgram(language, linked);
                            var function = context.asValue(new EntryValue(program, "main:RecordFieldClient." + entry, 1));
                            for (var row : rows)
                                assertEquals(row.get(column + 1), function.execute(row.getFirst()).asLong());
                            assertTrue(function.invokeMember("compile").asBoolean());
                            for (var row : rows.reversed()) {
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                assertEquals(row.get(column + 1), function.execute(row.getFirst()).asLong(),
                                    stage + "/" + backend + "/" + entry);
                                assertTrue(
                                    ((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                            }
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        } finally {
                            context.leave();
                        }
                    }
            }
        }
    }
}