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

/** The installed GHC c_close FCall retires a context descriptor on both backends. */
@SuppressWarnings("unchecked")
class OriginalStdioCloseNativeTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File fixture = new File(root, "build/original-stdio-close");
    private final List<String> names = List.of("originalClose", "originalCloseErrno");
    private Map<String, Object> json(File path) throws IOException { return (Map<String, Object>) Json.parse(Files.readString(path.toPath())); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void exercise(boolean compiled, String stage, String backend, String name, ExecutableProgram program,
            RootCallTarget entry, List<Map<String, Object>> oracle) throws Exception {
        for (var scenario : List.of("valid", "invalid")) {
            var privateFile = directory.resolve(stage + "-" + backend + "-" + name + "-" + scenario + "-" + (compiled ? "compiled" : "warm"));
            Files.writeString(privateFile, "keep"); var files = Language.currentState(null).getFiles();
            long fd = -1;
            if (scenario.equals("valid")) { fd = files.open(ManagedAddress.fromByteArray((privateFile + "\0").getBytes(StandardCharsets.UTF_8)), 0L, ForeignSafety.UNSAFE); assertTrue(fd >= 3L); }
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var actual = Calls.target(entry, new Object[] {0L, fd});
            var expected = single(oracle, row -> Objects.equals(row.get("entry"), name) && Objects.equals(row.get("scenario"), scenario)).get("result");
            assertEquals(expected, actual, stage + "/" + backend + "/" + name + "/" + scenario);
            if (scenario.equals("valid")) { assertEquals(-1L, files.size(fd), "the original close retired the managed descriptor"); assertEquals("keep", Files.readString(privateFile)); }
            if (compiled) assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
        }
    }
    @Test void originalCloseAndErrnoMatchNativeBeforeAndAfterCompilation() throws Exception {
        var manifest = json(new File(fixture, "manifest.json")); assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(names, manifest.get("entries")); assertEquals(4L, manifest.get("nativeRows"));
        hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalStdioCloseAudit.hs", "t/fixtures/compiler/OriginalStdioCloseAuditNative.hs",
            "t/haskell-fixtures/OriginalStdioCloseFixtures.hs", "bin/core_original_foreign.py"));
        hashes(root, manifest.get("artifactHashes"), Set.of("build/original-stdio-close/oracle.json", "build/original-stdio-close/pre/core/OriginalStdioCloseAudit.json",
            "build/original-stdio-close/post/core/OriginalStdioCloseAudit.json"), "build/original-stdio-close/");
        var oracle = (List<Map<String, Object>>) Json.parse(Files.readString(new File(fixture, "oracle.json").toPath()));
        var expectedOrder = new ArrayList<List<String>>(); for (var name : names) for (var scenario : List.of("valid", "invalid")) expectedOrder.add(List.of(name, scenario));
        var actualOrder = new ArrayList<List<Object>>(); for (var row : oracle) actualOrder.add(list(row.get("entry"), row.get("scenario"))); assertEquals(expectedOrder, actualOrder);
        long badFd = StdioHostAbi.load().error(4);
        for (var row : oracle) {
            long expected = switch (row.get("entry") + "/" + row.get("scenario")) {
                case "originalClose/valid" -> 0L; case "originalClose/invalid" -> -1L;
                case "originalCloseErrno/valid" -> -2L; case "originalCloseErrno/invalid" -> badFd;
                default -> fail("unknown native observation: " + row);
            };
            assertEquals(expected, row.get("result")); assertEquals("", row.get("stdoutHex")); assertEquals("", row.get("stderrHex"));
        }
        for (var stage : List.of("pre", "post")) {
            var modules = new ArrayList<Map<String, Object>>(); for (var part : List.of("OriginalStdioCloseAudit", "THC.InterfaceClosure")) modules.add(json(new File(fixture, stage + "/core/" + part + ".json")));
            var module = CoreModules.merge(modules); var bindings = (List<Map<String, Object>>) module.get("bindings");
            for (var name : names) {
                var binding = single(bindings, item -> Objects.equals(item.get("id"), "main:OriginalStdioCloseAudit." + name)); var calls = foreignCalls(binding.get("expr"));
                assertEquals(name.endsWith("Errno") ? 2 : 1, calls.size()); var symbols = new ArrayList<String>();
                for (var app : calls) {
                    var head = (List<Object>) app.get(1); CoreOriginalStdio.validateHead(head, false); var reps = new ArrayList<Object>();
                    for (var arg : (List<?>) app.get(2)) reps.add(((Map<?, ?>) ((List<?>) arg).getLast()).get("rep"));
                    symbols.add(Objects.requireNonNull(CoreOriginalStdio.validate(app.get(6), reps, (List<?>) app.get(3), ((Map<?, ?>) app.get(6)).get("rep"))).getSymbol());
                }
                var expected = new ArrayList<>(List.of("close")); if (name.endsWith("Errno")) expected.add("__hscore_get_errno"); assertEquals(expected, symbols);
                var rawSymbols = new ArrayList<String>();
                for (var app : calls) rawSymbols.add((String) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) app.get(6)).get("foreignCall")).get("target")).get("symbol"));
                audit(json(new File(fixture, stage + "/" + name + ".audit.json")), "main:OriginalStdioCloseAudit." + name, rawSymbols);
            }
            for (var backend : List.of("ast", "bytecode")) {
                var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
                try (var context = Context.newBuilder("thc").allowIO(IOAccess.ALL).out(output).err(errors).allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        for (var name : names) {
                            var linked = with(CoreModules.reachable(module, name), "instrument", true);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); var entry = program.entryTarget(name);
                            exercise(false, stage, backend, name, program, entry, oracle); var active = targets(entry);
                            assertEquals(1, active.size(), "entry contains the inlined runRW State body");
                            for (var target : active) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
                            exercise(true, stage, backend, name, program, entry, oracle); assertEquals(active, targets(entry)); for (var target : active) valid(target);
                            assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        }
                        assertEquals(0, output.size()); assertEquals(0, errors.size()); var handoff = language.getHandoffState().get();
                        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                    } finally { context.leave(); }
                }
            }
        }
    }
}
