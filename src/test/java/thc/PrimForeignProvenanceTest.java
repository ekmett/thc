// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PrimForeignProvenanceTest {
    private static final String PROFILE = "ghc-9.14.1-thc-stock-static-foreign-imports-v2";
    private static Map<String,Object> fields(Object... pairs) {
        var result = new LinkedHashMap<String,Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static Map<String,Object> internal(String module, String name, String namespace, Object... arguments) {
        return Map.of("kind", "tycon", "name", Map.of("unit", "ghc-internal", "module", module,
            "occurrence", name, "namespace", namespace), "arguments", List.of(arguments));
    }
    private static Object arrow(Object argument, Object result) {
        return Map.of("kind", "function", "multiplicity", internal("GHC.Internal.Types", "Many", "data"),
            "argument", argument, "result", result);
    }
    private static Object scalar(String kind, String rep) {
        return Map.of("kind", kind, "primReps", List.of(rep), "evaluated", false);
    }
    private static Object primitive(String rep) {
        return rep.equals("void") ? internal("GHC.Internal.Prim", "State#", "type", internal("GHC.Internal.Prim", "RealWorld", "type"))
            : internal("GHC.Internal.Prim", rep.substring(0, rep.length() - 3) + "#", "type");
    }
    private static Object output(List<String> result) {
        if (result.size() == 1) return primitive(result.getFirst());
        if (result.isEmpty()) return internal("GHC.Internal.Types", "Unit#", "type");
        var fields = new java.util.ArrayList<Object>();
        for (var rep : result) fields.add(internal("GHC.Internal.Types", rep.equals("void") ? "ZeroBitRep" : rep, rep.equals("void") ? "type" : "data"));
        for (var rep : result) fields.add(primitive(rep));
        return internal("GHC.Internal.Types", "Tuple" + result.size() + "#", "type", fields.toArray());
    }
    private static Map<String,Object> module(List<String> result) {
        var emitted = fields("symbol", "ordinary_prim", "unit", "example", "convention", "prim",
            "safety", "safe", "arguments", List.of("WordRep"), "result", result);
        var type = arrow(primitive("WordRep"), output(result));
        var declaration = fields("binder", Map.of("unit", "example", "module", "Example", "occurrence", "primitive", "namespace", "value"),
            "header", null, "symbol", "ordinary_prim", "unit", "example", "isFunction", true,
            "convention", "prim", "safety", "safe", "declaredType", type, "normalizedType", type,
            "normalizationRole", "representational", "emitted", emitted);
        var proof = fields("schema", 1L, "scope", "retained-static-import-products", "execution", "not-linked",
            "profile", PROFILE, "unit", "example", "module", "Example", "status", "verified", "wordBits", 64L,
            "expectedForeign", fields("schema", 1L, "execution", "not-linked", "stubs", null, "files", List.of()),
            "imports", List.of(declaration), "expectedCalls", List.of());
        var archive = fields("schema", 1L, "profile", "thc-package-native-archive-v1", "execution", "not-linked",
            "unit", "example", "module", "Example", "unsupportedImports", List.of(emitted),
            "unclassifiedReason", null, "unresolvedSymbols", List.of(), "artifact", null);
        return fields("schema", 2L, "ghc", "9.14.1", "unit", "example", "module", "Example",
            "staticForeignImports", proof, "packageNativeArchive", archive, "bindings", List.of());
    }
    private static Map<String,Object> emitted(Map<String,Object> module) {
        return (Map<String,Object>) ((Map<?,?>) ((List<?>) ((Map<?,?>) module.get("staticForeignImports")).get("imports")).getFirst()).get("emitted");
    }
    @Test void purePrimScalarAndTupleProductsRemainArchiveOnly() {
        for (var result : List.of(List.of("WordRep"), List.of("AddrRep", "WordRep"), List.of("void", "WordRep"), List.<String>of())) {
            var module = module(result);
            var archive = PackageNativeArchives.read(module);
            assertNotNull(archive);
            assertEquals(List.of(emitted(module)), archive.getExcluded());
            assertTrue(archive.blocks(Map.of("foreignCall", Map.of("target", Map.of("unit", "example", "symbol", "ordinary_prim"),
                "convention", "prim", "safety", "safe"))));
            assertNull(PackageScalarLinks.read(module));
        }
    }
    @Test void primProofCannotUseTheCOnlyProfileOrLoseItsObligation() {
        var module = module(List.of("WordRep"));
        ((Map<String,Object>) module.get("staticForeignImports")).put("profile", "ghc-9.14.1-thc-only-static-c-imports-v1");
        assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(module));
        var missing = module(List.of("WordRep"));
        ((Map<String,Object>) missing.get("packageNativeArchive")).put("unsupportedImports", List.of());
        assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(missing));
    }
    @Test void removingTheArchiveMarkerCannotAdmitAPrimProof() {
        var module = module(List.of("WordRep")); module.remove("packageNativeArchive");
        assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(module));
    }
    @Test void purePrimCannotBecomeANativeConflictWitness() {
        var module = module(List.of("WordRep"));
        var variant = new LinkedHashMap<>(emitted(module)); variant.put("result", List.of("IntRep"));
        ((Map<String,Object>) module.get("packageNativeArchive")).put("conflictingImports", List.of(emitted(module), variant));
        assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(module));
    }
    @Test void retainedPrimProductsNeverCreateACapiAdapter() {
        var module = module(List.of("AddrRep", "WordRep"));
        var proof = (Map<String,Object>) module.get("staticForeignImports");
        var product = (Map<String,Object>) proof.get("expectedForeign");
        product.put("stubs", fields("header", "", "source", "unchanged stock CAPI products",
            "initializers", List.of(), "finalizers", List.of()));
        module.put("foreign", product); module.put("staticForeignImportStubs", proof);
        assertNull(ManagedImportAdmission.read(module));
        ((Map<String,Object>) module.get("packageNativeArchive")).put("unsupportedImports", List.of());
        assertThrows(IllegalArgumentException.class, () -> ManagedImportAdmission.read(module));
    }
    @Test void theV2ProfileDoesNotBroadenCResultShapes() {
        var module = module(List.of("AddrRep", "WordRep"));
        var declaration = (Map<String,Object>) ((List<?>) ((Map<?,?>) module.get("staticForeignImports")).get("imports")).getFirst();
        declaration.put("convention", "ccall"); emitted(module).put("convention", "ccall");
        assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(module));
    }
    private static Map<String,Object> originalWord() {
        var module = module(List.of("WordRep")); module.put("unit", "ghc-internal"); module.put("module", "GHC.Internal.Stack.Decode");
        var proof = (Map<String,Object>) module.get("staticForeignImports"); proof.put("unit", "ghc-internal"); proof.put("module", module.get("module"));
        var archive = (Map<String,Object>) module.get("packageNativeArchive"); archive.put("unit", "ghc-internal"); archive.put("module", module.get("module"));
        var declaration = (Map<String,Object>) ((List<?>) proof.get("imports")).getFirst();
        declaration.put("binder", Map.of("unit", "ghc-internal", "module", module.get("module"), "occurrence", "getWord#", "namespace", "value"));
        declaration.put("symbol", "getWordzh"); declaration.put("unit", "ghc-internal");
        var type = arrow(internal("GHC.Internal.Prim", "StackSnapshot#", "type"),
            arrow(internal("GHC.Internal.Prim", "Word#", "type"), internal("GHC.Internal.Prim", "Word#", "type")));
        declaration.put("declaredType", type); declaration.put("normalizedType", type);
        var emitted = emitted(module); emitted.put("unit", "ghc-internal"); emitted.put("symbol", "getWordzh");
        emitted.put("arguments", List.of("BoxedRep (Just Unlifted)", "WordRep"));
        var call = fields("schema", 1L, "target", Map.of("kind", "static", "unit", "ghc-internal", "symbol", "getWordzh", "isFunction", true),
            "convention", "prim", "safety", "safe", "arity", 2L, "suppliedArity", 2L,
            "argumentReps", List.of(scalar("object", "BoxedRep (Just Unlifted)"), scalar("long", "WordRep")), "resultRep", scalar("long", "WordRep"));
        proof.put("expectedCalls", List.of(call)); module.put("bindings", List.of(Map.of("foreignCall", call)));
        return module;
    }
    @Test void originalStackProofRejectsSameCarrierWrongNominalTypes() {
        assertNotNull(PackageNativeArchives.read(originalWord()));
        for (var key : List.of("declaredType", "normalizedType")) {
            var module = originalWord(); var proof = (Map<?,?>) module.get("staticForeignImports");
            var declaration = (Map<String,Object>) ((List<?>) proof.get("imports")).getFirst();
            var type = new LinkedHashMap<>((Map<String,Object>) declaration.get(key));
            type.put("argument", internal("GHC.Internal.Prim", "ThreadId#", "type")); declaration.put(key, type);
            assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(module), key);
        }
    }
    @Test void originalStackProofRejectsScalarRelabeledAsASingletonTuple() {
        var module = originalWord(); var proof = (Map<?,?>) module.get("staticForeignImports");
        var call = (Map<String,Object>) ((List<?>) proof.get("expectedCalls")).getFirst();
        call.put("resultRep", Map.of("kind", "unknown", "primReps", List.of("WordRep"), "evaluated", false,
            "aggregate", "unboxed-tuple", "components", List.of(Map.of("kind", "long", "primReps", List.of("WordRep"), "evaluated", true))));
        assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(module));
    }
    private static Map<String,Object> nestedEmptyResult() {
        var module = module(List.of("void", "void")); var proof = (Map<String,Object>) module.get("staticForeignImports");
        var declaration = (Map<String,Object>) ((List<?>) proof.get("imports")).getFirst();
        var state = internal("GHC.Internal.Prim", "State#", "type", internal("GHC.Internal.Prim", "RealWorld", "type"));
        var tuple = internal("GHC.Internal.Types", "Tuple2#", "type", internal("GHC.Internal.Types", "ZeroBitRep", "type"),
            internal("GHC.Internal.Types", "ZeroBitRep", "type"), state, internal("GHC.Internal.Types", "Unit#", "type"));
        var type = arrow(internal("GHC.Internal.Prim", "Word#", "type"), tuple);
        declaration.put("declaredType", type); declaration.put("normalizedType", type);
        var result = fields("kind", "unknown", "primReps", List.of(), "evaluated", false, "aggregate", "unboxed-tuple", "components", List.of(
            fields("kind", "void", "primReps", List.of(), "evaluated", true),
            fields("kind", "unknown", "primReps", List.of(), "evaluated", true, "aggregate", "unboxed-tuple", "components", List.of())));
        var call = fields("schema", 1L, "target", Map.of("kind", "static", "unit", "example", "symbol", "ordinary_prim", "isFunction", true),
            "convention", "prim", "safety", "safe", "arity", 1L, "suppliedArity", 1L,
            "argumentReps", List.of(scalar("long", "WordRep")), "resultRep", result);
        proof.put("expectedCalls", List.of(call)); module.put("bindings", List.of(Map.of("foreignCall", call)));
        return module;
    }
    @Test void genericPrimCannotEraseNestedEmptyTupleIntoState() {
        assertNotNull(PackageNativeArchives.read(nestedEmptyResult()));
        var module = nestedEmptyResult(); var proof = (Map<?,?>) module.get("staticForeignImports");
        var call = (Map<?,?>) ((List<?>) proof.get("expectedCalls")).getFirst();
        var result = (Map<String,Object>) call.get("resultRep");
        result.put("components", List.of(Map.of("kind", "void", "primReps", List.of(), "evaluated", true),
            Map.of("kind", "void", "primReps", List.of(), "evaluated", true)));
        assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(module));
    }
    @Test void genuineOriginalStackProofAdmitsItsFullNominalInventoryWithoutNativeAdapters() throws Exception {
        String input = System.getenv("THC_TEST_STACK_PRIM_CBD"); assumeTrue(input != null, "requires genuine original stock prim proof");
        String expected = System.getenv("THC_TEST_STACK_PRIM_CBD_SHA256"); assertNotNull(expected);
        var path = Path.of(input);
        assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        var module = CoreCbdFixtures.read(path);
        assertEquals("ghc-internal", module.get("unit")); assertEquals("GHC.Internal.Stack.Decode", module.get("module"));
        var proof = (Map<?,?>) module.get("staticForeignImports"); assertEquals(PROFILE, proof.get("profile"));
        assertEquals(13, ((List<?>) proof.get("imports")).size());
        assertTrue(((List<?>) proof.get("expectedCalls")).size() >= 13);
        var retained = PackageNativeArchives.read(module); assertNotNull(retained);
        assertFalse(retained.getWholeModule()); assertEquals(13, retained.getExcluded().size());
        assertFalse(retained.blocks(module.get("bindings")), "existing context-owned operations retain their strict validators");
        assertNull(PackageScalarLinks.read(module));
        var header = new LinkedHashMap<>(module); header.remove("bindings");
        assertNotNull(new CoreModuleAdmission(header, _ -> { throw new AssertionError("cold admission must not demand a body"); }).getArchive());
        for (var key : List.of("declaredType", "normalizedType")) {
            var bad = (Map<String,Object>) CoreCbdFixtures.snapshot(module);
            var declaration = (Map<String,Object>) ((List<?>) ((Map<?,?>) bad.get("staticForeignImports")).get("imports")).stream()
                .filter(raw -> "getWordzh".equals(((Map<?,?>) raw).get("symbol"))).findFirst().orElseThrow();
            var type = new LinkedHashMap<>((Map<String,Object>) declaration.get(key));
            type.put("argument", internal("GHC.Internal.Prim", "ThreadId#", "type")); declaration.put(key, type);
            assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(bad), key);
        }
    }
    @Test void genuineProducerRetainsNestedPrimProductsWithoutAnOriginalUnitCapability() throws Exception {
        String input = System.getenv("THC_TEST_PRIM_CARRIERS_CBD"); assumeTrue(input != null, "requires genuine stock prim producer fixture");
        String expected = System.getenv("THC_TEST_PRIM_CARRIERS_CBD_SHA256"); assertNotNull(expected); var path = Path.of(input);
        assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        var module = CoreCbdFixtures.read(path); assertEquals("main", module.get("unit"));
        var proof = (Map<?,?>) module.get("staticForeignImports"); assertEquals(PROFILE, proof.get("profile"));
        var archive = PackageNativeArchives.read(module); assertNotNull(archive); assertEquals(4, archive.getExcluded().size());
        assertTrue(archive.blocks(module.get("bindings"))); assertNull(PackageScalarLinks.read(module));
        var nested = (Map<?,?>) ((List<?>) proof.get("expectedCalls")).stream().filter(raw ->
            "stg_sendCloneStackMessagezh".equals(((Map<?,?>) ((Map<?,?>) raw).get("target")).get("symbol"))).findFirst().orElseThrow();
        var fields = (List<?>) ((Map<?,?>) nested.get("resultRep")).get("components");
        assertEquals("void", ((Map<?,?>) fields.getFirst()).get("kind"));
        assertEquals(List.of(), ((Map<?,?>) fields.get(1)).get("components"));
        var bad = (Map<String,Object>) CoreCbdFixtures.snapshot(module); bad.remove("bindings");
        var wrong = (Map<?,?>) ((List<?>) ((Map<?,?>) bad.get("staticForeignImports")).get("expectedCalls")).stream().filter(raw ->
            "stg_sendCloneStackMessagezh".equals(((Map<?,?>) ((Map<?,?>) raw).get("target")).get("symbol"))).findFirst().orElseThrow();
        ((Map<String,Object>) wrong.get("resultRep")).put("components", List.of(fields.getFirst(), fields.getFirst()));
        assertThrows(IllegalArgumentException.class, () -> PackageNativeArchives.read(bad, false));
    }
}
