// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeConfig
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Matched wide-carrier control: first blocking cut, after ordinary nonblocking
 * calls, must not retire installed code or replay the completed first take. */
class BytecodeColdHandlerTest {
    @Test fun handlerPolicyComesFromImmutableCaptureAuthorityAndSurvivesCloning() {
        Context.newBuilder("thc").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (async in listOf(false, true)) for (delimited in listOf(false, true)) {
                    val metrics = Metrics(true)
                    val root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                        b.beginRoot(); b.emitEnterRoot(metrics)
                        b.beginReturn(); b.emitLoadNull(); b.endReturn(); b.endRoot()
                    }.getNode(0)
                    root.configureAsync(async); root.configureDelimited(delimited)
                    root.callTarget
                    val clone = root.javaClass.getDeclaredMethod("cloneUninitialized")
                        .apply { isAccessible = true }.invoke(root) as BytecodeRoot
                    for (prepared in listOf(root, clone)) {
                        prepared.callTarget
                        assertEquals(async || delimited, prepared.requiresUnprofiledExceptionHandlers())
                        assertEquals(async, prepared.isAsyncEnabled())
                        assertEquals(delimited, prepared.isDelimitedEnabled())
                    }
                    assertEquals(0L, metrics.compiledEntries)
                }
            } finally { context.leave() }
        }
    }

    private val stateRep = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val mvarRep = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
    private val dataRep = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val longs = List(6) { mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true) }
    private val pair = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to listOf("BoxedRep (Just Lifted)"), "components" to listOf(stateRep, dataRep), "evaluated" to false)
    private val resultRep = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to List(6) { "IntRep" }, "components" to listOf(stateRep) + longs, "evaluated" to false)
    private val values = arrayOf<Any?>(-128L, 255L, -32768L, 65535L, -2147483648L, 4294967295L)
    private fun variable(name: String, rep: Map<String, Any?>) = listOf("var", name, mapOf("rep" to rep))
    private fun module(): Map<String, Any?> {
        fun afterTake(cell: String, suffix: Any): List<Any?> {
            val call = listOf("app", listOf("prim", "takeMVar#"),
                listOf(variable(cell, mvarRep), listOf("void", mapOf("rep" to stateRep))),
                listOf(false, false), false, false, mapOf("rep" to pair))
            return listOf("case", call, "${cell}Result", listOf(listOf("data", "Pair",
                listOf("${cell}State", "${cell}Value"), suffix, mapOf("binders" to listOf(
                    mapOf("id" to "${cell}State", "rep" to stateRep),
                    mapOf("id" to "${cell}Value", "rep" to dataRep))))),
                mapOf("rep" to resultRep, "binder" to mapOf("id" to "${cell}Result", "rep" to pair)))
        }
        val tuple = listOf("app", listOf("con", "Result", 7),
            listOf(listOf("void", mapOf("rep" to stateRep))) + longs.mapIndexed { index, rep -> variable("n$index", rep) },
            List(7) { false }, false, false, mapOf("rep" to resultRep))
        val formals = listOf("prefix" to mvarRep, "blocked" to mvarRep) + longs.mapIndexed { i, rep -> "n$i" to rep }
        val parameters = formals.map { (id, rep) -> mapOf("id" to id, "name" to id,
            "lifted" to false, "coercion" to false, "rep" to rep) }
        return mapOf("instrument" to true, "bindings" to listOf(mapOf("id" to "entry", "name" to "entry",
            "lifted" to true, "expr" to listOf("lam", parameters,
                afterTake("prefix", afterTake("blocked", tuple)), mapOf("resultRep" to resultRep)))),
            "constructors" to listOf(mapOf("id" to "Pair", "name" to "Pair", "kind" to "unboxed-tuple", "arity" to 2),
                mapOf("id" to "Result", "name" to "Result", "kind" to "unboxed-tuple", "arity" to 7)))
    }

    @Test fun firstCompiledBlockingCutRetainsTargetValuesAndCompletedEffects() {
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw")
            .build().use { context ->
                context.initialize("thc"); context.enter()
                lateinit var language: Language
                lateinit var owner: Language.State
                lateinit var program: ExecutableProgram
                lateinit var target: com.oracle.truffle.api.RootCallTarget
                lateinit var shape: TupleShape
                fun arguments(prefix: ManagedMVar, blocked: ManagedMVar) = arrayOf<Any?>(0L, prefix, blocked, *values)
                fun valid() = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                fun checkResult(result: Any?) {
                    val tuple = TupleResultsKt.ownedTupleResult(result, shape)
                    assertEquals(6, shape.width)
                    for (i in values.indices) {
                        assertTrue(shape.layout.isLong(i))
                        assertEquals(values[i], shape.layout.getLong(tuple, i))
                        assertEquals(listOf("IntRep"), shape.leaves[i].primReps)
                    }
                }
                try {
                    language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    owner = Language.currentState()
                    program = BytecodeProgram(language, module(), true)
                    target = program.entryTarget("entry")
                    assertTrue((target.rootNode as BytecodeRoot).requiresUnprofiledExceptionHandlers())
                    shape = checkNotNull((target.rootNode as GuestRoot).tupleResult)
                    repeat(5) {
                        val prefix = ManagedMVar().also { assertTrue(it.tryPut("prefix")) }
                        val blocked = ManagedMVar().also { assertTrue(it.tryPut("suffix")) }
                        checkResult(Calls.target(target, arguments(prefix, blocked)))
                        assertTrue(prefix.isEmpty()); assertTrue(blocked.isEmpty())
                    }
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid()
                    val runtime = Truffle.getRuntime()
                    runtime.javaClass.getMethod("bypassedInstalledCode",
                        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
                    valid()
                } finally { context.leave() }

                val prefix = ManagedMVar().also { assertTrue(it.tryPut("once")) }
                val blocked = ManagedMVar()
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                val answer = CompletableFuture<SavedGuestContinuation>()
                val worker = Thread {
                    context.enter(); owner.threads.enterCurrent()
                    try {
                        val captured = checkNotNull(SavedGuestContinuationKt.savedGuestContinuation(Calls.target(target, arguments(prefix, blocked))))
                        checkNotNull(captured.asyncRequest()).acknowledge()
                        answer.complete(captured)
                    } catch (failure: Throwable) { answer.completeExceptionally(failure) }
                    finally { owner.threads.leaveCurrent(); context.leave() }
                }
                worker.start()
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (blocked.pendingCounts().takers != 1 && !answer.isDone && System.nanoTime() < deadline) Thread.sleep(1)
                    if (answer.isCompletedExceptionally) answer.get(1, TimeUnit.SECONDS)
                    assertEquals(1, blocked.pendingCounts().takers)
                    assertTrue(prefix.isEmpty(), "The first effect completed before the blocked cut")
                    owner.threads.send(owner.threads.pollState(worker).current!!.identity, "wide cut")
                    val captured = answer.get(10, TimeUnit.SECONDS)
                    worker.join(5000); assertFalse(worker.isAlive)
                    assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    val retainedAfterCapture = target.javaClass.getMethod("isValidLastTier").invoke(target)
                    val completed = CompletableFuture<Unit>()
                    val resumer = Thread {
                        context.enter()
                        try {
                            assertTrue(blocked.tryPut("continue"))
                            checkResult(captured.continueWith(Unit))
                            assertTrue(prefix.isEmpty(), "Resumption must not replay the completed first take")
                            assertTrue(blocked.isEmpty())
                            assertSame(target, program.entryTarget("entry"))
                            val handoff = language.handoffState.get()
                            assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.arguments.retainedReferences())
                            assertEquals(0, handoff.results.depth); assertEquals(0, handoff.results.retainedReferences())
                            assertNull(handoff.pending)
                            val bytecode = (target.rootNode as BytecodeRoot).bytecodeNode
                            val profiles = bytecode.javaClass.getDeclaredField("exceptionProfiles_")
                                .apply { isAccessible = true }.get(bytecode) as BooleanArray
                            assertTrue(profiles.none { it }, "Capture must not manufacture observed exception profiles")
                            assertEquals(true, retainedAfterCapture, "The first blocking cut retains installed code")
                            valid()
                            completed.complete(Unit)
                        } catch (failure: Throwable) { completed.completeExceptionally(failure) }
                        finally { context.leave() }
                    }
                    resumer.start()
                    try { completed.get(10, TimeUnit.SECONDS) }
                    finally {
                        if (!completed.isDone) context.close(true)
                        resumer.join(5000)
                    }
                    assertFalse(resumer.isAlive)
                } finally {
                    if (worker.isAlive) context.close(true)
                    worker.join(5000)
                }
            }
    }
}
