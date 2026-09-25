// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.io.File

/** Original ghc-internal errno messages against an exact typed FCall consumer. */
class OriginalStrerrorNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val fixture = File(root, "build/original-strerror")
    private fun json(path: File) = Json.parse(path.readText()) as Map<String, Any?>
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))

    @Test fun originalMessagesSurviveNativeCopyAndCompiledCalls() {
        val manifest = json(File(fixture, "manifest.json"))
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals("C", manifest["locale"])
        assertEquals(6L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalStrerrorNative.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("build/original-strerror/oracle.json"),
            "build/original-strerror/")
        val oracle = json(File(fixture, "oracle.json"))
        val messages = oracle["messages"] as List<Map<String, Any?>>
        val raw = oracle["raw"] as List<Map<String, Any?>>
        assertEquals(listOf(2L, 22L), messages.map { it["errno"] })
        assertEquals(listOf(22L to 512L, 999999L to 512L, 22L to 4L, 22L to 8L),
            raw.map { it["errno"] to it["length"] })
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowNativeAccess(true)
            .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val module = OriginalStdioFixtures.module(listOf("strerror"))
                    val program: ExecutableProgram = if (backend == "ast") Program(language, module)
                        else BytecodeProgram(language, module)
                    val entry = program.entryTarget("strerror")
                    fun replay(compiled: Boolean) {
                        for (row in messages) {
                            val bytes = ByteArray(512) { 0x55 }
                            val address = ManagedAddress.fromByteArray(bytes)
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(0L, Calls.target(entry, arrayOf(0L, row["errno"], address, 512L, Unit)))
                            if (compiled) {
                                assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                valid(entry)
                            }
                            val end = bytes.indexOf(0)
                            assertTrue(end in 1..511)
                            assertEquals(row["message"], String(bytes, 0, end, Charsets.US_ASCII))
                        }
                        for (row in raw) {
                            val length = (row["length"] as Number).toInt()
                            val bytes = ByteArray(length) { 0x55 }
                            val address = ManagedAddress.fromByteArray(bytes)
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals((row["status"] as Number).toLong(),
                                Calls.target(entry, arrayOf(0L, row["errno"], address, length.toLong(), Unit)))
                            if (compiled) {
                                assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                valid(entry)
                            }
                            assertEquals((row["bytes"] as List<Number>).map { it.toLong() },
                                bytes.map { (it.toInt() and 0xff).toLong() }, "native strerror buffer for ${row["errno"]}/$length")
                        }
                    }
                    replay(false)
                    entry.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(entry, true)
                    valid(entry)
                    replay(true)
                    val bytes = ByteArray(512) { 0x55 }
                    val address = ManagedAddress.fromByteArray(bytes)
                    fun reject(error: Any?, output: Any?, length: Any?, state: Any?) {
                        assertThrows(RuntimeFault::class.java) {
                            Calls.target(entry, arrayOf(0L, error, output, length, state))
                        }
                        assertTrue(bytes.all { it == 0x55.toByte() })
                    }
                    reject(22L, address, 3L, Unit) // Original C's ERANGE branch indexes buflen-4.
                    reject(22L, address, 513L, Unit)
                    reject(1L shl 32, address, 512L, Unit)
                    reject(22L, address, 512L, 9L)
                    reject(22L, ManagedAddress.nullAddress(), 512L, Unit)
                } finally { context.leave() }
            }
    }
}
