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

/** Installed GHC c_lseek metadata and private-file positions, including ESPIPE. */
@SuppressWarnings("unchecked")
class OriginalStdioSeekNativeTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File fixture = new File(root, "build/original-stdio-seek");
    private final List<String> names = List.of("originalSeek", "originalSeekErrno");
    private final List<String> scenarios = List.of("set", "cur", "end", "beyond", "wide", "negative", "bad-whence", "invalid", "pipe", "eof", "tell", "closed");
    private Map<String, Object> json(File path) throws IOException { return (Map<String, Object>) Json.parse(Files.readString(path.toPath())); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void exercise(boolean compiled, String stage, String backend, boolean inlining, String name, ExecutableProgram program,
            RootCallTarget entry, List<Map<String, Object>> oracle, StdioHostAbi abi, Map<String, Long> expectedPosition) throws Exception {
        for (var scenario : scenarios) {
            var row = single(oracle, item -> Objects.equals(item.get("entry"), name) && Objects.equals(item.get("scenario"), scenario));
            var privateFile = directory.resolve(stage + "-" + backend + "-" + name + "-" + scenario + "-" + compiled); Files.writeString(privateFile, "abcdef");
            var files = Language.currentState(null).getFiles(); var stdio = Language.currentState(null).getStdio();
            long fd;
            switch (scenario) {
                case "invalid" -> fd = -1;
                case "pipe" -> fd = 0; // The embedding input is also nonseekable.
                default -> { fd = files.open(ManagedAddress.fromByteArray((privateFile + "\0").getBytes(StandardCharsets.UTF_8)), 0, ForeignSafety.UNSAFE); assertTrue(fd >= 3); }
            }
            if (scenario.equals("closed")) assertEquals(0L, files.close(fd, ForeignSafety.UNSAFE));
            if (List.of("cur", "tell").contains(scenario)) assertEquals(3L, files.seek(fd, 3, 0, ForeignSafety.UNSAFE));
            // A successful constant lookup/seek must not erase an old C errno.
            assertEquals(-1L, stdio.close(-1)); assertEquals(abi.error(4), stdio.errno());
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            assertEquals(row.get("result"), Calls.target(entry, new Object[] {0L, fd, row.get("displacement"), row.get("whence")}), stage + "/" + backend + "/" + inlining + "/" + name + "/" + scenario);
            if (expectedPosition.containsKey(scenario)) assertEquals(abi.error(4), stdio.errno());
            if (fd >= 3 && !scenario.equals("closed")) {
                assertEquals(6L, files.size(fd)); assertEquals("abcdef", Files.readString(privateFile)); assertEquals(0L, files.close(fd, ForeignSafety.UNSAFE));
            }
            if (compiled) { assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue()); for (var target : targets(entry)) valid(target); }
        }
    }
    @Test void originalSeekMatchesNativeAndCompiledTargets() throws Exception {
        var manifest = json(new File(fixture, "manifest.json")); assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(names, manifest.get("entries")); assertEquals(24L, manifest.get("nativeRows"));
        hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalStdioSeekAudit.hs", "t/fixtures/compiler/OriginalStdioSeekAuditNative.hs",
            "t/haskell-fixtures/OriginalStdioSeekFixtures.hs", "bin/core_original_foreign.py"));
        hashes(root, manifest.get("artifactHashes"), Set.of("build/original-stdio-seek/oracle.json", "build/original-stdio-seek/pre/core/OriginalStdioSeekAudit.json",
            "build/original-stdio-seek/post/core/OriginalStdioSeekAudit.json"), "build/original-stdio-seek/");
        var oracle = (List<Map<String, Object>>) Json.parse(Files.readString(new File(fixture, "oracle.json").toPath()));
        var expectedOrder = new ArrayList<List<String>>(); for (var name : names) for (var scenario : scenarios) expectedOrder.add(List.of(name, scenario));
        var actualOrder = new ArrayList<List<Object>>(); for (var row : oracle) actualOrder.add(list(row.get("entry"), row.get("scenario"))); assertEquals(expectedOrder, actualOrder);
        var abi = StdioHostAbi.load(); var constants = new LinkedHashMap<String, Long>();
        for (var op : List.of(OriginalStdioOp.SEEK_SET, OriginalStdioOp.SEEK_CUR, OriginalStdioOp.SEEK_END)) constants.put(op.name(), abi.seekConstant(op));
        assertEquals(constants, manifest.get("nativeConstants"));
        var expectedPosition = Map.of("set", 2L, "cur", 5L, "end", 4L, "beyond", 10L, "wide", 8589934597L, "eof", 6L, "tell", 3L);
        for (var row : oracle) {
            var scenario = (String) row.get("scenario");
            long expected = Objects.equals(row.get("entry"), "originalSeek") ? expectedPosition.getOrDefault(scenario, -1L) : switch (scenario) {
                case "negative", "bad-whence" -> abi.error(5); case "invalid", "closed" -> abi.error(4); case "pipe" -> abi.notSeekable(); default -> -2L;
            };
            assertEquals(expected, row.get("result"), row.toString()); assertEquals("", row.get("stdoutHex")); assertEquals("", row.get("stderrHex"));
        }
        for (var stage : List.of("pre", "post")) {
            var modules = new ArrayList<Map<String, Object>>(); for (var part : List.of("OriginalStdioSeekAudit", "THC.InterfaceClosure")) modules.add(json(new File(fixture, stage + "/core/" + part + ".json")));
            var module = CoreModules.merge(modules); var bindings = (List<Map<String, Object>>) module.get("bindings");
            for (var name : names) {
                var owner = "main:OriginalStdioSeekAudit." + name; var binding = single(bindings, item -> Objects.equals(item.get("id"), owner));
                var calls = foreignCalls(binding.get("expr")); assertEquals(name.endsWith("Errno") ? 5 : 4, calls.size()); var symbols = new ArrayList<String>();
                for (var app : calls) {
                    var head = (List<Object>) app.get(1); CoreOriginalStdio.validateHead(head, false); var reps = new ArrayList<Object>();
                    for (var arg : (List<?>) app.get(2)) reps.add(((Map<?, ?>) ((List<?>) arg).getLast()).get("rep"));
                    symbols.add(Objects.requireNonNull(CoreOriginalStdio.validate(app.get(6), reps, (List<?>) app.get(3), ((Map<?, ?>) app.get(6)).get("rep"))).getSymbol());
                }
                var expected = new ArrayList<>(List.of(OriginalStdioOp.SEEK.getSymbol(), OriginalStdioOp.SEEK_SET.getSymbol(), OriginalStdioOp.SEEK_CUR.getSymbol(), OriginalStdioOp.SEEK_END.getSymbol()));
                if (name.endsWith("Errno")) expected.add("__hscore_get_errno"); var sorted = new ArrayList<>(symbols); Collections.sort(expected); Collections.sort(sorted);
                assertEquals(expected, sorted); audit(json(new File(fixture, stage + "/" + name + ".audit.json")), owner, symbols);
            }
            for (var backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[] {false, true}) {
                var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
                try (var context = Context.newBuilder("thc").allowIO(IOAccess.ALL).in(new ByteArrayInputStream(new byte[0])).out(output).err(errors)
                    .allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
                    .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        for (var name : names) {
                            var linked = with(CoreModules.reachable(module, name), "instrument", true);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); var entry = program.entryTarget(name);
                            exercise(false, stage, backend, inlining, name, program, entry, oracle, abi, expectedPosition); var active = targets(entry);
                            assertEquals(1, active.size(), "entry contains the inlined runRW State body");
                            for (var target : active) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
                            exercise(true, stage, backend, inlining, name, program, entry, oracle, abi, expectedPosition);
                            assertEquals(active, targets(entry)); for (var target : active) valid(target); assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        }
                        assertEquals(0, output.size()); assertEquals(0, errors.size()); var handoff = language.getHandoffState().get();
                        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                    } finally { context.leave(); }
                }
            }
        }
    }
}
