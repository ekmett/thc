// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import java.io.File
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import thc.Json

/** Closed inventories for the five related native fixtures, independent of their producer. */
internal object ByteArrayFixtureEvidence {
    private val modules = mapOf("bytearray" to "ByteArrayAudit", "mutable-bytearrays" to "MutableByteArrayAudit",
        "resize-bytearrays" to "ResizeByteArrayAudit", "mutable-bytearray-size" to "MutableByteArraySizeAudit",
        "compare-byte-arrays" to "CompareByteArraysAudit")
    private val entries = mapOf("bytearray" to listOf("shortBytes", "orderedBytes", "shortUncons", "copiedBytes"),
        "mutable-bytearrays" to listOf("filledBytes", "movedBytes", "disjointBytes", "copiedMutableBytes", "copiedDisjointBytes", "publicReplicate"),
        "resize-bytearrays" to listOf("resizedBytes", "resizedTwiceWrites"),
        "mutable-bytearray-size" to listOf("freshSize", "pureSize", "resizedSizes", "pureAfterResize", "orderedSize"),
        "compare-byte-arrays" to listOf("shortCompare", "shortPrefix", "shortSuffix", "rangeCompare", "aliasCompare"))
    private val drivers = mapOf("bytearray" to "NativeByteArray.hs", "mutable-bytearrays" to "NativeMutableByteArrays.hs",
        "compare-byte-arrays" to "NativeCompareByteArrays.hs")
    private val originalSources = listOf("LICENSE", "GHC/Internal/Base.hs", "GHC/Internal/List.hs",
        "GHC/Internal/Exception/Type.hs-boot", "GHC/Internal/IO.hs-boot", "GHC/Internal/Num.hs-boot",
        "GHC/Internal/Enum.hs-boot", "GHC/Internal/Real.hs-boot").map { "vendor/ghc-9.14.1/$it" }.toSet()
    private fun read(root: File, path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun verify(root: File, group: String, manifest: Map<String, Any?>) {
        val directory = "build/$group"; val module = modules.getValue(group); val names = entries.getValue(group)
        val original = group in listOf("bytearray", "compare-byte-arrays")
        val generated = group in drivers
        val commands = listOf("ghc-version", "ghc-info", "bytestring-version", "bytestring-description", "native-build", "native-oracle") +
            (if (original) emptyList() else listOf("compiler-build", "primop-coverage")) +
            (if (generated) emptyList() else listOf("native-inputs")) + listOf("pre", "post").flatMap { stage ->
                listOf("$stage-export") + (if (original) listOf("$stage-original-list") else emptyList()) + names.map { "$stage-$it-audit" }
            }
        val stages = listOf("pre", "post").associateWith { stage ->
            if (original) listOf(module, "THC.InterfaceClosure", "GHC.Internal.Base", "GHC.Internal.List").sorted().map { "$directory/$stage/core/$it.json" }
            else listOf(module, "THC.InterfaceClosure").sorted().map { "$directory/$stage-core/$it.json" }
        }
        val sourcePaths = listOf("compiler/test-fixtures/$module.hs", "test/haskell-fixtures/ByteArrayFixtures.hs",
            "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal", "scripts/audit-core.py",
            "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
            "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { it.relativeTo(root).path } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { it.relativeTo(root).path } +
            (if (original) listOf("compiler/export-boot.py") + originalSources else listOf("scripts/primop-coverage.py")) +
            (if (generated) emptyList() else listOf("compiler/test-fixtures/${module.removeSuffix("Audit")}Native.hs", "compiler/test-fixtures/ByteArrayFixtureInputs.hs"))
        val artifacts = listOf("$directory/requests.tsv", "$directory/oracle.tsv",
            "$directory/native/${if (group == "bytearray") "bytearray" else group}-oracle") +
            (drivers[group]?.let { listOf("$directory/$it") } ?: emptyList()) + stages.values.flatten() +
            listOf("pre", "post").flatMap { stage -> names.map { if (original) "$directory/$stage/$it.audit.json" else "$directory/$stage-$it.audit.json" } +
                (if (original) listOf("$directory/$stage/boot-provenance.json") else emptyList()) } +
            commands.flatMap { name -> listOf("stdout", "stderr", "command.json").map { "$directory/commands/$name.$it" } }
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals("0.12.2.0", manifest["bytestring"]); assertEquals(64L, manifest["wordBits"])
        assertEquals(names, manifest["entries"]); assertEquals(stages, manifest["stages"])
        for ((kind, required) in mapOf("inputHashes" to sourcePaths.toSet(), "artifactHashes" to artifacts.toSet())) {
            val hashes = manifest[kind] as Map<String, String>
            assertEquals(required, hashes.keys, "$group exact $kind inventory")
        }
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>)
            assertEquals(expected, digest(File(root, path)), "$group stale $path")
        val recorded = manifest["commands"] as List<Map<String, Any?>>
        assertEquals(commands.size, recorded.size)
        val actual = commands.map { read(root, "$directory/commands/$it.command.json") }
        assertEquals(actual.toSet(), recorded.toSet()); assertEquals(actual.size, actual.toSet().size)
        for (command in recorded) { assertEquals(0L, command["exit"]); assertEquals(0L, command["expectedExit"]) }
        for ((stage, paths) in stages) {
            val core = read(root, paths.single { it.endsWith("/$module.json") })
            assertEquals(if (stage == "pre") "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep", core["boundary"])
            for (name in names) {
                val audit = read(root, if (original) "$directory/$stage/$name.audit.json" else "$directory/$stage-$name.audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["issues"]); assertEquals(emptyList<Any>(), audit["missingGlobals"])
            }
            if (original) {
                val provenance = read(root, "$directory/$stage/boot-provenance.json")
                assertEquals("ghc-9.14.1-release", provenance["ghcTag"]); assertEquals(emptyList<Any>(), provenance["sourcePatches"])
                assertEquals("lists", provenance["frontier"])
                assertEquals(listOf("GHC.Internal.Base", "GHC.Internal.List"), provenance["sourceModules"])
                val sources = provenance["sources"] as List<Map<String, String>>
                assertEquals(originalSources, sources.map { it.getValue("path") }.toSet()); assertEquals(originalSources.size, sources.size)
                for (source in sources) assertEquals(source["sha256"], digest(File(root, source.getValue("path"))))
            }
        }
    }

    fun rejectionControls(root: File, group: String) {
        val good = read(root, "build/$group/manifest.json")
        verify(root, group, good)
        for (kind in listOf("inputHashes", "artifactHashes")) {
            val hashes = good[kind] as Map<String, String>
            for (path in hashes.keys)
                assertThrows(AssertionError::class.java, { verify(root, group, good + (kind to (hashes - path))) }, path)
            assertThrows(AssertionError::class.java) { verify(root, group, good + (kind to (hashes + (hashes.keys.first() to "0".repeat(64))))) }
        }
        for ((key, wrong) in listOf("entries" to emptyList<String>(), "stages" to emptyMap<String, Any>(), "wordBits" to 32L,
            "ghc" to "other", "bytestring" to "other", "commands" to emptyList<Any>()))
            assertThrows(AssertionError::class.java, { verify(root, group, good + (key to wrong)) }, key)
    }
}
