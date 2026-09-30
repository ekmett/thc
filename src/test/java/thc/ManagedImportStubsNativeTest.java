// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

class ManagedImportStubsNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path directory = root.resolve("build/interface-core");
    private Map<String, Object> original() throws Exception { return original("plain"); }
    private Map<String, Object> original(String variant) throws Exception {
        var manifest = object(Json.parse(Files.readString(directory.resolve("manifest.json"))));
        for (String kind : list("inputHashes", "artifactHashes")) for (var item : object(manifest.get(kind)).entrySet()) {
            var file = root.resolve(item.getKey()); assertTrue(file.toFile().getCanonicalFile().toPath().startsWith(root.toFile().getCanonicalFile().toPath()));
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); assertEquals(item.getValue(), actual, kind + "/" + item.getKey());
        }
        assertFalse(Files.exists(directory.resolve("source/ForeignImportStubs.hs")));
        assertEquals("(12,8,9,0,11)", Files.readString(directory.resolve("logs/import-stubs-native-oracle.stdout")).trim());
        var core = artifact(variant); CoreCbdFixtures.pairedDiagnostic(core); return CoreCbdFixtures.read(core);
    }
    private String id(String name) { return "thc-interface-fixture-0.1:ForeignImportStubs." + name; }
    private Path artifact(String variant) { return directory.resolve("import-stubs/" + variant + ".cbd"); }
    private String request(String backend) {
        var request = object(Json.parse(CoreModules.request(list(artifact("plain").toString()), id("probe"), true, false, backend)));
        request.put("strictLink", true); return Json.stringify(request);
    }
    @Test void unchangedOriginalImportsAdmitPureCoreAndFirstInstalledEntryOnBothBackends() throws Exception {
        var module = original(); assertNotNull(ManagedImportAdmission.read(module, true)); assertNull(CoreForeignArtifacts.linked(module, true));
        assertEquals("not-linked", object(module.get("foreign")).get("execution"));
        for (String backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", "false").option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            var entry = context.eval("thc", request(backend)); assertEquals(12L, entry.execute(5L).asLong()); assertTrue(entry.invokeMember("compile").asBoolean());
            long before = (Long) object(Json.parse(entry.getMember("diagnostics").asString())).get("compiledEntries");
            assertEquals(26L, entry.execute(19L).asLong()); assertTrue((Long) object(Json.parse(entry.getMember("diagnostics").asString())).get("compiledEntries") > before);
            assertTrue(context.getBindings("thc").getMemberKeys().isEmpty(), "C imports do not create host exports");
        }
    }
    @Test void unknownCallsStillFailWhenReachedAfterManagedArchiveAdmission() throws Exception {
        var module = original(); var imports = objects(object(module.get("staticForeignImportStubs")).get("imports"));
        for (String backend : list("ast", "bytecode")) try (var context = Context.create("thc")) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (String name : list("first", "second", "direct")) {
                    var declarations = imports.stream().filter(d -> name.equals(object(d.get("binder")).get("occurrence"))).toList(); assertEquals(1, declarations.size());
                    var symbol = (String) object(declarations.getFirst().get("emitted")).get("symbol");
                    var linked = CoreModules.reachable(CoreModules.merge(list(module)), id(name), true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, false, false) : new BytecodeProgram(language, linked);
                    // CInt/CLong wrappers in the original Core consume Int32/Int64 constructors.
                    var argument = name.equals("direct")
                        ? program.constructorLayout("ghc-internal:GHC.Internal.Int.I64#").createLong(-3L)
                        : program.constructorLayout("ghc-internal:GHC.Internal.Int.I32#").createInt(-3);
                    var target = program.entryTarget(id(name));
                    assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                    var error = assertThrows(UnsupportedCore.class, () -> Calls.target(target, new Object[]{0L, argument, Unit.INSTANCE}));
                    assertEquals("Unsupported foreign call: " + symbol, error.getMessage());
                    assertEquals(1L, program.diagnostics().get("unsupportedTraps"));
                    var handoff = language.getHandoffState().get();
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences());
                    assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                }
            } finally { context.leave(); }
        }
    }
    @Test void WholeProductAndTypedCallChangesNeverAcquireAdmission() throws Exception {
        var module = original(); var proof = object(module.get("staticForeignImportStubs")); var foreign = object(module.get("foreign")); var stubs = object(foreign.get("stubs"));
        var label = map("isInitializer", true, "unit", module.get("unit"), "module", module.get("module"), "name", "extra");
        var products = list(with(foreign, "stubs", with(stubs, "source", stubs.get("source").toString() + "extra")), with(foreign, "stubs", with(stubs, "header", "extra")),
            with(foreign, "stubs", with(stubs, "initializers", list(label))), with(foreign, "stubs", with(stubs, "finalizers", list(with(label, "isInitializer", false)))),
            with(foreign, "files", list(map("language", "LangC", "source", "extra", "extension", ".c"))));
        for (var changed : products) assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.requireExecutable(with(module, "foreign", changed)));
        for (var changed : products.subList(1, products.size())) assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.requireExecutable(with(module, "foreign", changed,
            "staticForeignImportStubs", with(proof, "expectedForeign", changed))));
        var imports = objects(proof.get("imports")); var capi = imports.stream().filter(d -> "capi".equals(d.get("convention"))).findFirst().orElseThrow(); var emitted = object(capi.get("emitted"));
        var missing = new LinkedHashMap<>(module); missing.remove("staticForeignImportStubs");
        var badModules = list(missing, with(module, "staticForeignImportStubs", with(proof, "profile", "unknown")), with(module, "staticForeignImportStubs", with(proof, "expectedCalls", list())),
            with(module, "staticForeignImportStubs", with(proof, "imports", imports.stream().map(d -> d == capi ? with(d, "emitted", with(emitted, "result", list("void", "DoubleRep"))) : d).toList())), with(module, "schema", 1L));
        for (var bad : badModules) assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.requireExecutable(bad));
        for (String variant : list("extra-file", "instrumented")) {
            var rejected = original(variant); assertNull(ManagedImportAdmission.read(rejected, true)); assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.requireExecutable(rejected));
        }
        var wrapper = original("wrapper");
        assertNotNull(ManagedImportAdmission.read(wrapper, true)); CoreForeignArtifacts.requireExecutable(wrapper);
        var wrapperProof = object(wrapper.get("staticForeignImportStubs"));
        var callbacks = ManagedCallbackMetadata.read(wrapper, wrapperProof); assertEquals(1, callbacks.size());
        var signature = callbacks.getFirst().signature();
        assertEquals(id("callback"), signature.binder()); assertTrue(signature.io());
        var scalar = map("kind", "tycon", "name", map("unit", "ghc-internal", "module", "GHC.Internal.Int", "occurrence", "Int32", "namespace", "type"), "arguments", list());
        assertEquals(list(scalar), signature.arguments()); assertEquals(scalar, signature.result());
        var declaration = objects(wrapperProof.get("wrappers")).getFirst();
        for (var changed : list(with(declaration, "arguments", list()), with(declaration, "result", with(scalar, "name", with(object(scalar.get("name")), "occurrence", "Int64"))))) {
            var malformed = with(wrapper, "staticForeignImportStubs", with(wrapperProof, "wrappers", list(changed)));
            assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.requireExecutable(malformed));
        }
        var malformedHelper = with(wrapper, "staticForeignImportStubs", with(wrapperProof, "wrappers", list(with(declaration, "helper", declaration.get("helper") + "changed"))));
        for (String backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var good = CoreModules.reachable(CoreModules.merge(list(wrapper)), id("callback"), true);
                if (backend.equals("ast")) new Program(language, good, false, false); else new BytecodeProgram(language, good);
                var bad = CoreModules.reachable(CoreModules.merge(list(malformedHelper)), id("callback"), true);
                var error = assertThrows(RuntimeFault.class, () -> { if (backend.equals("ast")) new Program(language, bad, false, false); else new BytecodeProgram(language, bad); });
                assertEquals("Unlinked native data label: " + declaration.get("helper"), error.getMessage());
            } finally { context.leave(); }
        }
    }
    private Map<String, Object> audit(String variant, String entry, int status) throws Exception {
        var output = directory.resolve("import-stubs-runtime-audit.json");
        var process = new ProcessBuilder("python3", root.resolve("bin/audit-core.py").toString(), artifact(variant).toString(),
            "--entry", id(entry), "--output", output.toString()).directory(root.toFile()).redirectErrorStream(true).start();
        var log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(status, process.waitFor(), log);
        return object(Json.parse(Files.readString(output)));
    }
    @Test void strictAuditorMatchesTheOriginalArchiveAndCallBoundary() throws Exception {
        original(); assertEquals(true, audit("plain", "probe", 0).get("accepted"));
        for (String entry : list("first", "second", "direct")) assertTrue(objects(audit("plain", entry, 1).get("issues")).stream().anyMatch(issue -> "foreign-call".equals(issue.get("code"))));
        for (String variant : list("extra-file", "instrumented")) assertEquals(false, audit(variant, "probe", 1).get("accepted"));
        assertEquals(true, audit("wrapper", "probe", 0).get("accepted"));
        assertEquals(true, audit("wrapper", "callback", 0).get("accepted"));
    }
}
