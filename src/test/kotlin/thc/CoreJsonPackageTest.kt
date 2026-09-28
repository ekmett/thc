// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc

import thc.Main.executionContext

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoreJsonPackageTest {
    @TempDir lateinit var directory: Path
    private val boundary = "optimized-Core-after-Tidy-before-CorePrep"
    private fun resource(name: String) = javaClass.getResourceAsStream("/core/$name")!!.use { it.readBytes() }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun module() = mapOf("name" to "LazyJson", "path" to "LazyJson.json", "boundary" to boundary,
        "sha256" to digest(resource("lazy-json-package.json")),
        "index" to mapOf("path" to "LazyJson.idx", "sha256" to digest(resource("lazy-json-package.idx"))))
    private fun manifest(module: Map<String, Any?> = module(), order: List<String>? = null,
        indexBytes: ByteArray = resource("lazy-json-package.idx")): Path {
        val json = resource("lazy-json-package.json")
        var unit: Map<String, Any?> = mapOf("id" to "synthetic", "depends" to emptyList<String>(), "modules" to listOf(module))
        if (order == null) {
            Files.write(directory.resolve("LazyJson.json"), json)
            Files.write(directory.resolve("LazyJson.idx"), indexBytes)
        } else {
            val index = Json.stringify(mapOf("format" to "thc-core-bundle", "schema" to 1,
                "unit" to "synthetic", "buildKey" to "0".repeat(64), "exportKey" to "1".repeat(64),
                "modules" to listOf(module))).toByteArray()
            val members = mapOf("manifest.json" to index, "LazyJson.json" to json, "LazyJson.idx" to indexBytes)
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip -> order.forEach { name ->
                zip.putNextEntry(ZipEntry(name)); zip.write(members[name] ?: byteArrayOf(1)); zip.closeEntry()
            } }
            val bytes = out.toByteArray()
            val path = directory.resolve("bundle.zip")
            Files.write(path, bytes)
            unit = unit + ("bundle" to mapOf("path" to path.toString(), "sha256" to digest(bytes)))
        }
        return directory.resolve("packages.json").also { Files.writeString(it, Json.stringify(mapOf(
            "format" to "thc-core-packages", "schema" to 1, "ghc" to "9.14.1", "units" to listOf(unit)))) }
    }
    private fun request(path: Path, backend: String = "bytecode", verifyArtifacts: Boolean = false) = CoreModules.request(listOf("@$path"), "synthetic:LazyJson.entry", true, false, backend, false, false, null, null, null, verifyArtifacts)
    private fun count(value: Value, name: String) = ((Json.parse(value.getMember("diagnostics").asString())
        as Map<*, *>)[name] as Number).toLong()
    private fun visit(request: String) = CoreModules.visitRequestModules(Json.parse(request) as Map<String, Any?>) { }

    @Test fun nativeIndexWorksInLooseOrderedAndReorderedPackagesWithoutEagerBodyLowering() {
        val orders = listOf(null, listOf("manifest.json", "LazyJson.json", "LazyJson.idx"),
            listOf("LazyJson.idx", "LazyJson.json", "manifest.json"))
        for (order in orders) for (backend in listOf("ast", "bytecode")) {
            val path = manifest(order = order)
            val serialized = request(path, backend)
            assertFalse(serialized.contains("unused body is deliberately"))
            assertFalse((Json.parse(serialized) as Map<*, *>).containsKey("modules"))
            executionContext().use { context ->
                val value = context.eval("thc", serialized)
                assertEquals(1L, count(value, "jsonBodyMaterializations"))
                assertEquals(1L, count(value, "loweredRootCount"))
                assertEquals(4L, count(value, "jsonBindingHeaders"))
                assertEquals(0L, count(value, "jsonSourceHashBytesScanned"))
                assertEquals(0L, count(value, "jsonStructuralBytesScanned"))
                assertEquals(0L, count(value, "jsonIndexSourceBytesScanned"))
                assertEquals(1L, value.execute(0L).asLong())
                assertEquals(2L, count(value, "jsonBodyMaterializations"))
                assertEquals(2L, count(value, "loweredRootCount"))
                assertEquals(7L, value.execute(5L).asLong())
                assertEquals(3L, count(value, "jsonBodyMaterializations"))
            }
        }
    }

    @Test fun requestCreationReadsOnlyDirectoryAndReplayChecksManifestAndArtifacts() {
        val path = manifest()
        Files.delete(directory.resolve("LazyJson.json"))
        Files.delete(directory.resolve("LazyJson.idx"))
        val serialized = request(path, verifyArtifacts = true) // Neither module JSON nor sidecar is opened here.
        assertThrows(java.nio.file.NoSuchFileException::class.java) { visit(serialized) }
        manifest()
        Files.write(directory.resolve("LazyJson.idx"), byteArrayOf(0))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { visit(serialized) }
            .message.orEmpty().contains("index hash mismatch"))
        manifest()
        Files.writeString(directory.resolve("LazyJson.json"), "{}")
        assertTrue(assertThrows(IllegalArgumentException::class.java) { visit(serialized) }
            .message.orEmpty().contains("artifact hash mismatch"))
        manifest()
        Files.writeString(path, Files.readString(path) + " ")
        assertTrue(assertThrows(IllegalArgumentException::class.java) { visit(serialized) }
            .message.orEmpty().contains("manifest changed"))
    }

    @Test fun normalLoadingTrustsDeclaredDigestsButVerificationRejectsMismatch() {
        val wrong = module() + mapOf("sha256" to "0".repeat(64),
            "index" to mapOf("path" to "LazyJson.idx", "sha256" to "0".repeat(64)))
        for (order in listOf(null, listOf("manifest.json", "LazyJson.json", "LazyJson.idx"),
            listOf("LazyJson.idx", "LazyJson.json", "manifest.json"))) {
            val path = manifest(wrong, order)
            if (order != null) {
                val doc = Json.parse(Files.readString(path)) as Map<String, Any?>
                val unit = (doc["units"] as List<Map<String, Any?>>).single()
                val bundle = unit["bundle"] as Map<String, Any?>
                Files.writeString(path, Json.stringify(doc + ("units" to listOf(unit +
                    ("bundle" to (bundle + ("sha256" to "0".repeat(64))))))))
            }
            for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                val serialized = request(path, backend)
                Files.writeString(path, Files.readString(path) + " ")
                val entry = context.eval("thc", serialized)
                assertEquals(7L, entry.execute(5L).asLong())
                assertEquals(0L, count(entry, "jsonSourceHashBytesScanned"))
                assertEquals(0L, count(entry, "jsonStructuralBytesScanned"))
                assertEquals(0L, count(entry, "jsonIndexSourceBytesScanned"))
            }
            assertThrows(IllegalArgumentException::class.java) { visit(request(path, verifyArtifacts = true)) }
        }
    }

    @Test fun bothArchiveOrdersRequireExactPairedInventoryAndEachIndexIdentity() {
        val valid = listOf("manifest.json", "LazyJson.json", "LazyJson.idx")
        for (order in listOf(valid, valid.reversed())) {
            val wrong = module() + ("index" to mapOf("path" to "LazyJson.idx", "sha256" to "0".repeat(64)))
            assertThrows(IllegalArgumentException::class.java) { visit(request(manifest(wrong, order), verifyArtifacts = true)) }
            // Hash-consistent sidecar from another JSON is still rejected by source binding.
            val other = resource("lazy-json-module.idx")
            val mismatched = module() + ("index" to mapOf("path" to "LazyJson.idx", "sha256" to digest(other)))
            assertThrows(IllegalArgumentException::class.java) { visit(request(manifest(mismatched, order, other), verifyArtifacts = true)) }
        }
        for (order in listOf(valid.dropLast(1), valid + "extra.idx", valid.reversed() + "extra.idx")) {
            assertThrows(IllegalArgumentException::class.java) { visit(request(manifest(order = order))) }
        }
    }

    @Test fun indexReferencesCannotAliasModulesEscapeRootOrChangeOwner() {
        for (index in listOf(null, mapOf("path" to "../outside.idx", "sha256" to "0".repeat(64)),
            mapOf("path" to "LazyJson.json", "sha256" to "0".repeat(64)),
            mapOf("path" to "manifest.json", "sha256" to "0".repeat(64)),
            mapOf("path" to "LazyJson.idx", "sha256" to "0".repeat(64), "extra" to true))) {
            assertThrows(RuntimeException::class.java) { visit(request(manifest(module() + ("index" to index)))) }
        }
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            visit(request(manifest(module() + ("name" to "Other"))))
        }.message.orEmpty().contains("unit/module/boundary mismatch"))
    }
}
