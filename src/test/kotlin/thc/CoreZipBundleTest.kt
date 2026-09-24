// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.runtime.TargetLayout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
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
                     innerUnit: String = id, inputs: ByteArray? = null,
                     layout: Map<String, Any?>? = null, abi: String = "bcbf",
                     platform: String = hostPlatform(), way: String = "dynamic-nonprofiling"): Map<String, Any?> {
        val core = "core/Shared.json"
        val inventory = listOf(mapOf("name" to "Shared", "boundary" to boundary,
            "path" to core, "sha256" to hash(source)))
        val indexFields = mutableMapOf<String, Any>("format" to "thc-core-bundle", "schema" to 1,
            "unit" to innerUnit, "buildKey" to "0".repeat(64), "exportKey" to "1".repeat(64),
            "modules" to inventory)
        val generated = (0 until 6).map { mapOf("path" to "source$it.hsc", "sha256" to "a".repeat(64)) }
        if (layout != null) {
            indexFields["targetLayout"] = layout
            indexFields["generatedSources"] = generated
        }
        val inputBytes = inputs ?: layout?.let {
            Json.stringify(mapOf("format" to "thc-core-build-inputs", "schema" to 1,
                "unit" to id, "buildKey" to "0".repeat(64), "exportKey" to "1".repeat(64),
                "compiler" to mapOf("id" to "ghc-9.14.1", "abi" to abi,
                    "platform" to platform, "way" to way),
                "targetLayout" to layout, "generatedSources" to generated)).toByteArray()
        }
        if (inputBytes != null) indexFields["buildInputs"] =
            mapOf("path" to "inplace-manifest.json", "sha256" to hash(inputBytes))
        val index = Json.stringify(indexFields).toByteArray()
        val entries = listOf("manifest.json" to index) +
            (if (omit) emptyList() else listOf(core to source)) +
            (if (inputBytes == null) emptyList() else listOf("inplace-manifest.json" to inputBytes)) +
            (if (extra) listOf("extra.json" to source) else emptyList())
        val archive = temporary.resolve("$id.zip")
        Files.write(archive, zipped(entries))
        return mapOf("id" to id, "depends" to emptyList<String>(),
            "bundle" to mapOf("path" to archive.toString(), "sha256" to hash(Files.readAllBytes(archive))),
            "modules" to inventory)
    }

    private fun hostPlatform(): String {
        val arch = if (System.getProperty("os.arch").lowercase() in setOf("arm64", "aarch64")) "aarch64" else "x86_64"
        val os = if (System.getProperty("os.name").startsWith("Mac")) "osx" else "linux"
        return "$arch-$os"
    }

    private fun targetLayout(): Map<String, Any?> = mapOf(
        "schema" to 1, "profiled" to false, "wordBytes" to 8,
        "targetPlatform" to hostPlatform(),
        "endianness" to (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big"),
        "infoTableBytes" to 16, "infoTablePtrsOffset" to 0, "infoTablePtrsBytes" to 4,
        "infoTableNptrsOffset" to 4, "infoTableNptrsBytes" to 4,
        "infoTableTypeOffset" to 8, "infoTableTypeBytes" to 4,
        "infoTableSrtOffset" to 12, "infoTableSrtBytes" to 4,
        "infoProvEntBytes" to 72, "infoProvBytes" to 64,
        "infoProvEntInfoOffset" to 0, "infoProvEntProvOffset" to 8,
        "infoProvNameOffset" to 0, "infoProvDescOffset" to 8, "infoProvDescBytes" to 4,
        "infoProvTyDescOffset" to 16, "infoProvLabelOffset" to 24,
        "infoProvUnitOffset" to 32, "infoProvModuleOffset" to 40,
        "infoProvFileOffset" to 48, "infoProvSpanOffset" to 56,
        "closureRetBco" to 29, "closureRetSmall" to 30, "closureRetBig" to 31,
        "closureRetFun" to 32, "closureUpdateFrame" to 33, "closureCatchFrame" to 34,
        "closureUnderflowFrame" to 35, "closureStopFrame" to 36,
        "closureStack" to 53, "closureAtomicallyFrame" to 55,
        "closureCatchRetryFrame" to 56, "closureCatchStmFrame" to 57,
        "closureAnnFrame" to 65, "stackHeaderBytes" to 8,
        "stackCatchHandlerBytes" to 8, "stackCatchFrameBytes" to 16,
        "stackCatchStmCodeBytes" to 8, "stackCatchStmHandlerBytes" to 16,
        "stackCatchStmFrameBytes" to 24, "stackUpdateeBytes" to 8,
        "stackUpdateFrameBytes" to 16, "stackAtomicallyCodeBytes" to 8,
        "stackAtomicallyResultBytes" to 16, "stackAtomicallyFrameBytes" to 24,
        "stackCatchRetryAltCodeBytes" to 8, "stackCatchRetryFirstCodeBytes" to 16,
        "stackCatchRetryAltBytes" to 24, "stackCatchRetryFrameBytes" to 32,
        "stackRetFunSizeBytes" to 8, "stackRetFunFunBytes" to 16,
        "stackRetFunPayloadBytes" to 24, "stackRetFunFrameBytes" to 24,
        "stackAnnPayloadBytes" to 8, "stackAnnFrameBytes" to 16,
        "stackClosurePayloadBytes" to 8,
    )

    @Test fun nativeBuildInputsAreVerifiedWhenPresent() {
        fun inputs(buildKey: String) = Json.stringify(mapOf("format" to "thc-core-build-inputs",
            "schema" to 1, "unit" to "pkg-a", "buildKey" to buildKey,
            "exportKey" to "1".repeat(64))).toByteArray()
        val good = manifest(listOf(unit("pkg-a", inputs = inputs("0".repeat(64)))))
        val request = Json.parse(CoreModules.request(listOf("@$good"), "pkg-a:Shared.entry")) as Map<*, *>
        assertEquals(1, (request["modules"] as List<*>).size)
        val bad = manifest(listOf(unit("pkg-a", inputs = inputs("f".repeat(64)))))
        assertTrue(assertThrows(RuntimeException::class.java) {
            CoreModules.request(listOf("@$bad"), "pkg-a:Shared.entry")
        }.message!!.contains("build inputs record"))
    }

    @Test fun wiredTargetLayoutIsValidatedAndCarriedToBothBackends() {
        val layout = targetLayout()
        val path = manifest(listOf(unit("pkg-a", layout = layout)))
        for (backend in listOf("ast", "bytecode")) {
            val request = CoreModules.request(listOf("@$path"), "pkg-a:Shared.entry", backend = backend)
            val input = Json.parse(request) as Map<*, *>
            val record = TargetLayout.fromDocument(input["targetLayout"])
            assertEquals(8, record.wordBytes)
            assertEquals(8, record.offset("infoProvEntProvOffset"))
            Context.newBuilder("thc").allowExperimentalOptions(true).build().use { context ->
                assertEquals(51L, context.eval("thc", request).execute().asLong(), backend)
                val original = input["targetLayout"] as Map<*, *>
                val source = original["layout"] as Map<*, *>
                val malformed = input + ("targetLayout" to
                    (original + ("layout" to (source + ("infoProvSpanOffset" to 64)))))
                assertThrows(RuntimeException::class.java) {
                    context.eval("thc", Json.stringify(malformed))
                }
            }
        }
        fun rejected(units: List<Map<String, Any?>>) = assertThrows(RuntimeException::class.java) {
            CoreModules.request(listOf("@${manifest(units)}"), "pkg-a:Shared.entry")
        }
        val wrongEndian = rejected(listOf(unit("pkg-a", layout = layout + ("endianness" to "invalid"))))
        assertTrue(wrongEndian.message!!.contains("endianness"))
        val wrongOffset = rejected(listOf(unit("pkg-a", layout = layout + ("infoProvSpanOffset" to 64))))
        assertTrue(wrongOffset.message!!.contains("exceeds"))
        val wrongPlatform = rejected(listOf(unit("pkg-a", layout = layout, platform = "other-os")))
        assertTrue(wrongPlatform.message!!.contains("identity or way"))
        val wrongWay = rejected(listOf(unit("pkg-a", layout = layout, way = "profiling")))
        assertTrue(wrongWay.message!!.contains("identity or way"))
        val conflicting = rejected(listOf(unit("pkg-a", layout = layout),
            unit("pkg-b", layout = layout, abi = "other")))
        assertTrue(conflicting.message!!.contains("Conflicting GHC target layouts"))
        val noReceipt = Json.stringify(mapOf("format" to "thc-core-build-inputs", "schema" to 1,
            "unit" to "pkg-a", "buildKey" to "0".repeat(64), "exportKey" to "1".repeat(64)))
            .toByteArray()
        val missingReceipt = rejected(listOf(unit("pkg-a", layout = layout, inputs = noReceipt)))
        assertTrue(missingReceipt.message!!.contains("receipts differ"))
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

        val truncated = valid.copyOf(valid.size - 10)
        Files.write(archive, truncated)
        manifest(listOf(original + ("bundle" to mapOf("path" to archive.toString(),
            "sha256" to hash(truncated)))))
        rejected()
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
        val reference = original["bundle"] as Map<*, *>
        check(original + ("bundle" to null))
        check(original + ("bundle" to (reference + ("path" to "pkg-a.zip"))))
        check(original + ("bundle" to (reference + ("unused" to true))))
        check(unit("pkg-a") + ("modules" to listOf(module + ("sha256" to "f".repeat(64)))))
        check(unit("pkg-a") + ("modules" to listOf(module + ("path" to "../Shared.json"))))
        check(unit("pkg-a") + ("modules" to listOf(module + ("path" to "C:Shared.json"))))
        check(unit("pkg-a") + ("modules" to listOf(module + ("path" to "core/\nShared.json"))))
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
