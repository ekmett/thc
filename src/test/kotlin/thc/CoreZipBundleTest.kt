// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class CoreZipBundleTest {
    @TempDir lateinit var temporary: Path
    private val boundary = "optimized-Core-after-Tidy-before-CorePrep"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun zipped(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            buildList {
                while (true) {
                    val member = zip.nextEntry ?: break
                    add(member.name to zip.readBytes())
                    zip.closeEntry()
                }
            }
        }

    private fun module(unit: String, target: String? = null): ByteArray {
        val expression: List<Any?> = if (target == null) listOf("lit", "int", "51") else
            listOf("var", target, mapOf("rep" to mapOf("primReps" to listOf("IntRep"),
                "kind" to "long", "evaluated" to true)))
        return Json.stringify(mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to unit,
            "module" to "Shared", "boundary" to boundary, "constructors" to emptyList<Any?>(),
            "bindings" to listOf(mapOf("id" to "$unit:Shared.entry", "name" to "entry",
                "type" to "Int#", "arity" to 0, "lifted" to false, "expr" to expression))))
            .toByteArray()
    }

    private fun unit(id: String, source: ByteArray = module(id),
                     omit: Boolean = false, extra: Boolean = false,
                     innerUnit: String = id): Map<String, Any?> {
        val core = "core/Shared.json"
        val inventory = listOf(mapOf("name" to "Shared", "boundary" to boundary,
            "path" to core, "sha256" to hash(source)))
        val index = Json.stringify(mapOf("format" to "thc-core-bundle", "schema" to 1,
            "unit" to innerUnit, "buildKey" to "0".repeat(64), "exportKey" to "1".repeat(64),
            "modules" to inventory)).toByteArray()
        val entries = listOf("manifest.json" to index) +
            (if (omit) emptyList() else listOf(core to source)) +
            (if (extra) listOf("extra.json" to source) else emptyList())
        val archive = temporary.resolve("$id.zip")
        Files.write(archive, zipped(entries))
        return mapOf("id" to id, "depends" to emptyList<String>(),
            "bundle" to mapOf("path" to archive.toString(), "sha256" to hash(Files.readAllBytes(archive))),
            "modules" to inventory)
    }

    private fun manifest(units: List<Map<String, Any?>>): Path = temporary.resolve("packages.json").also {
        Files.writeString(it, Json.stringify(mapOf("format" to "thc-core-packages", "schema" to 1,
            "ghc" to "9.14.1", "units" to units)))
    }

    @Test fun sameModuleNameInDifferentUnitsLinksAndExecutesInBothBackends() {
        val other = "pkg-b:Shared.entry"
        val path = manifest(listOf(unit("pkg-b"), unit("pkg-a", module("pkg-a", other))))
        for (backend in listOf("ast", "bytecode")) {
            val request = CoreModules.request(listOf("@$path"), "pkg-a:Shared.entry", backend = backend)
            val input = Json.parse(request) as Map<*, *>
            assertEquals(true, input["strictLink"])
            assertEquals(2, (input["modules"] as List<*>).size)
            Context.newBuilder("thc").allowExperimentalOptions(true).build().use { context ->
                assertEquals(51L, context.eval("thc", request).execute().asLong(), backend)
            }
        }
    }

    @Test fun archiveInventoryIdentityAndBothHashesAreRequired() {
        val original = unit("pkg-a")
        val path = manifest(listOf(original))
        fun rejected() = assertThrows(RuntimeException::class.java) {
            CoreModules.request(listOf("@$path"), "pkg-a:Shared.entry")
        }
        val archive = temporary.resolve("pkg-a.zip")
        val valid = Files.readAllBytes(archive)
        Files.write(archive, valid + 0.toByte())
        assertTrue(rejected().message!!.contains("hash mismatch"))
        Files.write(archive, valid)

        val changedCore = zipped(unzip(valid).map { (name, bytes) ->
            name to if (name == "core/Shared.json") "{}".toByteArray() else bytes
        })
        Files.write(archive, changedCore)
        manifest(listOf(original + ("bundle" to mapOf("path" to archive.toString(),
            "sha256" to hash(changedCore)))))
        assertTrue(rejected().message!!.contains("artifact hash mismatch"))
        Files.write(archive, valid)

        fun check(record: Map<String, Any?>) { manifest(listOf(record)); rejected() }
        check(unit("pkg-a", omit = true))
        check(unit("pkg-a", extra = true))
        check(unit("pkg-a", innerUnit = "pkg-b"))
        check(unit("pkg-a", source = module("pkg-b")))
        val module = (unit("pkg-a")["modules"] as List<Map<String, Any?>>).single()
        check(unit("pkg-a") + ("modules" to listOf(module + ("sha256" to "f".repeat(64)))))
        check(unit("pkg-a") + ("modules" to listOf(module + ("path" to "../Shared.json"))))
        check(unit("pkg-a") + ("modules" to listOf(module, module)))
    }

    @Test fun duplicateZipMemberNamesAreRejected() {
        val original = unit("pkg-a")
        val archive = temporary.resolve("pkg-a.zip")
        val entries = zipped(listOf("manifest.json" to "{}".toByteArray(),
            "core/a.json" to "{}".toByteArray(), "core/b.json" to "{}".toByteArray()))
        // ZIP's writer prevents duplicates, so rename the second equal-length
        // local-header name after writing; the loader reads local entries.
        val changed = entries.toString(Charsets.ISO_8859_1).replace("core/b.json", "core/a.json")
            .toByteArray(Charsets.ISO_8859_1)
        Files.write(archive, changed)
        val record = original + ("bundle" to mapOf("path" to archive.toString(), "sha256" to hash(changed)))
        val path = manifest(listOf(record))
        val failure = assertThrows(RuntimeException::class.java) {
            CoreModules.request(listOf("@$path"), "pkg-a:Shared.entry")
        }
        assertTrue(failure.message!!.contains("Duplicate ZIP entry"))
    }
}
