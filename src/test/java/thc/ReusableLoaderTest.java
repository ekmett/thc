// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Model dependency controls; the original IO/FFI fixture qualifies execution. */
@SuppressWarnings("unchecked")
class ReusableLoaderTest {
    @TempDir Path directory;
    @AfterEach void releaseMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private Map<String,Object> binding(String id, Object body) {
        return map("id", id, "name", id.substring(id.lastIndexOf('.') + 1), "lifted", true, "arity", 0, "expr", body);
    }
    private List<Object> literal(int value) { return list("lit", "int", Integer.toString(value)); }
    private Map<String,Object> unit(String unit, String module, List<Map<String,Object>> bindings) throws Exception {
        var metadata = map("schema", 1, "ghc", "9.14.1", "unit", unit, "module", module,
            "boundary", "optimized-Core-after-Tidy-before-CorePrep", "constructors", List.of());
        String prefix = Json.stringify(metadata); prefix = prefix.substring(0, prefix.length() - 1) + ",\"bindings\":[";
        var json = new StringBuilder(prefix); var symbols = new StringBuilder();
        for (var binding : bindings) {
            if (json.length() != prefix.length()) json.append(',');
            symbols.append(binding.get("id")).append(' ').append(json.toString().getBytes(UTF_8).length).append('\n');
            json.append(Json.stringify(binding));
        }
        int bindingsEnd = json.toString().getBytes(UTF_8).length + 1; json.append("]}");
        byte[] original = json.toString().getBytes(UTF_8), header = Json.stringify(metadata).getBytes(UTF_8);
        byte[] bytes = (json + "\n" + Json.stringify(metadata)).getBytes(UTF_8), rows = symbols.toString().getBytes(UTF_8);
        Path source = directory.resolve(unit + ".jsons"), index = directory.resolve(unit + ".symbols");
        Files.write(source, bytes); Files.write(index, rows);
        return map("id", unit, "depends", List.of(), "json", map("path", source.toString(), "sha256", hash(bytes)),
            "symbols", map("path", index.toString(), "sha256", hash(rows)), "modules", list(map("name", module,
                "path", module + ".json", "sha256", hash(original), "boundary", metadata.get("boundary"),
                "start", 0, "end", original.length, "bindingsStart", prefix.getBytes(UTF_8).length - 1, "bindingsEnd", bindingsEnd,
                "metadataStart", original.length + 1, "metadataEnd", original.length + 1 + header.length,
                "containsDelimitedControl", false, "registrationObligations", false, "mainAlias", false, "packageScalarDeclarations", false)));
    }
    private Map<String,Object> input(List<Map<String,Object>> units, String shutdown) throws Exception {
        Path manifest = directory.resolve("packages.json");
        Files.writeString(manifest, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", units)));
        return (Map<String,Object>) Json.parse(CoreModules.request(List.of("@" + manifest), "app:Main.entry",
            true, false, "ast", false, shutdown != null, shutdown, false, false, true));
    }
    private Map<String,Object> detached(Map<String,Object> input) {
        return (Map<String,Object>) Json.parse(CoreModules.detachedRequest(input, "app:Main.entry"));
    }
    private Set<String> ids(Map<String,Object> detached) {
        var ids = new LinkedHashSet<String>();
        for (var module : (List<Map<String,Object>>) detached.get("modules"))
            for (var binding : (List<Map<String,Object>>) module.get("bindings")) ids.add((String) binding.get("id"));
        return ids;
    }
    @Test void detachedPackageIncludesShutdownWithoutSelectingUnrelatedBodies() throws Exception {
        var app = unit("app", "Main", List.of(binding("app:Main.entry", literal(7)), binding("app:Main.unused", list("unsupported", "untouched"))));
        var exit = unit("exit", "Shutdown", List.of(binding("exit:Shutdown.stop", literal(0))));
        var request = input(List.of(app, exit), "exit:Shutdown.stop");
        var result = detached(request);
        assertEquals(Set.of("app:Main.entry", "exit:Shutdown.stop"), ids(result));
        assertEquals("exit:Shutdown.stop", result.get("shutdownEntry"));
        assertFalse(result.containsKey("packageManifest")); assertFalse(result.containsKey("packageCapability"));
        assertEquals(Boolean.TRUE, result.get("detachedBindings"));
    }
    @Test void detachedPackageIncludesImplicitArithmeticPayload() throws Exception {
        String payload = thc.runtime.CoreArithmeticExceptions.payload("raiseDivZero#");
        var app = unit("app", "Main", List.of(binding("app:Main.entry", list("prim", "raiseDivZero#"))));
        var exception = unit("ghc-internal", "GHC.Internal.Exception.Type", List.of(binding(payload, literal(91))));
        assertEquals(Set.of("app:Main.entry", payload), ids(detached(input(List.of(app, exception), null))));
    }
    @Test void detachedPackageSkipsUndefinedOriginalForeignHead() throws Exception {
        var state = map("kind", "void", "primReps", List.of(), "evaluated", true);
        var address = map("kind", "address", "primReps", list("AddrRep"), "evaluated", true);
        var result = map("kind", "unknown", "primReps", List.of(), "evaluated", true, "aggregate", "unboxed-tuple", "components", list(state));
        var descriptor = map("schema", 1, "target", map("kind", "static", "symbol", "getProgArgv", "unit", "ghc-internal", "isFunction", true),
            "convention", "ccall", "safety", "unsafe", "arity", 3, "suppliedArity", 3,
            "argumentReps", list(with(address, "evaluated", false), with(address, "evaluated", false), with(state, "evaluated", false)),
            "resultRep", with(result, "evaluated", false));
        var nil = list("lit", "null-addr", "0", map("rep", address));
        var call = list("app", list("var", "ghc-internal:GHC.Internal.Environment.originalFCall"),
            list(nil, nil, list("void", map("rep", state))), list(false, false, false), false, false, map("rep", result, "foreignCall", descriptor));
        var app = unit("app", "Main", List.of(binding("app:Main.entry", call)));
        assertEquals(Set.of("app:Main.entry"), ids(detached(input(List.of(app), null))));
    }
    @Test void detachedPackageStillRejectsMissingOrdinaryGlobal() throws Exception {
        var app = unit("app", "Main", List.of(binding("app:Main.entry", list("var", "missing:Other.value"))));
        var request = input(List.of(app), null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> detached(request)).getMessage().contains("missing:Other.value"));
    }
    @Test void detachedPackageIncludesPlainAndIndexedConsumerDependencies() throws Exception {
        var dependency = unit("dependency", "Library", List.of(binding("dependency:Library.value", literal(17))));
        input(List.of(dependency), null);
        Path consumer = directory.resolve("Main.json");
        Files.writeString(consumer, Json.stringify(map("schema", 1, "ghc", "9.14.1", "unit", "app", "module", "Main",
            "boundary", "optimized-Core-after-Tidy-before-CorePrep", "constructors", list(),
            "bindings", list(binding("app:Main.entry", list("var", "dependency:Library.value"))))));
        for (boolean indexed : new boolean[]{false, true}) {
            var request = (Map<String,Object>) Json.parse(CoreModules.request(List.of(consumer.toString(), "@" + directory.resolve("packages.json")),
                "app:Main.entry", true, false, "ast", false, false, null, false, indexed, true));
            var result = detached(request);
            assertEquals(Set.of("app:Main.entry", "dependency:Library.value"), ids(result));
            assertFalse(result.containsKey("consumerModules")); assertFalse(result.containsKey("indexedModuleFiles"));
        }
    }
    private Map<String,Object> ioModule() {
        var state = map("kind", "void", "primReps", list(), "evaluated", true);
        var unit = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var closure = with(unit, "kind", "closure");
        var result = map("kind", "unknown", "aggregate", "unboxed-tuple", "components", list(state, unit),
            "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        String unitId = "ghc-internal:GHC.Internal.Tuple.()";
        var body = list("app", list("con", "StateUnit", 2), list(list("void", map("rep", state)), list("con", unitId, 0, map("rep", unit))),
            list(false, true), true, true, map("rep", result));
        var lambda = list("lam", list(map("id", "s", "name", "s", "type", "State# RealWorld", "lifted", false, "rep", state)),
            body, map("rep", closure, "resultRep", result));
        var entry = map("id", "entry", "name", "entry", "type", "IO ()", "arity", 1, "lifted", true, "rep", closure, "expr", lambda);
        var stop = with(entry, "id", "stop", "name", "stop");
        return map("schema", 1, "ghc", "9.14.1", "module", "PreparedIo", "bindings", list(entry, stop), "constructors",
            list(map("id", "StateUnit", "name", "StateUnit", "kind", "unboxed-tuple", "arity", 2),
                map("id", unitId, "name", "()", "kind", "boxed", "arity", 0, "strictFields", list(), "fieldLifted", list(), "fieldReps", list())));
    }
    @Test void preparedIoFactoryRetainsShutdownAndCreatesFreshEntryLifecycle() throws Exception {
        var module = ioModule();
        var source = Source.newBuilder("thc", Json.stringify(map("modules", list(module), "entry", "entry", "shutdownEntry", "stop",
            "ioMain", true, "backend", "ast", "asyncExceptions", false, "prepareCode", true)), "prepared-io").cached(true).build();
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) { preparation.parse(source); }
            for (int i = 0; i < 2; i++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                for (int load = 0; load < 2; load++) {
                    var action = context.parse(source).execute();
                    assertFalse(action.canExecute()); assertTrue(action.canInvokeMember("runIO"));
                    var before = (Map<?,?>) Json.parse(action.getMember("diagnostics").asString());
                    assertEquals(0L, before.get("loweredRootCount")); assertEquals(0L, before.get("compiledEntries"));
                    assertTrue(action.invokeMember("runIO").asBoolean());
                    assertTrue(assertThrows(PolyglotException.class, () -> action.invokeMember("runIO")).getMessage().contains("already started"));
                }
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"platform", "loom"})
    void cachedIoReturnChecksInstalledCodeAfterMainAndShutdown(String hosting) {
        String oldCached = System.getProperty("thc.requireCachedCode"), oldCompiled = System.getProperty("thc.requireCompiledCode");
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true).option("thc.ThreadHosting", hosting)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = ioModule();
                var definitions = new ArrayList<>((List<Map<String,Object>>) module.get("bindings"));
                definitions.add(with(definitions.getFirst(), "id", "other", "name", "other"));
                module.put("bindings", definitions);
                var code = thc.runtime.Program.prepareCode(language, module, List.of("entry", "stop", "other"));
                var program = code.newInstance(language);
                var entryTarget = (com.oracle.truffle.runtime.OptimizedCallTarget) program.entryTarget("entry");
                var stopTarget = (com.oracle.truffle.runtime.OptimizedCallTarget) program.entryTarget("stop");
                for (var target : List.of(entryTarget, stopTarget)) {
                    assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT()); target.compile(true);
                    assertTrue(target.isValidLastTier()); assertFalse(target.wasExecuted());
                }
                assertFalse(((com.oracle.truffle.runtime.OptimizedCallTarget) program.entryTarget("other")).isValidLastTier());
                var result = thc.runtime.CoreRepresentations.ioUnitMainResult(definitions.getFirst(), definitions);
                System.setProperty("thc.requireCachedCode", "true"); System.setProperty("thc.requireCompiledCode", "true");
                var raw = new EntryValue(program, "entry", 1, null, result, language, "stop", result, false, null, null, code);
                var action = context.asValue(raw);
                var failure = assertThrows(PolyglotException.class, () -> action.invokeMember("runIO"));
                assertTrue(failure.getMessage().contains("Cached compiled target required"), failure.getMessage());
                // Upstream wasExecuted tracks interpreter/tier counters, not last-tier calls.
                assertSame(entryTarget, program.entryTarget("entry")); assertSame(stopTarget, program.entryTarget("stop"));
                assertTrue(entryTarget.isValidLastTier()); assertTrue(stopTarget.isValidLastTier());
                assertEquals(2L, program.diagnostics().get("compiledEntries"), "both cold original IO targets execute compiled exactly once");
            } finally { context.leave(); }
        } finally {
            if (oldCached == null) System.clearProperty("thc.requireCachedCode"); else System.setProperty("thc.requireCachedCode", oldCached);
            if (oldCompiled == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", oldCompiled);
        }
    }
}
