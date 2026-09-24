// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Test-only Polyglot entry; the bodies and Box constructor remain genuine exported GHC Core. */
@ExportLibrary(InteropLibrary::class)
internal class ManagedMVarContextCall(
    private val program: ExecutableProgram,
    private val name: String,
    private val arguments: Array<Any?>,
) : TruffleObject {
    @ExportMessage fun isExecutable() = true
    @ExportMessage fun execute(hostArguments: Array<Any?>): Any? {
        check(hostArguments.isEmpty())
        return Calls.target(program.hostEntryTarget(arguments.size), arrayOf(program.entryValue(name), arguments))
    }
}

/** One entered guest executor, with host-only cell operations/cancellation on the test thread. */
@Timeout(90)
class ManagedMVarContextTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("waitTake", "waitRead", "waitPut", "makeBox")
    private val noWaiters = ManagedMVar.PendingCounts(0, 0, 0)
    private data class Oracle(val name: String, val input: Long, val result: Long)
    private data class Fixture(val modules: Map<String, Map<String, Any?>>, val oracle: List<Oracle>)
    private data class Published(val cell: ManagedMVar, val original: Any?, val offered: Any?)
    private data class Completed(val result: Long, val decodedPut: Long?)

    private fun fixture(): Fixture {
        val path = File(root, "build/managed-mvars/manifest.json")
        assertTrue(path.isFile, "Run scripts/prepare-managed-mvars.py for genuine GHC MVar fixtures")
        val manifest = Json.parse(path.readText()) as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names.toSet(), (manifest["contextEntryNames"] as List<String>).toSet())
        for (kind in listOf("inputHashes", "artifactHashes")) {
            for ((name, expected) in manifest[kind] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, name).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "Stale managed MVar fixture: $name")
            }
        }
        val rows = File(root, "build/managed-mvars/context-oracle.tsv").readLines().map {
            val fields = it.split('\t')
            assertEquals(3, fields.size)
            Oracle(fields[0].removePrefix("native").replaceFirstChar { c -> c.lowercase() },
                fields[1].toLong(), fields[2].toLong())
        }
        assertEquals((manifest["nativeContextRows"] as Number).toInt(), rows.size)
        assertEquals(names.dropLast(1).toSet(), rows.map { it.name }.toSet())
        assertEquals(rows.size, rows.map { it.name to it.input }.toSet().size)
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        val modules = stages.mapValues { (_, paths) ->
            val module = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
            // Merge root-specific closures, preserving constructor/source metadata and
            // deduplicating only shared identical bindings, never replacing their bodies.
            val seen = mutableSetOf<String>()
            CoreModules.merge(names.map { name ->
                val reached = CoreModules.reachable(module, name)
                reached + ("bindings" to (reached["bindings"] as List<Map<String, Any?>>)
                    .filter { seen.add(it["id"] as String) })
            }) + ("instrument" to true)
        }
        return Fixture(modules, rows)
    }

    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()

    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)

    private fun call(context: Context, program: ExecutableProgram, name: String, vararg arguments: Any?): Long =
        context.asValue(ManagedMVarContextCall(program, name, arrayOf(*arguments))).execute().asLong()

    private fun box(program: ExecutableProgram, value: Long): Any? =
        Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("makeBox"), arrayOf(value)))

    private fun released(language: Language, label: String) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth, "$label argument depth")
        assertEquals(0, handoff.results.depth, "$label result depth")
        assertEquals(0, handoff.arguments.retainedReferences(), "$label argument references")
        assertEquals(0, handoff.results.retainedReferences(), "$label result references")
        assertNull(handoff.pending, "$label pending transfer")
    }

    private fun <T> entered(context: Context, label: String, action: (Language) -> T): T {
        context.initialize("thc")
        context.enter()
        var language: Language? = null
        try {
            language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            return action(language)
        } finally {
            try {
                language?.let { released(it, label) }
            } finally {
                context.leave()
            }
        }
    }

    private fun withExecutor(action: (Context, ExecutorService) -> Unit) {
        val context = context()
        val executor = Executors.newSingleThreadExecutor()
        try {
            action(context, executor)
        } finally {
            try {
                context.close(true)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Guest executor did not terminate")
            }
        }
    }

    private fun variants(action: (Map<String, Any?>, String, String, Fixture) -> Unit) {
        val fixture = fixture()
        val old = System.getProperty(HANDOFF_PROPERTY)
        try {
            for (handoff in listOf(false, true)) {
                System.setProperty(HANDOFF_PROPERTY, handoff.toString())
                for ((stage, module) in fixture.modules) for (backend in listOf("ast", "bytecode"))
                    action(module, backend, "$stage/$backend/handoff=$handoff", fixture)
            }
        } finally {
            if (old == null) System.clearProperty(HANDOFF_PROPERTY) else System.setProperty(HANDOFF_PROPERTY, old)
        }
    }

    @Test fun readyOperationsMatchEveryNativeContextOracleRow() = variants { module, backend, label, fixture ->
        withExecutor { context, executor ->
            executor.submit {
                entered(context, label) { language ->
                    val program = program(language, module, backend)
                    for (row in fixture.oracle) {
                        val case = "$label/${row.name}/${row.input}"
                        val cell = ManagedMVar()
                        val value = box(program, row.input)
                        if (row.name != "waitPut") assertTrue(cell.tryPut(value))
                        val result = if (row.name == "waitPut") call(context, program, row.name, cell, row.input, Unit)
                            else call(context, program, row.name, cell, Unit)
                        assertEquals(row.result, result, case)
                        assertEquals(if (row.name == "waitPut") row.input + 17 else row.input, row.result, "$case model")
                        when (row.name) {
                            "waitTake" -> assertTrue(cell.isEmpty(), case)
                            "waitRead" -> assertSame(value, cell.tryRead().value, case)
                            "waitPut" -> assertEquals(row.input, call(context, program, "waitRead", cell, Unit), case)
                        }
                        assertEquals(noWaiters, cell.pendingCounts(), case)
                        released(language, case)
                    }
                }
            }.get(60, TimeUnit.SECONDS)
        }
    }

    private fun expectedWaiters(name: String) = when (name) {
        "waitTake" -> ManagedMVar.PendingCounts(1, 0, 0)
        "waitRead" -> ManagedMVar.PendingCounts(0, 1, 0)
        "waitPut" -> ManagedMVar.PendingCounts(0, 0, 1)
        else -> error(name)
    }

    private fun awaitPending(cell: ManagedMVar, name: String, future: Future<*>, label: String) {
        val expected = expectedWaiters(name)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (cell.pendingCounts() != expected) {
            if (future.isDone) {
                future.get(1, TimeUnit.SECONDS)
                fail<Unit>("$label returned without registering the blocked operation")
            }
            if (System.nanoTime() >= deadline) fail<Unit>("$label did not register $expected")
            Thread.yield()
        }
        assertFalse(future.isDone, "$label unexpectedly completed while still queued")
    }

    private fun start(context: Context, executor: ExecutorService, module: Map<String, Any?>, backend: String,
                      name: String, input: Long, label: String): Pair<Published, Future<Completed>> {
        val published = CompletableFuture<Published>()
        val future = executor.submit<Completed> {
            try {
                entered(context, label) { language ->
                    val program = program(language, module, backend)
                    val original = box(program, input xor Long.MIN_VALUE)
                    val offered = box(program, input)
                    val cell = ManagedMVar()
                    if (name == "waitPut") assertTrue(cell.tryPut(original))
                    published.complete(Published(cell, original, offered))
                    val result = if (name == "waitPut") call(context, program, name, cell, input, Unit)
                        else call(context, program, name, cell, Unit)
                    Completed(result, if (name == "waitPut") call(context, program, "waitRead", cell, Unit) else null)
                }
            } catch (failure: Throwable) {
                published.completeExceptionally(failure)
                throw failure
            }
        }
        val ready = published.get(10, TimeUnit.SECONDS)
        awaitPending(ready.cell, name, future, label)
        return ready to future
    }

    @Test fun blockedOperationsWakeFromExternalHostCellOperations() = variants { module, backend, variant, fixture ->
        for (name in names.dropLast(1)) withExecutor { context, executor ->
            val row = fixture.oracle.first { it.name == name && it.input == Long.MAX_VALUE }
            val label = "$variant/$name"
            val (ready, future) = start(context, executor, module, backend, name, row.input, label)
            if (name == "waitPut") {
                val old = ready.cell.tryTake()
                assertTrue(old.present, label)
                assertSame(ready.original, old.value, label)
            } else assertTrue(ready.cell.tryPut(ready.offered), label)
            val completed = future.get(10, TimeUnit.SECONDS)
            assertEquals(row.result, completed.result, label)
            when (name) {
                "waitTake" -> assertTrue(ready.cell.isEmpty(), label)
                "waitRead" -> assertSame(ready.offered, ready.cell.tryRead().value, label)
                "waitPut" -> assertEquals(row.input, completed.decodedPut, label)
            }
            assertEquals(noWaiters, ready.cell.pendingCounts(), label)
        }
    }

    @Test fun contextCloseCancelsPendingOperationsWithoutCommittingThem() = cancel(interrupt = false)

    @Test fun contextInterruptCancelsPendingOperationsWithoutCommittingThem() = cancel(interrupt = true)

    private fun cancel(interrupt: Boolean) = variants { module, backend, variant, _ ->
        for (name in names.dropLast(1)) withExecutor { context, executor ->
            val label = "$variant/$name/interrupt=$interrupt"
            val (ready, future) = start(context, executor, module, backend, name, 77L, label)
            if (interrupt) context.interrupt(Duration.ofSeconds(10)) else context.close(true)
            val failure = assertThrows(ExecutionException::class.java, { future.get(10, TimeUnit.SECONDS) }, label).cause
            val guestFailure = assertInstanceOf(PolyglotException::class.java, failure, label)
            assertTrue(if (interrupt) guestFailure.isInterrupted else guestFailure.isCancelled,
                "$label must unwind through the actual Polyglot cancellation boundary: $guestFailure")
            assertEquals(noWaiters, ready.cell.pendingCounts(), label)
            if (name == "waitPut") {
                val remaining = ready.cell.tryTake()
                assertTrue(remaining.present, label)
                assertSame(ready.original, remaining.value, "$label must preserve the original full cell")
                assertTrue(ready.cell.isEmpty(), "$label cancelled put must not publish after a later take")
            } else {
                assertTrue(ready.cell.isEmpty(), label)
                val probe = Any()
                assertTrue(ready.cell.tryPut(probe), label)
                assertSame(probe, ready.cell.tryRead().value, "$label must not leave a waiter to steal later input")
            }
            if (interrupt) {
                // Polyglot interrupt is not permanent context closure. This fresh,
                // independent call proves cleanup; it does not resume a cancelled thunk.
                val result = executor.submit<Long> {
                    entered(context, "$label/reuse") { language ->
                        val program = program(language, module, backend)
                        val cell = ManagedMVar()
                        assertTrue(cell.tryPut(box(program, -913L)))
                        call(context, program, "waitTake", cell, Unit)
                    }
                }.get(10, TimeUnit.SECONDS)
                assertEquals(-913L, result, "$label reusable context")
            }
        }
    }
}
