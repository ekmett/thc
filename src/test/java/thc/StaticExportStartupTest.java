// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Model Core declarations through real public loaders and original C constructors. */
class StaticExportStartupTest {
    @TempDir Path directory;
    @BeforeEach void requireNativeTarget() {
        Assumptions.assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
    }
    private final String unit = "startup", name = "Exports", id = "startup:Exports.identity";
    private final String component = "a".repeat(64), nativeEntry = "thc_native_" + component + "_0";
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private Map<String,Object> module() throws Exception { return module(false); }
    private Map<String,Object> module(boolean failInitialization) throws Exception {
        var c = directory.resolve("startup.c"); var bc = directory.resolve("startup.bc");
        Files.writeString(c, """
            extern int declared_identity(int);
            static int (*retained)(int);
            static int initial;
            %s
            __attribute__((constructor)) static void initialize(void) {
                retained = declared_identity; initial = retained(7);
                %s
            }
            int %s(int value) { return initial + retained(value); }
            """.formatted(failInitialization ? "extern void missing_initializer_dependency(void);" : "",
                failInitialization ? "missing_initializer_dependency();" : "", nativeEntry));
        var process = new ProcessBuilder(System.getenv().getOrDefault("THC_CLANG", "clang"),
            "--target=x86_64-unknown-linux-gnu", "-O1", "-emit-llvm", "-c", c.toString(), "-o", bc.toString()).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), UTF_8); assertEquals(0, process.waitFor(), output);
        var bytes = Files.readAllBytes(bc);
        var scalar = map("kind", "tycon", "name", map("unit", "ghc-internal", "module", "GHC.Internal.Int", "occurrence", "Int32", "namespace", "type"), "arguments", List.of());
        var many = map("kind", "tycon", "name", map("unit", "ghc-internal", "module", "GHC.Internal.Types", "occurrence", "Many", "namespace", "data"), "arguments", List.of());
        var function = map("kind", "function", "multiplicity", many, "argument", scalar, "result", scalar);
        var binder = map("unit", unit, "module", name, "occurrence", "identity", "namespace", "value");
        var declaration = map("binder", binder, "symbol", "declared_identity", "convention", "ccall", "declaredType", function,
            "normalizedType", function, "normalizationRole", "representational", "arguments", list(scalar), "result", scalar, "effect", "pure");
        var inventory = map("schema", 1L, "producer", "THC.Plugin/typeCheckResultAction", "scope", "static-export-associations",
            "execution", "not-linked", "unit", unit, "module", name, "exports", list(declaration));
        var product = map("schema", 1L, "execution", "not-linked", "files", List.of(), "stubs", map("header", "int declared_identity(int);",
            "source", "model registration", "initializers", list(map("unit", unit, "module", name, "isInitializer", true, "name", "register_export")), "finalizers", List.of()));
        var registration = map("schema", 2L, "scope", "retained-foreign-products", "execution", "not-linked",
            "profile", "ghc-9.14.1-thc-only-native-static-c-products-v3", "status", "verified", "roots", list(binder), "wordBits", 64L,
            "expectedForeign", product, "expectedExports", inventory);
        var emptyProduct = map("schema", 1L, "execution", "not-linked", "stubs", null, "files", List.of());
        var imported = map("binder", with(binder, "occurrence", "observed"), "header", null, "symbol", "observed", "unit", null,
            "isFunction", true, "convention", "ccall", "safety", "safe", "declaredType", scalar, "normalizedType", scalar,
            "normalizationRole", "representational", "emitted", map("symbol", "observed", "unit", unit, "convention", "ccall", "safety", "safe",
                "arguments", list("Int32Rep", "void"), "result", list("void", "Int32Rep")));
        var proof = map("schema", 4L, "scope", "retained-static-import-products", "execution", "not-linked", "profile", "ghc-9.14.1-thc-only-static-c-imports-v1",
            "unit", unit, "module", name, "status", "verified", "wordBits", 64L, "expectedForeign", product, "importForeign", emptyProduct,
            "imports", list(imported), "expectedCalls", List.of(), "addresses", List.of(), "wrappers", List.of());
        var link = map("schema", 1L, "format", "llvm-bitcode", "profile", "thc-package-c-ffi-v1", "unit", unit,
            "target", "x86_64-unknown-linux-gnu", "componentSha256", component, "bitcodeSha256", hash(bytes), "bitcodeHex", HexFormat.of().formatHex(bytes),
            "abi", list(map("symbol", "observed", "entry", nativeEntry, "arguments", list("Int32Rep"), "result", "Int32Rep", "convention", "ccall", "safety", "safe")));
        var data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
        var closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var binding = map("id", id, "name", "identity", "lifted", true, "arity", 1L, "rep", closure,
            "expr", list("lam", list(map("id", "x", "lifted", true, "coercion", false, "rep", data)), list("var", "x", map("rep", data)), map("rep", closure, "resultRep", data)));
        return map("schema", 2L, "ghc", "9.14.1", "unit", unit, "module", name, "boundary", "optimized-Core-after-Tidy-before-CorePrep",
            "bindings", list(binding), "constructors", list(map("id", "ghc-internal:GHC.Internal.Int.I32#", "name", "I32#", "kind", "boxed",
                "arity", 1L, "fieldReps", list(list("Int32Rep")), "strictFields", list(false), "fieldLifted", list(false))),
            "foreign", product, "staticForeignImports", proof, "staticForeignExports", inventory, "staticForeignExportRegistration", registration, "packageNativeLink", link);
    }
    private Path paired(Map<String,Object> module) throws Exception {
        var original = Json.stringify(module).getBytes(UTF_8); var metadata = Json.stringify(without(module, "bindings")).getBytes(UTF_8);
        var out = new ByteArrayOutputStream(); out.writeBytes(original); out.write(10); out.writeBytes(metadata); var bytes = out.toByteArray();
        var json = directory.resolve("module.jsons"); var symbols = directory.resolve("module.symbols"); Files.write(json, bytes);
        Map<String,Object> record;
        try (var index = CoreJsonIndex.fromBytes(original)) {
            var bindings = Objects.requireNonNull(index.getRoot().member("bindings"));
            Files.writeString(symbols, id + " " + bindings.elements().getFirst().getStart() + "\n");
            record = map("name", name, "path", "Exports.json", "sha256", hash(original), "boundary", module.get("boundary"), "start", 0L, "end", original.length,
                "bindingsStart", bindings.getStart(), "bindingsEnd", bindings.getEndExclusive(), "metadataStart", original.length + 1, "metadataEnd", bytes.length,
                "containsDelimitedControl", false, "registrationObligations", true, "mainAlias", false, "packageScalarDeclarations", true);
        }
        return Files.writeString(directory.resolve("packages.json"), Json.stringify(map("format", "thc-core-packages", "schema", 1L, "ghc", "9.14.1", "units", list(
            map("id", unit, "depends", List.of(), "json", map("path", json.toString(), "sha256", hash(bytes)),
                "symbols", map("path", symbols.toString(), "sha256", hash(Files.readAllBytes(symbols))), "modules", list(record))))));
    }
    @AfterEach void releaseMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void failedNativeInitializationDoesNotPublishRootsOrCallableExports(String backend, boolean lazy) throws Exception {
        var module = module(true); var file = Files.writeString(directory.resolve("module.json"), Json.stringify(module));
        var paths = List.of(lazy ? "@" + paired(module) : file.toString());
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            var failure = assertThrows(RuntimeException.class,
                () -> context.eval("thc", CoreModules.managedExportRequest(paths, backend, true)));
            assertTrue(failure.getMessage().contains("missing_initializer_dependency"), failure.toString());
            context.enter();
            try {
                var owner = Language.currentState();
                assertEquals(0, owner.getForeignRoots().size());
                assertTrue(owner.getCoreUnitPrograms().isEmpty());
                assertTrue(context.getBindings("thc").getMemberKeys().isEmpty());
                assertFalse(com.oracle.truffle.api.interop.InteropLibrary.getUncached().isMemberReadable(
                    owner.getNativeCallbacks().namespace(), "declared_identity"));
            } finally { context.leave(); }
        }
    }
    @Timeout(20)
    @ParameterizedTest @CsvSource({"ast,false,false", "bytecode,false,false", "ast,true,false", "bytecode,true,false",
        "ast,false,true", "bytecode,false,true", "ast,true,true", "bytecode,true,true"})
    void publicLoadRegistersBeforeOriginalConstructorCallsBack(String backend, boolean lazy, boolean managed) throws Exception {
        var module = module(); var file = Files.writeString(directory.resolve("module.json"), Json.stringify(module));
        var paths = List.of(lazy ? "@" + paired(module) : file.toString());
        var request = managed ? CoreModules.managedExportRequest(paths, backend, true) : CoreModules.request(paths, id, true, false, backend, false);
        for (String hosting : List.of("platform", "loom")) try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowCreateThread(true)
                .allowExperimentalOptions(true).option("thc.ThreadHosting", hosting).build()) {
            var value = context.eval("thc", request);
            if (managed) assertEquals(11, value.getMember(unit).getMember(name).getMember("declared_identity").execute(11).asInt());
            context.enter();
            try {
                var owner = Language.currentState(); var link = Objects.requireNonNull(PackageScalarLinks.read(module)).getLink();
                var function = owner.getPackageCbits().resolve(link, link.getAbi().getFirst()).getReceiver();
                var interop = com.oracle.truffle.api.interop.InteropLibrary.getUncached();
                assertEquals(49, interop.asInt(interop.execute(function, 42)), hosting);
                assertEquals(50, interop.asInt(interop.execute(function, 43)), "constructor executes once");
                assertEquals(1, owner.getForeignRoots().size());
            } finally { context.leave(); }
        }
    }
}
