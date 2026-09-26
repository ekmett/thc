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
            listOf("ByteArray#", "AddrRep"), listOf("AddrRep", "AddrRep"))) {
            assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(listOf(variant(reps))) }
        }
    }

    @Test fun scalarSafeMetadataMustAgreeAtEveryRetainedBoundary() {
        fun native(rep: String, safety: String): Map<String, Any?> {
            val base = module()
            val oldLink = base["packageScalarLink"] as Map<String, Any?>
            val oldProof = base["staticForeignImports"] as Map<String, Any?>
            val oldImport = (oldProof["imports"] as List<Map<String, Any?>>).single()
            val oldEmitted = oldImport["emitted"] as Map<String, Any?>
            val abi = mapOf("symbol" to "scalar_value", "entry" to "thc_native_${"a".repeat(64)}_0",
                "convention" to "ccall", "safety" to safety, "arguments" to listOf(rep), "result" to "Int32Rep")
            val imported = oldImport + mapOf("safety" to safety,
                "emitted" to (oldEmitted + mapOf("safety" to safety, "arguments" to listOf(rep, "void"))))
            return (base - "packageScalarLink") + mapOf(
                "packageNativeLink" to (oldLink + mapOf("profile" to "thc-package-c-ffi-v1", "abi" to listOf(abi))),
                "staticForeignImports" to (oldProof + ("imports" to listOf(imported))))
        }
        val accepted = native("Int32Rep", "safe")
        assertEquals("safe", PackageScalarLinks.read(accepted)!!.link.abi.single().safety)
        val proof = accepted["staticForeignImports"] as Map<String, Any?>
        val imported = (proof["imports"] as List<Map<String, Any?>>).single()
        val emitted = imported["emitted"] as Map<String, Any?>
        for (changed in listOf(imported + ("safety" to "unsafe"),
            imported + ("emitted" to (emitted + ("safety" to "unsafe"))))) {
            assertThrows(IllegalArgumentException::class.java) {
                PackageScalarLinks.read(accepted + ("staticForeignImports" to (proof + ("imports" to listOf(changed)))))
            }
        }
        for ((rep, safety) in listOf("AddrRep" to "safe", "ByteArray#" to "safe", "MutableByteArray#" to "safe",
            "Int32Rep" to "interruptible")) assertThrows(IllegalArgumentException::class.java) {
            PackageScalarLinks.read(native(rep, safety))
        }
    }
}
