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
    private final Path core = directory.resolve("typed-foreign-exports/registration.json");
    private String verified(Path file) throws Exception {
        var manifest = object(Json.INSTANCE.parse(Files.readString(directory.resolve("manifest.json"))));
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        assertEquals(object(manifest.get("artifactHashes")).get(root.relativize(file).toString()), digest); return Files.readString(file);
    }
    private Map<String, Object> original() throws Exception {
        assertFalse(Files.exists(directory.resolve("typed-export-source/ForeignExportRegistration.hs")));
        assertEquals("7", verified(directory.resolve("logs/managed-export-registration-native-oracle.stdout")).trim()); return object(Json.INSTANCE.parse(verified(core)));
    }
    private String request(Map<String, Object> original, String backend) { return Json.INSTANCE.stringify(map("modules", list(original), "entry", "probe", "strictLink", true, "backend", backend)); }
    private ManagedForeignRoots registry() { return Language.currentState(null).getForeignRoots$org_intelligence_thc(); }
    private ExecutableProgram single(ManagedForeignRoots registry) { var programs = registry.programs$org_intelligence_thc(); assertEquals(1, programs.size()); return programs.getFirst(); }
    @Test void retainedOriginalCafIsNotEvaluatedAndFirstInstalledKernelStillRuns() throws Exception {
        for (String backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", "false").option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            var module = original(); var entry = context.eval("thc", request(module, backend)); context.enter();
            try {
                var registry = registry(); var program = single(registry); var roots = Objects.requireNonNull(registry.retained$org_intelligence_thc(program));
                var declarations = objects(object(module.get("staticForeignExports")).get("exports"));
                var bottoms = declarations.stream().filter(d -> "thc_registration_bottom".equals(d.get("symbol"))).toList(); assertEquals(1, bottoms.size());
                var bottom = object(bottoms.getFirst().get("binder")); var id = bottom.get("unit") + ":" + bottom.get("module") + "." + bottom.get("occurrence");
                var thunk = (Thunk) Objects.requireNonNull(roots.get(id)); assertEquals(0, thunk.getState()); assertSame(program.entryValue(id), thunk); assertEquals(2, roots.size());
                assertTrue(context.getBindings("thc").getMemberKeys().isEmpty(), "Registration is not a callable namespace");
                for (int i = 0; i < 4; i++) assertEquals(7L, entry.execute(7).asLong());
                var target = program.entryTarget("probe"); var cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); cls.getMethod("compile", boolean.class).invoke(target, true);
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
                var source = Source.newBuilder("thc", request(original, backend), "registration").cached(true).buildLiteral(); assertEquals(3L, first.eval(source).execute(3).asLong());
                ManagedForeignRoots registry; ExecutableProgram program; first.enter();
                try { registry = registry(); program = single(registry); } finally { first.leave(); }
                assertEquals(5L, second.eval(source).execute(5).asLong()); second.enter();
                try { assertNotSame(program, single(registry())); assertThrows(RuntimeFault.class, () -> registry.retain(program, list())); } finally { second.leave(); }
                first.close(); assertEquals(0, registry.size$org_intelligence_thc()); assertNull(registry.retained$org_intelligence_thc(program));
                second.enter(); try { assertEquals(1, registry().size$org_intelligence_thc()); } finally { second.leave(); }
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
                    var linked = CoreModules.INSTANCE.reachable(CoreModules.INSTANCE.merge(list(source)), item.getKey(), true);
                    var error = assertThrows(RuntimeException.class, () -> { if (backend.equals("ast")) new Program(language, linked, false, false); else new BytecodeProgram(language, linked); });
                    assertTrue(Objects.toString(error.getMessage(), "").contains(item.getValue()), error.getMessage());
                }
                assertEquals(0, registry().size$org_intelligence_thc());
            } finally { context.leave(); }
        }
    }
    private Map<String, Object> audit(String entry) throws Exception {
        var output = directory.resolve("registration-runtime-audit.json");
        var process = new ProcessBuilder("python3", root.resolve("scripts/audit-core.py").toString(), core.toString(), "--entry", entry, "--output", output.toString()).directory(root.toFile()).redirectErrorStream(true).start();
        var log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(entry.equals("probe") ? 0 : 1, process.waitFor(), log);
        return object(Json.INSTANCE.parse(Files.readString(output)));
    }
    @Test void strictAuditorChecksRetainedBodiesAndDoesNotEnableNativeCalls() throws Exception {
        original(); var good = audit("probe"); assertEquals(true, good.get("accepted")); assertEquals(2, ((List<?>) good.get("retainedExports")).size());
        for (String entry : list("callbackAgain", "unsupported")) assertTrue(objects(audit(entry).get("issues")).stream().anyMatch(issue -> "foreign-call".equals(issue.get("code"))));
    }
}
