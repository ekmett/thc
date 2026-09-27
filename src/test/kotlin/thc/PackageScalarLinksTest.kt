// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat

/** Structural controls only: these bytes are never parsed as LLVM or called. */
class PackageScalarLinksTest {
    @Test fun demandedBindingsUseOriginalInventoriesWithoutClaimingCompleteness() {
        val base = module()
        val proof = base["staticForeignImports"] as Map<String, Any?>
        val call = mapOf("target" to mapOf("unit" to base["unit"], "symbol" to "scalar_value"))
        fun binding(id: String, calls: List<Any?>) = mapOf("id" to "scalar-fixture:Scalar.$id",
            "expr" to listOf("lit", "int", "7", calls.map { mapOf("foreignCall" to it) }))
        val first = binding("first", listOf(call))
        val second = binding("second", listOf(call))
        val original = base + mapOf("bindings" to listOf(first, second),
            "staticForeignImports" to (proof + ("expectedCalls" to listOf(call, call))))
        CoreModules.merge(listOf(original))
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.merge(listOf(original + ("bindings" to listOf(first))))
        }
        val admission = CoreModuleAdmission(original + ("bindings" to emptyList<Any>())) { error("No exports") }
        val merged = CoreModules.Merger().also { it.addSelected(admission, listOf(first)) }.finish()
        assertEquals(listOf(first), merged["bindings"])
        for (bad in listOf(binding("altered", listOf(call + ("safety" to "safe"))),
            binding("duplicated", listOf(call, call, call))))
            assertThrows(IllegalArgumentException::class.java) { admission.selected(listOf(bad)) }

        // Inlined-only modules still need the unit's original declaration
        // providers; no selected body can invent that missing ABI evidence.
        val scalar = original["packageScalarLink"] as Map<String, Any?>
        val entry = (scalar["abi"] as List<Map<String, Any?>>).single()
        val nativeLink = scalar + mapOf("profile" to "thc-package-c-ffi-v1", "abi" to listOf(entry + mapOf(
            "entry" to "thc_native_${"a".repeat(64)}_0", "convention" to "ccall", "safety" to "unsafe")))
        val native = (original - "packageScalarLink") + ("packageNativeLink" to nativeLink)
        val declaration = CoreModuleAdmission(native + ("bindings" to emptyList<Any>())) { error("No exports") }
        val inlined = (native - "staticForeignImports") + mapOf("module" to "Inline", "bindings" to emptyList<Any>())
        val inlineAdmission = CoreModuleAdmission(inlined) { error("No exports") }
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.Merger().also { it.addSelected(inlineAdmission, emptyList()) }.finish()
        }
        CoreModules.Merger().also {
            it.addSelected(inlineAdmission, emptyList())
            it.addPackageProvenance(declaration.packageLink!!)
        }.finish()
    }

    @Test fun partialNativeLinkKeepsOriginalIndicesAndRequiresCompleteDependencyReceipt() {
        val base = module()
        val scalar = base["packageScalarLink"] as Map<String, Any?>
        val entry = (scalar["abi"] as List<Map<String, Any?>>).single()
        val entries = listOf("scalar_value", "scalar_z").mapIndexed { index, symbol -> entry + mapOf(
            "symbol" to symbol, "entry" to "thc_native_${"a".repeat(64)}_$index", "convention" to "ccall", "safety" to "unsafe") }
        val original = scalar + mapOf("profile" to "thc-package-c-ffi-v1", "abi" to entries,
            "buildInputs" to mapOf("unresolved" to listOf("unknown_external")))
        val proof = base["staticForeignImports"] as Map<String, Any?>
        val imported = (proof["imports"] as List<Map<String, Any?>>).single()
        val extra = imported + mapOf("symbol" to "scalar_z", "binder" to
            ((imported["binder"] as Map<String, Any?>) + ("occurrence" to "extra")),
            "emitted" to ((imported["emitted"] as Map<String, Any?>) + ("symbol" to "scalar_z")))
        val selected = original + mapOf("availableEntries" to listOf(entries[1]["entry"]), "bitcodeHex" to "4342",
            "bitcodeSha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(byteArrayOf(0x43, 0x42))))
        val closures = entries.mapIndexed { index, abi -> mapOf("entry" to abi["entry"], "bitcodeSha256" to "b".repeat(64),
            "unresolved" to if (index == 0) listOf("unknown_external") else emptyList<String>()) }
        val resolution = mapOf("schema" to 1L, "profile" to "llvm-globaldce-adapter-closures-v1",
            "inputBitcodeSha256" to original["bitcodeSha256"], "outputBitcodeSha256" to selected["bitcodeSha256"],
            "entries" to closures, "unresolved" to emptyList<String>())
        val archive = mapOf("schema" to 1L, "profile" to "thc-package-native-archive-v1", "execution" to "not-linked",
            "unit" to base["unit"], "module" to base["module"], "unsupportedImports" to emptyList<Any>(),
            "unclassifiedReason" to null, "unresolvedSymbols" to listOf("unknown_external"), "artifact" to original,
            "entryResolution" to resolution)
        val partial = (base - "packageScalarLink") + mapOf("packageNativeArchive" to archive, "packageNativeLink" to selected,
            "staticForeignImports" to (proof + ("imports" to listOf(imported, extra))))
        val admission = PackageScalarLinks.read(partial)!!
        assertEquals(listOf(entries[1]["entry"]), admission.link.abi.map { it.entry })
        assertEquals(setOf(entries[1]["entry"]), admission.proved)
        fun call(symbol: String) = mapOf("foreignCall" to mapOf("target" to mapOf("unit" to base["unit"], "symbol" to symbol),
            "convention" to "ccall", "safety" to "unsafe", "argumentReps" to listOf(
                mapOf("primReps" to listOf("Int32Rep")), mapOf("primReps" to emptyList<String>()))))
        assertTrue(PackageNativeArchives.read(partial)!!.blocks(call("scalar_value")))
        assertFalse(PackageNativeArchives.read(partial)!!.blocks(call("scalar_z")))
        val old = (partial - "packageNativeLink") + ("packageNativeArchive" to (archive - "entryResolution"))
        assertTrue(PackageNativeArchives.read(old)!!.blocks(emptyMap<String, Any>()))
        for ((key, value) in listOf("schema" to true, "profile" to "invented", "inputBitcodeSha256" to "c".repeat(64),
            "outputBitcodeSha256" to "c".repeat(64), "entries" to closures.reversed(), "entries" to closures.take(1),
            "entries" to listOf(closures[0], closures[1] + ("unresolved" to listOf("unrecorded"))),
            "unresolved" to listOf("unknown_external")))
            assertThrows(IllegalArgumentException::class.java) {
                PackageScalarLinks.read(partial + ("packageNativeArchive" to (archive + ("entryResolution" to (resolution + (key to value))))))
            }
        for (changed in listOf(selected - "availableEntries", selected + ("availableEntries" to entries.map { it["entry"] }),
            selected + ("bitcodeHex" to "4243")))
            assertThrows(IllegalArgumentException::class.java) { PackageScalarLinks.read(partial + ("packageNativeLink" to changed)) }
        for (symbol in listOf("memcpy", "erf", "getentropy", "wcwidth", "_ZNSt8ios_base4InitC1Ev")) {
            val inputs = mapOf("unresolved" to listOf(symbol, "unknown_external").sorted())
            val providerResolution = resolution + mapOf("unresolved" to listOf(symbol),
                "entries" to listOf(closures[0], closures[1] + ("unresolved" to listOf(symbol))))
            val candidate = partial + mapOf("packageNativeLink" to (selected + ("buildInputs" to inputs)),
                "packageNativeArchive" to (archive + mapOf("artifact" to (original + ("buildInputs" to inputs)),
                    "entryResolution" to providerResolution)))
            if (symbol == "memcpy") assertEquals(1, PackageScalarLinks.read(candidate)!!.link.abi.size)
            else assertThrows(IllegalArgumentException::class.java) { PackageScalarLinks.read(candidate) }
        }
    }

    @Test fun conflictingOriginalAbiWitnessesExcludeOnlyThatSymbol() {
        val base = module()
        val scalar = base["packageScalarLink"] as Map<String, Any?>
        val abi = (scalar["abi"] as List<Map<String, Any?>>).single()
        val link = scalar + mapOf("profile" to "thc-package-c-ffi-v1", "abi" to listOf(abi + mapOf(
            "entry" to "thc_native_${"a".repeat(64)}_0", "convention" to "ccall", "safety" to "unsafe")))
        val proof = base["staticForeignImports"] as Map<String, Any?>
        val original = (proof["imports"] as List<Map<String, Any?>>).single()
        val emitted = (original["emitted"] as Map<String, Any?>) + ("symbol" to "width")
        val wide = emitted + ("result" to listOf("void", "Int64Rep"))
        val narrow = original + mapOf("symbol" to "width", "emitted" to emitted,
            "binder" to ((original["binder"] as Map<String, Any?>) + ("occurrence" to "narrow")))
        val archive = mapOf("schema" to 1L, "profile" to "thc-package-native-archive-v1", "execution" to "not-linked",
            "unit" to base["unit"], "module" to base["module"], "unsupportedImports" to listOf(emitted),
            "unclassifiedReason" to null, "unresolvedSymbols" to emptyList<String>(), "artifact" to null,
            "conflictingImports" to listOf(emitted, wide))
        val mixed = (base - "packageScalarLink") + mapOf("packageNativeLink" to link, "packageNativeArchive" to archive,
            "staticForeignImports" to (proof + ("imports" to listOf(original, narrow))))
        val retained = PackageNativeArchives.read(mixed)!!
        assertFalse(retained.wholeModule)
        assertFalse(retained.blocks(emptyMap<String, Any>()))
        assertTrue(retained.blocks(mapOf("foreignCall" to mapOf("target" to mapOf("unit" to base["unit"], "symbol" to "width"),
            "convention" to "ccall", "safety" to "unsafe"))))
        assertEquals(setOf("thc_native_${"a".repeat(64)}_0"), PackageScalarLinks.read(mixed)!!.proved)
        for (bad in listOf(emptyList(), listOf(emitted), listOf(wide), listOf(emitted, emitted),
            listOf(emitted, wide + ("unit" to "other")), listOf(emitted, wide + ("result" to listOf("void", "invented"))))) {
            assertThrows(IllegalArgumentException::class.java) {
                PackageScalarLinks.read(mixed + ("packageNativeArchive" to (archive + ("conflictingImports" to bad))))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackageScalarLinks.read(mixed + ("staticForeignImports" to (proof + ("imports" to listOf(original,
                narrow + ("normalizedType" to emptyMap<Any, Any>()))))))
        }
    }

    @Test fun nativeArchiveKeepsMixedDeclarationsButNeverAdmitsTheirUnsupportedEffects() {
        val base = module()
        val scalar = base["packageScalarLink"] as Map<String, Any?>
        val entry = (scalar["abi"] as List<Map<String, Any?>>).single()
        val link = scalar + mapOf("profile" to "thc-package-c-ffi-v1", "abi" to listOf(entry + mapOf(
            "entry" to "thc_native_${"a".repeat(64)}_0", "convention" to "ccall", "safety" to "unsafe")))
        val proof = base["staticForeignImports"] as Map<String, Any?>
        val original = (proof["imports"] as List<Map<String, Any?>>).single()
        val binder = original["binder"] as Map<String, Any?>
        val emitted = mapOf("symbol" to "blocked", "unit" to base["unit"], "convention" to "ccall", "safety" to "interruptible",
            "arguments" to listOf("AddrRep", "void"), "result" to listOf("void", "Int32Rep"))
        val blocked = original + mapOf("symbol" to "blocked", "safety" to "interruptible", "binder" to (binder + ("occurrence" to "blocked")), "emitted" to emitted)
        val call = mapOf("target" to mapOf("unit" to base["unit"], "symbol" to "blocked"), "convention" to "ccall", "safety" to "interruptible")
        val goodId = "scalar-fixture:Scalar.good"
        val badId = "scalar-fixture:Scalar.bad"
        val bindings = listOf(mapOf("id" to goodId, "expr" to listOf("lit", "int", 7L)),
            mapOf("id" to badId, "expr" to listOf("lit", "int", 0L, mapOf("foreignCall" to call))))
        val archive = mapOf("schema" to 1L, "profile" to "thc-package-native-archive-v1", "execution" to "not-linked",
            "unit" to base["unit"], "module" to base["module"], "unsupportedImports" to listOf(emitted),
            "unclassifiedReason" to null, "unresolvedSymbols" to emptyList<String>(), "artifact" to null)
        val mixedProof = proof + mapOf("imports" to listOf(original, blocked), "expectedCalls" to listOf(call))
        val mixed = (base - "packageScalarLink") + mapOf("packageNativeLink" to link, "packageNativeArchive" to archive,
            "staticForeignImports" to mixedProof, "bindings" to bindings)
        val merged = CoreModules.merge(listOf(mixed))
        assertEquals(setOf("thc_native_${"a".repeat(64)}_0"), PackageScalarLinks.read(mixed)!!.proved)
        assertEquals(listOf(bindings.first()), CoreModules.reachable(merged, goodId, true)["bindings"])
        val failure = assertThrows(IllegalArgumentException::class.java) { CoreModules.reachable(merged, badId, true) }
        assertTrue(failure.message!!.contains("archive-only"))
        assertThrows(IllegalArgumentException::class.java) { CoreForeignArtifacts.requireExecutable(mixed) }
        for (bad in listOf(
            mixed + ("packageNativeArchive" to (archive + ("unsupportedImports" to emptyList<Any>()))),
            mixed + ("staticForeignImports" to (mixedProof + ("expectedCalls" to emptyList<Any>()))),
            mixed + ("staticForeignImports" to (mixedProof + ("imports" to listOf(original, blocked + ("normalizedType" to emptyMap<Any, Any>()))))),
            mixed + ("packageNativeLink" to (link + ("bitcodeSha256" to "0".repeat(64))))))
            assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(listOf(bad)) }

        val unresolved = (mixed - "packageNativeLink") + ("packageNativeArchive" to (archive + mapOf(
            "unresolvedSymbols" to listOf("unknown_external"), "artifact" to link)))
        assertEquals(emptyList<Any>(), CoreModules.merge(listOf(unresolved))["packageScalarLinks"])
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.reachable(CoreModules.merge(listOf(unresolved)), goodId, true)
        }
        val invalid = unresolved + ("packageNativeArchive" to (archive + mapOf("unresolvedSymbols" to listOf("unknown_external"),
            "artifact" to (link + ("bitcodeHex" to "4342")))))
        assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(listOf(invalid)) }
    }

    private fun module(unit: String = "scalar-fixture", name: String = "Scalar",
        digest: String = (if (unit == "scalar-fixture") "a" else "b").repeat(64)): Map<String, Any?> {
        val bytes = byteArrayOf(0x42, 0x43)
        val symbol = "scalar_value"
        val scalarType = mapOf("kind" to "tycon", "arguments" to emptyList<Any>(), "name" to
            mapOf("unit" to "ghc-internal", "module" to "GHC.Internal.Int", "occurrence" to "Int32", "namespace" to "type"))
        val entry = mapOf("symbol" to symbol, "entry" to "thc_scalar_${digest}_0",
            "arguments" to listOf("Int32Rep"), "result" to "Int32Rep")
        val link = mapOf("schema" to 1L, "format" to "llvm-bitcode", "profile" to "thc-local-scalar-ccall-v1",
            "unit" to unit, "target" to if (System.getProperty("os.name").startsWith("Mac"))
                "${System.getProperty("os.arch")}-apple-darwin" else
                "${if (System.getProperty("os.arch") == "amd64") "x86_64" else System.getProperty("os.arch")}-unknown-linux-gnu",
            "componentSha256" to digest, "bitcodeSha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            "bitcodeHex" to "4243", "abi" to listOf(entry))
        val imported = mapOf("binder" to mapOf("unit" to unit, "module" to name, "occurrence" to "value", "namespace" to "value"),
            "header" to null, "symbol" to symbol, "unit" to null, "isFunction" to true, "convention" to "ccall", "safety" to "unsafe",
            "declaredType" to scalarType, "normalizedType" to scalarType, "normalizationRole" to "representational",
            "emitted" to mapOf("symbol" to symbol, "unit" to unit, "convention" to "ccall", "safety" to "unsafe",
                "arguments" to listOf("Int32Rep", "void"), "result" to listOf("void", "Int32Rep")))
        val proof = mapOf("schema" to 1L, "scope" to "retained-static-import-products", "execution" to "not-linked",
            "profile" to "ghc-9.14.1-thc-only-static-c-imports-v1", "unit" to unit, "module" to name,
            "status" to "verified", "wordBits" to 64L, "expectedForeign" to mapOf("schema" to 1L,
                "execution" to "not-linked", "stubs" to null, "files" to emptyList<Any>()),
            "imports" to listOf(imported), "expectedCalls" to emptyList<Any>())
        return mapOf("schema" to 1L, "ghc" to "9.14.1", "unit" to unit, "module" to name,
            "bindings" to emptyList<Any>(), "constructors" to emptyList<Any>(), "staticForeignImports" to proof,
            "packageScalarLink" to link)
    }

    @Test fun linkNeedsExactOwnedTypedAbiAndEmptyForeignProducts() {
        val original = module()
        val link = original["packageScalarLink"] as Map<String, Any?>
        val proof = original["staticForeignImports"] as Map<String, Any?>
        val imports = proof["imports"] as List<Map<String, Any?>>
        val imported = imports.single()
        val emitted = imported["emitted"] as Map<String, Any?>
        fun proofChange(item: Map<String, Any?>) = original + ("staticForeignImports" to (proof + ("imports" to listOf(item))))
        val bad = listOf(
            original + ("packageScalarLink" to (link + ("unit" to "another-unit"))),
            original + ("packageScalarLink" to (link + ("bitcodeSha256" to "0".repeat(64)))),
            original + ("packageScalarLink" to (link + ("target" to "other-unknown-linux-gnu"))),
            original + ("foreign" to proof["expectedForeign"]),
            original + ("staticForeignImports" to (proof + ("expectedCalls" to listOf(mapOf("schema" to 1L))))),
            proofChange(imported + ("safety" to "safe")),
            proofChange(imported + ("header" to "foreign.h")),
            proofChange(imported + ("emitted" to (emitted + ("unit" to null)))),
            proofChange(imported + ("emitted" to (emitted + ("unit" to "another-unit")))),
            proofChange(imported + ("emitted" to (emitted + ("arguments" to listOf("AddrRep", "void"))))),
            proofChange(imported + ("emitted" to (emitted + ("result" to listOf("void", "Int64Rep"))))))
        assertEquals(setOf("thc_scalar_${"a".repeat(64)}_0"), PackageScalarLinks.read(original)!!.proved)
        if (System.getProperty("os.name").startsWith("Mac")) {
            val clangTarget = "${System.getProperty("os.arch")}-apple-macosx15.0.0"
            assertNotNull(PackageScalarLinks.read(original + ("packageScalarLink" to (link + ("target" to clangTarget)))))
        }
        for ((index, altered) in bad.withIndex()) assertThrows(IllegalArgumentException::class.java,
            { CoreModules.merge(listOf(altered)) }, "mutation $index")
    }

    @Test fun mergedModulesMustProveTheWholeComponentAndKeepOneIdentity() {
        val first = module()
        val link = first["packageScalarLink"] as Map<String, Any?>
        val abi = link["abi"] as List<Map<String, Any?>>
        val extra = abi.single() + mapOf("symbol" to "scalar_z", "entry" to "thc_scalar_${"a".repeat(64)}_1")
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.merge(listOf(first + ("packageScalarLink" to (link + ("abi" to (abi + extra))))))
        }
        val second = module(name = "Second")
        assertEquals(1, (CoreModules.merge(listOf(first, second))["packageScalarLinks"] as List<*>).size)
        val otherLink = (second["packageScalarLink"] as Map<String, Any?>) +
            mapOf("bitcodeHex" to "4342", "bitcodeSha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(byteArrayOf(0x43, 0x42))))
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.merge(listOf(first, second + ("packageScalarLink" to otherLink)))
        }
        assertEquals(2, (CoreModules.merge(listOf(first, module("separate-unit")))["packageScalarLinks"] as List<*>).size)
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.merge(listOf(first, module("separate-unit", digest = "a".repeat(64))))
        }
    }

    @Test fun nativeCapiRetainsWrappersAndAdmitsOnlyDeclaredByteArrayCarriers() {
        val base = module()
        val oldLink = base["packageScalarLink"] as Map<String, Any?>
        val oldProof = base["staticForeignImports"] as Map<String, Any?>
        val oldImport = (oldProof["imports"] as List<Map<String, Any?>>).single()
        val scalarType = oldImport["declaredType"]
        val quantified = mapOf("kind" to "forall", "binderKind" to scalarType,
            "body" to mapOf("kind" to "bound-variable", "index" to 0L))
        val abi = mapOf("symbol" to "wrapper", "entry" to "thc_native_${"a".repeat(64)}_0",
            "convention" to "capi", "safety" to "unsafe", "arguments" to listOf("MutableByteArray#", "ByteArray#"),
            "result" to "void")
        val link = oldLink + mapOf("profile" to "thc-package-c-ffi-v1", "abi" to listOf(abi))
        val foreign = mapOf("schema" to 1L, "execution" to "not-linked", "files" to emptyList<Any>(),
            "stubs" to mapOf("header" to "", "source" to "void wrapper(void *s, void *p) { update(s,p); }",
                "initializers" to emptyList<Any>(), "finalizers" to emptyList<Any>()))
        val imported = oldImport + mapOf("header" to "original.h", "convention" to "capi",
            "declaredType" to quantified, "normalizedType" to quantified,
            "emitted" to mapOf("symbol" to "wrapper", "unit" to base["unit"], "convention" to "capi", "safety" to "unsafe",
                "arguments" to listOf("MutableByteArray#", "ByteArray#", "void"), "result" to listOf("void")))
        val proof = oldProof + mapOf("expectedForeign" to foreign, "imports" to listOf(imported))
        val native = (base - "packageScalarLink") + mapOf("schema" to 2L, "foreign" to foreign,
            "staticForeignImports" to proof, "staticForeignImportStubs" to proof, "packageNativeLink" to link)
        assertEquals(setOf("thc_native_${"a".repeat(64)}_0"), PackageScalarLinks.read(native)!!.proved)
        assertNotNull(PackageScalarLinks.read(native + ("packageNativeLink" to (link + ("buildInputs" to emptyMap<String, Any>())))))
        assertEquals(1, (CoreModules.merge(listOf(native))["packageScalarLinks"] as List<*>).size)
        val inlined = (base - "packageScalarLink" - "staticForeignImports") +
            mapOf("module" to "Inlined", "packageNativeLink" to link)
        assertEquals(emptySet<String>(), PackageScalarLinks.read(inlined)!!.proved)
        assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(listOf(inlined)) }
        assertEquals(1, (CoreModules.merge(listOf(native, inlined))["packageScalarLinks"] as List<*>).size)
        fun abiChange(change: Map<String, Any?>) = native + ("packageNativeLink" to (link + ("abi" to listOf(abi + change))))
        val bad = listOf(
            native - "foreign",
            native - "staticForeignImports",
            native + ("staticForeignImportStubs" to (proof + ("imports" to emptyList<Any>()))),
            native + ("foreign" to (foreign + ("files" to listOf("unlinked.c")))),
            abiChange(mapOf("arguments" to listOf("BoxedRep (Just Unlifted)"))),
            abiChange(mapOf("result" to "AddrRep")),
            abiChange(mapOf("safety" to "safe")))
        bad.forEachIndexed { index, altered -> assertThrows(IllegalArgumentException::class.java,
            { CoreModules.merge(listOf(altered)) }, "native mutation $index") }
        val freeType = mapOf("kind" to "bound-variable", "index" to 0L)
        val freeProof = proof + ("imports" to listOf(imported + ("declaredType" to freeType)))
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.merge(listOf(native + mapOf("staticForeignImports" to freeProof, "staticForeignImportStubs" to freeProof)))
        }
    }

    @Test fun nativePointerVariantsNeedSeparateExactImportProofs() {
        val base = module()
        val originalLink = base["packageScalarLink"] as Map<String, Any?>
        val originalProof = base["staticForeignImports"] as Map<String, Any?>
        val originalImport = (originalProof["imports"] as List<Map<String, Any?>>).single()
        fun variant(reps: List<String>): Map<String, Any?> {
            val abi = reps.mapIndexed { index, rep -> mapOf("symbol" to "read_bytes",
                "entry" to "thc_native_${"a".repeat(64)}_$index", "convention" to "ccall", "safety" to "unsafe",
                "arguments" to listOf(rep), "result" to "WordRep") }
            val imports = reps.mapIndexed { index, rep -> originalImport + mapOf(
                "symbol" to "read_bytes", "binder" to ((originalImport["binder"] as Map<String, Any?>) +
                    ("occurrence" to "read$index")), "emitted" to mapOf("symbol" to "read_bytes", "unit" to base["unit"],
                    "convention" to "ccall", "safety" to "unsafe", "arguments" to listOf(rep, "void"),
                    "result" to listOf("void", "WordRep"))) }
            return (base - "packageScalarLink") + mapOf(
                "packageNativeLink" to (originalLink + mapOf("profile" to "thc-package-c-ffi-v1", "abi" to abi)),
                "staticForeignImports" to (originalProof + ("imports" to imports)))
        }
        val native = variant(listOf("AddrRep", "ByteArray#"))
        val admitted = PackageScalarLinks.read(native)!!
        assertEquals(admitted.link.abi.map { it.entry }.toSet(), admitted.proved)
        assertEquals(2, admitted.proved.size)
        assertEquals(1, (CoreModules.merge(listOf(native))["packageScalarLinks"] as List<*>).size)
        val proof = native["staticForeignImports"] as Map<String, Any?>
        val imports = proof["imports"] as List<*>
        fun header(value: Any?) = native + ("staticForeignImports" to (proof +
            ("imports" to imports.map { (it as Map<String, Any?>) + ("header" to value) })))
        assertEquals(admitted.proved, PackageScalarLinks.read(header("original.h"))!!.proved)
        for (invalid in listOf("", "bad\u0000header", 7L))
            assertThrows(IllegalArgumentException::class.java) { PackageScalarLinks.read(header(invalid)) }
        for (one in imports) assertThrows(IllegalArgumentException::class.java) {
            CoreModules.merge(listOf(native + ("staticForeignImports" to (proof + ("imports" to listOf(one))))))
        }
        for (reps in listOf(listOf("AddrRep", "WordRep"), listOf("ByteArray#", "MutableByteArray#"),
            listOf("ByteArray#", "AddrRep"), listOf("AddrRep", "AddrRep"),
            listOf("Int8Rep", "Word16Rep"), listOf("IntRep", "Word64Rep"))) {
            assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(listOf(variant(reps))) }
        }
        for (width in listOf("", "8", "16", "32", "64")) {
            val signed = variant(listOf("Int${width}Rep", "Word${width}Rep"))
            assertThrows(IllegalArgumentException::class.java) { PackageScalarLinks.read(signed) }
            val signedProof = signed["staticForeignImports"] as Map<String, Any?>
            val signedImports = signedProof["imports"] as List<Map<String, Any?>>
            fun headers(header: Any?) = signed + ("staticForeignImports" to (signedProof +
                ("imports" to signedImports.map { it + ("header" to header) })))
            val admittedSigned = PackageScalarLinks.read(headers("primitive-memops.h"))!!
            assertEquals(2, admittedSigned.proved.size)
            assertEquals(listOf("Int${width}Rep", "Word${width}Rep"), admittedSigned.link.abi.map { it.arguments.single() })
            for (invalid in listOf("", "bad\nheader", "bad\"header", "bad\\header", null))
                assertThrows(IllegalArgumentException::class.java) { PackageScalarLinks.read(headers(invalid)) }
        }
    }

    @Test fun scalarSafeMetadataMustAgreeAtEveryRetainedBoundary() {
        fun native(rep: String, safety: String, result: String = "Int32Rep"): Map<String, Any?> {
            val base = module()
            val oldLink = base["packageScalarLink"] as Map<String, Any?>
            val oldProof = base["staticForeignImports"] as Map<String, Any?>
            val oldImport = (oldProof["imports"] as List<Map<String, Any?>>).single()
            val oldEmitted = oldImport["emitted"] as Map<String, Any?>
            val abi = mapOf("symbol" to "scalar_value", "entry" to "thc_native_${"a".repeat(64)}_0",
                "convention" to "ccall", "safety" to safety, "arguments" to listOf(rep), "result" to result)
            val imported = oldImport + mapOf("safety" to safety,
                "emitted" to (oldEmitted + mapOf("safety" to safety, "arguments" to listOf(rep, "void"),
                    "result" to listOf("void", result))))
            return (base - "packageScalarLink") + mapOf(
                "packageNativeLink" to (oldLink + mapOf("profile" to "thc-package-c-ffi-v1", "abi" to listOf(abi))),
                "staticForeignImports" to (oldProof + ("imports" to listOf(imported))))
        }
        val accepted = native("Int32Rep", "safe")
        assertEquals("safe", PackageScalarLinks.read(accepted)!!.link.abi.single().safety)
        assertEquals("AddrRep", PackageScalarLinks.read(native("Int32Rep", "safe", "AddrRep"))!!.link.abi.single().result)
        for (rep in listOf("AddrRep", "ByteArray#", "MutableByteArray#")) {
            val signature = PackageScalarLinks.read(native(rep, "safe"))!!.link.abi.single()
            assertEquals(listOf(rep), signature.arguments)
            assertEquals("safe", signature.safety)
        }
        val shared = native("AddrRep", "safe")
        val sharedLink = shared["packageNativeLink"] as Map<String, Any?>
        val sharedAbi = (sharedLink["abi"] as List<Map<String, Any?>>).single()
        val sharedProof = shared["staticForeignImports"] as Map<String, Any?>
        val sharedImport = (sharedProof["imports"] as List<Map<String, Any?>>).single()
        val unsafeImport = sharedImport + mapOf("safety" to "unsafe",
            "binder" to ((sharedImport["binder"] as Map<String, Any?>) + ("occurrence" to "unsafeValue")),
            "emitted" to ((sharedImport["emitted"] as Map<String, Any?>) + ("safety" to "unsafe")))
        val sharedVariants = shared + mapOf("packageNativeLink" to (sharedLink + ("abi" to listOf(sharedAbi,
            sharedAbi + mapOf("entry" to "thc_native_${"a".repeat(64)}_1", "safety" to "unsafe")))),
            "staticForeignImports" to (sharedProof + ("imports" to listOf(sharedImport, unsafeImport))))
        assertEquals(listOf("safe", "unsafe"), PackageScalarLinks.read(sharedVariants)!!.link.abi.map { it.safety })
        val proof = accepted["staticForeignImports"] as Map<String, Any?>
        val imported = (proof["imports"] as List<Map<String, Any?>>).single()
        val emitted = imported["emitted"] as Map<String, Any?>
        for (changed in listOf(imported + ("safety" to "unsafe"),
            imported + ("emitted" to (emitted + ("safety" to "unsafe"))))) {
            assertThrows(IllegalArgumentException::class.java) {
                PackageScalarLinks.read(accepted + ("staticForeignImports" to (proof + ("imports" to listOf(changed)))))
            }
        }
        for (rep in listOf("Int32Rep", "AddrRep", "ByteArray#", "MutableByteArray#")) assertThrows(IllegalArgumentException::class.java) {
            PackageScalarLinks.read(native(rep, "interruptible"))
        }
    }
}
