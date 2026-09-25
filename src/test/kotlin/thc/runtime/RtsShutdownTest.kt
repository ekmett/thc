// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleContext
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RtsShutdownTest {
    private val state = scalar("void", emptyList())
    private val cint = scalar("long", listOf("Int32Rep"))
    private val integer = scalar("long", listOf("IntRep"))
    private val closure = scalar("closure", listOf("BoxedRep (Just Lifted)"))
    private val boxed = closure + ("kind" to "data")
    private val done = tuple(listOf(state))
    private val io = tuple(listOf(state, boxed))
    private fun scalar(kind: String, reps: List<String>) = mapOf("kind" to kind, "primReps" to reps, "evaluated" to true)
    private fun tuple(fields: List<Map<String, Any?>>) = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to fields.flatMap { it["primReps"] as List<String> }, "components" to fields, "evaluated" to true)
    private fun variable(id: String, rep: Map<String, Any?>) = listOf("var", id, mapOf("rep" to rep))
    private fun binder(id: String, rep: Map<String, Any?>, lifted: Boolean = false) = mapOf("id" to id, "name" to id, "lifted" to lifted, "rep" to rep)
    private fun lambda(args: List<Map<String, Any?>>, body: Any, result: Map<String, Any?>) =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result))
    private fun descriptor(op: RtsShutdownOp) = mapOf("schema" to 1L,
        "target" to mapOf("kind" to "static", "symbol" to op.symbol, "unit" to "ghc-internal", "isFunction" to true),
        "convention" to "ccall", "safety" to "safe", "arity" to 3L, "suppliedArity" to 3L,
        "argumentReps" to listOf(cint, cint, state).map { it + ("evaluated" to false) }, "resultRep" to (done + ("evaluated" to false)))
    private fun call(op: RtsShutdownOp, proof: Map<String, Any?> = descriptor(op)): List<Any?> = listOf("app",
        variable("original-shutdown", closure), listOf(variable("code", cint), variable("fast", cint), listOf("void", mapOf("rep" to state))),
        listOf(false, false, false), false, false, mapOf("rep" to done, "foreignCall" to proof))
    private fun caseOf(scrutinee: Any, rep: Map<String, Any?>, body: Any, result: Map<String, Any?>) =
        listOf("case", scrutinee, "ignored", listOf(listOf("default", null, emptyList<String>(), body)),
            mapOf("rep" to result, "binder" to binder("ignored", rep)))
    /** Synthetic ABI consumer. catch# surrounds the real shutdown adapter so a
     * hard exit cannot accidentally become a catchable Haskell exception. */
    private fun module(op: RtsShutdownOp, proof: Map<String, Any?> = descriptor(op)): Map<String, Any?> {
        val unit = listOf("con", "Unit", 0, mapOf("rep" to boxed))
        val pair = listOf("app", listOf("con", "Pair", 2), listOf(listOf("void", mapOf("rep" to state)), unit),
            listOf(false, true), false, false, mapOf("rep" to io))
        val action = lambda(listOf(binder("s", state)), caseOf(call(op, proof), done, pair, io), io)
        val handler = lambda(listOf(binder("e", boxed, true), binder("t", state)), pair, io)
        val caught = listOf("app", listOf("prim", "catch#"), listOf(action, handler, listOf("void", mapOf("rep" to state))),
            listOf(true, true, false), false, false, mapOf("rep" to io))
        val body = caseOf(caught, io, listOf("lit", "int", "99", mapOf("rep" to integer)), integer)
        return mapOf("instrument" to true, "constructors" to listOf(
            mapOf("id" to "Unit", "name" to "()", "arity" to 0, "tag" to 1),
            mapOf("id" to "Pair", "name" to "(#,#)", "arity" to 2, "tag" to 1, "kind" to "unboxed-tuple")),
            "bindings" to listOf(binder("entry", closure, true) + mapOf("arity" to 2,
                "expr" to lambda(listOf(binder("code", cint), binder("fast", cint)), body, integer))))
    }
    private fun context(native: Boolean = false) = Context.newBuilder("thc").useSystemExit(false).allowNativeAccess(native)
        .allowExperimentalOptions(true).option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun cases(): List<Map<String, Any?>> {
        val root = File(System.getProperty("thc.projectRoot")); val prefix = "build/rts-shutdown"
        val manifest = Json.parse(File(root, "$prefix/manifest.json").readText()) as Map<String, Any?>
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/RtsShutdownNative.hs", "test/haskell-fixtures/RtsShutdownFixtures.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json"), "$prefix/")
        return (Json.parse(File(root, "$prefix/oracle.json").readText()) as Map<String, Any?>)["cases"] as List<Map<String, Any?>>
    }
    @Test fun nativeStatusContractAndFirstInstalledExitCloseOnlyTheGuestContext() {
        val rows = cases(); assertEquals(14, rows.size)
        for (backend in listOf("ast", "bytecode")) for (compiled in listOf(false, true)) for (row in rows) {
            val op = if (row["kind"] == "exit") RtsShutdownOp.EXIT else RtsShutdownOp.SIGNAL
            if (compiled && row["code"] !in listOf(37L, 15L)) continue
            val code = row["code"] as Long; val fast = row["fast"] as Long
            val status = if (op == RtsShutdownOp.EXIT) (row["exit"] as Long).toInt() else -code.toInt()
            val context = context()
            lateinit var program: ExecutableProgram; lateinit var owner: Language.State; lateinit var value: Value
            lateinit var handle: TruffleContext
            var before = 0L
            entered(context) { language ->
                owner = Language.currentState(); handle = owner.env.context
                program = program(language, backend, module(op))
                value = context.asValue(EntryValue(program, "entry", 2))
                // Warm exactly the shutdown call without terminating the context.
                // Out-of-CInt carriers reject before any exit request is installed.
                assertFalse(assertThrows(PolyglotException::class.java) { value.execute(Long.MAX_VALUE, 0L) }.isExit)
                assertNull(owner.shutdown.get())
                if (compiled) {
                    assertTrue(value.invokeMember("compile").asBoolean())
                    before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                }
            }
            val failure = assertThrows(PolyglotException::class.java) { context.use { value.execute(code, fast) } }
            assertTrue(failure.isExit, "$backend/$row: $failure"); assertEquals(status, failure.exitStatus)
            assertEquals(GuestShutdown(status, fast != 0L), owner.shutdown.get())
            if (compiled) assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
            assertTrue(handle.isClosed)
            // A fresh context on the same JVM still works after each hard exit.
            context().use { fresh -> entered(fresh) { assertNull(Language.currentState().shutdown.get()) } }
        }
    }
    @Test fun malformedProofsAndCarriersRejectWithoutClosingTheContext() {
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            for (op in RtsShutdownOp.entries) {
                for ((key, bad) in listOf("safety" to "unsafe", "convention" to "capi", "arity" to 2L, "resultRep" to state))
                    assertThrows(RuntimeFault::class.java) { program(language, backend, module(op, descriptor(op) + (key to bad))) }
                val value = context.asValue(EntryValue(program(language, backend, module(op)), "entry", 2))
                for (args in listOf(arrayOf(Long.MAX_VALUE, 0L), arrayOf(0L, Long.MIN_VALUE))) {
                    val failure = assertThrows(PolyglotException::class.java) { value.execute(*args) }
                    assertFalse(failure.isExit); assertTrue(failure.message.orEmpty().contains("signed CInt"))
                    assertNull(Language.currentState().shutdown.get())
                }
            }
        } }
    }
    @Test fun invalidSignedSignalNumbersExitTheContextWithFallbackStatus() {
        for (backend in listOf("ast", "bytecode")) for (code in listOf(-1L, 0L, 65L)) {
            val context = context()
            val value = entered(context) { language ->
                context.asValue(EntryValue(program(language, backend, module(RtsShutdownOp.SIGNAL)), "entry", 2))
            }
            val failure = assertThrows(PolyglotException::class.java) { context.use { value.execute(code, 0L) } }
            assertTrue(failure.isExit); assertEquals(255, failure.exitStatus)
        }
    }
    @Test fun hardExitDisposesNativeHandlesAndStableRootsInBothModes() {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        for (fast in listOf(0L, 1L)) {
            val context = context(true); lateinit var owner: Language.State; lateinit var root: ManagedAddress; lateinit var value: Value
            entered(context) { language ->
                owner = Language.currentState()
                val utf8 = ManagedAddress.fromByteArray("UTF-8\u0000".toByteArray())
                assertTrue(owner.iconv.open(utf8, utf8) > 0)
                owner.nativeAllocations.malloc(8); root = owner.stablePointers.make(Any())
                value = context.asValue(EntryValue(program(language, "bytecode", module(RtsShutdownOp.EXIT)), "entry", 2))
            }
            assertTrue(assertThrows(PolyglotException::class.java) { context.use { value.execute(37L, fast) } }.isExit)
            assertEquals(0, owner.iconv.liveHandles()); assertEquals(0, owner.nativeAllocations.liveCount())
            assertThrows(RuntimeFault::class.java) { owner.stablePointers.dereference(root) }
        }
    }
    @Test fun completedWakeRaceNeverSwallowsThreadDeathInSendOrResume() {
        for (resume in listOf(false, true)) {
            val ready = CountDownLatch(1); val finish = CountDownLatch(1); val death = ThreadDeath()
            var armed = !resume
            val threads = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { target ->
                if (armed) { finish.countDown(); target.join(5000); assertFalse(target.isAlive); throw death }
            }
            val worker = Thread {
                threads.registerCurrent(); ready.countDown()
                try { check(finish.await(5, TimeUnit.SECONDS)) } finally { threads.completeCurrent() }
            }
            worker.start(); assertTrue(ready.await(5, TimeUnit.SECONDS))
            try {
                val request = if (resume) threads.send(worker.threadId(), "payload").also { assertTrue(threads.pause(it)) } else null
                armed = true
                val thrown = assertThrows(ThreadDeath::class.java) {
                    if (request == null) threads.send(worker.threadId(), "payload") else threads.resume(request)
                }
                assertSame(death, thrown)
            } finally { finish.countDown(); worker.join(5000); threads.close() }
        }
    }
}
