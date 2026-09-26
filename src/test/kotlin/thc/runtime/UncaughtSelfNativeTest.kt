// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import thc.CoreModules
import thc.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UncaughtSelfNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))

    @Suppress("UNCHECKED_CAST")
    @ParameterizedTest @ValueSource(strings = ["ast", "bytecode"])
    fun publicSelfThrowWithoutCatchIsGuestFailure(backend: String) {
        val receipt = Json.parse(File(root, "build/uncaught-self/manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", receipt["ghc"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in receipt[kind] as Map<String, String>) {
                val bytes = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                assertEquals(expected, bytes.joinToString("") { "%02x".format(it) }, "Stale $path")
            }
        // The native oracle uses a real SomeException. The guest Core uses an
        // opaque lifted payload, so rendering dependencies never enter the ABI proof.
        val native = ProcessBuilder(File(root, "build/uncaught-self/native/oracle").path,
            "+RTS", "-N2", "-RTS").start()
        try {
            assertTrue(native.waitFor(5, TimeUnit.SECONDS), "Native self throw did not terminate")
            assertEquals(1, native.exitValue(), "Uncaught native self throw must fail")
            assertFalse(native.inputStream.bufferedReader().readText().contains("99"))
            assertTrue(native.errorStream.bufferedReader().readText().contains("thread killed"))
        } finally { native.destroyForcibly() }
        for (stage in listOf("pre", "post")) {
            for (report in listOf("audit.json", "io-audit.json")) {
                @Suppress("UNCHECKED_CAST")
                val audit = Json.parse(File(root, "build/uncaught-self/$stage/$report").readText()) as Map<String, Any?>
                assertEquals(true, audit["accepted"], "$stage $report strict Core audit")
                assertEquals(emptyList<Any>(), audit["missingGlobals"])
                assertEquals(emptyList<Any>(), audit["issues"])
            }
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                val source = File(root, "build/uncaught-self/$stage/core/UncaughtSelfAudit.json")
                val entry = context.eval("thc", CoreModules.request(listOf(source.path), "selfUncaught",
                    backend = backend, asyncExceptions = true))
                val failure = assertThrows(PolyglotException::class.java) { entry.execute(0L) }
                assertGuestFailure(failure, "$backend $stage")
                assertTrue(entry.invokeMember("compile").asBoolean())
                val before = Json.parse(entry.getMember("diagnostics").asString()) as Map<*, *>
                val installed = assertThrows(PolyglotException::class.java) { entry.execute(1L) }
                assertGuestFailure(installed, "$backend $stage compiled entry")
                val after = Json.parse(entry.getMember("diagnostics").asString()) as Map<*, *>
                assertTrue((after["compiledEntries"] as Number).toLong() >
                    (before["compiledEntries"] as Number).toLong(), "$backend $stage installed guest code")
                val io = context.eval("thc", CoreModules.request(listOf(source.path), "selfUncaughtIO",
                    backend = backend, ioMain = true, asyncExceptions = true))
                assertFalse(io.canExecute(), "$stage IO action must use runIO")
                assertTrue(io.canInvokeMember("runIO"))
                val ioFailure = assertThrows(PolyglotException::class.java) { io.invokeMember("runIO") }
                assertGuestFailure(ioFailure, "$backend $stage uncaught runIO")
            }
        }
    }

    private fun assertGuestFailure(failure: PolyglotException, label: String) {
        assertTrue(failure.isGuestException, "$label must expose a guest exception")
        val message = failure.message.orEmpty()
        assertFalse(message.contains("Internal ") || message.contains("continuation", ignoreCase = true),
            "$label must not expose a continuation or internal suspension")
    }
}
