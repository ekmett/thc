// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import thc.Language
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** All six primitive Int carriers survive a real compiled blocking cut. The
 * completed first take is deliberately empty when the saved suffix resumes. */
class NarrowIntegerContinuationTest {
    @Test fun onlyCapturingRootsDeclareMaterializableFramesBeforePublicationAndCloning() {
        val declaration = FunctionRoot::class.java.getDeclaredMethod("requiresMaterializableFrame")
        assertTrue(declaration.trySetAccessible())
        for ((async, delimited) in listOf(false to false, true to false, false to true)) {
            val layout = FrameLayout()
            val slot = layout.bind("narrow")
            val proof = CoreRepresentation(CoreKind.LONG, evaluated = true, present = true,
                primReps = listOf("Int32Rep"))
            val body = object : Expr() {
                override fun execute(frame: VirtualFrame): Any? = error("Policy preparation must not execute guest code")
            }
            val root = FunctionRoot(null, layout.build(), "capture policy", null,
                intArrayOf(), intArrayOf(slot), intArrayOf(0), body,
                Metrics(true), arrayOf(proof), body.representation, body.coreSourceLocation,
                booleanArrayOf(), null, null, intArrayOf(),
                null, async, emptyArray(), delimited,
                FunctionRootRole.FUNCTION, false)
            assertEquals(async || delimited, declaration.invoke(root))
            assertEquals(FrameSlotKind.Int, root.frameDescriptor.getSlotKind(slot))
            assertSame(root, root.callTarget.rootNode)
            assertEquals(delimited, root.delimitedControlEnabled)
            val clone = NodeUtil.cloneNode(root)
            assertNotSame(root, clone)
            assertEquals(async || delimited, declaration.invoke(clone))
            assertSame(clone, clone.callTarget.rootNode)
            assertEquals(delimited, clone.delimitedControlEnabled)
            assertEquals(FrameSlotKind.Int, clone.frameDescriptor.getSlotKind(slot))
        }
    }

    private val stateRep = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val mvarRep = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
    private val dataRep = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val ints = NarrowInteger.values().map { mapOf("kind" to "long", "primReps" to listOf(it.rep), "evaluated" to true) }
    private val pair = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to listOf("BoxedRep (Just Lifted)"), "components" to listOf(stateRep, dataRep), "evaluated" to false)
    private val resultRep = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to NarrowInteger.values().map { it.rep }, "components" to listOf(stateRep) + ints, "evaluated" to false)
    private val values = arrayOf<Any?>(-128, 255, -32768, 65535, Int.MIN_VALUE, -1)
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
            listOf(listOf("void", mapOf("rep" to stateRep))) + ints.mapIndexed { index, rep -> variable("n$index", rep) },
            List(7) { false }, false, false, mapOf("rep" to resultRep))
        val formals = listOf("prefix" to mvarRep, "blocked" to mvarRep) + ints.mapIndexed { i, rep -> "n$i" to rep }
        val parameters = formals.map { (id, rep) -> mapOf("id" to id, "name" to id,
            "lifted" to false, "coercion" to false, "rep" to rep) }
        return mapOf("instrument" to true, "bindings" to listOf(mapOf("id" to "entry", "name" to "entry",
            "lifted" to true, "expr" to listOf("lam", parameters,
                afterTake("prefix", afterTake("blocked", tuple)), mapOf("resultRep" to resultRep)))),
            "constructors" to listOf(mapOf("id" to "Pair", "name" to "Pair", "kind" to "unboxed-tuple", "arity" to 2),
                mapOf("id" to "Result", "name" to "Result", "kind" to "unboxed-tuple", "arity" to 7)))
    }

    @ParameterizedTest @ValueSource(strings = ["ast", "bytecode", "ast-long-control", "bytecode-long-control"])
    fun firstCompiledCutPreservesAllNarrowFieldsAndDoesNotReplayCompletedTake(mode: String) {
        val backend = mode.substringBefore('-')
        val narrow = !mode.endsWith("long-control")
        val actualValues = if (narrow) values else Array<Any?>(values.size) { index ->
            NarrowInteger.values()[index].widen(values[index] as Int)
        }
        fun wideControl(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.mapValues { wideControl(it.value) }
            is List<*> -> value.map(::wideControl)
            is String -> if (NarrowInteger.values().any { it.rep == value }) "IntRep" else value
            else -> value
        }
        @Suppress("UNCHECKED_CAST")
        val input = if (narrow) module() else wideControl(module()) as Map<String, Any?>
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
                fun arguments(prefix: ManagedMVar, blocked: ManagedMVar) = arrayOf<Any?>(0L, prefix, blocked, *actualValues)
                fun valid() = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                fun checkResult(result: Any?) {
                    val tuple = TupleResultsKt.ownedTupleResult(result, shape)
                    assertEquals(6, shape.width)
                    for (i in actualValues.indices) {
                        assertEquals(narrow, shape.layout.isInt(i)); assertEquals(!narrow, shape.layout.isLong(i))
                        assertEquals(actualValues[i], if (narrow) shape.layout.getInt(tuple, i) else shape.layout.getLong(tuple, i))
                        assertEquals(listOf(if (narrow) NarrowInteger.values()[i].rep else "IntRep"), shape.leaves[i].primReps)
                    }
                }
                try {
                    language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    owner = Language.currentState()
                    program = if (backend == "ast") Program(language, input, true)
                        else BytecodeProgram(language, input, true)
                    target = program.entryTarget("entry")
                    shape = checkNotNull((target.rootNode as GuestRoot).tupleResult)
                    repeat(5) {
                        val prefix = ManagedMVar().also { assertTrue(it.tryPut("prefix")) }
                        val blocked = ManagedMVar().also { assertTrue(it.tryPut("suffix")) }
                        checkResult(callScalarTestTarget(target, arguments(prefix, blocked)))
                        assertTrue(prefix.isEmpty()); assertTrue(blocked.isEmpty())
                    }
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid()
                    // Restore a retired host call stub without entering guest code.
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
                        val captured = checkNotNull(SavedGuestContinuationKt.savedGuestContinuation(callScalarTestTarget(target, arguments(prefix, blocked))))
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
                    owner.threads.send(owner.threads.pollState(worker).current!!.identity, "narrow cut")
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
                            if (backend == "ast") assertThrows(RuntimeFault::class.java) { captured.continueWith(Unit) }
                            val handoff = language.handoffState.get()
                            assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.arguments.retainedReferences())
                            assertEquals(0, handoff.results.depth); assertEquals(0, handoff.results.retainedReferences())
                            assertNull(handoff.pending)
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
