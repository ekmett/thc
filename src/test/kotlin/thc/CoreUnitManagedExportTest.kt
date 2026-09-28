// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc

import thc.Main.loadManagedExports
import thc.Main.executionContext

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.runtime.RuntimeFault

class CoreUnitManagedExportTest {
    @TempDir lateinit var directory: Path
    @AfterEach fun releaseIdleMappings() { CoreFileMappings.shared.evictIdleBelow(directory) }
    private val root = File(System.getProperty("thc.projectRoot"))
    private val fixtures = File(root, "build/interface-core")
    private val unit = "thc-interface-fixture-0.1"
    private val module = "ForeignExportManaged"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun verified(relative: String): ByteArray {
        val file = File(fixtures, relative)
        val receipt = Json.parse(File(fixtures, "manifest.json").readText()) as Map<String, Any?>
        val bytes = file.readBytes()
        assertEquals((receipt["artifactHashes"] as Map<String, String>)[file.relativeTo(root).path], hash(bytes))
        return bytes
    }

    /** Test-only packaging of unchanged genuine GHC output. The reference
     * index locates bytes here; the runtime consumes only the text directory. */
    private fun manifest(label: String, corrupt: Boolean = false): Path {
        val original = verified("typed-foreign-exports/managed.json")
        val source = Json.parse(original.toString(Charsets.UTF_8)) as Map<String, Any?>
        val metadata = source.filterKeys { it in setOf("schema", "ghc", "unit", "module", "boundary", "providedModules", "constructors",
            "foreign", "foreignLink", "staticForeignImportStubs", "staticForeignImports", "staticForeignExports",
            "staticForeignExportRegistration", "packageScalarLink", "packageNativeLink", "packageNativeArchive",
            "foreignExceptionBridge", "foreignExceptionBridgeUnit") }
        val admitted = if (!corrupt) metadata else metadata + ("staticForeignExportRegistration" to
            ((metadata["staticForeignExportRegistration"] as Map<String, Any?>) + ("status" to "unclassified")))
        val encoded = Json.stringify(admitted).toByteArray()
        val notes = source.filterKeys { it == "sourceFiles" || it == "sourceSpans" }
        val encodedNotes = if (notes.isEmpty()) byteArrayOf() else Json.stringify(notes).toByteArray()
        val bytes = original + byteArrayOf(10) + encoded +
            if (notes.isEmpty()) byteArrayOf() else byteArrayOf(10) + encodedNotes
        val json = directory.resolve("$label.jsons")
        val symbols = directory.resolve("$label.symbols")
        val record = CoreJsonIndex.fromBytes(original).use { index ->
            val bindings = index.root.member("bindings")!!
            val rows = bindings.elements().map { span ->
                (span.member("id")!!.decode() as String) to span.start
            }.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a.first.toByteArray(), b.first.toByteArray()) }
            Files.writeString(symbols, rows.joinToString("") { (id, offset) -> "$id $offset\n" })
            mapOf("name" to module, "path" to "core/$module.json", "sha256" to hash(original),
                "boundary" to source["boundary"], "start" to 0, "end" to original.size,
                "bindingsStart" to bindings.start, "bindingsEnd" to bindings.endExclusive,
                "metadataStart" to original.size + 1, "metadataEnd" to original.size + 1 + encoded.size,
                "containsDelimitedControl" to thc.runtime.DelimitedControl.contains(source["bindings"]),
                "registrationObligations" to CoreForeignArtifacts.hasRegistrationObligations(source),
                "mainAlias" to (source["bindings"] as List<Map<String, Any?>>).any { it["id"] == "main::$module.main" },
                "packageScalarDeclarations" to ((source["staticForeignImports"] as? Map<*, *>)?.get("imports") as? List<*>)
                    .orEmpty().isNotEmpty()) + if (notes.isEmpty()) emptyMap() else mapOf(
                        "sourceMetadataStart" to original.size + encoded.size + 2,
                        "sourceMetadataEnd" to bytes.size)
        }
        Files.write(json, bytes)
        val active = mapOf("id" to unit, "depends" to emptyList<String>(),
            "json" to mapOf("path" to json.toString(), "sha256" to hash(bytes)),
            "symbols" to mapOf("path" to symbols.toString(), "sha256" to hash(Files.readAllBytes(symbols))),
            "modules" to listOf(record))
        val cold = mapOf("id" to "cold", "depends" to emptyList<String>(),
            "json" to mapOf("path" to directory.resolve("absent.jsons").toString(), "sha256" to "0".repeat(64)),
            "symbols" to mapOf("path" to directory.resolve("absent.symbols").toString(), "sha256" to "0".repeat(64)),
            "modules" to listOf(record + mapOf("name" to "Unused", "registrationObligations" to false)))
        return directory.resolve("$label-packages.json").also { path ->
            Files.writeString(path, Json.stringify(mapOf("format" to "thc-core-packages", "schema" to 1,
                "ghc" to "9.14.1", "units" to listOf(active, cold))))
        }
    }
    private fun exports(value: Value) = value.getMember(unit).getMember(module)
    private fun program(context: Context): CoreUnitProgram {
        context.enter()
        return try { Language.currentState().coreUnitPrograms.single() } finally { context.leave() }
    }

    @Test fun pairedGenuineExportsInvokeAndShareCafsWithoutOpeningUnrelatedUnits() {
        val path = manifest("valid")
        val native = verified("logs/managed-export-native-oracle.stdout").toString(Charsets.UTF_8).trim().lines()
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val symbols = exports(loadManagedExports(context, listOf("@$path"), backend))
            assertEquals(native[0].toInt(), symbols.getMember("thc_add_one").execute(-19).asInt())
            assertEquals(native[0].toInt(), symbols.getMember("thc_add_alias").execute(-19).asInt())
            assertEquals(native[3].toInt(), symbols.getMember("thc_constant").execute().asInt())
            assertEquals(native[4].toInt(), symbols.getMember("thc_next").execute(3).asInt())
            assertEquals(native[5].toInt(), symbols.getMember("thc_next_alias").execute(4).asInt())
            val counts = program(context).diagnostics()
            assertEquals(1L, counts["coreUnitSourceOpens"])
            assertEquals(1L, counts["coreUnitDirectoryOpens"])
            assertEquals(0L, counts["coreUnitHashBytesScanned"])
            assertEquals(0L, counts["unsupportedTraps"])
        }
        assertFalse(Files.exists(directory.resolve("absent.jsons")))
    }

    @Test fun failedRegistrationClosesSourcesAndDoesNotPublishOrPoisonRetry() {
        val bad = manifest("bad", corrupt = true)
        val good = manifest("good")
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            assertThrows(RuntimeException::class.java) { loadManagedExports(context, listOf("@$bad"), backend) }
            assertTrue(context.getBindings("thc").memberKeys.isEmpty())
            context.enter()
            try {
                assertTrue(Language.currentState().coreUnitPrograms.isEmpty())
                assertEquals(0, Language.currentState().foreignRoots.size())
            } finally { context.leave() }
            assertEquals(1, CoreFileMappings.shared.evictIdleBelow(directory.resolve("bad.jsons")),
                "Failed registration must release its source lease before retry")
            val symbols = exports(loadManagedExports(context, listOf("@$good"), backend))
            val next = symbols.getMember("thc_next")
            assertThrows(RuntimeException::class.java) { next.execute("bad") }
            assertEquals(1, next.execute(1).asInt())
            assertThrows(RuntimeException::class.java) { loadManagedExports(context, listOf("@$good"), backend) }
            assertEquals(2, symbols.getMember("thc_next_alias").execute(1).asInt())
        }
    }

    @Test fun explicitVerificationStillChecksTheOriginalModuleAndProjectedMetadata() {
        val good = manifest("verified")
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val symbols = exports(loadManagedExports(context, listOf("@$good"), backend, true, true))
            assertEquals(42, symbols.getMember("thc_add_one").execute(41).asInt())
            val counts = program(context).diagnostics()
            assertTrue((counts["coreUnitHashBytesScanned"] as Long) > 0)
            assertTrue((counts["coreUnitVerifiedModuleBytes"] as Long) > 0)
            assertEquals(1L, counts["coreUnitSourceOpens"])
        }
    }

    @Test fun sharedEngineLoadKeepsRegistrationAndMemoizedStateContextOwned() {
        val path = manifest("shared")
        for (backend in listOf("ast", "bytecode")) Engine.newBuilder().build().use { engine ->
            val request = Source.newBuilder("thc", CoreModules.managedExportRequest(listOf("@$path"), backend, true),
                "paired-managed-exports").cached(true).buildLiteral()
            val first = Context.newBuilder("thc").engine(engine).build()
            val second = Context.newBuilder("thc").engine(engine).build()
            try {
                val a = exports(first.eval(request)).getMember("thc_next")
                val b = exports(second.eval(request)).getMember("thc_next_alias")
                assertEquals(3, a.execute(3).asInt())
                assertEquals(4, b.execute(4).asInt())
                assertNotSame(program(first), program(second))
                first.enter()
                val scope = try { Language.currentState().managedExports.scope } finally { first.leave() }
                second.enter()
                try { assertThrows(RuntimeFault::class.java) { scope.hasMembers() } } finally { second.leave() }
                first.close()
                assertThrows(RuntimeException::class.java) { a.execute(1) }
                assertEquals(5, b.execute(1).asInt())
            } finally { first.close(); second.close() }
        }
    }
}
