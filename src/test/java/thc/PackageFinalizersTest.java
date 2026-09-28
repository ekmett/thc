// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Independent models of the pinned GHC FunPtr (Ptr a -> IO ()) declaration. */
class PackageFinalizersTest {
    private Map<String,Object> named(String module, String name, Object... arguments) {
        return map("kind", "tycon", "name", map("unit", "ghc-internal", "module", module,
            "occurrence", name, "namespace", "type"), "arguments", Arrays.asList(arguments));
    }
    private final Map<String,Object> unit = named("GHC.Internal.Tuple", "Unit");
    private final Map<String,Object> pointer = named("GHC.Internal.Ptr", "Ptr", unit);
    private final Map<String,Object> io = named("GHC.Internal.Types", "IO", unit);
    private Map<String,Object> function(Object argument, Object result) {
        return map("kind", "function", "multiplicity", named("GHC.Internal.Types", "Many"),
            "argument", argument, "result", result);
    }
    private Map<String,Object> type(Object argument, Object result) {
        return named("GHC.Internal.Ptr", "FunPtr", function(argument, result));
    }
    private Map<String,Object> address(Object normalized) {
        return map("binder", map("unit", "package", "module", "Finalizers", "occurrence", "cleanup", "namespace", "value"),
            "header", "original.h", "symbol", "cleanup", "isFunction", true, "convention", "capi",
            "declaredType", type(pointer, io), "normalizedType", normalized, "normalizationRole", "representational",
            "callback", map("arguments", list("AddrRep"), "result", "void"));
    }
    private Map<String,Object> module(Object... addresses) throws Exception {
        String component = "a".repeat(64), entry = "thc_native_" + component + "_0";
        var foreign = map("schema", 1L, "execution", "not-linked", "stubs", null, "files", List.of());
        var proof = map("schema", 2L, "scope", "retained-static-import-products", "execution", "not-linked",
            "profile", "ghc-9.14.1-thc-only-static-c-imports-v1", "unit", "package", "module", "Finalizers",
            "status", "verified", "wordBits", 64L, "expectedForeign", foreign, "expectedCalls", List.of(),
            "imports", List.of(), "addresses", Arrays.asList(addresses));
        // Structural placeholder bytes are never parsed or executed as native code.
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[]{0x42, 0x43}));
        String arch = switch (System.getProperty("os.arch")) { case "amd64" -> "x86_64"; case "arm64" -> "aarch64"; default -> System.getProperty("os.arch"); };
        String target = arch + (System.getProperty("os.name").equals("Linux") ? "-unknown-linux-gnu" : "-apple-darwin");
        var link = map("schema", 2L, "profile", "thc-package-c-ffi-v1", "format", "llvm-bitcode", "unit", "package",
            "target", target, "componentSha256", component, "bitcodeSha256", hash, "bitcodeHex", "4243", "finalizers", list(entry),
            "abi", list(map("symbol", "cleanup", "entry", entry, "arguments", list("AddrRep"), "result", "void", "convention", "ccall", "safety", "unsafe")));
        return map("schema", 1L, "ghc", "9.14.1", "unit", "package", "module", "Finalizers", "bindings", List.of(),
            "constructors", List.of(), "staticForeignImports", proof, "packageNativeLink", link);
    }

    @Test void aLabelsOnlyModuleProvesItsComponentWithoutInventingForeignCalls() throws Exception {
        var original = module(address(type(pointer, io)));
        var admission = Objects.requireNonNull(PackageScalarLinks.read(original));
        assertEquals(Set.of("thc_native_" + "a".repeat(64) + "_0"), admission.getProved());
        assertEquals(1, ((List<?>) CoreModules.merge(List.of(original)).get("packageScalarLinks")).size());
        var inlined = without(original, "staticForeignImports");
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(inlined)));
        var merger = new CoreModules.Merger();
        merger.addSelected(new CoreModuleAdmission(inlined, id -> { throw new AssertionError("No exports"); }), List.of());
        merger.addPackageProvenance(admission);
        merger.finish();
        assertEquals(List.of(), ((Map<?,?>) original.get("staticForeignImports")).get("expectedCalls"));
    }

    @Test void callbackMetadataCannotReplaceNormalizedNominalTypeOrOwnership() throws Exception {
        assertEquals(Set.of("cleanup"), PackageFinalizers.declarations(module(address(type(pointer, io)))));
        var integer = named("GHC.Internal.Int", "Int32");
        for (var candidate : List.of(type(integer, io), type(pointer, named("GHC.Internal.Types", "IO", integer)),
                type(pointer, function(pointer, io)), type(pointer, unit), type(named("Impostor", "Ptr", unit), io),
                type(pointer, named("GHC.Internal.Types", "IO", named("Impostor", "Unit"))),
                named("Impostor", "FunPtr", function(pointer, io)))) {
            var altered = module(address(candidate));
            assertThrows(IllegalArgumentException.class, () -> PackageFinalizers.declarations(altered));
        }
        var original = address(type(pointer, io));
        for (var candidate : List.of(with(original, "isFunction", false), with(original, "normalizationRole", "phantom"),
                with(original, "callback", map("arguments", list("AddrRep", "AddrRep"), "result", "void")))) {
            var altered = module(candidate);
            assertThrows(IllegalArgumentException.class, () -> PackageFinalizers.declarations(altered));
        }
        var duplicate = module(original, with(original, "symbol", "other"));
        assertThrows(IllegalArgumentException.class, () -> PackageFinalizers.declarations(duplicate));
        assertEquals(Set.of(), PackageFinalizers.declarations(module(with(address(integer), "callback", null))),
            "unsupported labels remain recorded, not made callable");
    }

    @Test void nativeRootsMustNameExactlyTheCheckedOnePointerVoidEntry() {
        var signature = new PackageScalarSignature("cleanup", "adapter", List.of("AddrRep"), "void");
        var fields = map("schema", 2L, "finalizers", list("adapter"));
        assertEquals(Set.of("adapter"), PackageFinalizers.entries(fields, List.of(signature)));
        for (var candidate : List.of(new PackageScalarSignature("cleanup", "adapter", List.of("IntRep"), "void"),
                new PackageScalarSignature("cleanup", "adapter", List.of("AddrRep"), "Int32Rep"),
                new PackageScalarSignature("cleanup", "adapter", List.of("AddrRep"), "void", "ccall", "safe"),
                new PackageScalarSignature("cleanup", "adapter", List.of("AddrRep"), "void", "capi", "unsafe"),
                new PackageScalarSignature("free", "adapter", List.of("AddrRep"), "void")))
            assertThrows(IllegalArgumentException.class, () -> PackageFinalizers.entries(fields, List.of(candidate)));
        for (var roots : List.of(List.of(), list("absent"), list("adapter", "adapter")))
            assertThrows(IllegalArgumentException.class, () -> PackageFinalizers.entries(with(fields, "finalizers", roots), List.of(signature)));
    }
}
