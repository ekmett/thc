// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

class ManagedForeignRootsNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path directory = root.resolve("build/interface-core");
    private final Path core = directory.resolve("typed-foreign-exports/registration.cbd");
    private void verifyFile(Path file) throws Exception {
        var manifest = object(Json.parse(Files.readString(directory.resolve("manifest.json"))));
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        assertEquals(object(manifest.get("artifactHashes")).get(root.relativize(file).toString()), digest);
    }
    private String verified(Path file) throws Exception { verifyFile(file); return Files.readString(file); }
    private Map<String, Object> original() throws Exception {
        assertFalse(Files.exists(directory.resolve("typed-export-source/ForeignExportRegistration.hs")));
        assertEquals("7", verified(directory.resolve("logs/managed-export-registration-native-oracle.stdout")).trim()); verifyFile(core); return CoreCbdFixtures.read(core);
    }
    private String id(String name) { return "thc-interface-fixture-0.1:ForeignExportRegistration." + name; }
    private String request(String backend) {
        var request = object(Json.parse(CoreModules.request(list(core.toString()), id("probe"), true, false, backend)));
        request.put("strictLink", true); return Json.stringify(request);
    }
    private ManagedForeignRoots registry() { return Language.currentState(null).getForeignRoots(); }
    private ExecutableProgram single(ManagedForeignRoots registry) { var programs = registry.programs(); assertEquals(1, programs.size()); return programs.getFirst(); }
    @Test void retainedOriginalCafIsNotEvaluatedAndFirstInstalledKernelStillRuns() throws Exception {
        for (String backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", "false").option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            var module = original(); var entry = context.eval("thc", request(backend)); context.enter();
            try {
                var registry = registry(); var program = single(registry); var roots = Objects.requireNonNull(registry.retained(program));
                var declarations = objects(object(module.get("staticForeignExports")).get("exports"));
                var bottoms = declarations.stream().filter(d -> "thc_registration_bottom".equals(d.get("symbol"))).toList(); assertEquals(1, bottoms.size());
                var bottom = object(bottoms.getFirst().get("binder")); var id = bottom.get("unit") + ":" + bottom.get("module") + "." + bottom.get("occurrence");
                var thunk = (Thunk) Objects.requireNonNull(roots.get(id)); assertEquals(0, thunk.getState()); assertSame(program.entryValue(id), thunk); assertEquals(2, roots.size());
                assertTrue(context.getBindings("thc").getMemberKeys().isEmpty(), "Registration is not a callable namespace");
                for (int i = 0; i < 4; i++) assertEquals(7L, entry.execute(7).asLong());
                var target = program.entryTarget(id("probe")); var cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); cls.getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, cls.getMethod("isValidLastTier").invoke(target)); var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", cls).invoke(runtime, target);
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); assertEquals(19L, entry.execute(19).asLong());
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before); assertEquals(0, thunk.getState(), "Neither registration nor a different kernel may force the exported CAF");
            } finally { context.leave(); }
        }
    }
    @Test void sharedParseRootsHaveContextOwnedProgramsAndReleaseOnDisposal() throws Exception {
        var original = original();
        for (String backend : list("ast", "bytecode")) try (var engine = Engine.create()) {
            var first = Context.newBuilder("thc").engine(engine).build(); var second = Context.newBuilder("thc").engine(engine).build();
            try {
                var source = Source.newBuilder("thc", request(backend), "registration").cached(true).buildLiteral(); assertEquals(3L, first.eval(source).execute(3).asLong());
                ManagedForeignRoots registry; ExecutableProgram program; first.enter();
                try { registry = registry(); program = single(registry); } finally { first.leave(); }
                assertEquals(5L, second.eval(source).execute(5).asLong()); second.enter();
                try { assertNotSame(program, single(registry())); assertThrows(RuntimeFault.class, () -> registry.retain(program, list())); } finally { second.leave(); }
                first.close(); assertEquals(0, registry.size()); assertNull(registry.retained(program));
                second.enter(); try { assertEquals(1, registry().size()); } finally { second.leave(); }
            } finally { first.close(); second.close(); }
        }
    }
    @Test void originalReimportAndOtherNativeCallsRemainUnsupported() throws Exception {
        var source = original();
        for (String backend : list("ast", "bytecode")) try (var context = Context.create("thc")) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var item : List.of(Map.entry("callbackAgain", "thc_registration_entry"), Map.entry("unsupported", "abort"))) {
                    var entries = item.getKey().equals("callbackAgain") ? List.of(id(item.getKey()), id("unsupported")) : List.of(id(item.getKey()));
                    var linked = CoreModules.reachable(CoreModules.merge(list(source)), entries, true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, false, false) : new BytecodeProgram(language, linked);
                    var stable = Language.currentState(null).getStablePointers();
                    var pointer = item.getKey().equals("callbackAgain") ? stable.make(program.entryValue(id("unsupported"))) : null;
                    try {
                        var arguments = pointer == null ? new Object[]{0L, Unit.INSTANCE} :
                            new Object[]{0L, program.constructorLayout("ghc-internal:GHC.Internal.Stable.StablePtr").create(new Object[]{pointer}), Unit.INSTANCE};
                        var error = assertThrows(UnsupportedCore.class, () -> Calls.target(program.entryTarget(id(item.getKey())), arguments));
                        assertEquals("Unsupported foreign call: " + item.getValue(), error.getMessage());
                    } finally { if (pointer != null) stable.free(pointer); }
                    var handoff = language.getHandoffState().get();
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences());
                    assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                }
                assertEquals(0, registry().size());
            } finally { context.leave(); }
        }
    }
    private Map<String, Object> audit(String entry) throws Exception {
        var output = directory.resolve("registration-runtime-audit.json");
        var process = new ProcessBuilder("python3", root.resolve("bin/audit-core.py").toString(), core.toString(), "--entry", id(entry), "--output", output.toString()).directory(root.toFile()).redirectErrorStream(true).start();
        var log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(entry.equals("probe") ? 0 : 1, process.waitFor(), log);
        return object(Json.parse(Files.readString(output)));
    }
    @Test void strictAuditorChecksRetainedBodiesAndDoesNotEnableNativeCalls() throws Exception {
        original(); var good = audit("probe"); assertEquals(true, good.get("accepted")); assertEquals(2, ((List<?>) good.get("retainedExports")).size());
        for (String entry : list("callbackAgain", "unsupported")) assertTrue(objects(audit(entry).get("issues")).stream().anyMatch(issue -> "foreign-call".equals(issue.get("code"))));
    }
}
