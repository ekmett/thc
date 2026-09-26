// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import java.io.File
import org.junit.jupiter.api.Assertions.*
import thc.Json

/** Independently derived, closed inventories; producer-supplied lists are not authority. */
internal object SimdByteArrayEvidence {
    private val shapes = mapOf("int32x4" to "Int32X4", "word32x4" to "Word32X4", "floatx4" to "FloatX4", "doublex2" to "DoubleX2")
    private val wrong = mapOf("int32x4" to listOf("Word32ElemRep"), "word32x4" to listOf("Int32ElemRep"),
        "floatx4" to listOf("Int32ElemRep", "Word32ElemRep", "DoubleElemRep"),
        "doublex2" to listOf("Int64ElemRep", "Int32ElemRep", "Word32ElemRep", "FloatElemRep"))
    private val frontiers = listOf("vectorArgument", "readVectorEscape", "readTupleEscape",
        "vectorReadWorker", "vectorWriteWorker", "scalarReadWorker", "scalarWriteWorker")
    private val local = listOf("vectorIndex", "vectorRead", "vectorWrite", "scalarIndex", "scalarRead", "scalarWrite")
    private fun floating(family: String) = family in listOf("floatx4", "doublex2")
    private fun records(manifest: Map<String, Any?>, field: String, required: Set<String>) {
        val rows = manifest[field] as List<Map<String, String>>
        assertEquals(required, rows.map { it["path"] }.toSet(), "$field inventory")
        assertEquals(required.size, rows.size, "$field duplicates")
        for (row in rows) {
            assertEquals(setOf("path", "sha256"), row.keys)
            assertTrue(row.getValue("sha256").matches(Regex("[0-9a-f]{64}")))
        }
    }
    fun inventory(root: File, family: String, manifest: Map<String, Any?>) {
        val directory = "build/simd-$family-bytearray"; val module = "Simd${shapes.getValue(family)}ByteArray"
        val attempt = manifest["attempt"] as String
        assertTrue(attempt.matches(Regex("$directory/prepare-run-[A-Za-z0-9]+")))
        assertEquals(1L, manifest["schema"]); assertEquals("$family-bytearray", manifest["vector"])
        val stages = manifest["stages"] as List<String>
        assertTrue(stages == listOf("pre") || stages == listOf("pre", "post"))
        val native = stages.size == 2
        val corpus = SimdByteArrayCorpus(family)
        assertEquals(corpus.rowCount.toLong(), manifest["modelRows"])
        assertEquals(if (native) corpus.rowCount.toLong() else null, manifest["nativeRows"])
        assertEquals(if (native) true else null, manifest["modelMatched"])
        assertEquals("little", manifest["modelByteOrder"])
        assertEquals(if (native) "little" else null, manifest["nativeByteOrder"])
        val entries = corpus.cases().keys
        val graphs = listOf("vector", "scalar").flatMap { listOf("${it}Index${if (floating(family)) "Graph" else "Worker"}", "${it}StoreGraph") }
        assertEquals(frontiers, manifest["frontiers"])
        val fresh = stages.flatMap { stage -> (entries+graphs+frontiers).map { "$stage-$it" } }
        val mutations = (stages+listOf("retained-pre", "retained-post")).flatMap { stage ->
            wrong.getValue(family).flatMap { element -> local.map { "$stage-wrong-$element-$it" } }
        }
        val retained = listOf("pre", "post").flatMap { stage -> local.map { "retained-$stage-${it}Case" } }
        val audits = fresh+mutations+retained
        val commands = listOf("ghc-version", "ghc-info", "host", "architecture", "system", "compiler-build",
            "retained-provenance", "retained-pre", "retained-post") + stages.map { "$it-export" } + audits +
            if (native) listOf("native-build", "native-oracle") + if (floating(family)) listOf("snan-oracle") else emptyList() else emptyList()
        val artifacts = listOf("$directory/expected.tsv", "$directory/requests.tsv") +
            stages.flatMap { listOf("$directory/$it-core/$module.json", "$directory/$it-audit.json") } +
            audits.map { "$attempt/audits/$it.json" } + mutations.map { "$attempt/mutations/$it.json" } +
            listOf("$attempt/retained/pre.json", "$attempt/retained/post.json") +
            (if (floating(family)) emptyList() else listOf("$attempt/retained-original-source.hs")) +
            commands.flatMap { name -> listOf("stdout", "stderr", "command.json").map { "$attempt/commands/$name.$it" } } +
            if (native) listOf("$directory/oracle.tsv", "$directory/native/$family-bytearray-oracle") +
                if (floating(family)) listOf("snan-expected.tsv", "snan-requests.tsv", "snan-oracle.tsv").map { "$directory/$it" } else emptyList() else emptyList()
        records(manifest, "artifacts", artifacts.toSet())
        val retainedBase = "bench/experiments/$family-bytearray/evidence-x86_64" + if (family == "doublex2") "/captures/doublex2" else ""
        val sources = listOf("compiler/test-fixtures/$module.hs", "compiler/test-fixtures/${module}Native.hs",
            "test/haskell-fixtures/SimdByteArrayFixtures.hs", "test/haskell-fixtures/SimdByteArrayModel.hs",
            "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
            "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/kotlin/thc/runtime/VectorMemoryPrimitives.kt",
            "src/main/resources/thc/scalar-primop-signatures.json", "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py") +
            (if (family == "doublex2") listOf("src/main/kotlin/thc/runtime/VectorMemory.kt") else emptyList()) +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { "compiler/THC/${it.name}" } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { "scripts/${it.name}" } +
            listOf("pre-core.json.gz", "post-core.json.gz", if (floating(family)) "input-provenance.json.gz" else "native/provenance.json.gz").map { "$retainedBase/$it" }
        records(manifest, "sources", sources.toSet())
        val controls = if (floating(family)) "familyNegativeControls" else if (family == "int32x4") "unsignedNegativeControls" else "signedNegativeControls"
        assertEquals(stages.toSet(), (manifest[controls] as Map<*, *>).keys)
        assertEquals(setOf("pre", "post"), (manifest["retainedControls"] as Map<*, *>).keys)
        assertEquals(commands.size, (manifest["commands"] as List<*>).size)
    }
    fun controls(root: File, family: String, manifest: Map<String, Any?>) {
        // Validate every required omission, duplicate, malformed hash, and escaping addition.
        // This stays metadata-only; the existing native tests hash the actual bytes.
        for (field in listOf("sources", "artifacts")) {
            val rows = manifest[field] as List<Map<String, String>>
            for (index in rows.indices) assertThrows(AssertionError::class.java) {
                inventory(root, family, manifest + (field to rows.filterIndexed { i, _ -> i != index }))
            }
            for (bad in listOf(rows+rows.first(), rows+mapOf("path" to "../escape", "sha256" to "0".repeat(64)),
                listOf(rows.first()+("sha256" to "not-a-hash"))+rows.drop(1))) assertThrows(AssertionError::class.java) {
                inventory(root, family, manifest+(field to bad))
            }
        }
        val commands = (manifest["artifacts"] as List<Map<String, String>>).map { it.getValue("path") }.filter { it.endsWith(".command.json") }
        for (path in commands) {
            val command = Json.parse(File(root, path).readText()) as Map<String, Any?>
            val name = File(path).name.removeSuffix(".command.json")
            val negative = "-wrong-" in name || frontiers.any { name == "pre-$it" || name == "post-$it" }
            assertEquals(if (negative) 1L else 0L, command["expectedExit"], path)
            assertEquals(command["expectedExit"], command["exit"], path)
            assertNull(command["timedOut"], path)
        }
    }
}
