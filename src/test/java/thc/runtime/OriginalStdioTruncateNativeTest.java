// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

/** Installed GHC c_ftruncate metadata and private-file sizes and bytes. */
@SuppressWarnings("unchecked")
class OriginalStdioTruncateNativeTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File fixture = new File(root, "build/original-stdio-truncate");
    private final List<String> names = List.of("originalTruncate", "originalTruncateErrno");
    private final List<String> scenarios = List.of("shrink", "same", "extend", "negative", "readonly", "invalid", "pipe");
    private Map<String, Object> cbd(File path) throws Exception { return thc.CoreCbdFixtures.read(path.toPath()); }
    private static String entryId(String name) { return "main:OriginalStdioTruncateAudit." + name; }
    private Map<String, Object> json(File path) throws IOException { return (Map<String, Object>) Json.parse(Files.readString(path.toPath())); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void exercise(boolean compiled, String stage, String backend, String name, ExecutableProgram program,
            RootCallTarget entry, List<Map<String, Object>> oracle, Map<String, Long> expectedSize) throws Exception {
        for (var scenario : scenarios) {
            var row = single(oracle, item -> Objects.equals(item.get("entry"), name) && Objects.equals(item.get("scenario"), scenario));
            var privateFile = directory.resolve(stage + "-" + backend + "-" + name + "-" + scenario + "-" + compiled); Files.writeString(privateFile, "abcdef");
            var files = Language.currentState(null).getFiles(); long fd;
            switch (scenario) {
                case "invalid" -> fd = -1;
                case "pipe" -> fd = 0; // The embedding input is nonseekable.
                default -> { fd = files.open(ManagedAddress.fromByteArray((privateFile + "\0").getBytes(StandardCharsets.UTF_8)), scenario.equals("readonly") ? 0L : 3L, ForeignSafety.UNSAFE); assertTrue(fd >= 3); }
            }
            if (fd >= 3) assertEquals(4L, files.seek(fd, 4, 0, ForeignSafety.UNSAFE));
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            assertEquals(row.get("result"), Calls.target(entry, new Object[] {0L, fd, row.get("length")}), stage + "/" + backend + "/" + name + "/" + scenario);
            if (fd >= 3) {
                assertEquals(4L, files.seek(fd, 0, 1, ForeignSafety.UNSAFE), "ftruncate preserves the open-file position"); assertEquals(expectedSize.get(scenario), files.size(fd));
                byte[] expectedBytes = switch (scenario) {
                    case "shrink" -> "abc".getBytes(StandardCharsets.UTF_8);
                    case "extend" -> Arrays.copyOf("abcdef".getBytes(StandardCharsets.UTF_8), 9);
                    default -> "abcdef".getBytes(StandardCharsets.UTF_8);
                };
                assertArrayEquals(expectedBytes, Files.readAllBytes(privateFile)); assertEquals(0L, files.close(fd, ForeignSafety.UNSAFE));
            }
            if (compiled) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before,"Must execute installed code");
        }
    }
    @Test void originalTruncateMatchesNativeAndCompiledTargets() throws Exception {
        var manifest = json(new File(fixture, "manifest.json")); assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(names, manifest.get("entries")); assertEquals(14L, manifest.get("nativeRows"));
        hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalStdioTruncateAudit.hs", "t/fixtures/compiler/OriginalStdioTruncateAuditNative.hs",
            "t/haskell-fixtures/OriginalStdioTruncateFixtures.hs", "bin/core_original_foreign.py"));
        hashes(root, manifest.get("artifactHashes"), Set.of("build/original-stdio-truncate/oracle.json", "build/original-stdio-truncate/pre/core/OriginalStdioTruncateAudit.cbd",
            "build/original-stdio-truncate/post/core/OriginalStdioTruncateAudit.cbd"), "build/original-stdio-truncate/");
        var oracle = (List<Map<String, Object>>) Json.parse(Files.readString(new File(fixture, "oracle.json").toPath()));
        var expectedOrder = new ArrayList<List<String>>(); for (var name : names) for (var scenario : scenarios) expectedOrder.add(List.of(name, scenario));
        var actualOrder = new ArrayList<List<Object>>(); for (var row : oracle) actualOrder.add(list(row.get("entry"), row.get("scenario"))); assertEquals(expectedOrder, actualOrder);
        var abi = StdioHostAbi.load(); var expectedSize = Map.of("shrink", 3L, "same", 6L, "extend", 9L, "negative", 6L, "readonly", 6L, "invalid", -1L, "pipe", -1L);
        for (var row : oracle) {
            var scenario = (String) row.get("scenario");
            long expected = Objects.equals(row.get("entry"), "originalTruncate") ? (List.of("shrink", "same", "extend").contains(scenario) ? 0L : -1L) : switch (scenario) {
                case "negative", "readonly", "pipe" -> abi.error(5); case "invalid" -> abi.error(4); default -> -2L;
            };
            assertEquals(expected, row.get("result"), row.toString()); assertEquals(expectedSize.get(scenario), row.get("size"), row.toString());
            assertEquals(List.of("invalid", "pipe").contains(scenario) ? -1L : 4L, row.get("position"), row.toString());
        }
        for (var stage : List.of("pre", "post")) {
            var modules = new ArrayList<Map<String, Object>>(); for (var part : List.of("OriginalStdioTruncateAudit", "THC.InterfaceClosure")) modules.add(cbd(new File(fixture, stage + "/core/" + part + ".cbd")));
            var module = CoreModules.merge(modules);
            var report = json(new File(fixture, stage + "/audit.json"));
            assertEquals(true, report.get("accepted")); assertEquals(List.of(), report.get("issues"));
            assertEquals(List.of(), report.get("missingGlobals"));
            for (var backend : List.of("ast", "bytecode")) {
                var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
                try (var context = Context.newBuilder("thc").allowIO(IOAccess.ALL).in(new ByteArrayInputStream(new byte[0])).out(output).err(errors)
                    .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        for (var name : names) {
                            var linked = with(CoreModules.reachable(module, entryId(name)), "instrument", true);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); var entry = program.entryTarget(entryId(name));
                            exercise(false, stage, backend, name, program, entry, oracle, expectedSize); var active = targets(entry);
                            for (var target : active) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
                            exercise(true, stage, backend, name, program, entry, oracle, expectedSize);
                            for (var target : active) valid(target); assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        }
                        assertEquals(0, output.size()); assertEquals(0, errors.size()); var handoff = language.getHandoffState().get();
                        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                    } finally { context.leave(); }
                }
            }
        }
    }
}
