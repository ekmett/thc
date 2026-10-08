// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    private Map<String,Object> module(boolean failInitialization) throws Exception { return module(failInitialization, null); }
    private Map<String,Object> module(boolean failInitialization, String labelKind) throws Exception {
        String labelEntry = "thc_native_" + component + "_1";
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
            %s
            """.formatted(failInitialization ? "extern void missing_initializer_dependency(void);" : "",
                failInitialization ? "missing_initializer_dependency();" : "", nativeEntry,
                labelKind == null ? "" : labelKind.equals("function-addr")
                    ? "void " + labelEntry + "(unsigned char *p) { if (p) ++*p; }"
                    : "static unsigned char payload = 29; void *" + labelEntry + "(void) { return &payload; }"));
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
        var result = map("schema", 2L, "ghc", "9.14.1", "unit", unit, "module", name, "boundary", "optimized-Core-after-Tidy-before-CorePrep",
            "bindings", list(binding), "constructors", list(map("id", "ghc-internal:GHC.Internal.Int.I32#", "name", "I32#", "kind", "boxed",
                "arity", 1L, "tag", 1L, "fieldReps", list(list("Int32Rep")),
                "fieldTypes", list(map("kind", "long", "primReps", list("Int32Rep"), "evaluated", true)),
                "strictFields", list(false), "fieldLifted", list(false))),
            "foreign", product, "staticForeignImports", proof, "staticForeignExports", inventory, "staticForeignExportRegistration", registration, "packageNativeLink", link);
        if (labelKind != null) {
            boolean finalizer = labelKind.equals("function-addr");
            var address = map("kind", "address", "primReps", list("AddrRep"), "evaluated", true);
            var unitType = map("kind", "tycon", "name", map("unit", "ghc-internal", "module", "GHC.Internal.Tuple", "occurrence", "Unit", "namespace", "type"), "arguments", List.of());
            var pointer = map("kind", "tycon", "name", map("unit", "ghc-internal", "module", "GHC.Internal.Ptr", "occurrence", "Ptr", "namespace", "type"), "arguments", list(unitType));
            var io = map("kind", "tycon", "name", map("unit", "ghc-internal", "module", "GHC.Internal.Types", "occurrence", "IO", "namespace", "type"), "arguments", list(unitType));
            var type = finalizer ? map("kind", "tycon", "name", map("unit", "ghc-internal", "module", "GHC.Internal.Ptr", "occurrence", "FunPtr", "namespace", "type"),
                "arguments", list(map("kind", "function", "multiplicity", many, "argument", pointer, "result", io))) : pointer;
            var declared = map("binder", with(binder, "occurrence", "label"), "header", null, "symbol", "startup_label", "isFunction", finalizer,
                "convention", "ccall", "declaredType", type, "normalizedType", type, "normalizationRole", "representational",
                "callback", finalizer ? map("arguments", list("AddrRep"), "result", "void") : null);
            var abi = new ArrayList<Object>((List<?>) link.get("abi"));
            abi.add(map("symbol", "startup_label", "entry", labelEntry, "arguments", finalizer ? list("AddrRep") : List.of(),
                "result", finalizer ? "void" : "AddrRep", "convention", "ccall", "safety", "unsafe"));
            var labelLink = with(link, "abi", abi);
            labelLink = finalizer ? with(labelLink, "schema", 2L, "finalizers", list(labelEntry)) : with(labelLink, "dataSymbols", list(labelEntry));
            var labelBinding = map("id", unit + ":" + name + ".label", "name", "label", "arity", 0L, "lifted", false, "rep", address,
                "expr", list("lit", labelKind, "startup_label", map("rep", address)));
            // The original C constructor enters Haskell before native loading
            // completes, and that callback demands this same component's label.
            var body = list("case", list("var", unit + ":" + name + ".label", map("rep", address)), "address",
                list(list("default", null, List.of(), list("var", "x", map("rep", data)), map("binders", List.of()))),
                map("rep", data, "binder", map("id", "address", "lifted", false, "rep", address)));
            binding = with(binding, "expr", list("lam", list(map("id", "x", "lifted", true, "coercion", false, "rep", data)), body,
                map("rep", closure, "resultRep", data)));
            result = with(result, "bindings", list(binding, labelBinding), "staticForeignImports", with(proof, "addresses", list(declared)), "packageNativeLink", labelLink);
        }
        return result;
    }
    private Path packaged(Map<String,Object> module) throws Exception {
        var record = CoreCbdFixtures.module(directory.resolve("module.cbd"), module);
        return Files.writeString(directory.resolve("packages.json"), Json.stringify(map("format", "thc-core-packages",
            "schema", 1L, "ghc", "9.14.1", "units", list(map("id", unit, "depends", List.of(), "modules", list(record))))));
    }
    @AfterEach void releaseMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void explicitProgramEntriesShareRegistrationAndIndependentLoadsStillConflict(String backend) throws Exception {
        var module = module(false, "data-addr");
        var paths = List.of("@" + packaged(module));
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            // Invalid legacy entry selection must precede native constructors;
            // otherwise their retained callback would belong to a failed program.
            assertThrows(RuntimeException.class, () -> Main.loadEntry(context, paths, "missing", true, backend));
            var program = Main.loadProgram(context, paths, true, backend, false, true);
            assertTrue(Main.loadEntry(program, id).canExecute());
            assertTrue(Main.loadEntry(program, unit + ":" + name + ".label").canExecute());
            var failure = assertThrows(RuntimeException.class, () -> Main.loadProgram(context, paths, true, backend, false, true));
            assertTrue(failure.getMessage().contains("Conflicting native static export"), failure.toString());
            assertTrue(Main.loadEntry(program, id).canExecute(), "failed independent load cannot invalidate the original views");
            context.enter();
            try {
                var owner = Language.currentState();
                var link = Objects.requireNonNull(PackageScalarLinks.read(module)).getLink();
                var function = owner.getPackageCbits().resolve(link, link.getAbi().getFirst()).getReceiver();
                var interop = com.oracle.truffle.api.interop.InteropLibrary.getUncached();
                assertEquals(49, interop.asInt(interop.execute(function, 42)), "constructor and retained callback still use the first program");
            } finally { context.leave(); }
        }
    }

    /** Real C constructors, with the existing exact legacy CAPI admission shape. */
    private Map<String,Object> capiModule(String initializer) throws Exception {
        String capiUnit = "base-test-unit", capiModule = "System.CPUTime.Posix.ClockGetTime";
        String source = """
            #include <stdio.h>
            extern int declared_identity(int);
            extern void missing_capi_initializer(void);
            static long initial;
            __attribute__((constructor)) static void initialize(void) { %s }
            long fixture_clock_id(void) { return initial++; }
            int fixture_clock_read(unsigned long n, void *p) { return 0; }
            int fixture_clock_res(unsigned long n, void *p) { return 0; }
            int thc_capi_errno(void) { return 0; }
            """.formatted(initializer);
        var c = directory.resolve("capi.c"); var bc = directory.resolve("capi.bc");
        Files.writeString(c, source);
        var process = new ProcessBuilder(System.getenv().getOrDefault("THC_CLANG", "clang"),
            "--target=x86_64-unknown-linux-gnu", "-O1", "-emit-llvm", "-c", c.toString(), "-o", bc.toString()).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), UTF_8); assertEquals(0, process.waitFor(), output);
        var bytes = Files.readAllBytes(bc);
        var symbols = List.of("fixture_clock_id", "fixture_clock_read", "fixture_clock_res");
        var link = map("schema", 2L, "format", "llvm-bitcode", "unit", capiUnit, "module", capiModule,
            "target", "x86_64-unknown-linux-gnu", "symbols", symbols, "abi", list(
                map("symbol", symbols.get(0), "kind", "clock-id"), map("symbol", symbols.get(1), "kind", "clock-buffer"),
                map("symbol", symbols.get(2), "kind", "clock-buffer")),
            "sourceSha256", hash(source.getBytes(UTF_8)), "bitcodeSha256", hash(bytes), "bitcodeHex", HexFormat.of().formatHex(bytes));
        return map("schema", 2L, "ghc", "9.14.1", "unit", capiUnit, "module", capiModule, "boundary", "optimized-Core-after-Tidy-before-CorePrep", "bindings", List.of(), "constructors", List.of(),
            "foreign", map("schema", 1L, "execution", "not-linked", "files", List.of(), "stubs",
                map("header", "", "source", source, "initializers", List.of(), "finalizers", List.of())), "foreignLink", link);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void conflictingCapiOwnershipRejectsBeforeConstructorEffects(boolean sameModule) throws Exception {
        var original = Objects.requireNonNull(CoreForeignArtifacts.linked(capiModule("initial = 1;"), false));
        var marker = directory.resolve("conflicting-constructor.txt");
        String path = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        var different = Objects.requireNonNull(CoreForeignArtifacts.linked(capiModule(
            "FILE *file = fopen(\"" + path + "\", \"w\"); if (file) { fputs(\"executed\", file); fclose(file); }"), false));
        var conflict = new ForeignBitcode(different.unit(), sameModule ? different.module() : "Other",
            different.target(), different.symbols(), different.abi(), different.bytes());
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowIO(org.graalvm.polyglot.io.IOAccess.ALL).build()) {
            context.initialize("thc"); context.enter();
            try {
                var cbits = Language.currentState().cbits(); cbits.link(original);
                assertThrows(IllegalArgumentException.class, () -> cbits.link(conflict));
                assertFalse(Files.exists(marker), "Conflicting library must never run its constructor");
                assertEquals(1L, cbits.capiZero(original.unit(), "fixture_clock_id"));
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true}) @Timeout(30)
    @SuppressWarnings("unchecked")
    void preparedLoadRegistersBeforeOriginalConstructorCallsBack(boolean earlyFinalizer) throws Exception {
        var module = module(false, earlyFinalizer ? "function-addr" : null);
        var file = CoreCbdFixtures.write(directory.resolve("module.cbd"), module);
        var request = (Map<String,Object>) Json.parse(CoreModules.request(List.of(file.toString()), id, true, false, "ast", false));
        request.put("prepareCode", true); request.put("asyncExceptions", false);
        for (String hosting : List.of("platform", "loom")) try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowCreateThread(true)
                .allowExperimentalOptions(true).option("thc.ThreadHosting", hosting).build()) {
            context.eval("thc", Json.stringify(request));
            context.enter();
            try {
                var owner = Language.currentState(); var link = Objects.requireNonNull(PackageScalarLinks.read(module)).getLink();
                var function = owner.getPackageCbits().resolve(link, link.getAbi().getFirst()).getReceiver();
                var interop = com.oracle.truffle.api.interop.InteropLibrary.getUncached();
                assertEquals(49, interop.asInt(interop.execute(function, 42)), hosting);
                assertEquals(50, interop.asInt(interop.execute(function, 43)), "constructor executes once");
                assertEquals(1, owner.getForeignRoots().size());
                assertEquals(0, owner.getForeignRoots().programs().getFirst().diagnostics().get("loweredRootCount"));
                if (earlyFinalizer) {
                    byte[] bytes = {40};
                    owner.getPackageCbits().finalizer("startup_label").invoke(thc.runtime.ManagedAddress.fromByteArray(bytes));
                    assertArrayEquals(new byte[]{41}, bytes);
                }
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true}) @Timeout(30)
    @SuppressWarnings("unchecked")
    void failedPreparedConstructorKeepsItsFailureAndPublishesNoExport(boolean earlyFinalizer) throws Exception {
        var module = module(true, earlyFinalizer ? "function-addr" : null);
        var file = CoreCbdFixtures.write(directory.resolve("module.cbd"), module);
        var request = (Map<String,Object>) Json.parse(CoreModules.request(List.of(file.toString()), id, true, false, "ast", false));
        request.put("prepareCode", true); request.put("asyncExceptions", false);
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            var failure = assertThrows(RuntimeException.class, () -> context.eval("thc", Json.stringify(request)));
            assertTrue(failure.getMessage().contains("missing_initializer_dependency"), failure.toString());
            context.enter();
            try {
                var owner = Language.currentState(); var link = Objects.requireNonNull(PackageScalarLinks.read(module)).getLink();
                var first = assertThrows(RuntimeException.class, () -> owner.getPackageCbits().resolve(link, link.getAbi().getFirst()));
                assertTrue(first.getMessage().contains("missing_initializer_dependency"), first.toString());
                assertSame(first, assertThrows(RuntimeException.class, () -> owner.getPackageCbits().resolve(link, link.getAbi().getFirst())));
                assertEquals(0, owner.getForeignRoots().size());
                assertFalse(com.oracle.truffle.api.interop.InteropLibrary.getUncached().isMemberReadable(
                    owner.getNativeCallbacks().namespace(), "declared_identity"));
                if (earlyFinalizer) {
                    assertSame(first, assertThrows(RuntimeException.class, () -> owner.getPackageCbits().finalizer("startup_label")));
                    var field = thc.runtime.PackageScalarLibraries.class.getDeclaredField("finalizers"); field.setAccessible(true);
                    var retained = ((thc.runtime.PackageFinalizerRegistry) field.get(owner.getPackageCbits())).resolve("startup_label");
                    assertNotNull(retained);
                    byte[] bytes = {40};
                    assertSame(first, assertThrows(RuntimeException.class, () -> retained.invoke(thc.runtime.ManagedAddress.fromByteArray(bytes))));
                    assertArrayEquals(new byte[]{40}, bytes);
                }
            } finally { context.leave(); }
        }
    }

    @Timeout(20)
    @ParameterizedTest @CsvSource({"ast,false,function-addr", "bytecode,false,function-addr", "ast,true,function-addr", "bytecode,true,function-addr",
        "ast,false,data-addr", "bytecode,false,data-addr", "ast,true,data-addr", "bytecode,true,data-addr"})
    void originalAddressAndFinalizerLiteralsLoadBeforeConstructorCallbacks(String backend, boolean lazy, String kind) throws Exception {
        var module = module(false, kind); var file = CoreCbdFixtures.write(directory.resolve("module.cbd"), module);
        var paths = List.of(lazy ? "@" + packaged(module) : file.toString());
        for (String hosting : List.of("platform", "loom")) try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowCreateThread(true)
                .allowExperimentalOptions(true).option("thc.ThreadHosting", hosting).build()) {
            context.eval("thc", CoreModules.request(paths, unit + ":" + name + ".label", true, false, backend, false));
            context.enter();
            try {
                var owner = Language.currentState(); var program = owner.getForeignRoots().programs().getFirst();
                var label = assertInstanceOf(thc.runtime.ManagedAddress.class, program.entryValue(unit + ":" + name + ".label"));
                if (kind.equals("function-addr")) {
                    byte[] bytes = {40}; label.finalizerFunction().invoke(thc.runtime.ManagedAddress.fromByteArray(bytes));
                    assertArrayEquals(new byte[]{41}, bytes);
                    assertSame(label.finalizerFunction(), owner.getPackageCbits().finalizer("startup_label"),
                        "constructor and completed library retain one canonical finalizer");
                } else assertEquals(29, label.readWord8(0));
                var link = Objects.requireNonNull(PackageScalarLinks.read(module)).getLink();
                var receiver = owner.getPackageCbits().resolve(link, link.getAbi().getFirst()).getReceiver();
                var interop = com.oracle.truffle.api.interop.InteropLibrary.getUncached();
                assertEquals(49, interop.asInt(interop.execute(receiver, 42)));
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void failedNativeInitializationDoesNotPublishRootsOrCallableExports(String backend, boolean lazy) throws Exception {
        var module = module(true); var file = CoreCbdFixtures.write(directory.resolve("module.cbd"), module);
        var paths = List.of(lazy ? "@" + packaged(module) : file.toString());
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
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void failedConstructorCannotPublishItsEarlyFinalizer(String backend, boolean lazy) throws Exception {
        var module = module(true, "function-addr"); var file = CoreCbdFixtures.write(directory.resolve("module.cbd"), module);
        var paths = List.of(lazy ? "@" + packaged(module) : file.toString());
        for (String hosting : List.of("platform", "loom")) try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowCreateThread(true)
                .allowExperimentalOptions(true).option("thc.ThreadHosting", hosting).build()) {
            var failure = assertThrows(RuntimeException.class,
                () -> context.eval("thc", CoreModules.managedExportRequest(paths, backend, true)));
            assertTrue(failure.getMessage().contains("missing_initializer_dependency"), failure.toString());
            context.enter();
            try {
                var owner = Language.currentState();
                var lookup = assertThrows(RuntimeException.class, () -> owner.getPackageCbits().finalizer("startup_label"));
                assertTrue(lookup.getMessage().contains("missing_initializer_dependency"), lookup.toString());
                assertSame(lookup, assertThrows(RuntimeException.class, () -> owner.getPackageCbits().finalizer("startup_label")),
                    "repeat lookup observes the same failed load, not a retry");
                // Inspect the real object published during the callback, bypassing
                // the now-rejecting lookup to model an already retained handle.
                var field = thc.runtime.PackageScalarLibraries.class.getDeclaredField("finalizers"); field.setAccessible(true);
                var retained = ((thc.runtime.PackageFinalizerRegistry) field.get(owner.getPackageCbits())).resolve("startup_label");
                assertNotNull(retained);
                byte[] bytes = {40};
                assertSame(lookup, assertThrows(RuntimeException.class, () -> retained.invoke(thc.runtime.ManagedAddress.fromByteArray(bytes))));
                assertArrayEquals(new byte[]{40}, bytes, "failed component cannot execute its retained finalizer");
                assertEquals(0, owner.getForeignRoots().size());
            } finally { context.leave(); }
        }
    }
    @Timeout(20)
    @ParameterizedTest @CsvSource({"ast,false,false", "bytecode,false,false", "ast,true,false", "bytecode,true,false",
        "ast,false,true", "bytecode,false,true", "ast,true,true", "bytecode,true,true"})
    void publicLoadRegistersBeforeOriginalConstructorCallsBack(String backend, boolean lazy, boolean managed) throws Exception {
        var module = module(); var file = CoreCbdFixtures.write(directory.resolve("module.cbd"), module);
        var paths = List.of(lazy ? "@" + packaged(module) : file.toString());
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
