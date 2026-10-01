// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import thc.PackageScalarLink;
import thc.PackageScalarLinks;
import static org.junit.jupiter.api.Assertions.*;

/** Real compiler products: adapters must not borrow a sibling provider or copy its C state. */
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
public class PackageNativeDemandTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) public Path directory;
    private static final class Entry extends RootNode {
        @Child private PackageScalarAccess access;
        Entry(Language language, PackageScalarCall call) { super(language); access = new PackageScalarAccess(call); }
        @Override public Object execute(VirtualFrame frame) { return access.executeLong(new Object[0], Unit.INSTANCE); }
    }
    private String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private byte[] compile(String name, String body) throws Exception {
        var source = directory.resolve(name + ".c"); var output = directory.resolve(name + ".bc");
        Files.writeString(source, body);
        var process = new ProcessBuilder(System.getenv().getOrDefault("THC_CLANG", "clang"),
            "--target=x86_64-unknown-linux-gnu", "-O1", "-emit-llvm", "-c", source.toString(), "-o", output.toString())
            .redirectErrorStream(true).start();
        var errors = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), errors);
        return Files.readAllBytes(output);
    }
    private Map<String,Object> fields(Object... values) {
        var result = new LinkedHashMap<String,Object>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    private PackageScalarLink provider(String unit, int initial) throws Exception {
        String source = "static unsigned long state; __attribute__((constructor)) static void initialize(void) { state = " + initial + "; }\n" +
            "__attribute__((noinline)) long next(void) { return ++state; }\n";
        String component = hash((unit + "\0x86_64-unknown-linux-gnu\0" + source).getBytes(StandardCharsets.UTF_8));
        String name = "thc_provider_" + component + "_next";
        byte[] bytes = compile(unit, source + "long " + name + "(void) { return next(); }\n");
        var abi = new ArrayList<Map<String,Object>>(); var seeds = new ArrayList<Map<String,Object>>();
        for (int index = 0; index < 2; index++) {
            String entry = "thc_native_" + component + "_" + index;
            abi.add(fields("symbol", "next", "entry", entry, "convention", "ccall", "safety", index == 0 ? "safe" : "unsafe",
                "arguments", List.of(), "result", "IntRep"));
            byte[] seed = compile(unit + "-seed-" + index, "extern long " + name + "(void); long " + entry + "(void) { return " + name + "(); }\n");
            seeds.add(fields("entry", entry, "bitcodeHex", HexFormat.of().formatHex(seed), "bitcodeSha256", hash(seed),
                "providerUnit", unit, "providerComponentSha256", component, "providerSymbol", name));
        }
        var link = fields("schema", 3L, "format", "llvm-bitcode", "profile", "thc-package-c-ffi-demand-v1",
            "unit", unit, "target", "x86_64-unknown-linux-gnu", "componentSha256", component,
            "bitcodeSha256", hash(bytes), "bitcodeHex", HexFormat.of().formatHex(bytes), "abi", abi,
            "exports", List.of("next", name), "dependencies", List.of(), "callSeeds", seeds);
        var module = fields("schema", 1L, "ghc", "9.14.1", "unit", unit, "module", "Demand", "bindings", List.of(),
            "packageNativeLink", link);
        return PackageScalarLinks.read(module).getLink();
    }
    @Test public void namespacedSameCNameAdaptersShareOnlyTheirCanonicalProvidersState() throws Exception {
        var first = provider("first-provider", 40);
        var second = provider("second-provider", 100);
        for (int contextIndex = 0; contextIndex < 2; contextIndex++) {
            try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowCreateProcess(true).allowExperimentalOptions(true)
                    .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.INHERIT)
                    .option("thc.PackageNativeBuilder", System.getenv().getOrDefault("THC_TEST_DRIVER", ""))
                    .option("thc.PackageNativeCache", directory.resolve("products").toString())
                    .allowIO(IOAccess.ALL).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var registry = Language.currentState().getPackageCbits();
                    registry.link(first); registry.link(second);
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var firstSafe = new Entry(language, new PackageScalarCall(first, first.getAbi().getFirst())).getCallTarget();
                    var secondSafe = new Entry(language, new PackageScalarCall(second, second.getAbi().getFirst())).getCallTarget();
                    var firstUnsafe = new Entry(language, new PackageScalarCall(first, first.getAbi().getLast())).getCallTarget();
                    var secondUnsafe = new Entry(language, new PackageScalarCall(second, second.getAbi().getLast())).getCallTarget();
                    assertEquals(41L, firstSafe.call(), "first actual provider initializes once in this context");
                    assertEquals(101L, secondSafe.call(), "same C name cannot borrow the other unit's definition");
                    assertEquals(42L, firstUnsafe.call(), "second adapter uses the existing provider state, not a copied C module");
                    assertEquals(102L, secondUnsafe.call());
                    assertEquals(43L, firstSafe.call(), "adapters and constructors are not reinitialized");
                } finally { context.leave(); }
            }
        }
    }
    private PackageScalarLink missingProvider() throws Exception {
        String unit = "missing-provider", component = hash(unit.getBytes(StandardCharsets.UTF_8));
        String entry = "thc_native_" + component + "_0";
        byte[] seed = compile("missing-seed", "extern long ordinary_missing(void); long " + entry + "(void) { return ordinary_missing(); }\n");
        var abi = fields("symbol", "ordinary_missing", "entry", entry, "convention", "ccall", "safety", "unsafe",
            "arguments", List.of(), "result", "IntRep");
        var link = fields("schema", 3L, "format", "llvm-bitcode", "profile", "thc-package-c-ffi-demand-v1",
            "unit", unit, "target", "x86_64-unknown-linux-gnu", "componentSha256", component,
            "bitcodeSha256", hash(new byte[0]), "bitcodeHex", "", "abi", List.of(abi), "exports", List.of(), "dependencies", List.of(),
            "callSeeds", List.of(fields("entry", entry, "bitcodeHex", HexFormat.of().formatHex(seed), "bitcodeSha256", hash(seed),
                "providerUnit", null, "providerComponentSha256", null, "providerSymbol", null)));
        return PackageScalarLinks.read(fields("schema", 1L, "ghc", "9.14.1", "unit", unit,
            "module", "Demand", "bindings", List.of(), "packageNativeLink", link)).getLink();
    }
    @Test public void unusedMissingOrdinaryProviderIsNotAnEagerNativeObligation() throws Exception {
        var declaration = missingProvider();
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var registry = Language.currentState().getPackageCbits();
                assertDoesNotThrow(() -> registry.link(declaration), "cataloguing an unused typed import must not resolve it");
                var failure = assertThrows(RuntimeFault.class, () -> registry.resolve(declaration, declaration.getAbi().getFirst()));
                assertTrue(failure.getMessage().contains("missing-provider:ordinary_missing"), failure.getMessage());
                assertSame(failure, assertThrows(RuntimeFault.class, () -> registry.resolve(declaration, declaration.getAbi().getFirst())),
                    "first demand failure is stored, not retried or replaced by an invented provider");
                assertFalse(Files.exists(directory.resolve("products")), "failure does not invent a native provider or product");
            } finally { context.leave(); }
        }
    }
    private Map<String,Object> scalar(String rep, boolean evaluated) {
        return fields("kind", rep == null ? "void" : "long", "primReps", rep == null ? List.of() : List.of(rep), "evaluated", evaluated);
    }
    private Map<String,Object> binding(String name, PackageScalarLink link) {
        var signature = link.getAbi().getFirst();
        var state = scalar(null, true); var word = scalar("IntRep", true);
        var tuple = fields("kind", "unknown", "primReps", List.of("IntRep"), "evaluated", false,
            "aggregate", "unboxed-tuple", "components", List.of(state, word));
        var closure = fields("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var descriptor = fields("schema", 1L, "target", fields("kind", "static", "symbol", signature.getSymbol(),
            "unit", link.getUnit(), "isFunction", true), "convention", "ccall", "safety", signature.getSafety(),
            "arity", 1L, "suppliedArity", 1L, "argumentReps", List.of(scalar(null, false)), "resultRep", tuple);
        var stateBinder = fields("id", "state", "name", "state", "lifted", false, "rep", state);
        var call = List.of("app", List.of("var", "foreign", fields("rep", closure)),
            List.of(List.of("var", "state", fields("rep", state))), List.of(false), false, false,
            fields("rep", tuple, "foreignCall", descriptor));
        var body = List.of("case", call, "result", List.of(List.of("data", "Pair", List.of("returnedState", "value"),
            List.of("var", "value", fields("rep", word)), fields("binders", List.of(
                fields("id", "returnedState", "lifted", false, "rep", state), fields("id", "value", "lifted", false, "rep", word))))),
            fields("rep", word, "binder", fields("id", "result", "lifted", false, "rep", tuple)));
        return fields("id", name, "name", name, "lifted", true, "arity", 1, "rep", closure,
            "expr", List.of("lam", List.of(stateBinder), body, fields("rep", closure, "resultRep", word)));
    }
    @org.junit.jupiter.api.Tag("foreign-exceptions-full-core")
    @Test public void preparedAstDefersUnusedAdapterAndItsFirstInstalledCallUsesCanonicalState() throws Exception {
        var provider = provider("prepared-provider", 40); var missing = missingProvider();
        var module = fields("schema", 1L, "ghc", "9.14.1", "unit", "demand-fixture", "module", "Demand", "instrument", true,
            "bindings", List.of(binding("next", provider), binding("missing", missing)),
            "constructors", List.of(fields("id", "Pair", "kind", "unboxed-tuple", "arity", 2, "tag", 1)),
            "packageScalarLinks", List.of(provider, missing));
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowCreateProcess(true).allowExperimentalOptions(true)
                .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.INHERIT)
                .option("thc.PackageNativeBuilder", System.getenv().getOrDefault("THC_TEST_DRIVER", ""))
                .option("thc.PackageNativeCache", directory.resolve("products").toString())
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").allowIO(IOAccess.ALL).build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var registry = Language.currentState().getPackageCbits(); registry.link(provider); registry.link(missing);
                var checked = thc.ForeignExceptionFixtureSupport.nativeModules(List.of(module));
                checked.put("packageScalarLinks", List.of(provider, missing));
                var code = Program.prepareCode(language, checked, List.of("next", "missing"));
                assertFalse(Files.exists(directory.resolve("products")), "preparation does not finalize either retained call");
                var program = code.newInstance(language);
                assertFalse(Files.exists(directory.resolve("products")), "instantiation does not finalize unused calls");
                var entry = (Closure) program.entryValue("next");
                var target = (com.oracle.truffle.runtime.OptimizedCallTarget) entry.target;
                assertFalse(target.wasExecuted()); target.prepareForAOT(); target.compile(true); target.waitForCompilation();
                assertTrue(target.isValidLastTier()); assertFalse(target.wasExecuted());
                assertEquals(41L, Calls.target(target, new Object[]{0L, entry.environment, Unit.INSTANCE}));
                assertTrue(target.isValidLastTier(), "first real C effect retains the installed prepared root");
                assertEquals(1L, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                assertEquals(42L, Calls.target(target, new Object[]{0L, entry.environment, Unit.INSTANCE}));
                var unavailable = (Closure) program.entryValue("missing");
                var failure = assertThrows(RuntimeFault.class, () -> Calls.target(unavailable.target,
                    new Object[]{0L, unavailable.environment, Unit.INSTANCE}));
                assertTrue(failure.getMessage().contains("missing-provider:ordinary_missing"), failure.getMessage());
                assertEquals(43L, Calls.target(target, new Object[]{0L, entry.environment, Unit.INSTANCE}), "failed ordinary demand changes no provider state");
            } finally { context.leave(); }
        }
    }
    @SuppressWarnings("unchecked") private Map<String, Object> normalFixture() throws Exception {
        var root = new File(System.getProperty("thc.projectRoot"));
        String prefix = "build/package-native-demand/";
        var manifest = (Map<String, Object>) thc.Json.parse(Files.readString(root.toPath().resolve(prefix + "manifest.json")));
        assertEquals(1L, manifest.get("schema"));
        assertEquals(true, manifest.get("supported"));
        assertEquals(true, manifest.get("runtimeVerified"));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of(
            "t/fixtures/run-scalar-cbits/src/Demand.hs", "t/fixtures/run-scalar-cbits/app/DemandMain.hs",
            "t/fixtures/run-scalar-cbits/cbits/demand.c", "t/haskell-fixtures/PackageScalarFixtures.hs",
            "t/haskell-fixtures/FixtureSupport.hs", "src/driver/THC/Driver/PackageNative.hs",
            "src/driver/THC/Driver/NativeArgumentBridge.hs", "src/driver/THC/Driver/Run.hs",
            "src/driver/THC/Driver/Project.hs", "src/main/java/thc/Main.java"));
        assertEquals(prefix + "acquired/packages.json", manifest.get("packages"));
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), Set.of(
            prefix + "acquired/packages.json", prefix + "acquired/audit.json",
            prefix + "logs/thc-run.stdout", prefix + "logs/native-run.stdout"), prefix);
        assertEquals("41\n42\n43\n", Files.readString(root.toPath().resolve(prefix + "logs/native-run.stdout")));
        assertEquals("41\n42\n43\n", Files.readString(root.toPath().resolve(prefix + "logs/thc-run.stdout")));
        var packages = (Map<?, ?>) thc.Json.parse(Files.readString(root.toPath().resolve((String) manifest.get("packages"))));
        var directory = thc.CoreUnitDirectory.read(packages);
        var selected = directory.getModules().stream().filter(module -> module.unit().equals(manifest.get("unit")) &&
            module.name().equals("Demand")).toList();
        assertEquals(1, selected.size(), "exact original acquired library module");
        var artifact = selected.getFirst();
        assertEquals(artifact.sha256(), hash(Files.readAllBytes(artifact.artifact().path())));
        var module = thc.CoreCbdFixtures.read(artifact.artifact().path());
        assertEquals("9.14.1", module.get("ghc"));
        assertEquals(artifact.unit(), module.get("unit")); assertEquals("Demand", module.get("module"));
        assertEquals("optimized-Core-after-Tidy-before-CorePrep", module.get("boundary"));
        return module;
    }
    @org.junit.jupiter.api.Tag("foreign-exceptions-full-core")
    @Test public void normalCapturedCbdFirstInstalledCallsShareTheirOriginalProvider() throws Exception {
        var module = normalFixture();
        String prefix = module.get("unit") + ":Demand.";
        var admission = PackageScalarLinks.read(module);
        assertNotNull(admission); assertEquals(2, admission.getLink().getCallSeeds().size());
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowCreateProcess(true).allowExperimentalOptions(true)
                .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.INHERIT)
                .option("thc.PackageNativeBuilder", System.getenv().getOrDefault("THC_TEST_DRIVER", ""))
                .option("thc.PackageNativeCache", directory.resolve("products").toString())
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").allowIO(IOAccess.ALL).build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var checked = thc.ForeignExceptionFixtureSupport.nativeModules(List.of(module));
                var code = Program.prepareCode(language, checked,
                    List.of(prefix + "next", prefix + "nextSafe"));
                var program = code.newInstance(language);
                assertFalse(Files.exists(directory.resolve("products")), "actual CBD startup does not finalize an unused adapter");
                var unsafe = (Closure) program.entryValue(prefix + "next");
                var safe = (Closure) program.entryValue(prefix + "nextSafe");
                for (var closure : List.of(unsafe, safe)) {
                    var target = (com.oracle.truffle.runtime.OptimizedCallTarget) closure.target;
                    assertFalse(target.wasExecuted()); target.prepareForAOT(); target.compile(true); target.waitForCompilation();
                    assertTrue(target.isValidLastTier()); assertFalse(target.wasExecuted());
                }
                assertEquals(41L, Calls.target(unsafe.target, new Object[]{0L, unsafe.environment, Unit.INSTANCE}));
                assertTrue(((com.oracle.truffle.runtime.OptimizedCallTarget) unsafe.target).isValidLastTier());
                assertEquals(42L, Calls.target(safe.target, new Object[]{0L, safe.environment, Unit.INSTANCE}));
                assertTrue(((com.oracle.truffle.runtime.OptimizedCallTarget) safe.target).isValidLastTier());
                assertEquals(43L, Calls.target(unsafe.target, new Object[]{0L, unsafe.environment, Unit.INSTANCE}));
                assertEquals(3L, ((Number) program.diagnostics().get("compiledEntries")).longValue());
            } finally { context.leave(); }
        }
    }
    @org.junit.jupiter.api.Tag("foreign-exceptions-full-core")
    @Test @SuppressWarnings("unchecked") public void normalCbdCatalogueRejectsChangedSeedOwnershipAndAbi() throws Exception {
        var module = normalFixture();
        assertDoesNotThrow(() -> PackageScalarLinks.read(module));
        var original = (Map<String,Object>) module.get("packageNativeLink");
        var seeds = (List<Map<String,Object>>) original.get("callSeeds");
        for (var mutation : List.of(fields("invented", true), fields("entry", "unrelated_entry"),
                fields("bitcodeSha256", "0".repeat(64)), fields("providerUnit", "unrelated-owner"),
                fields("providerComponentSha256", "0".repeat(64)), fields("providerSymbol", "next"))) {
            var changedSeed = new LinkedHashMap<>(seeds.getFirst()); changedSeed.putAll(mutation);
            var changedLink = new LinkedHashMap<>(original); changedLink.put("callSeeds", List.of(changedSeed, seeds.getLast()));
            var changedModule = new LinkedHashMap<>(module); changedModule.put("packageNativeLink", changedLink);
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(changedModule), mutation.toString());
        }
        var abi = (List<Map<String,Object>>) original.get("abi");
        for (var mutation : List.of(fields("symbol", "unrelated_native"), fields("convention", "capi"),
                fields("safety", "interruptible"), fields("arguments", List.of("IntRep")), fields("result", "WordRep"))) {
            var changedAbi = new LinkedHashMap<>(abi.getFirst()); changedAbi.putAll(mutation);
            var changedLink = new LinkedHashMap<>(original); changedLink.put("abi", List.of(changedAbi, abi.getLast()));
            var changedModule = new LinkedHashMap<>(module); changedModule.put("packageNativeLink", changedLink);
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(changedModule), mutation.toString());
        }
    }
}
