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
    private List<Object> literal(int value) { return list("lit", "int", Integer.toString(value), map()); }
    private Map<String,Object> unit(String unit, String module, List<Map<String,Object>> bindings) throws Exception {
        var metadata = map("schema", 1, "ghc", "9.14.1", "unit", unit, "module", module,
            "boundary", "optimized-Core-after-Tidy-before-CorePrep", "constructors", List.of());
        metadata.put("bindings", bindings);
        var record = CoreCbdFixtures.module(directory.resolve(unit + ".cbd"), metadata);
        return map("id", unit, "depends", List.of(), "modules", list(record));
    }

    private Map<String,Object> input(List<Map<String,Object>> units, String shutdown) throws Exception {
        Path manifest = directory.resolve("packages.json");
        Files.writeString(manifest, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", units)));
        return (Map<String,Object>) Json.parse(CoreModules.request(List.of("@" + manifest), "app:Main.entry",
            true, false, "ast", false, shutdown != null, shutdown, false, true));
    }
    private Map<String,Object> detached(Map<String,Object> input) {
        return CoreModules.selectedModules(input, "app:Main.entry");
    }
    private Set<String> ids(Map<String,Object> detached) {
        var ids = new LinkedHashSet<String>();
        for (var module : (List<Map<String,Object>>) detached.get("modules"))
            for (var binding : (List<Map<String,Object>>) module.get("bindings")) ids.add((String) binding.get("id"));
        return ids;
    }
    @Test void detachedPackageIncludesShutdownWithoutSelectingUnrelatedBodies() throws Exception {
        var app = unit("app", "Main", List.of(binding("app:Main.entry", literal(7)), binding("app:Main.unused", list("unsupported", "untouched", map()))));
        var exit = unit("exit", "Shutdown", List.of(binding("exit:Shutdown.stop", literal(0))));
        var request = input(List.of(app, exit), "exit:Shutdown.stop");
        var result = detached(request);
        assertEquals(Set.of("app:Main.entry", "exit:Shutdown.stop"), ids(result));
        assertEquals("exit:Shutdown.stop", result.get("shutdownEntry"));
        assertFalse(result.containsKey("packageManifest")); assertFalse(result.containsKey("packageCapability"));
        assertFalse(result.containsKey("moduleFiles"));
    }
    @Test void detachedPackageIncludesImplicitArithmeticPayload() throws Exception {
        String payload = thc.runtime.CoreArithmeticExceptions.payload("raiseDivZero#");
        var app = unit("app", "Main", List.of(binding("app:Main.entry", list("prim", "raiseDivZero#", map()))));
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
        var call = list("app", list("var", "ghc-internal:GHC.Internal.Environment.originalFCall", map()),
            list(nil, nil, list("void", map("rep", state))), list(false, false, false), false, false, map("rep", result, "foreignCall", descriptor));
        var app = unit("app", "Main", List.of(binding("app:Main.entry", call)));
        assertEquals(Set.of("app:Main.entry"), ids(detached(input(List.of(app), null))));
    }
    @Test void detachedPackageStillRejectsMissingOrdinaryGlobal() throws Exception {
        var app = unit("app", "Main", List.of(binding("app:Main.entry", list("var", "missing:Other.value", map()))));
        var request = input(List.of(app), null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> detached(request)).getMessage().contains("missing:Other.value"));
    }
    @Test void detachedPackageIncludesConsumerDependencies() throws Exception {
        var dependency = unit("dependency", "Library", List.of(binding("dependency:Library.value", literal(17))));
        input(List.of(dependency), null);
        Path consumer = directory.resolve("Main.cbd");
        CoreCbdFixtures.write(consumer, map("schema", 1, "ghc", "9.14.1", "unit", "app", "module", "Main",
            "boundary", "optimized-Core-after-Tidy-before-CorePrep", "constructors", list(),
            "bindings", list(binding("app:Main.entry", list("var", "dependency:Library.value", map())))));
        var request = (Map<String,Object>) Json.parse(CoreModules.request(List.of(consumer.toString(), "@" + directory.resolve("packages.json")),
            "app:Main.entry", true, false, "ast", false, false, null, false, true));
        var result = detached(request);
        assertEquals(Set.of("app:Main.entry", "dependency:Library.value"), ids(result));
        assertFalse(result.containsKey("moduleFiles"));
    }
    private Map<String,Object> ioModule() {
        var state = map("kind", "void", "primReps", list(), "evaluated", true);
        var unit = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var closure = with(unit, "kind", "closure");
        var result = map("kind", "unknown", "aggregate", "unboxed-tuple", "components", list(state, unit),
            "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        String unitId = "ghc-internal:GHC.Internal.Tuple.()";
        var body = list("app", list("con", "StateUnit", 2, map()), list(list("void", map("rep", state)), list("con", unitId, 0, map("rep", unit))),
            list(false, true), true, true, map("rep", result));
        var lambda = list("lam", list(map("id", "s", "name", "s", "type", "State# RealWorld", "lifted", false, "rep", state)),
            body, map("rep", closure, "resultRep", result));
        var entry = map("id", "entry", "name", "entry", "type", "IO ()", "arity", 1, "lifted", true, "rep", closure, "expr", lambda);
        var stop = with(entry, "id", "stop", "name", "stop");
        return map("schema", 1, "ghc", "9.14.1", "unit", "fixture", "boundary", "optimized-Core-after-Tidy-before-CorePrep", "module", "PreparedIo", "bindings", list(entry, stop), "constructors",
            list(map("id", "StateUnit", "name", "StateUnit", "kind", "unboxed-tuple", "arity", 2, "tag", 1,
                    "strictFields", list(false, false), "fieldLifted", list(false, true), "fieldReps", list(list(), list("BoxedRep (Just Lifted)")), "fieldTypes", list(state, unit)),
                map("id", unitId, "name", "()", "kind", "boxed", "arity", 0, "tag", 1, "strictFields", list(), "fieldLifted", list(), "fieldReps", list(), "fieldTypes", list())));
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preparedIoFactoryRetainsShutdownAndCreatesFreshEntryLifecycle(boolean eager) throws Exception {
        var module = ioModule();
        var path = CoreCbdFixtures.write(directory.resolve("PreparedIo.cbd"), module);
        var request = document(CoreModules.request(List.of(path.toString()), "entry", true, false, "ast", false, true, "stop", eager, true));
        request.put("prepareCode", true);
        var source = Source.newBuilder("thc", Json.stringify(request), "prepared-io").cached(true).build();
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) { preparation.parse(source); }
            for (int i = 0; i < 2; i++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                for (int load = 0; load < 2; load++) {
                    var action = context.parse(source).execute();
                    assertFalse(action.canExecute()); assertTrue(action.canInvokeMember("runIO"));
                    var before = (Map<?,?>) Json.parse(action.getMember("diagnostics").asString());
                    assertEquals(eager, before.get("asyncExceptions"));
                    assertEquals(0L, before.get("loweredRootCount")); assertEquals(0L, before.get("compiledEntries"));
                    assertTrue(action.invokeMember("runIO").asBoolean());
                    assertTrue(assertThrows(PolyglotException.class, () -> action.invokeMember("runIO")).getMessage().contains("already started"));
                }
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void programLookupPreservesExecutableViewLifecycleAndReusableIo(String backend) throws Exception {
        var path = CoreCbdFixtures.write(directory.resolve("ProgramIo.cbd"), ioModule());
        try (var context = Context.newBuilder("thc").build()) {
            var program = Main.loadProgram(context, List.of(path.toString()), true, backend, false, true);
            assertThrows(PolyglotException.class, () -> Main.loadEntry(program, "entry", false, "stop"));
            var action = Main.loadEntry(program, "entry", true, "stop");
            assertTrue(action.invokeMember("runIO").asBoolean());
            var again = Main.loadEntry(program, "entry", true, "stop");
            assertTrue(assertThrows(PolyglotException.class, () -> again.invokeMember("runIO")).getMessage().contains("already started"));
            // A standalone IO action has no executable shutdown latch.
            var reusable = Main.loadEntry(program, "entry", true, null);
            assertTrue(reusable.invokeMember("runIO").asBoolean());
            assertTrue(Main.loadEntry(program, "entry", true, null).invokeMember("runIO").asBoolean());
        }
    }
    private String nativeBinding(Map<String,Object> module) throws Exception {
        module = with(module, "bindings", ((List<Map<String,Object>>) module.get("bindings")).stream()
            .map(binding -> with(binding, "id", "fixture:PreparedIo." + binding.get("id"))).toList());
        var record = CoreCbdFixtures.module(directory.resolve("PreparedIo.cbd"), module);
        var manifest = directory.resolve("packages.json");
        Files.writeString(manifest, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1",
            "units", list(map("id", "fixture", "depends", list(), "modules", list(record))))));
        return Json.stringify(list("--run-executable", "@" + manifest, "fixture:PreparedIo.entry", "fixture:PreparedIo.stop", "--", "fixture"));
    }

    /** Boundary control only: this callback is not a CRaC checkpoint/restore. */
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void checkpointCoreRunsWithoutCbdAndPropagatesCheckpointFailure(String backend) throws Exception {
        var args = NativeExecutable.launcherArguments(nativeBinding(ioModule()), new String[0]);
        var request = CoreModules.request(List.of(args[1]), args[2], true, false, backend, false, true, args[3], false, true);
        var failure = new IllegalStateException("checkpoint refused");
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> CracExecutable.checkpoint(context, request, () -> { throw failure; })));
        }
        long[] opens = new long[1];
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            var action = CracExecutable.checkpoint(context, request, () -> {
                var mappings = CoreFileMappings.shared.statistics();
                assertEquals(0, mappings.activeLeases());
                assertEquals(0, mappings.idleMappings());
                opens[0] = mappings.mappingOpens();
                try {
                    Files.delete(directory.resolve("PreparedIo.cbd"));
                    Files.delete(directory.resolve("packages.json"));
                } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            assertTrue(action.invokeMember("runIO").asBoolean());
            assertTrue(assertThrows(PolyglotException.class, () -> action.invokeMember("runIO"))
                .getMessage().contains("already started"));
        }
        assertEquals(opens[0], CoreFileMappings.shared.statistics().mappingOpens(), "Restore must not reopen CBD");
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void checkpointRejectsReachableColdLoweringFailure(String backend) throws Exception {
        var module = ioModule();
        var definitions = (List<Map<String,Object>>) module.get("bindings");
        var entry = definitions.getFirst();
        var lambda = (List<Object>) entry.get("expr");
        var metadata = (Map<String,Object>) lambda.getLast();
        var cold = list("app", list("var", "fixture:PreparedIo.cold", map("rep", entry.get("rep"))),
            list(list("void", map("rep", map("kind", "void", "primReps", list(), "evaluated", true)))),
            list(false), true, true, map("rep", metadata.get("resultRep")));
        var body = list("case", literal(0), "branch", list(
            list("lit", list("int", "0"), list(), lambda.get(2), map("binders", list())),
            list("default", null, list(), cold, map("binders", list()))), map("resultRep", metadata.get("resultRep")));
        module.put("bindings", list(with(entry, "expr", list("lam", lambda.get(1), body, lambda.getLast())),
            definitions.get(1), with(entry, "id", "cold", "name", "cold", "expr",
                list("lam", lambda.get(1), list("unsupported", "cold code must be lowered before checkpoint", map()), metadata))));
        var args = NativeExecutable.launcherArguments(nativeBinding(module), new String[0]);
        var request = CoreModules.request(List.of(args[1]), args[2], true, false, backend, false, true, args[3], false, true);
        var called = new boolean[1];
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            var failure = assertThrows(RuntimeException.class,
                () -> CracExecutable.checkpoint(context, request, () -> called[0] = true));
            assertEquals("Unsupported Core node unsupported", failure.getMessage());
            assertFalse(called[0], "Unsupported reachable code must fail before invoking the engine");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void executableImagePropertyDoesNotDisableJvmParsing(String backend) throws Exception {
        var app = unit("app", "Main", List.of(binding("app:Main.entry", literal(7))));
        var request = input(List.of(app), null);
        request.put("backend", backend);
        String old = System.setProperty("thc.nativeImage.executable", "true");
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            assertEquals(7L, context.eval("thc", Json.stringify(request)).execute().asLong());
        } finally {
            if (old == null) System.clearProperty("thc.nativeImage.executable");
            else System.setProperty("thc.nativeImage.executable", old);
        }
    }
    @Test void capturedExecutableRejectsMissingColdDependency() throws Exception {
        var module = ioModule();
        var definitions = (List<Map<String,Object>>) module.get("bindings");
        var entry = definitions.getFirst();
        var lambda = (List<Object>) entry.get("expr");
        var body = list("case", literal(0), "branch", list(list("lit", list("int", "0"), list(), lambda.get(2), map("binders", list())),
            list("default", null, list(), list("var", "missing:Cold.value", map()), map("binders", list()))), map());
        module.put("bindings", list(with(entry, "expr", list("lam", lambda.get(1), body, lambda.getLast())), definitions.get(1)));
        var configuration = nativeBinding(module);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> NativeExecutable.capture(configuration)).getMessage().contains("missing:Cold.value"));
    }
    @Test void capturedExecutableRejectsMalformedAsyncPolicy() throws Exception {
        var configuration = Json.stringify(map("arguments", Json.parse(nativeBinding(ioModule())),
            "properties", map("thc.asyncExceptions", "yes")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> NativeExecutable.capture(configuration))
            .getMessage().contains("thc.asyncExceptions must be true or false"));
    }
    /** Actual upstream preinit; inputs are one CBD model/manifest, removed before execution. */
    @Test void preinitializedApplicationRunsWithoutCoreInputsOrRuntimeLowering() throws Exception {
        var output = directory.resolve("preinit.log");
        var command = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
            "--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED",
            "--add-exports=org.graalvm.truffle.compiler/com.oracle.truffle.compiler=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-Dthc.handoffSlabs=" + System.getProperty("thc.handoffSlabs", "false"),
            "-cp", System.getProperty("thc.testRuntimeClasspath"), ReusableLoaderTest.class.getName(), directory.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile());
        for (var variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) command.environment().remove(variable);
        var process = command.start();
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Hosted application preparation timed out");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }

    public static void main(String[] arguments) throws Exception {
        var fixture = new ReusableLoaderTest(); fixture.directory = Path.of(arguments[0]);
        var module = fixture.ioModule();
        var definitions = new ArrayList<>((List<Map<String,Object>>) module.get("bindings"));
        definitions.add(fixture.binding("unusedbad", list("unsupported", "must remain unselected", map())));
        module.put("bindings", definitions);
        var configuration = Json.stringify(map("arguments", Json.parse(fixture.nativeBinding(module)),
            "properties", map("thc.asyncExceptions", "true")));
        var captured = NativeExecutable.capture(configuration);
        var application = NativeExecutable.class.getDeclaredField("application"); application.setAccessible(true);
        application.set(null, captured);
        var core = NativeExecutable.class.getDeclaredField("core"); core.setAccessible(true);
        assertEquals(true, ((Map<?,?>) core.get(captured)).get("asyncExceptions"));
        String option = "polyglot.image-build-time.PreinitializeContexts";
        System.clearProperty(option);
        var holder = Class.forName("org.graalvm.polyglot.Engine$ImplHolder");
        var preinitialize = holder.getDeclaredMethod("preInitializeEngine"); preinitialize.setAccessible(true);
        var reset = holder.getDeclaredMethod("resetPreInitializedEngine"); reset.setAccessible(true);
        try {
            System.setProperty(option, "thc"); preinitialize.invoke(null); System.clearProperty(option);
            assertNull(core.get(captured), "Hosted preparation must drop the detached Core bodies");
            var factoryField = NativeExecutable.class.getDeclaredField("factory"); factoryField.setAccessible(true);
            var factory = (Language.PreparedRoot) factoryField.get(captured);
            assertNotNull(factory, "Upstream preinitialization must produce the application factory");
            assertFalse(((com.oracle.truffle.runtime.OptimizedCallTarget) factory.getCallTarget()).wasExecuted(),
                "Preparation must not instantiate or run the application");
            CoreFileMappings.shared.evictIdleBelow(fixture.directory);
            Files.delete(fixture.directory.resolve("PreparedIo.cbd"));
            Files.delete(fixture.directory.resolve("packages.json"));
            System.setProperty("thc.requireCachedCode", "true");
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.Compilation", "false").build()) {
                for (int load = 0; load < 2; load++) {
                    var action = captured.load(context);
                    assertEquals(true, ((Map<?,?>) Json.parse(action.getMember("diagnostics").asString())).get("asyncExceptions"));
                    assertTrue(action.invokeMember("runIO").asBoolean());
                    assertTrue(assertThrows(PolyglotException.class, () -> action.invokeMember("runIO"))
                        .getMessage().contains("already started"));
                }
            }
            // A second implicit engine cannot consume the one-shot preinitialized language.
            try (var foreign = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.Compilation", "false").build()) {
                var failure = assertThrows(thc.runtime.UnsupportedCore.class, () -> captured.load(foreign));
                assertTrue(failure.getMessage().contains("prepared and current language"), failure.getMessage());
            }
        } finally {
            System.clearProperty(option); System.clearProperty("thc.requireCachedCode");
            application.set(null, null); reset.invoke(null); fixture.releaseMappings();
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
                var result = thc.runtime.CoreRepresentations.ioMainResult(definitions.getFirst(), definitions);
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
