// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BoxedForeignProvenanceTest {
    private static final String BOX = "BoxedRep (Just Unlifted)";
    private static Map<String,Object> fields(Object... pairs) {
        var result = new LinkedHashMap<String,Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static Map<String,Object> nominal(String unit, String module, String occurrence) {
        return Map.of("kind", "tycon", "name", Map.of("unit", unit, "module", module,
            "occurrence", occurrence, "namespace", "type"), "arguments", List.of());
    }
    private static Map<String,Object> internal(String module, String occurrence) {
        return nominal("ghc-internal", module, occurrence);
    }
    private static Map<String,Object> arrow(Object argument, Object result) {
        var many = Map.of("kind", "tycon", "name", Map.of("unit", "ghc-internal", "module", "GHC.Internal.Types",
            "occurrence", "Many", "namespace", "data"), "arguments", List.of());
        return Map.of("kind", "function", "multiplicity", many, "argument", argument, "result", result);
    }
    private static Map<String,Object> module(List<String> arguments, List<String> result) {
        var emitted = fields("symbol", "ordinary_boxed", "unit", "example", "convention", "ccall",
            "safety", "unsafe", "arguments", arguments, "result", result);
        var type = nominal("ghc-prim", "GHC.Prim", "ThreadId#");
        var declaration = fields("binder", Map.of("unit", "example", "module", "Example",
            "occurrence", "boxed", "namespace", "value"), "header", null, "symbol", "ordinary_boxed",
            "unit", "example", "isFunction", true, "convention", "ccall", "safety", "unsafe",
            "declaredType", type, "normalizedType", type, "normalizationRole", "representational", "emitted", emitted);
        var product = fields("schema", 1L, "execution", "not-linked", "stubs", null, "files", List.of());
        var proof = fields("schema", 1L, "scope", "retained-static-import-products", "execution", "not-linked",
            "profile", "ghc-9.14.1-thc-only-static-c-imports-v1", "unit", "example", "module", "Example",
            "status", "verified", "wordBits", 64L, "expectedForeign", product, "imports", List.of(declaration), "expectedCalls", List.of());
        var archive = fields("schema", 1L, "profile", "thc-package-native-archive-v1", "execution", "not-linked",
            "unit", "example", "module", "Example", "unsupportedImports", List.of(emitted),
            "unclassifiedReason", null, "unresolvedSymbols", List.of(), "artifact", null);
        return fields("schema", 2L, "ghc", "9.14.1", "unit", "example", "module", "Example",
            "staticForeignImports", proof, "packageNativeArchive", archive, "bindings", List.of());
    }
    @Test void concreteBoxedInputsAndResultsRemainArchiveObligations() {
        for (var carriers : List.of(List.of(BOX, "void"), List.of("BoxedRep (Just Lifted)", "void"))) {
            var module = module(carriers, List.of("void"));
            assertEquals(carriers, ((Map<?,?>) PackageNativeArchives.read(module).getExcluded().getFirst()).get("arguments"));
            assertNull(PackageScalarLinks.read(module));
        }
        var module = module(List.of("void"), List.of("void", BOX));
        assertEquals(List.of("void", BOX), PackageNativeArchives.read(module).getExcluded().getFirst().get("result"));
        assertNull(PackageScalarLinks.read(module));
    }
    @Test void missingBoxedExclusionAndUnknownLevityReject() {
        var module = module(List.of(BOX, "void"), List.of("void"));
        ((Map<String,Object>) module.get("packageNativeArchive")).put("unsupportedImports", List.of());
        assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(module));
        assertThrows(IllegalArgumentException.class,
            () -> PackageNativeArchives.read(module(List.of("BoxedRep Nothing", "void"), List.of("void"))));
    }
    @Test void boxedConflictsCannotInventANativeAbi() {
        var module = module(List.of(BOX, "void"), List.of("void"));
        var archive = (Map<String,Object>) module.get("packageNativeArchive");
        var emitted = (Map<String,Object>) ((List<?>) archive.get("unsupportedImports")).getFirst();
        var variant = new LinkedHashMap<>(emitted); variant.put("arguments", List.of("AddrRep", "void"));
        archive.put("conflictingImports", List.of(emitted, variant));
        assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(module));
    }
    @Test void retainedBoxedStubProofDoesNotGrantAManagedOrNativeAdapter() {
        var module = module(List.of(BOX, "void"), List.of("void"));
        var proof = (Map<String,Object>) module.get("staticForeignImports");
        var product = (Map<String,Object>) proof.get("expectedForeign");
        product.put("stubs", fields("header", "", "source", "retained original C products", "initializers", List.of(), "finalizers", List.of()));
        module.put("foreign", product); module.put("staticForeignImportStubs", proof);
        assertNotNull(PackageNativeArchives.read(module));
        assertNull(ManagedImportAdmission.read(module));
        assertNull(PackageScalarLinks.read(module));
    }
    private static Map<String,Object> originalThreadModule() {
        var module = module(List.of(BOX, "void"), List.of("void", "Word64Rep")); module.put("unit", "ghc-internal");
        var proof = (Map<String,Object>) module.get("staticForeignImports"); proof.put("unit", "ghc-internal");
        var archive = (Map<String,Object>) module.get("packageNativeArchive"); archive.put("unit", "ghc-internal");
        var declaration = (Map<String,Object>) ((List<?>) proof.get("imports")).getFirst();
        var binder = new LinkedHashMap<>((Map<String,Object>) declaration.get("binder")); binder.put("unit", "ghc-internal"); declaration.put("binder", binder);
        declaration.put("unit", "ghc-internal"); declaration.put("symbol", "rts_getThreadId");
        var emitted = (Map<String,Object>) declaration.get("emitted"); emitted.put("unit", "ghc-internal"); emitted.put("symbol", "rts_getThreadId");
        declaration.put("declaredType", arrow(internal("GHC.Internal.Prim", "ThreadId#"), internal("GHC.Internal.Foreign.C.Types", "CULLong")));
        declaration.put("normalizedType", arrow(internal("GHC.Internal.Prim", "ThreadId#"), internal("GHC.Internal.Word", "Word64")));
        return module;
    }
    @Test void runtimeOverrideRequiresBothExactNominalDeclarations() {
        assertNotNull(PackageNativeArchives.read(originalThreadModule()));
        for (var key : List.of("declaredType", "normalizedType")) {
            var module = originalThreadModule();
            var proof = (Map<?,?>) module.get("staticForeignImports");
            var declaration = (Map<String,Object>) ((List<?>) proof.get("imports")).getFirst();
            var type = new LinkedHashMap<>((Map<String,Object>) declaration.get(key));
            type.put("argument", internal("GHC.Internal.Prim", "ByteArray#")); declaration.put(key, type);
            assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(module), key);
        }
    }
    @Test void genuineProducerRetainsBoxedProofWithoutGrantingANativeOrOriginalUnitCapability() throws Throwable {
        String input = System.getenv("THC_TEST_GC_CARRIERS_CBD");
        assumeTrue(input != null, "requires the focused stock-GHC boxed carrier fixture");
        String expected = System.getenv("THC_TEST_GC_CARRIERS_CBD_SHA256"); assertNotNull(expected);
        var path = Path.of(input);
        assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        try (var file = new CoreCompactFile(path, expected, true)) {
            var records = new CoreCompactRecords(file, expected); var module = new LinkedHashMap<>(records.header());
            var bindings = new ArrayList<Map<String,Object>>(); file.verifyBindingOffsets(offset -> bindings.add(records.binding(offset))); module.put("bindings", bindings);
            assertEquals("main", module.get("unit")); assertEquals("PackageNativeGcCarriers", module.get("module"));
            var archive = PackageNativeArchives.read(module); assertNotNull(archive); assertEquals(7, archive.getExcluded().size());
            assertNull(PackageScalarLinks.read(module)); assertNull(thc.runtime.CoreBoxedForeignDeclarations.read(module, true));
            assertEquals(List.of("True", "True", "True", "True", "True", "True"), Files.readAllLines(path.resolveSibling("oracle.txt")));
            var assembled = CoreModules.merge(List.of(module)); int blocked = 0;
            for (var binding : bindings) if (archive.blocks(binding)) {
                blocked++; assertThrows(IllegalArgumentException.class, () -> CoreModules.reachable(assembled, (String) binding.get("id"), true));
            }
            assertTrue(blocked >= 7, "genuine boxed roots remain archive-only under their actual owner");
        }
    }
}
