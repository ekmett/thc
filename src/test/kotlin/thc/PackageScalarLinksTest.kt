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
    private fun module(unit: String = "scalar-fixture", name: String = "Scalar"): Map<String, Any?> {
        val digest = "a".repeat(64)
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
        assertEquals(setOf("scalar_value"), PackageScalarLinks.read(original)!!.proved)
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
    }
}
