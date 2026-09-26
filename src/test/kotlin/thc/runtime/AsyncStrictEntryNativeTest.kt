// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A real GHC PAP and catch#, with a supplied valid CBV contract on its unused strict formal. */
class AsyncStrictEntryNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val entries = listOf("strictEntry", "strictCall", "strictWorker", "takeReady", "takeRunning", "releaseGate",
        "forceShared", "prefixCount", "warmLoop", "asyncPayload")

    @Suppress("UNCHECKED_CAST")
    private fun fixture(stage: String): Map<String, Any?> {
        val receipt = Json.parse(File(root, "build/live-async/manifest.json").readText()) as Map<String, Any?>
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in receipt[kind] as Map<String, String>) {
                val bytes = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                assertEquals(expected, bytes.joinToString("") { "%02x".format(it) }, "Stale $path")
            }
        assertEquals(listOf("1007", "-1", "10000008", "1", "1031"),
            File(root, "build/live-async/strict-oracle.txt").readLines())
        for (entry in entries) {
            val audit = Json.parse(File(root, "build/live-async/$stage/$entry-audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"], entry)
            assertEquals(emptyList<Any>(), audit["missingGlobals"], entry)
        }
        val module = Json.parse(File(root, "build/live-async/$stage/core/LiveAsyncAudit.json").readText()) as Map<String, Any?>
        val reachable = entries.flatMap { CoreModules.reachable(module, it)["bindings"] as List<Map<String, Any?>> }
            .distinctBy { it["id"] }
        val worker = reachable.single { it["name"] == "strictWorker" }
        val expression = worker["expr"] as List<Any?>
        val strictCase = expression[2] as List<Any?>
        assertEquals("case", strictCase[0])
        val alternative = (strictCase[3] as List<List<Any?>>).single()
        val value = alternative[3]
        assertEquals("var", (value as List<*>)[0])
        assertEquals(listOf(false, false), worker["entryStrict"])
        // GHC's ordinary top-level binding has no worker-style CBV marks. Its
        // bang-pattern case proves the first formal is strict; supply that same
        // contract to isolate callee-prologue suspension, and remove only the
        // now-redundant case. Without the prologue, the worker returns before
        // the shared thunk signals readiness, so this test cannot pass.
        val contract = listOf(true, false)
        val annotated = expression.toMutableList().also {
            it[2] = value
            it[3] = (expression[3] as Map<String, Any?>) + ("entryStrict" to contract)
        }
        val bindings = reachable.map { if (it === worker) it + mapOf("expr" to annotated, "entryStrict" to contract) else it }
        return module + mapOf("bindings" to bindings, "instrument" to true)
    }

    @ParameterizedTest @ValueSource(strings = ["bytecode", "ast"])
    fun blockedDynamicPapDemandsUnusedStrictFormalInsideOriginalCatch(backend: String) {
        for (stage in listOf("pre", "post")) {
            val module = fixture(stage)
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc")
                lateinit var program: ExecutableProgram
                lateinit var state: Language.State
                lateinit var functions: Map<String, org.graalvm.polyglot.Value>
                context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    state = Language.currentState()
                    // Retain one graph for the dynamic PAP and all synchronizing
                    // entries; each call still crosses the public EntryValue ABI.
                    program = if (backend == "ast") Program(language, module, true)
                        else BytecodeProgram(language, module, true)
                    assertEquals(backend, program.diagnostics()["backend"])
                    assertEquals(true, (program.entryTarget("strictWorker").rootNode as GuestRoot).entryStrict[0])
                    functions = entries.filter { it in setOf("strictEntry", "takeReady", "takeRunning", "releaseGate", "forceShared", "prefixCount", "warmLoop") }
                        .associateWith { context.asValue(EntryValue(program, it, 1)) }
                } finally { context.leave() }
                fun call(name: String, input: Long = 0) = functions.getValue(name).execute(input).asLong()
                val shared = program.entryValue("shared") as Thunk
                assertEquals(1L, call("releaseGate"))
                assertEquals(9999007L, call("warmLoop", 9999000L))
                assertEquals(7L, call("takeReady"))
                assertEquals(7L, call("takeRunning"))
                assertEquals(0, shared.state)
                val result = CompletableFuture<Long>()
                val target = Thread({
                    try { result.complete(call("strictEntry")) }
                    catch (failure: Throwable) { result.completeExceptionally(failure) }
                }, "thc-$backend-$stage-strict-entry-target")
                target.isDaemon = true
                target.start()
                val ready = CompletableFuture<Long>()
                val waiter = Thread({
                    try { ready.complete(call("takeReady")) }
                    catch (failure: Throwable) { ready.completeExceptionally(failure) }
                }, "thc-$backend-$stage-strict-entry-ready")
                waiter.isDaemon = true
                waiter.start()
                try {
                    CompletableFuture.anyOf(result, ready).get(15, TimeUnit.SECONDS)
                    assertTrue(ready.isDone, "The dynamic worker returned before demanding its strict PAP prefix")
                    assertEquals(1007L, ready.get(1, TimeUnit.SECONDS))
                    assertFalse(result.isDone, "The PAP prefix must be demanded before the worker returns")
                    val request = state.threads.send(target.threadId(), program.entryValue("asyncPayload"))
                    assertEquals(-1L, result.get(15, TimeUnit.SECONDS), "The original catch# handles delivery")
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
                    assertEquals(5, shared.state)
                    assertEquals(1L, call("prefixCount"))
                    assertEquals(1L, call("releaseGate"))
                    assertEquals(10000008L, call("forceShared", 1))
                    assertEquals(2, shared.state)
                    assertEquals(1L, call("prefixCount"), "Resumption must not replay the effectful prefix")
                } finally {
                    target.join(5000)
                    if (target.isAlive || waiter.isAlive) context.close(true)
                }
            }
        }
    }
}
