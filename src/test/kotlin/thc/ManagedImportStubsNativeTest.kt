// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

class ManagedImportStubsNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/interface-core")
    private fun original(variant: String = "plain"): Map<String, Any?> {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val file = File(root, path)
                assertTrue(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "$kind/$path")
            }
        assertFalse(File(directory, "source/ForeignImportStubs.hs").exists())
        assertEquals("(12,8,9,0,11)", File(directory, "logs/import-stubs-native-oracle.stdout").readText().trim())
        return Json.parse(File(directory, "import-stubs/$variant.json").readText()) as Map<String, Any?>
    }
    private fun request(module: Map<String, Any?>, backend: String, entry: String = "probe") =
        Json.stringify(mapOf("modules" to listOf(module), "backend" to backend, "strictLink" to true, "entry" to entry))

    @Test fun unchangedOriginalImportsAdmitPureCoreAndFirstInstalledEntryOnBothBackends() {
        val module = original()
        assertNotNull(ManagedImportAdmission.read(module))
        assertNull(CoreForeignArtifacts.linked(module))
        assertEquals("not-linked", (module["foreign"] as Map<*, *>)["execution"])
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", "false")
            .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                val entry = context.eval("thc", request(module, backend))
                assertEquals(12L, entry.execute(5L).asLong())
                assertTrue(entry.invokeMember("compile").asBoolean())
                fun compiled() = (Json.parse(entry.getMember("diagnostics").asString()) as Map<String, Any?>)["compiledEntries"] as Long
                val before = compiled()
                assertEquals(26L, entry.execute(19L).asLong())
                assertTrue(compiled() > before)
                assertTrue(context.getBindings("thc").memberKeys.isEmpty(), "C imports do not create host exports")
            }
    }

    @Test fun unknownCallsAreStillRejectedAfterManagedArchiveAdmission() {
        val module = original()
        for (backend in listOf("ast", "bytecode")) Context.create("thc").use { context ->
            for (name in listOf("first", "second", "direct")) {
                val error = assertThrows(PolyglotException::class.java) { context.eval("thc", request(module, backend, name)) }
                assertFalse(error.message.orEmpty().contains("archive-only"), error.message)
                assertTrue(error.message.orEmpty().contains("foreign", ignoreCase = true), error.message)
            }
        }
    }

    @Test fun WholeProductAndTypedCallChangesNeverAcquireAdmission() {
        val module = original()
        val proof = module["staticForeignImportStubs"] as Map<String, Any?>
        val foreign = module["foreign"] as Map<String, Any?>
        val stubs = foreign["stubs"] as Map<String, Any?>
        val label = mapOf("isInitializer" to true, "unit" to module["unit"], "module" to module["module"], "name" to "extra")
        val products = listOf(
            foreign + ("stubs" to (stubs + ("source" to (stubs["source"].toString() + "extra")))),
            foreign + ("stubs" to (stubs + ("header" to "extra"))),
            foreign + ("stubs" to (stubs + ("initializers" to listOf(label)))),
            foreign + ("stubs" to (stubs + ("finalizers" to listOf(label + ("isInitializer" to false))))),
            foreign + ("files" to listOf(mapOf("language" to "LangC", "source" to "extra", "extension" to ".c"))))
        for (changed in products) assertThrows(IllegalArgumentException::class.java) {
            CoreForeignArtifacts.requireExecutable(module + ("foreign" to changed))
        }
        // Even coordinated product evidence cannot admit explicit lifecycle/file obligations.
        for (changed in products.drop(1)) assertThrows(IllegalArgumentException::class.java) {
            CoreForeignArtifacts.requireExecutable(module + mapOf("foreign" to changed,
                "staticForeignImportStubs" to (proof + ("expectedForeign" to changed))))
        }
        val imports = proof["imports"] as List<Map<String, Any?>>
        val capi = imports.first { it["convention"] == "capi" }
        val emitted = capi["emitted"] as Map<String, Any?>
        for (bad in listOf(
            module - "staticForeignImportStubs",
            module + ("staticForeignImportStubs" to (proof + ("profile" to "unknown"))),
            module + ("staticForeignImportStubs" to (proof + ("expectedCalls" to emptyList<Any>()))),
            module + ("staticForeignImportStubs" to (proof + ("imports" to imports.map {
                if (it === capi) it + ("emitted" to (emitted + ("result" to listOf("void", "DoubleRep")))) else it
            }))),
            module + ("schema" to 1L))) assertThrows(IllegalArgumentException::class.java) {
                CoreForeignArtifacts.requireExecutable(bad)
            }
        for (variant in listOf("extra-file", "wrapper", "instrumented")) {
            val rejected = original(variant)
            assertNull(ManagedImportAdmission.read(rejected))
            assertThrows(IllegalArgumentException::class.java) { CoreForeignArtifacts.requireExecutable(rejected) }
        }
    }

    @Test fun strictAuditorMatchesTheOriginalArchiveAndCallBoundary() {
        original()
        val output = File(directory, "import-stubs-runtime-audit.json")
        fun audit(variant: String, entry: String, status: Int): Map<String, Any?> {
            val process = ProcessBuilder("python3", File(root, "scripts/audit-core.py").path,
                File(directory, "import-stubs/$variant.json").path, "--entry", entry, "--output", output.path)
                .directory(root).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            assertEquals(status, process.waitFor(), log)
            return Json.parse(output.readText()) as Map<String, Any?>
        }
        assertEquals(true, audit("plain", "probe", 0)["accepted"])
        for (entry in listOf("first", "second", "direct"))
            assertTrue((audit("plain", entry, 1)["issues"] as List<Map<String, Any?>>).any { it["code"] == "foreign-call" })
        for (variant in listOf("extra-file", "wrapper", "instrumented"))
            assertEquals(false, audit(variant, "probe", 1)["accepted"])
    }
}
