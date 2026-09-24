// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real Core, a running Java target, and ordinary catch#: no manually armed checkpoints. */
class LiveAsyncNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val entries = listOf("forceShared", "takeReady", "takeRunning", "releaseGate", "prefixCount", "warmLoop", "asyncPayload")

    @Suppress("UNCHECKED_CAST")
    private fun fixture(stage: String): Map<String, Any?> {
        val receipt = Json.parse(File(root, "build/live-async/manifest.json").readText()) as Map<String, Any?>
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in receipt[kind] as Map<String, String>) {
                val bytes = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                assertEquals(expected, bytes.joinToString("") { "%02x".format(it) }, "Stale $path")
            }
        assertEquals(listOf("1007", "-1", "10000008", "1", "1031"),
            File(root, "build/live-async/oracle.txt").readLines())
        for (entry in entries) {
            val audit = Json.parse(File(root, "build/live-async/$stage/$entry-audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"], entry)
            assertEquals(emptyList<Any>(), audit["missingGlobals"], entry)
        }
        val module = Json.parse(File(root, "build/live-async/$stage/core/LiveAsyncAudit.json").readText()) as Map<String, Any?>
        val bindings = entries.flatMap { CoreModules.reachable(module, it)["bindings"] as List<Map<String, Any?>> }
            .distinctBy { it["id"] }
        return module + mapOf("bindings" to bindings, "instrument" to true)
    }

    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    }

    private fun <T> entered(context: Context, action: () -> T): T {
        context.enter()
        try { return action() } finally { context.leave() }
    }

    private fun exercise(stage: String, running: Boolean) {
        val module = fixture(stage)
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.Splitting", "false")
            .option("engine.CompilationFailureAction", "Throw").build().use { context ->
            context.initialize("thc")
            lateinit var program: BytecodeProgram
            lateinit var state: Language.State
            lateinit var functions: Map<String, Value>
            entered(context) {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                state = Language.currentState()
                program = BytecodeProgram(language, module, true)
                functions = entries.filter { it != "asyncPayload" }.associateWith {
                    context.asValue(EntryValue(program, it, 1))
                }
            }
            fun call(name: String, argument: Long = 0): Long = functions.getValue(name).execute(argument).asLong()
            val shared = program.entryValue("shared") as Thunk
            val loop = program.entryTarget("longLoop")
            // Warm every loop branch without entering the shared CAF. Drain the
            // two signals so the next run synchronizes with the actual target.
            assertEquals(1L, call("releaseGate"))
            assertEquals(9999007L, call("warmLoop", 9999000L))
            assertEquals(7L, call("takeReady"))
            assertEquals(7L, call("takeRunning"))
            assertEquals(0, shared.state)
            assertEquals(0L, call("prefixCount"))
            entered(context) { compile(loop) }
            if (running) assertEquals(1L, call("releaseGate"))

            val result = CompletableFuture<Long>()
            val target = Thread({
                try { result.complete(call("forceShared")) }
                catch (failure: Throwable) { result.completeExceptionally(failure) }
            }, "thc-async-target")
            target.isDaemon = true
            target.start()
            try {
                assertEquals(1007L, call("takeReady"))
                if (running) assertEquals(1007L, call("takeRunning"))
                assertFalse(result.isDone, "Interruption must target the live computation")
                assertSame(loop, program.entryTarget("longLoop"))
                assertEquals(true, loop.javaClass.getMethod("isValidLastTier").invoke(loop),
                    "The actual loop remains compiled before delivery")
                val request = state.threads.send(target.threadId(), program.entryValue("asyncPayload"))
                assertEquals(-1L, result.get(15, TimeUnit.SECONDS), "Original catch# must handle delivery")
                target.join(5000)
                assertFalse(target.isAlive)
                assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
                assertEquals(5, shared.state, "The abandoned shared thunk retains its continuation")
                assertEquals(1L, call("prefixCount"))
                if (!running) assertEquals(1L, call("releaseGate"))
                // This call uses a different Java thread from the original owner.
                assertEquals(10000008L, call("forceShared", 1))
                assertEquals(2, shared.state)
                assertEquals(1L, call("prefixCount"), "Resumption must not replay the effectful prefix")
                assertNull(shared.target)
                assertNull(shared.environment)
                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
            } finally {
                // Context cancellation is only a failed-test escape hatch; it is
                // not how asynchronous guest delivery is implemented or tested.
                if (target.isAlive) context.close(true)
            }
        }
    }

    @Test fun blockedMVarPreservesSharedThunk() {
        for (stage in listOf("pre", "post")) exercise(stage, false)
    }

    @Test fun compiledLoopPreservesSharedThunk() {
        for (stage in listOf("pre", "post")) exercise(stage, true)
    }
}
