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
    @Test void nativeDependencyBytesAreVerifiedAndPartOfLoadedIdentity() throws Exception {
        var base = module(); var scalar = object(base, "packageScalarLink"); var entry = single(scalar, "abi");
        var dependency = map("sha256", hash(new byte[]{1, 2}), "hex", "0102");
        var link = with(scalar, "profile", "thc-package-c-ffi-v1", "nativeLibrary", dependency,
            "format", System.getProperty("os.name").startsWith("Mac") ? "llvm-embedded-mach-o" : "llvm-embedded-elf",
            "abi", list(with(entry, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var nativeModule = with(without(base, "packageScalarLink"), "packageNativeLink", link);
        if (!System.getProperty("os.name").equals("Linux") && !System.getProperty("os.name").startsWith("Mac")) {
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(nativeModule));
            return;
        }
        var first = Objects.requireNonNull(PackageScalarLinks.read(nativeModule)).getLink();
        var changed = with(link, "nativeLibrary", map("sha256", hash(new byte[]{1, 3}), "hex", "0103"));
        assertFalse(first.same(Objects.requireNonNull(PackageScalarLinks.read(with(nativeModule, "packageNativeLink", changed))).getLink()));
        assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(nativeModule, "packageNativeLink",
            with(link, "nativeLibrary", with(dependency, "hex", "0103")))));
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
        // Selected inlined bodies cannot invent a missing original ABI provider.
        var scalar = object(original, "packageScalarLink"); var entry = single(scalar, "abi");
        var nativeLink = with(scalar, "profile", "thc-package-c-ffi-v1", "abi", list(with(entry, "entry", nativeEntry, "convention", "ccall", "safety", "unsafe")));
        var nativeModule = with(without(original, "packageScalarLink"), "packageNativeLink", nativeLink);
        var declaration = new CoreModuleAdmission(with(nativeModule, "bindings", List.of()), id -> { throw new IllegalStateException("No exports"); });
        var inlined = with(without(nativeModule, "staticForeignImports"), "module", "Inline", "bindings", List.of());
        var inlineAdmission = new CoreModuleAdmission(inlined, id -> { throw new IllegalStateException("No exports"); });
        assertThrows(IllegalArgumentException.class, () -> { var m = new CoreModules.Merger(); m.addSelected(inlineAdmission, List.of()); m.finish(); });
        var complete = new CoreModules.Merger(); complete.addSelected(inlineAdmission, List.of()); complete.addPackageProvenance(Objects.requireNonNull(declaration.getPackageLink())); complete.finish();
    }
    private Map<String, Object> foreignCall(Map<?, ?> base, String symbol) {
        return map("foreignCall", map("target", map("unit", base.get("unit"), "symbol", symbol), "convention", "ccall", "safety", "unsafe",
            "argumentReps", list(map("primReps", list("Int32Rep")), map("primReps", List.of()))));
    }
    @Test void partialNativeLinkKeepsOriginalIndicesAndRequiresCompleteDependencyReceipt() throws Exception {
        var base = module(); var scalar = object(base, "packageScalarLink"); var entry = single(scalar, "abi"); var entries = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 2; i++) entries.add(with(entry, "symbol", i == 0 ? "scalar_value" : "scalar_z", "entry", "thc_native_" + digest + "_" + i, "convention", "ccall", "safety", "unsafe"));
        var original = with(scalar, "profile", "thc-package-c-ffi-v1", "abi", entries, "buildInputs", map("unresolved", list("unknown_external")));
        var proof = object(base, "staticForeignImports"); var imported = single(proof, "imports");
        var extra = with(imported, "symbol", "scalar_z", "binder", with(object(imported, "binder"), "occurrence", "extra"), "emitted", with(object(imported, "emitted"), "symbol", "scalar_z"));
        var selected = with(original, "availableEntries", list(entries.get(1).get("entry")), "bitcodeHex", "4342", "bitcodeSha256", hash(new byte[]{0x43, 0x42}));
        var closures = List.of(map("entry", entries.get(0).get("entry"), "bitcodeSha256", "b".repeat(64), "unresolved", list("unknown_external")),
            map("entry", entries.get(1).get("entry"), "bitcodeSha256", "b".repeat(64), "unresolved", List.of()));
        var resolution = map("schema", 1L, "profile", "llvm-globaldce-adapter-closures-v1", "inputBitcodeSha256", original.get("bitcodeSha256"),
            "outputBitcodeSha256", selected.get("bitcodeSha256"), "entries", closures, "unresolved", List.of());
        var archive = map("schema", 1L, "profile", "thc-package-native-archive-v1", "execution", "not-linked", "unit", base.get("unit"), "module", base.get("module"),
            "unsupportedImports", List.of(), "unclassifiedReason", null, "unresolvedSymbols", list("unknown_external"), "artifact", original, "entryResolution", resolution);
        var partial = with(without(base, "packageScalarLink"), "packageNativeArchive", archive, "packageNativeLink", selected, "staticForeignImports", with(proof, "imports", list(imported, extra)));
        var admission = Objects.requireNonNull(PackageScalarLinks.read(partial));
        assertEquals(list(entries.get(1).get("entry")), admission.getLink().getAbi().stream().map(it -> it.getEntry()).toList()); assertEquals(Set.of(entries.get(1).get("entry")), admission.getProved());
        assertTrue(Objects.requireNonNull(PackageNativeArchives.read(partial)).blocks(foreignCall(base, "scalar_value"))); assertFalse(Objects.requireNonNull(PackageNativeArchives.read(partial)).blocks(foreignCall(base, "scalar_z")));
        var old = with(without(partial, "packageNativeLink"), "packageNativeArchive", without(archive, "entryResolution")); assertTrue(Objects.requireNonNull(PackageNativeArchives.read(old)).blocks(Map.of()));
        var changes = List.of(map("schema", true), map("profile", "invented"), map("inputBitcodeSha256", "c".repeat(64)), map("outputBitcodeSha256", "c".repeat(64)),
            map("entries", list(closures.get(1), closures.get(0))), map("entries", list(closures.getFirst())), map("entries", list(closures.get(0), with(closures.get(1), "unresolved", list("unrecorded")))), map("unresolved", list("unknown_external")));
        for (var change : changes) { var changed = with(resolution); changed.putAll(change);
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(partial, "packageNativeArchive", with(archive, "entryResolution", changed)))); }
        for (var changed : List.of(without(selected, "availableEntries"), with(selected, "availableEntries", entries.stream().map(it -> it.get("entry")).toList()), with(selected, "bitcodeHex", "4243")))
            assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(with(partial, "packageNativeLink", changed)));
        for (var symbol : List.of("memcpy", "erf", "getentropy", "wcwidth", "_ZNSt8ios_base4InitC1Ev")) {
            var sorted = new ArrayList<>(List.of(symbol, "unknown_external")); Collections.sort(sorted); var inputs = map("unresolved", sorted);
            var providerResolution = with(resolution, "unresolved", list(symbol), "entries", list(closures.get(0), with(closures.get(1), "unresolved", list(symbol))));
            var candidate = with(partial, "packageNativeLink", with(selected, "buildInputs", inputs), "packageNativeArchive", with(archive, "artifact", with(original, "buildInputs", inputs), "entryResolution", providerResolution));
            if (symbol.equals("memcpy")) assertEquals(1, Objects.requireNonNull(PackageScalarLinks.read(candidate)).getLink().getAbi().size());
            else assertThrows(IllegalArgumentException.class, () -> PackageScalarLinks.read(candidate));
        }
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
        assertEquals(Set.of(), Objects.requireNonNull(PackageScalarLinks.read(inlined)).getProved());
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(inlined)));
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
        for (var reps : List.of(List.of("AddrRep", "WordRep"), List.of("ByteArray#", "MutableByteArray#"), List.of("ByteArray#", "AddrRep"), List.of("AddrRep", "AddrRep"), List.of("Int8Rep", "Word16Rep"), List.of("IntRep", "Word64Rep")))
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
