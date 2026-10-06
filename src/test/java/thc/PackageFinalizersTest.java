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

    @Test void environmentCallbackRequiresItsExactTwoPointerDeclarationAndComponentABI() throws Exception {
        var declaration = with(address(type(pointer, function(pointer, io))),
            "callback", map("arguments", list("AddrRep", "AddrRep"), "result", "void"));
        var original = module(declaration);
        var link = (Map<?,?>) original.get("packageNativeLink");
        var entry = (Map<?,?>) ((List<?>) link.get("abi")).getFirst();
        var complete = with(original, "packageNativeLink", with(link, "abi",
            list(with(entry, "arguments", list("AddrRep", "AddrRep")))));
        assertEquals(Set.of("thc_native_" + "a".repeat(64) + "_0"),
            Objects.requireNonNull(PackageScalarLinks.read(complete)).getProved());
        assertEquals(1, ((List<?>) CoreModules.merge(List.of(complete)).get("packageScalarLinks")).size());
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(original)),
            "a two-pointer declaration cannot certify a one-pointer component");
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(original));
        var onePointer = module(address(type(pointer, io)));
        var mismatched = with(onePointer, "packageNativeLink", complete.get("packageNativeLink"));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(mismatched)),
            "a one-pointer declaration cannot certify a two-pointer component");
    }

    @Test void ordinaryCallProofCannotAuthorizeAMarkedFinalizer() throws Exception {
        var original = module(address(type(pointer, io)));
        var proof = (Map<?,?>) original.get("staticForeignImports");
        var call = map("binder", map("unit", "package", "module", "Finalizers", "occurrence", "callCleanup", "namespace", "value"),
            "header", null, "symbol", "cleanup", "unit", "package", "isFunction", true, "convention", "ccall", "safety", "unsafe",
            "declaredType", function(pointer, io), "normalizedType", function(pointer, io), "normalizationRole", "representational",
            "emitted", map("symbol", "cleanup", "unit", "package", "convention", "ccall", "safety", "unsafe",
                "arguments", list("AddrRep", "void"), "result", list("void")));
        var ordinary = with(original, "staticForeignImports", with(without(proof, "addresses"), "schema", 1L, "imports", list(call)));
        assertEquals(Set.of(), Objects.requireNonNull(PackageScalarLinks.read(ordinary)).getProved());
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(ordinary)));

        var link = (Map<?,?>) ordinary.get("packageNativeLink");
        var unmarked = with(ordinary, "packageNativeLink", with(without(link, "finalizers"), "schema", 1L));
        var expected = Set.of("thc_native_" + "a".repeat(64) + "_0");
        assertEquals(expected, Objects.requireNonNull(PackageScalarLinks.read(unmarked)).getProved());
        assertEquals(1, ((List<?>) CoreModules.merge(List.of(unmarked)).get("packageScalarLinks")).size());

        var declaration = address(type(pointer, io));
        var typed = with(original, "module", "Callbacks", "staticForeignImports", with(proof, "module", "Callbacks", "addresses",
            list(with(declaration, "binder", with((Map<?,?>) declaration.get("binder"), "module", "Callbacks")))));
        assertEquals(expected, Objects.requireNonNull(PackageScalarLinks.read(typed)).getProved());
        var mismatch = with(typed, "staticForeignImports", with((Map<?,?>) typed.get("staticForeignImports"), "addresses",
            list(with(declaration, "binder", with((Map<?,?>) declaration.get("binder"), "module", "Callbacks"), "symbol", "different_finalizer"))));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(ordinary, mismatch)));
        for (var modules : List.of(List.of(ordinary, typed), List.of(typed, ordinary)))
            assertEquals(1, ((List<?>) CoreModules.merge(modules).get("packageScalarLinks")).size());
    }

    @Test void retainedUnitLinkNeedsItsDefiningFinalizerProofBeforeAssembly() throws Exception {
        // The retained zlib Internal module is schema 1 with the full component
        // but no source import proof; Stream owns the schema-2 typed CLabels.
        var original = module(address(type(pointer, io)));
        var proof = (Map<?,?>) original.get("staticForeignImports");
        var foreign = with((Map<?,?>) proof.get("expectedForeign"), "stubs", map("header", "",
            "source", "void retained_capi_wrapper(void *p) { cleanup(p); }", "initializers", List.of(), "finalizers", List.of()));
        var streamProof = with(proof, "expectedForeign", foreign);
        var stream = with(original, "schema", 2L, "foreign", foreign,
            "staticForeignImports", streamProof, "staticForeignImportStubs", streamProof);
        var link = (Map<?,?>) original.get("packageNativeLink");
        String callEntry = "thc_native_" + "a".repeat(64) + "_1";
        var abi = new ArrayList<Object>((List<?>) link.get("abi"));
        abi.add(map("symbol", "version", "entry", callEntry, "arguments", List.of(), "result", "Int32Rep",
            "convention", "ccall", "safety", "unsafe"));
        var shared = with(link, "abi", abi);
        stream = with(stream, "packageNativeLink", shared);
        var internal = with(without(original, "staticForeignImports"), "module", "Internal", "packageNativeLink", shared);

        var consumer = new CoreModuleAdmission(internal, id -> { throw new AssertionError("No exports"); });
        assertEquals(Set.of(callEntry), consumer.getPackageLink().getProved(), "link attachment cannot prove a finalizer");
        var merger = new CoreModules.Merger();
        merger.addSelected(consumer, List.of());
        assertThrows(IllegalArgumentException.class, merger::finish, "missing typed CLabel authority must still fail");
        var defining = new CoreModuleAdmission(stream, id -> { throw new AssertionError("No exports"); });
        assertEquals(Set.of("thc_native_" + "a".repeat(64) + "_0"), defining.getPackageLink().getProved());
        merger.addPackageProvenance(defining.getPackageLink());
        assertEquals(1, ((List<?>) merger.finish().get("packageScalarLinks")).size());

        var retainedUnprovedStubs = with(internal, "schema", 2L, "foreign", foreign, "staticForeignImportStubs", streamProof);
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(retainedUnprovedStubs),
            "retained import obligations cannot use the ordinary link-only path");
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
