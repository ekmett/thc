// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Structural controls only: these bytes are never parsed as LLVM or called. */
class PackageScalarLinksTest {
    private final String digest = "a".repeat(64), nativeEntry = "thc_native_" + digest + "_0";
    private Map<?, ?> object(Map<?, ?> owner, String key) { return (Map<?, ?>) owner.get(key); }
    private Map<?, ?> single(Map<?, ?> owner, String key) { var values = (List<?>) owner.get(key); assertEquals(1, values.size()); return (Map<?, ?>) values.getFirst(); }
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private Map<String, Object> module() throws Exception { return module("scalar-fixture", "Scalar", digest); }
    private Map<String, Object> module(String unit, String name, String component) throws Exception {
        var scalarType = map("kind", "tycon", "arguments", List.of(), "name", map("unit", "ghc-internal", "module", "GHC.Internal.Int", "occurrence", "Int32", "namespace", "type"));
        var entry = map("symbol", "scalar_value", "entry", "thc_scalar_" + component + "_0", "arguments", list("Int32Rep"), "result", "Int32Rep");
        var target = System.getProperty("os.name").startsWith("Mac") ? System.getProperty("os.arch") + "-apple-darwin"
            : System.getProperty("os.name").startsWith("Windows") ? "x86_64-pc-windows-msvc19.33.0"
            : (System.getProperty("os.arch").equals("amd64") ? "x86_64" : System.getProperty("os.arch")) + "-unknown-linux-gnu";
        var link = map("schema", 1L, "format", "llvm-bitcode", "profile", "thc-local-scalar-ccall-v1", "unit", unit, "target", target,
            "componentSha256", component, "bitcodeSha256", hash(new byte[]{0x42, 0x43}), "bitcodeHex", "4243", "abi", list(entry));
        var imported = map("binder", map("unit", unit, "module", name, "occurrence", "value", "namespace", "value"), "header", null,
            "symbol", "scalar_value", "unit", null, "isFunction", true, "convention", "ccall", "safety", "unsafe", "declaredType", scalarType,
            "normalizedType", scalarType, "normalizationRole", "representational", "emitted", map("symbol", "scalar_value", "unit", unit,
                "convention", "ccall", "safety", "unsafe", "arguments", list("Int32Rep", "void"), "result", list("void", "Int32Rep")));
        var proof = map("schema", 1L, "scope", "retained-static-import-products", "execution", "not-linked", "profile", "ghc-9.14.1-thc-only-static-c-imports-v1",
            "unit", unit, "module", name, "status", "verified", "wordBits", 64L, "expectedForeign", map("schema", 1L, "execution", "not-linked", "stubs", null, "files", List.of()),
            "imports", list(imported), "expectedCalls", List.of());
        return map("schema", 1L, "ghc", "9.14.1", "unit", unit, "module", name, "bindings", List.of(), "constructors", List.of(), "staticForeignImports", proof, "packageScalarLink", link);
    }
    private Map<String, Object> binding(String id, List<?> calls) {
        return map("id", "scalar-fixture:Scalar." + id, "expr", list("lit", "int", "7", calls.stream().map(it -> map("foreignCall", it)).toList()));
    }
    @Test void exactCoreOwnedImportDoesNotRequireAnInventedNativeEntry() throws Exception {
        var base = module("ghc-internal", "Scalar", digest);
        var scalar = object(base, "packageScalarLink"); var abi = single(scalar, "abi");
        var link = with(scalar, "profile", "thc-package-c-ffi-v1",
            "abi", list(with(abi, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var proof = object(base, "staticForeignImports"); var original = single(proof, "imports");
        var state = map("kind", "void", "primReps", List.of(), "evaluated", false);
        var address = map("kind", "address", "primReps", list("AddrRep"), "evaluated", false);
        var call = map("schema", 1L, "target", map("kind", "static", "unit", "ghc-internal",
            "symbol", "getProgArgv", "isFunction", true), "convention", "ccall", "safety", "unsafe",
            "arity", 3L, "suppliedArity", 3L, "argumentReps", list(address, address, state),
            "resultRep", map("kind", "unknown", "primReps", List.of(), "evaluated", false,
                "aggregate", "unboxed-tuple", "components", list(with(state, "evaluated", true))));
        var emitted = map("symbol", "getProgArgv", "unit", "ghc-internal", "convention", "ccall",
            "safety", "unsafe", "arguments", list("AddrRep", "AddrRep", "void"), "result", list("void"));
        var owned = with(original, "symbol", "getProgArgv", "binder",
            with(object(original, "binder"), "occurrence", "getProgArgv"), "emitted", emitted);
        var mixed = with(without(base, "packageScalarLink"), "packageNativeLink", link,
            "staticForeignImports", with(proof, "imports", list(original, owned), "expectedCalls", list(call)),
            "bindings", list(map("foreignCall", call)));
        assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(mixed)).getProved());
        assertEquals(1, Objects.requireNonNull(PackageScalarLinks.read(mixed)).getLink().getAbi().size(),
            "the real ordinary native entry stays; the Core operation is not advertised as native");
        for (var header : List.of("Rts.h", "native.h")) {
            var retainedHeader = with(mixed, "staticForeignImports",
                with(proof, "imports", list(original, with(owned, "header", header)), "expectedCalls", list(call)));
            assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(retainedHeader)).getProved());
            assertEquals(1, Objects.requireNonNull(PackageScalarLinks.read(retainedHeader)).getLink().getAbi().size(),
                "source headers do not manufacture native providers for exact Core capabilities");
        }
        for (var bad : list(with(emitted, "unit", "ordinary-unit"), with(emitted, "safety", "safe"),
                with(emitted, "arguments", list("IntRep", "AddrRep", "void"))))
            assertThrows(RuntimeException.class, () -> PackageScalarLinks.read(with(mixed, "staticForeignImports",
                with(proof, "imports", list(original, with(owned, "emitted", bad)), "expectedCalls", list(call)))));
        for (var bad : list(with(call, "schema", 2L), with(call, "safety", "safe"),
                with(call, "argumentTypes", list(null, null, null)),
                with(call, "target", with(object(call, "target"), "isFunction", false))))
            assertThrows(RuntimeException.class, () -> PackageScalarLinks.read(with(mixed,
                "staticForeignImports", with(proof, "imports", list(original, owned), "expectedCalls", list(bad)),
                "bindings", list(map("foreignCall", bad)))));
        assertThrows(RuntimeException.class, () -> PackageScalarLinks.read(with(mixed,
            "staticForeignImports", with(proof, "imports", list(with(original, "emitted",
                with(object(original, "emitted"), "symbol", "missing_ordinary")), owned), "expectedCalls", list(call)))));
        assertThrows(RuntimeException.class, () -> PackageScalarLinks.read(with(mixed, "bindings", List.of())));
    }
    @Test void javascriptDescriptorLeavesRealNativeAdapterObligations() throws Exception {
        var base = module(); var scalar = object(base, "packageScalarLink"); var abi = single(scalar, "abi");
        var link = with(scalar, "profile", "thc-package-c-ffi-v1", "abi", list(with(abi, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var proof = object(base, "staticForeignImports"); var original = single(proof, "imports");
        String source = "() => 7", symbol = "thc_javascript_v1_" + HexFormat.of().formatHex(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var call = map("schema", 1L, "intrinsic", "javascript-v1", "javascriptSource", source, "convention", "ccall", "safety", "unsafe",
            "target", map("kind", "static", "isFunction", true, "unit", base.get("unit"), "symbol", symbol));
        var emitted = with(object(original, "emitted"), "symbol", symbol, "arguments", list("void"), "result", list("void", "IntRep"));
        var javascript = with(original, "symbol", symbol, "binder", with(object(original, "binder"), "occurrence", "javascript"), "emitted", emitted);
        var imports = list(original, javascript);
        var mixed = with(without(base, "packageScalarLink"), "packageNativeLink", link,
            "staticForeignImports", with(proof, "imports", imports, "expectedCalls", list(call)), "bindings", list(binding("javascript", list(call))));
        assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(mixed)).getProved());
        assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(with(mixed, "bindings", List.of()), true, false)).getProved());
        for (var bad : list(with(call, "intrinsic", "other"), with(call, "javascriptSource", "() => 8"),
                with(call, "target", with(object(call, "target"), "unit", "other"))))
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(mixed,
                "staticForeignImports", with(proof, "imports", imports, "expectedCalls", list(bad)), "bindings", list(binding("javascript", list(bad))))));
        for (var bad : list(with(emitted, "unit", "other"), with(emitted, "safety", "safe"),
                with(emitted, "arguments", list("invented", "void")), with(emitted, "result", list("void", "MutableByteArray#"))))
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(mixed, "staticForeignImports",
                with(proof, "imports", list(original, with(javascript, "emitted", bad)), "expectedCalls", list(call)))));
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(mixed, "bindings", List.of())));
        for (var bad : list(list(original, with(javascript, "normalizedType", Map.of())),
                list(with(original, "emitted", with(object(original, "emitted"), "result", list("void", "IntRep"))), javascript)))
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(mixed, "staticForeignImports",
                with(proof, "imports", bad, "expectedCalls", list(call)))));
    }
    @Test void mixedStaticExportRegistrationKeepsItsOriginalProductsSeparateFromNativeAdapters() throws Exception {
        var base = module(); var scalar = object(base, "packageScalarLink"); var abi = single(scalar, "abi");
        var link = with(scalar, "profile", "thc-package-c-ffi-v1",
            "abi", list(with(abi, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var proof = object(base, "staticForeignImports");
        var type = object(single(proof, "imports"), "normalizedType");
        var binder = map("unit", base.get("unit"), "module", base.get("module"), "occurrence", "exported", "namespace", "value");
        var declaration = map("binder", binder, "symbol", "managed_value", "convention", "ccall", "declaredType", type,
            "normalizedType", type, "normalizationRole", "representational", "arguments", List.of(), "result", type, "effect", "pure");
        var inventory = map("schema", 1L, "producer", "THC.Plugin/typeCheckResultAction", "scope", "static-export-associations",
            "execution", "not-linked", "unit", base.get("unit"), "module", base.get("module"), "exports", list(declaration));
        var product = map("schema", 1L, "execution", "not-linked", "files", List.of(), "stubs", map("header", "HsInt32 managed_value(void);",
            "source", "original GHC export products", "initializers", list(map("unit", base.get("unit"), "module", base.get("module"),
                "isInitializer", true, "name", "register_export")), "finalizers", List.of()));
        var registration = map("schema", 2L, "scope", "retained-foreign-products", "execution", "not-linked",
            "profile", "ghc-9.14.1-thc-only-native-static-c-products-v3", "status", "verified", "roots", list(binder),
            "wordBits", 64L, "expectedForeign", product, "expectedExports", inventory);
        var mixedProof = with(proof, "schema", 4L, "expectedForeign", product, "importForeign", proof.get("expectedForeign"),
            "addresses", List.of(), "wrappers", List.of());
        var mixed = with(without(base, "packageScalarLink"), "schema", 2L, "foreign", product, "staticForeignImports", mixedProof,
            "staticForeignExports", inventory, "staticForeignExportRegistration", registration, "packageNativeLink", link,
            "bindings", list(binding("exported", List.of())));
        assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(mixed)).getProved());
        assertEquals("managed_value", ManagedExportAdmission.read(mixed).getExports().getFirst().symbol());
        for (var changed : List.of(without(mixed, "staticForeignExportRegistration"),
            with(mixed, "staticForeignExports", with(inventory, "exports", List.of())),
            with(mixed, "staticForeignImports", with(mixedProof, "importForeign", product)),
            with(mixed, "foreign", with(product, "files", list("unexpected"))),
            with(mixed, "staticForeignExportRegistration", with(registration, "roots", List.of()))))
            assertThrows(RuntimeException.class, () -> PackageScalarLinks.read(changed));
    }
    @Test void nativeDependencyBytesAreVerifiedAndPartOfLoadedIdentity() throws Exception {
        var base = module(); var scalar = object(base, "packageScalarLink"); var entry = single(scalar, "abi");
        var dependency = map("sha256", hash(new byte[]{1, 2}), "hex", "0102");
        var link = with(scalar, "profile", "thc-package-c-ffi-v1", "nativeLibrary", dependency,
            "format", System.getProperty("os.name").startsWith("Windows") ? "llvm-bitcode" :
                System.getProperty("os.name").startsWith("Mac") ? "llvm-embedded-mach-o" : "llvm-embedded-elf",
            "abi", list(with(entry, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var nativeModule = with(without(base, "packageScalarLink"), "packageNativeLink", link);
        if (!System.getProperty("os.name").equals("Linux") && !System.getProperty("os.name").startsWith("Mac") &&
                !System.getProperty("os.name").startsWith("Windows")) {
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(nativeModule));
            return;
        }
        var first = Objects.requireNonNull(PackageScalarLinks.read(nativeModule)).getLink();
        assertArrayEquals(new byte[]{1, 2}, Objects.requireNonNull(PackageScalarLinks.read(with(nativeModule,
            "packageNativeLink", with(link, "format", "llvm-bitcode")))).getLink().getNativeLibrary());
        var changed = with(link, "nativeLibrary", map("sha256", hash(new byte[]{1, 3}), "hex", "0103"));
        assertFalse(first.same(Objects.requireNonNull(PackageScalarLinks.read(with(nativeModule, "packageNativeLink", changed))).getLink()));
        var components = new HashMap<String,PackageScalarLink>();
        var one = new CoreModuleAdmission(nativeModule, id -> null, components).getPackageLink();
        var two = new CoreModuleAdmission(nativeModule, id -> null, components).getPackageLink();
        assertSame(one.link(), two.link());
        assertEquals(one.proved(), two.proved());
        assertNotSame(one.link(), new CoreModuleAdmission(nativeModule, id -> null).getPackageLink().link());
        assertThrows(IllegalArgumentException.class, () -> new CoreModuleAdmission(
            with(nativeModule, "packageNativeLink", changed), id -> null, components));
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(nativeModule, "packageNativeLink",
            with(link, "nativeLibrary", with(dependency, "hex", "0103")))));
        var letters = map("sha256", hash(new byte[]{(byte) 0xab, (byte) 0xff}), "hex", "abff");
        assertArrayEquals(new byte[]{(byte) 0xab, (byte) 0xff}, Objects.requireNonNull(PackageScalarLinks.read(with(nativeModule,
            "packageNativeLink", with(link, "nativeLibrary", letters)))).getLink().getNativeLibrary());
        for (String hex : List.of("ABFF", "abFf", "abfg", "abf", ""))
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(nativeModule, "packageNativeLink",
                with(link, "nativeLibrary", with(letters, "hex", hex)))));
    }
    @Test void bundledNativeProvidersValidateNamesBytesAndIdentity() throws Exception {
        var base = module(); var scalar = object(base, "packageScalarLink"); var entry = single(scalar, "abi");
        var provider = map("name", "libfixture.so.1", "sha256", hash(new byte[]{3, 4}), "hex", "0304");
        var nativeLibrary = map("sha256", hash(new byte[]{1, 2}), "hex", "0102", "bundledLibraries", list(provider));
        var link = with(scalar, "profile", "thc-package-c-ffi-v1", "nativeLibrary", nativeLibrary,
            "abi", list(with(entry, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var module = with(without(base, "packageScalarLink"), "packageNativeLink", link);
        if (!System.getProperty("os.name").equals("Linux")) {
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(module));
            return;
        }
        var first = Objects.requireNonNull(PackageScalarLinks.read(module)).getLink();
        var bundled = first.getComponent().bundledLibraries().getFirst();
        assertEquals("libfixture.so.1", bundled.name());
        var copy = bundled.bytes(); copy[0] = 99;
        assertArrayEquals(new byte[]{3, 4}, bundled.bytes(), "provider bytes are immutable");
        var sourceBytes = new byte[]{3, 4};
        var retained = new PackageNativeComponent.BundledLibrary(bundled.name(), bundled.sha256(), sourceBytes);
        sourceBytes[0] = 99; assertTrue(bundled.same(retained), "provider retains its own immutable bytes");
        var nested = map("schema", 1L, "profile", "thc-package-native-component-v1", "unit", "native-provider",
            "target", scalar.get("target"), "componentSha256", "b".repeat(64), "bitcodeSha256", hash(new byte[]{5, 6}),
            "bitcodeHex", "0506", "format", "llvm-bitcode", "exports", List.of(), "dependencies", List.of(), "nativeLibrary", nativeLibrary);
        var withDependency = with(module, "packageNativeLink", with(link, "exports", List.of(), "dependencies", list(nested)));
        assertTrue(bundled.same(Objects.requireNonNull(PackageScalarLinks.read(withDependency)).getLink()
            .getComponent().dependencies().getFirst().bundledLibraries().getFirst()));
        var changed = with(provider, "sha256", hash(new byte[]{3, 5}), "hex", "0305");
        assertFalse(first.same(Objects.requireNonNull(PackageScalarLinks.read(with(module, "packageNativeLink",
            with(link, "nativeLibrary", with(nativeLibrary, "bundledLibraries", list(changed)))))).getLink()));
        for (String name : List.of("../libfixture.so", "/libfixture.so", "lib/fixture.so", "lib\\fixture.so", ".", "..", "-libfixture.so", "libfixture.so\n"))
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(module, "packageNativeLink",
                with(link, "nativeLibrary", with(nativeLibrary, "bundledLibraries", list(with(provider, "name", name)))))));
        for (var invalid : list(with(provider, "hex", "0305"), with(provider, "hex", ""),
                with(provider, "hex", "030A"), with(provider, "sha256", "invalid")))
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(module, "packageNativeLink",
                with(link, "nativeLibrary", with(nativeLibrary, "bundledLibraries", list(invalid))))));
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(module, "packageNativeLink",
            with(link, "nativeLibrary", with(nativeLibrary, "bundledLibraries", list(provider, provider))))));
    }
    @Test void windowsAdmissionRejectsMinGwAndForeignCpuTargets() throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) return;
        var base = module(); var link = object(base, "packageScalarLink");
        assertNotNull(PackageScalarLinks.read(base));
        for (String target : List.of("x86_64-w64-windows-gnu", "x86_64-unknown-windows-gnu",
                "aarch64-pc-windows-msvc19.33.0", "x86_64-unknown-linux-gnu"))
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(base,
                "packageScalarLink", with(link, "target", target))));
    }
    @Test void nativeProvidersDoNotInventHaskellAbisAndKeepTheirExactClosure() throws Exception {
        var base = module(); var scalar = object(base, "packageScalarLink"); var entry = single(scalar, "abi");
        var provider = map("schema", 1L, "profile", "thc-package-native-component-v1", "unit", "native-provider",
            "target", scalar.get("target"), "componentSha256", "b".repeat(64), "bitcodeSha256", hash(new byte[]{1, (byte) 0xab}),
            "bitcodeHex", "01ab", "format", "llvm-bitcode", "exports", list("provider_next"), "dependencies", List.of());
        var link = with(scalar, "profile", "thc-package-c-ffi-v1", "exports", list("scalar_value"), "dependencies", list(provider),
            "abi", list(with(entry, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var nativeModule = with(without(base, "packageScalarLink"), "packageNativeLink", link);
        var admitted = Objects.requireNonNull(PackageScalarLinks.read(nativeModule));
        assertEquals(Set.of(nativeEntry), admitted.getProved());
        assertEquals(1, admitted.getLink().getAbi().size(), "native exports are not Haskell callable signatures");
        var component = admitted.getLink().getComponent().dependencies().getFirst();
        assertEquals("native-provider", component.unit());
        assertEquals(Set.of("provider_next"), component.exports());
        for (var bad : list(with(provider, "bitcodeHex", "0103"), with(provider, "bitcodeHex", "01AB"), with(provider, "target", "other-target"),
                with(provider, "exports", list("provider_next", "provider_next")), with(provider, "abi", List.of()),
                with(provider, "unit", base.get("unit")), with(provider, "dependencies", list(provider))))
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(nativeModule,
                "packageNativeLink", with(link, "dependencies", list(bad)))));
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(nativeModule,
            "packageNativeLink", with(link, "dependencies", list(provider, provider)))));
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(nativeModule,
            "packageNativeLink", with(link, "abi", List.of()))), "ordinary typed ABI proof is still required");
    }
    @Test void completeCallInventoriesIgnoreTraversalOrderButKeepDescriptorsAndCounts() {
        var first = map("target", map("unit", "scalar-fixture", "symbol", "first"),
            "safety", "unsafe", "argumentReps", list("AddrRep", "IntRep"));
        var second = with(first, "target", map("unit", "scalar-fixture", "symbol", "second"));
        var expected = list(first, second, first);
        // CBD enumerates bindings by fingerprint; this is not call execution order.
        assertDoesNotThrow(() -> CoreCallInventory.check(expected, list(second, first, first), true));
        for (var actual : List.of(list(first, second), list(first, second, first, second),
                list(first, first, first), list(second, first, with(first, "safety", "safe")),
                list(second, first, with(first, "argumentReps", list("IntRep", "AddrRep")))))
            assertThrows(IllegalArgumentException.class, () -> CoreCallInventory.check(expected, actual, true));
    }
    @Test void nestedCallInventoriesRetainDuplicatesAndMalformedValuesForAdmission() throws Exception {
        var first = map("target", "first"); var second = map("target", "second");
        var nested = list(map("foreignCall", first, "children", list(map("foreignCall", second), map("foreignCall", first))),
                map("foreignCall", "malformed"), map("foreignCall", null), "literal");
        var actual = CoreCallInventory.calls(nested);
        CoreCallInventory.check(list(second, first, first, "malformed"), actual, true);
        CoreCallInventory.check(list(second, first, first), CoreCallInventory.mapCalls(nested), true);
        assertThrows(IllegalArgumentException.class, () -> CoreCallInventory.check(list(first, second, "malformed"), actual, true));
        assertThrows(IllegalArgumentException.class, () -> CoreCallInventory.check(list(first, first, first, second, "malformed"), actual, true));
        var original = module();
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(original,
                "bindings", list(map("foreignCall", "malformed")))));
    }
    @Test void demandedBindingsUseOriginalInventoriesWithoutClaimingCompleteness() throws Exception {
        var base = module(); var proof = object(base, "staticForeignImports"); var call = map("target", map("unit", base.get("unit"), "symbol", "scalar_value"));
        var first = binding("first", list(call)); var second = binding("second", list(call));
        var original = with(base, "bindings", list(first, second), "staticForeignImports", with(proof, "expectedCalls", list(call, call)));
        CoreModules.merge(List.of(original)); assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(with(original, "bindings", list(first)))));
        var admission = new CoreModuleAdmission(with(original, "bindings", List.of()), id -> { throw new IllegalStateException("No exports"); });
        var merger = new CoreModules.Merger(); merger.addSelected(admission, List.of(first)); assertEquals(list(first), merger.finish().get("bindings"));
        for (var bad : List.of(binding("altered", list(with(call, "safety", "safe"))), binding("duplicated", list(call, call, call))))
            assertThrows(IllegalArgumentException.class, () -> admission.selected(List.of(bad)));
        // Installed native components retain a compiled ABI even when their
        // original source annotations are absent from an older interface.
        var scalar = object(original, "packageScalarLink"); var entry = single(scalar, "abi");
        var nativeLink = with(scalar, "profile", "thc-package-c-ffi-v1", "abi", list(with(entry, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var nativeModule = with(without(original, "packageScalarLink"), "packageNativeLink", nativeLink);
        var declaration = new CoreModuleAdmission(with(nativeModule, "bindings", List.of()), id -> { throw new IllegalStateException("No exports"); });
        var inlined = with(without(nativeModule, "staticForeignImports"), "module", "Inline", "bindings", List.of());
        var inlineAdmission = new CoreModuleAdmission(inlined, id -> { throw new IllegalStateException("No exports"); });
        var installed = new CoreModules.Merger(); installed.addSelected(inlineAdmission, List.of()); installed.finish();
        assertTrue(PackageFinalizers.hasDeclarations(inlined));
        var stubs = map("header", "", "source", "int capi_wrapper(void) { return 7; }", "initializers", List.of(), "finalizers", List.of());
        var product = map("schema", 1L, "execution", "not-linked", "stubs", stubs, "files", List.of());
        assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(with(inlined, "foreign", product))).getProved());
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(inlined, "foreign",
            with(product, "stubs", with(stubs, "initializers", list("register_callback"))))));
        var complete = new CoreModules.Merger(); complete.addSelected(inlineAdmission, List.of()); complete.addPackageProvenance(Objects.requireNonNull(declaration.getPackageLink())); complete.finish();
    }
    @Test void detachedOriginalInventoryIsRecheckedAsACountedSubset() throws Exception {
        var base = module(); var proof = object(base, "staticForeignImports");
        var call = map("target", map("unit", base.get("unit"), "symbol", "scalar_value"));
        var entry = map("id", "scalar-fixture:Scalar.entry", "name", "entry", "arity", 0, "lifted", false,
            "rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true), "expr", list("lit", "int", "7"));
        var selected = with(base, "bindings", list(entry), "staticForeignImports", with(proof, "expectedCalls", list(call, call)));
        // Inspect the decoded cold-admission boundary. These structural bitcode
        // bytes must never become a public inline runtime input or be executed.
        var admission = new CoreModuleAdmission(with(selected, "bindings", List.of()), _ -> entry);
        assertEquals(list(entry), admission.selected(List.of(entry)).get("bindings"));
        assertNotNull(PackageScalarLinks.read(selected, true, false));
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(selected, true, true));
        for (var bad : List.of(binding("altered", list(with(call, "safety", "safe"))), binding("duplicated", list(call, call, call))))
            assertThrows(IllegalArgumentException.class, () -> admission.selected(List.of(entry, bad)));
    }
    private Map<String, Object> foreignCall(Map<?, ?> base, String symbol) {
        return map("foreignCall", map("target", map("unit", base.get("unit"), "symbol", symbol), "convention", "ccall", "safety", "unsafe",
            "argumentReps", list(map("primReps", list("Int32Rep")), map("primReps", List.of()))));
    }
    @Test void retiredPartialNativeProtocolIsRejected() throws Exception {
        var base = module(); var scalar = object(base, "packageScalarLink"); var entry = single(scalar, "abi");
        var nativeLink = with(scalar, "profile", "thc-package-c-ffi-v1",
            "abi", list(with(entry, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var nativeModule = with(without(base, "packageScalarLink"), "packageNativeLink", nativeLink);
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(nativeModule,
            "packageNativeLink", with(nativeLink, "availableEntries", list(nativeEntry)))));
        assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(with(base,
            "packageNativeArchive", map("entryResolution", Map.of()))));
        var pointerCall = with(entry, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe", "arguments", List.of(), "result", "AddrRep");
        String addressEntry = "thc_native_" + digest + "_1";
        var withAddress = with(nativeLink, "abi", list(pointerCall, with(pointerCall, "entry", addressEntry)), "dataSymbols", list(addressEntry));
        var addressModule = with(without(nativeModule, "staticForeignImports"), "packageNativeLink", withAddress);
        assertEquals(Set.of(nativeEntry, addressEntry), Objects.requireNonNull(PackageScalarLinks.read(addressModule)).getProved());
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(addressModule,
            "packageNativeLink", without(withAddress, "dataSymbols"))));
    }
    @Test void conflictingOriginalAbiWitnessesExcludeOnlyThatSymbol() throws Exception {
        var base = module(); var scalar = object(base, "packageScalarLink"); var abi = single(scalar, "abi");
        var link = with(scalar, "profile", "thc-package-c-ffi-v1", "abi", list(with(abi, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var proof = object(base, "staticForeignImports"); var original = single(proof, "imports"); var emitted = with(object(original, "emitted"), "symbol", "width");
        var wide = with(emitted, "result", list("void", "Int64Rep")); var narrow = with(original, "symbol", "width", "emitted", emitted, "binder", with(object(original, "binder"), "occurrence", "narrow"));
        var archive = map("schema", 1L, "profile", "thc-package-native-archive-v1", "execution", "not-linked", "unit", base.get("unit"), "module", base.get("module"),
            "unsupportedImports", list(emitted), "unclassifiedReason", null, "unresolvedSymbols", List.of(), "artifact", null, "conflictingImports", list(emitted, wide));
        var mixed = with(without(base, "packageScalarLink"), "packageNativeLink", link, "packageNativeArchive", archive, "staticForeignImports", with(proof, "imports", list(original, narrow)));
        var retained = Objects.requireNonNull(PackageNativeArchives.read(mixed)); assertFalse(retained.getWholeModule()); assertFalse(retained.blocks(Map.of()));
        assertTrue(retained.blocks(map("foreignCall", map("target", map("unit", base.get("unit"), "symbol", "width"), "convention", "ccall", "safety", "unsafe"))));
        assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(mixed)).getProved());
        for (var bad : list(List.of(), list(emitted), list(wide), list(emitted, emitted), list(emitted, with(wide, "unit", "other")), list(emitted, with(wide, "result", list("void", "invented")))))
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(mixed, "packageNativeArchive", with(archive, "conflictingImports", bad))));
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(mixed, "staticForeignImports", with(proof, "imports", list(original, with(narrow, "normalizedType", Map.of()))))));
    }
    @Test void nativeArchiveKeepsMixedDeclarationsButNeverAdmitsTheirUnsupportedEffects() throws Exception {
        var base = module(); var scalar = object(base, "packageScalarLink"); var entry = single(scalar, "abi");
        var link = with(scalar, "profile", "thc-package-c-ffi-v1", "abi", list(with(entry, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var proof = object(base, "staticForeignImports"); var original = single(proof, "imports"); var binder = object(original, "binder");
        var emitted = map("symbol", "blocked", "unit", base.get("unit"), "convention", "ccall", "safety", "interruptible", "arguments", list("AddrRep", "void"), "result", list("void", "Int32Rep"));
        var blocked = with(original, "symbol", "blocked", "safety", "interruptible", "binder", with(binder, "occurrence", "blocked"), "emitted", emitted);
        var call = map("target", map("unit", base.get("unit"), "symbol", "blocked"), "convention", "ccall", "safety", "interruptible");
        var goodId = "scalar-fixture:Scalar.good"; var badId = "scalar-fixture:Scalar.bad";
        var bindings = list(map("id", goodId, "expr", list("lit", "int", 7L)), map("id", badId, "expr", list("lit", "int", 0L, map("foreignCall", call))));
        var archive = map("schema", 1L, "profile", "thc-package-native-archive-v1", "execution", "not-linked", "unit", base.get("unit"), "module", base.get("module"), "unsupportedImports", list(emitted), "unclassifiedReason", null, "unresolvedSymbols", List.of(), "artifact", null);
        var mixedProof = with(proof, "imports", list(original, blocked), "expectedCalls", list(call));
        var mixed = with(without(base, "packageScalarLink"), "packageNativeLink", link, "packageNativeArchive", archive, "staticForeignImports", mixedProof, "bindings", bindings);
        var installed = without(mixed, "packageNativeArchive");
        assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(installed)).getProved());
        assertEquals(mixedProof, installed.get("staticForeignImports"));
        assertEquals(bindings, CoreModules.reachable(CoreModules.merge(List.of(installed)), List.of(goodId, badId), true).get("bindings"));
        var merged = CoreModules.merge(List.of(mixed)); assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(mixed)).getProved());
        assertEquals(list(bindings.getFirst()), CoreModules.reachable(merged, goodId, true).get("bindings"));
        var failure = assertThrows(IllegalArgumentException.class, () -> CoreModules.reachable(merged, badId, true)); assertTrue(failure.getMessage().contains("archive-only"));
        assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.requireExecutable(mixed));
        for (var bad : List.of(with(mixed, "packageNativeArchive", with(archive, "unsupportedImports", List.of())), with(mixed, "staticForeignImports", with(mixedProof, "expectedCalls", List.of())),
                with(mixed, "staticForeignImports", with(mixedProof, "imports", list(original, with(blocked, "normalizedType", Map.of())))), with(mixed, "packageNativeLink", with(link, "bitcodeSha256", "0".repeat(64)))))
            assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(bad)));
        var unresolved = with(without(mixed, "packageNativeLink"), "packageNativeArchive", with(archive, "unresolvedSymbols", list("unknown_external"), "artifact", link));
        assertEquals(List.of(), CoreModules.merge(List.of(unresolved)).get("packageScalarLinks"));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.reachable(CoreModules.merge(List.of(unresolved)), goodId, true));
        var invalid = with(unresolved, "packageNativeArchive", with(archive, "unresolvedSymbols", list("unknown_external"), "artifact", with(link, "bitcodeHex", "4342")));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(invalid)));
    }
    @Test void primProofKeepsTheMixedNativeCAdapterAndItsArchiveGuard() throws Exception {
        var base = module(); var scalar = object(base, "packageScalarLink"); var abi = single(scalar, "abi");
        var link = with(scalar, "profile", "thc-package-c-ffi-v1", "abi", list(with(abi, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var proof = object(base, "staticForeignImports"); var original = single(proof, "imports");
        var emitted = map("symbol", "ordinary_prim", "unit", base.get("unit"), "convention", "prim", "safety", "safe",
            "arguments", list("WordRep"), "result", list("AddrRep", "WordRep"));
        var primitive = with(original, "symbol", "ordinary_prim", "unit", base.get("unit"), "convention", "prim", "safety", "safe",
            "binder", with(object(original, "binder"), "occurrence", "primitive"), "emitted", emitted);
        var word = map("kind", "tycon", "name", map("unit", "ghc-internal", "module", "GHC.Internal.Prim", "occurrence", "Word#", "namespace", "type"), "arguments", List.of());
        var address = with(word, "name", with(object(word, "name"), "occurrence", "Addr#"));
        var many = with(word, "name", with(object(word, "name"), "module", "GHC.Internal.Types", "occurrence", "Many", "namespace", "data"));
        var tuple = with(word, "name", with(object(word, "name"), "module", "GHC.Internal.Types", "occurrence", "Tuple2#"), "arguments", list(
            with(many, "name", with(object(many, "name"), "occurrence", "AddrRep")), with(many, "name", with(object(many, "name"), "occurrence", "WordRep")), address, word));
        var type = map("kind", "function", "multiplicity", many, "argument", word, "result", tuple);
        primitive = with(primitive, "declaredType", type, "normalizedType", type);
        var call = map("schema", 1L, "target", map("kind", "static", "unit", base.get("unit"), "symbol", "ordinary_prim", "isFunction", true),
            "convention", "prim", "safety", "safe", "arity", 1L, "suppliedArity", 1L,
            "argumentReps", list(map("kind", "long", "primReps", list("WordRep"), "evaluated", false)),
            "resultRep", map("kind", "unknown", "primReps", list("AddrRep", "WordRep"), "evaluated", false, "aggregate", "unboxed-tuple", "components", list(
                map("kind", "address", "primReps", list("AddrRep"), "evaluated", true), map("kind", "long", "primReps", list("WordRep"), "evaluated", true))));
        var archive = map("schema", 1L, "profile", "thc-package-native-archive-v1", "execution", "not-linked", "unit", base.get("unit"), "module", base.get("module"),
            "unsupportedImports", list(emitted), "unclassifiedReason", null, "unresolvedSymbols", List.of(), "artifact", null);
        var mixed = with(without(base, "packageScalarLink"), "packageNativeLink", link, "packageNativeArchive", archive,
            "staticForeignImports", with(proof, "profile", "ghc-9.14.1-thc-stock-static-foreign-imports-v2", "imports", list(original, primitive), "expectedCalls", list(call)),
            "bindings", list(binding("primitive", list(call))));
        assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(mixed)).getProved());
        var retained = Objects.requireNonNull(PackageNativeArchives.read(mixed));
        assertFalse(retained.getWholeModule()); assertTrue(retained.blocks(mixed.get("bindings")));
        assertFalse(retained.blocks(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(mixed,
            "packageNativeArchive", with(archive, "unsupportedImports", List.of()))));
    }
    private Map<String, Object> proofChange(Map<?, ?> original, Map<?, ?> proof, Map<?, ?> item) {
        return with(original, "staticForeignImports", with(proof, "imports", list(item)));
    }
    @Test void linkNeedsExactOwnedTypedAbiAndEmptyForeignProducts() throws Exception {
        var original = module(); var link = object(original, "packageScalarLink"); var proof = object(original, "staticForeignImports");
        var imported = single(proof, "imports"); var emitted = object(imported, "emitted");
        var bad = List.of(with(original, "packageScalarLink", with(link, "unit", "another-unit")),
            with(original, "packageScalarLink", with(link, "bitcodeSha256", "0".repeat(64))),
            with(original, "packageScalarLink", with(link, "target", "other-unknown-linux-gnu")),
            with(original, "foreign", proof.get("expectedForeign")), with(original, "staticForeignImports", with(proof, "expectedCalls", list(map("schema", 1L)))),
            proofChange(original, proof, with(imported, "safety", "safe")), proofChange(original, proof, with(imported, "header", "foreign.h")),
            proofChange(original, proof, with(imported, "emitted", with(emitted, "unit", null))),
            proofChange(original, proof, with(imported, "emitted", with(emitted, "unit", "another-unit"))),
            proofChange(original, proof, with(imported, "emitted", with(emitted, "arguments", list("AddrRep", "void")))),
            proofChange(original, proof, with(imported, "emitted", with(emitted, "result", list("void", "Int64Rep")))));
        assertEquals(Set.of("thc_scalar_" + digest + "_0"), Objects.requireNonNull(PackageScalarLinks.read(original)).getProved());
        if (System.getProperty("os.name").startsWith("Mac")) assertNotNull(PackageScalarLinks.read(with(original, "packageScalarLink", with(link, "target", System.getProperty("os.arch") + "-apple-macosx15.0.0"))));
        for (int i = 0; i < bad.size(); i++) { var altered = bad.get(i); assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(altered)), "mutation " + i); }
    }
    @Test void mergedModulesMustProveTheWholeComponentAndKeepOneIdentity() throws Exception {
        var first = module(); var link = object(first, "packageScalarLink"); var abi = single(link, "abi");
        var extra = with(abi, "symbol", "scalar_z", "entry", "thc_scalar_" + digest + "_1");
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(with(first, "packageScalarLink", with(link, "abi", list(abi, extra))))));
        var second = module("scalar-fixture", "Second", digest);
        assertEquals(1, ((List<?>) CoreModules.merge(List.of(first, second)).get("packageScalarLinks")).size());
        var other = with(object(second, "packageScalarLink"), "bitcodeHex", "4342", "bitcodeSha256", hash(new byte[]{0x43, 0x42}));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(first, with(second, "packageScalarLink", other))));
        assertEquals(2, ((List<?>) CoreModules.merge(List.of(first, module("separate-unit", "Scalar", "b".repeat(64)))).get("packageScalarLinks")).size());
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(first, module("separate-unit", "Scalar", digest))));
    }
    @Test void nativeCapiRetainsWrappersAndAdmitsOnlyDeclaredByteArrayCarriers() throws Exception {
        var base = module(); var oldLink = object(base, "packageScalarLink"); var oldProof = object(base, "staticForeignImports"); var oldImport = single(oldProof, "imports");
        var quantified = map("kind", "forall", "binderKind", oldImport.get("declaredType"), "body", map("kind", "bound-variable", "index", 0L));
        var abi = map("symbol", "wrapper", "entry", nativeEntry, "convention", "capi", "safety", "unsafe", "arguments", list("MutableByteArray#", "ByteArray#"), "result", "void");
        var link = with(oldLink, "profile", "thc-package-c-ffi-v1", "abi", list(abi));
        var foreign = map("schema", 1L, "execution", "not-linked", "files", List.of(), "stubs", map("header", "", "source", "void wrapper(void *s, void *p) { update(s,p); }", "initializers", List.of(), "finalizers", List.of()));
        var imported = with(oldImport, "header", "original.h", "convention", "capi", "declaredType", quantified, "normalizedType", quantified,
            "emitted", map("symbol", "wrapper", "unit", base.get("unit"), "convention", "capi", "safety", "unsafe", "arguments", list("MutableByteArray#", "ByteArray#", "void"), "result", list("void")));
        var proof = with(oldProof, "expectedForeign", foreign, "imports", list(imported));
        var nativeModule = with(without(base, "packageScalarLink"), "schema", 2L, "foreign", foreign, "staticForeignImports", proof, "staticForeignImportStubs", proof, "packageNativeLink", link);
        assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(nativeModule)).getProved());
        assertNotNull(PackageScalarLinks.read(with(nativeModule, "packageNativeLink", with(link, "buildInputs", Map.of()))));
        assertEquals(1, ((List<?>) CoreModules.merge(List.of(nativeModule)).get("packageScalarLinks")).size());
        var inlined = with(without(base, "packageScalarLink", "staticForeignImports"), "module", "Inlined", "packageNativeLink", link);
        assertEquals(Set.of(nativeEntry), Objects.requireNonNull(PackageScalarLinks.read(inlined)).getProved());
        assertEquals(1, ((List<?>) CoreModules.merge(List.of(inlined)).get("packageScalarLinks")).size());
        assertEquals(1, ((List<?>) CoreModules.merge(List.of(nativeModule, inlined)).get("packageScalarLinks")).size());
        var bad = List.of(without(nativeModule, "foreign"), without(nativeModule, "staticForeignImports"),
            with(nativeModule, "staticForeignImportStubs", with(proof, "imports", List.of())), with(nativeModule, "foreign", with(foreign, "files", list("unlinked.c"))),
            with(nativeModule, "packageNativeLink", with(link, "abi", list(with(abi, "arguments", list("BoxedRep (Just Unlifted)"))))),
            with(nativeModule, "packageNativeLink", with(link, "abi", list(with(abi, "result", "AddrRep")))),
            with(nativeModule, "packageNativeLink", with(link, "abi", list(with(abi, "safety", "safe")))));
        for (int i = 0; i < bad.size(); i++) { var altered = bad.get(i); assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(altered)), "native mutation " + i); }
        var freeProof = with(proof, "imports", list(with(imported, "declaredType", map("kind", "bound-variable", "index", 0L))));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(with(nativeModule, "staticForeignImports", freeProof, "staticForeignImportStubs", freeProof))));
    }
    private Map<String, Object> variant(List<String> reps) throws Exception {
        var base = module(); var originalLink = object(base, "packageScalarLink"); var originalProof = object(base, "staticForeignImports"); var originalImport = single(originalProof, "imports");
        var abi = new ArrayList<Map<String, Object>>(); var imports = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < reps.size(); i++) {
            var rep = reps.get(i);
            abi.add(map("symbol", "read_bytes", "entry", "thc_native_" + digest + "_" + i, "convention", "ccall", "safety", "unsafe", "arguments", list(rep), "result", "WordRep"));
            imports.add(with(originalImport, "symbol", "read_bytes", "binder", with(object(originalImport, "binder"), "occurrence", "read" + i),
                "emitted", map("symbol", "read_bytes", "unit", base.get("unit"), "convention", "ccall", "safety", "unsafe", "arguments", list(rep, "void"), "result", list("void", "WordRep"))));
        }
        return with(without(base, "packageScalarLink"), "packageNativeLink", with(originalLink, "profile", "thc-package-c-ffi-v1", "abi", abi), "staticForeignImports", with(originalProof, "imports", imports));
    }
    private Map<String, Object> headers(Map<?, ?> nativeModule, Object header) {
        var proof = object(nativeModule, "staticForeignImports");
        return with(nativeModule, "staticForeignImports", with(proof, "imports", ((List<?>) proof.get("imports")).stream().map(it -> with((Map<?, ?>) it, "header", header)).toList()));
    }
    @Test void nativePointerVariantsNeedSeparateExactImportProofs() throws Exception {
        var nativeModule = variant(List.of("AddrRep", "ByteArray#")); var admitted = Objects.requireNonNull(PackageScalarLinks.read(nativeModule));
        assertEquals(new HashSet<>(admitted.getLink().getAbi().stream().map(it -> it.getEntry()).toList()), admitted.getProved()); assertEquals(2, admitted.getProved().size());
        assertEquals(1, ((List<?>) CoreModules.merge(List.of(nativeModule)).get("packageScalarLinks")).size());
        var proof = object(nativeModule, "staticForeignImports"); var imports = (List<?>) proof.get("imports");
        assertEquals(admitted.getProved(), Objects.requireNonNull(PackageScalarLinks.read(headers(nativeModule, "original.h"))).getProved());
        for (var invalid : list("", "bad\u0000header", 7L)) assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(headers(nativeModule, invalid)));
        for (var one : imports) assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(with(nativeModule, "staticForeignImports", with(proof, "imports", list(one))))));
        for (var reps : List.of(List.of("AddrRep", "WordRep"), List.of("ByteArray#", "AddrRep"), List.of("AddrRep", "AddrRep"), List.of("Int8Rep", "Word16Rep"), List.of("IntRep", "Word64Rep")))
            assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(variant(reps))));
        for (var width : List.of("", "8", "16", "32", "64")) {
            var signed = variant(List.of("Int" + width + "Rep", "Word" + width + "Rep"));
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(signed));
            var admittedSigned = Objects.requireNonNull(PackageScalarLinks.read(headers(signed, "primitive-memops.h")));
            assertEquals(2, admittedSigned.getProved().size());
            assertEquals(List.of("Int" + width + "Rep", "Word" + width + "Rep"), admittedSigned.getLink().getAbi().stream().map(it -> { assertEquals(1, it.getArguments().size()); return it.getArguments().getFirst(); }).toList());
            for (var invalid : list("", "bad\nheader", "bad\"header", "bad\\header", null)) assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(headers(signed, invalid)));
        }
    }
    @Test void typedArrayCallsSelectMutabilityWithoutGuessingErasedCalls() throws Exception {
        var admitted = Objects.requireNonNull(PackageScalarLinks.read(variant(List.of("ByteArray#", "MutableByteArray#"))));
        assertEquals(2, admitted.getProved().size());
        var array = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", false);
        var state = map("kind", "void", "primReps", list(), "evaluated", false);
        var word = map("kind", "long", "primReps", list("WordRep"), "evaluated", true);
        var result = map("kind", "unknown", "primReps", list("WordRep"), "evaluated", false,
            "aggregate", "unboxed-tuple", "components", list(with(state, "evaluated", true), word));
        var arguments = list(array, state);
        var call = map("schema", 2L, "target", map("kind", "static", "symbol", "read_bytes", "unit", admitted.getLink().getUnit(), "isFunction", true),
            "convention", "ccall", "safety", "unsafe", "arity", 2L, "suppliedArity", 2L,
            "argumentReps", arguments, "resultRep", result);
        for (String rep : List.of("ByteArray#", "MutableByteArray#")) {
            var typed = with(call, "argumentTypes", list(rep, null));
            var selected = thc.runtime.CorePackageScalarForeign.validate(map("foreignCall", typed, "rep", result),
                arguments, list(false, false), result, List.of(admitted.getLink()));
            assertEquals(List.of(rep), selected.getSignature().arguments());
        }
        var erased = with(call, "schema", 1L);
        assertThrows(thc.runtime.RuntimeFault.class, () -> thc.runtime.CorePackageScalarForeign.validate(
            map("foreignCall", erased, "rep", result), arguments, list(false, false), result, List.of(admitted.getLink())));
    }
    private Map<String, Object> nativeModule(String rep, String safety, String result) throws Exception {
        var base = module(); var oldLink = object(base, "packageScalarLink"); var oldProof = object(base, "staticForeignImports"); var oldImport = single(oldProof, "imports");
        var abi = map("symbol", "scalar_value", "entry", nativeEntry, "convention", "ccall", "safety", safety, "arguments", list(rep), "result", result);
        var imported = with(oldImport, "safety", safety, "emitted", with(object(oldImport, "emitted"), "safety", safety, "arguments", list(rep, "void"), "result", list("void", result)));
        return with(without(base, "packageScalarLink"), "packageNativeLink", with(oldLink, "profile", "thc-package-c-ffi-v1", "abi", list(abi)), "staticForeignImports", with(oldProof, "imports", list(imported)));
    }
    @Test void scalarSafeMetadataMustAgreeAtEveryRetainedBoundary() throws Exception {
        var accepted = nativeModule("Int32Rep", "safe", "Int32Rep");
        var acceptedAbi = Objects.requireNonNull(PackageScalarLinks.read(accepted)).getLink().getAbi(); assertEquals(1, acceptedAbi.size()); assertEquals("safe", acceptedAbi.getFirst().getSafety());
        var pointerAbi = Objects.requireNonNull(PackageScalarLinks.read(nativeModule("Int32Rep", "safe", "AddrRep"))).getLink().getAbi(); assertEquals(1, pointerAbi.size()); assertEquals("AddrRep", pointerAbi.getFirst().getResult());
        for (var rep : List.of("AddrRep", "ByteArray#", "MutableByteArray#")) {
            var signatures = Objects.requireNonNull(PackageScalarLinks.read(nativeModule(rep, "safe", "Int32Rep"))).getLink().getAbi(); assertEquals(1, signatures.size());
            assertEquals(list(rep), signatures.getFirst().getArguments()); assertEquals("safe", signatures.getFirst().getSafety());
        }
        var shared = nativeModule("AddrRep", "safe", "Int32Rep"); var link = object(shared, "packageNativeLink"); var abi = single(link, "abi"); var proof = object(shared, "staticForeignImports"); var imported = single(proof, "imports");
        var unsafeImport = with(imported, "safety", "unsafe", "binder", with(object(imported, "binder"), "occurrence", "unsafeValue"), "emitted", with(object(imported, "emitted"), "safety", "unsafe"));
        var variants = with(shared, "packageNativeLink", with(link, "abi", list(abi, with(abi, "entry", "thc_native_" + digest + "_1", "safety", "unsafe"))), "staticForeignImports", with(proof, "imports", list(imported, unsafeImport)));
        assertEquals(list("safe", "unsafe"), Objects.requireNonNull(PackageScalarLinks.read(variants)).getLink().getAbi().stream().map(it -> it.getSafety()).toList());
        var acceptedProof = object(accepted, "staticForeignImports"); var acceptedImport = single(acceptedProof, "imports");
        for (var changed : List.of(with(acceptedImport, "safety", "unsafe"), with(acceptedImport, "emitted", with(object(acceptedImport, "emitted"), "safety", "unsafe"))))
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(accepted, "staticForeignImports", with(acceptedProof, "imports", list(changed)))));
        for (var rep : List.of("Int32Rep", "AddrRep", "ByteArray#", "MutableByteArray#")) assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(nativeModule(rep, "interruptible", "Int32Rep")));
    }
}
