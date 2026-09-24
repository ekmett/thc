// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

/** The object boundary remains real; every transport assertion also runs interpreted. */
class HandoffTest {
    private val longRep = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val closureRep = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val proof = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
    private fun v(id: String): List<Any?> = listOf("var", id)
    private fun n(value: Long): List<Any?> = listOf("lit", "int", value.toString())
    private fun call(fn: String, vararg values: List<Any?>): List<Any?> = listOf("app", v(fn), values.toList(), List(values.size) { false })
    private fun prim(fn: String, vararg values: List<Any?>): List<Any?> = listOf("app", listOf("prim", fn), values.toList(), List(values.size) { false })
    private fun choose(test: List<Any?>, yes: List<Any?>, no: List<Any?>): List<Any?> = listOf("case", test, "test", listOf(
        listOf("lit", listOf("int", "1"), emptyList<String>(), yes), listOf("default", null, emptyList<String>(), no)))
    private fun binding(id: String, body: List<Any?>): Map<String, Any?> = mapOf("id" to id, "name" to id, "lifted" to true,
        "expr" to listOf("lam", listOf(mapOf("id" to "n", "name" to "n", "lifted" to false, "coercion" to false, "rep" to longRep)), body,
            mapOf("rep" to closureRep, "resultRep" to longRep, "entryStrict" to listOf(false))))
    private fun module(bindings: List<Map<String, Any?>>): Map<String, Any?> = mapOf("bindings" to bindings, "constructors" to emptyList<Any>(), "instrument" to true)
    private fun invoke(program: ExecutableProgram, value: Long): Any? = Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("entry"), arrayOf(value)))
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private fun withLanguage(inlining: Boolean = true, action: (Language) -> Unit) {
        val previous = System.getProperty(HANDOFF_PROPERTY)
        System.setProperty(HANDOFF_PROPERTY, "true")
        try {
            (if (inlining) executionContext() else Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                .option("compiler.CompilationTimeout", "30").build()).use { context ->
                context.initialize("thc"); context.enter()
                try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
                finally { context.leave() }
            }
        } finally {
            if (previous == null) System.clearProperty(HANDOFF_PROPERTY) else System.setProperty(HANDOFF_PROPERTY, previous)
        }
    }
    private fun assertReleased(state: HandoffState) {
        assertEquals(0, state.arguments.depth)
        assertNull(state.pending)
        assertEquals(0, state.arguments.retainedReferences())
    }

    @Test fun internedRepVectorsGenerateMutableDenseFieldsAndSeparateLifetimes() = withLanguage { language ->
        val reps = listOf("IntRep", "BoxedRep (Just Lifted)", "WordRep")
        val layout = language.handoffLayouts.intern(reps)
        assertSame(layout, language.handoffLayouts.intern(reps.toList()))
        assertSame(layout, language.handoffLayouts.intern(listOf("WordRep", "BoxedRep (Just Unlifted)", "Int8Rep")))
        val state = language.handoffState.get()
        val input = state.arguments.acquire(layout)
        val independentPool = HandoffPool()
        val output = independentPool.acquire(layout)
        assertNotSame(input, output)
        assertSame(input.javaClass, output.javaClass)
        val fields = input.javaClass.declaredFields.filter { it.name.startsWith("handoff_") }
        assertEquals(3, fields.size)
        assertEquals(2, fields.count { it.type == Long::class.javaPrimitiveType })
        assertEquals(1, fields.count { it.type == Any::class.java })
        assertTrue(input.javaClass.declaredFields.none { it.type.isArray })
        val objectValue = Any()
        layout.copyIn(input, arrayOf(4_000_000_000L, objectValue, Long.MIN_VALUE))
        state.arguments.release(input)
        assertEquals(0, state.arguments.depth)
        assertEquals(1, independentPool.depth)
        assertTrue(output.live)
        val again = state.arguments.acquire(layout)
        assertSame(input, again)
        assertNull(layout.getObject(again, 1))
        layout.copyIn(again, arrayOf(7L, Any(), 9L))
        assertEquals(7L, layout.getLong(again, 0))
        state.arguments.release(again)
        independentPool.release(output)
        assertReleased(state)
    }

    @Test fun nestedNonTailCallsKeepDistinctResultsAcrossCompilationAndReuse() = withLanguage { language ->
        val body = choose(prim("<=#", v("n"), n(0)), n(3_000_000_017L),
            prim("+#", call("worker", prim("-#", v("n"), n(1))), v("n")))
        val data = module(listOf(binding("worker", body), binding("entry", prim("+#", call("worker", v("n")), n(7)))))
        for (backend in listOf("ast", "bytecode")) {
            val program: ExecutableProgram = if (backend == "ast") Program(language, data) else BytecodeProgram(language, data)
            for (input in listOf(0L, 1L, 19L, 80L)) assertEquals(3_000_000_024L + input * (input + 1) / 2, invoke(program, input), backend)
            assertReleased(language.handoffState.get())
            compile(program.entryTarget("entry"))
            assertEquals(3_000_003_264L, invoke(program, 80L), backend)
            assertReleased(language.handoffState.get())
        }
        assertTrue(language.handoffState.get().calls > 0)
    }

    @Test fun interiorTailCyclesForwardDestinationAndReuseIncomingLoan() = withLanguage { language ->
        val condition = prim("<=#", v("n"), n(0))
        fun worker(id: String, next: String) = binding(id, choose(condition, n(3_000_000_017L), call(next, prim("-#", v("n"), n(1)))))
        val data = module(listOf(worker("a", "b"), worker("b", "c"), worker("c", "a"),
            binding("entry", prim("+#", call("a", v("n")), n(7)))))
        val program = Program(language, data)
        assertEquals(3_000_000_024L, invoke(program, 12_000L))
        val state = language.handoffState.get()
        assertTrue(state.tailTransfers > 0)
        assertReleased(state)
        val incomingAllocations = state.arguments.allocations
        compile(program.entryTarget("entry"))
        assertEquals(3_000_000_024L, invoke(program, 15_000L))
        assertReleased(state)
        assertEquals(incomingAllocations, state.arguments.allocations)
    }

    @Test fun forcedResidualCallsSupportMultipleAncestorCyclesAndPhysicalRepAliases() = withLanguage(false) { language ->
        fun terminal(next: List<Any?>) = choose(prim("<=#", v("n"), n(0)), n(3_000_000_017L), next)
        fun next(name: String) = call(name, prim("-#", v("n"), n(1)))
        val b = binding("b", terminal(next("c"))).toMutableMap()
        val bLambda = (b.getValue("expr") as List<*>).toMutableList()
        bLambda[3] = mapOf("rep" to closureRep, "resultRep" to (longRep + ("primReps" to listOf("WordRep"))), "entryStrict" to listOf(false))
        b["expr"] = bLambda
        val data = module(listOf(binding("a", terminal(next("b"))), b,
            binding("c", terminal(choose(prim("==#", prim("andI#", v("n"), n(1)), n(0)), next("b"), next("d")))),
            binding("d", terminal(choose(prim("==#", prim("andI#", v("n"), n(2)), n(0)), next("c"), next("a")))),
            binding("entry", prim("+#", call("a", v("n")), n(7)))))
        val program = Program(language, data)
        assertEquals(3_000_000_024L, invoke(program, 4_001L))
        for (id in listOf("a", "b", "c", "d", "entry")) compile(program.entryTarget(id))
        assertEquals(3_000_000_024L, invoke(program, 40_001L))
        assertTrue(language.handoffState.get().tailTransfers > 0)
        assertReleased(language.handoffState.get())
    }

    @Test fun interiorCReentryThenOutwardBReentryRemainInActiveRoots() = withLanguage { language ->
        fun next(name: String) = call(name, prim("-#", v("n"), n(1)))
        fun worker(id: String, body: List<Any?>) = binding(id, choose(prim("<=#", v("n"), n(0)), n(3_000_000_017L), body))
        val data = module(listOf(binding("a", call("b", v("n"))), binding("b", call("c", v("n"))),
            worker("c", next("d")), worker("d", next("e")),
            worker("e", choose(prim("<=#", v("n"), n(5)), next("b"), next("c"))),
            binding("entry", prim("+#", call("a", v("n")), n(7)))))
        val program = generateSequence { Program(language, data) }.take(64).first { candidate ->
            var ancestry = 0L
            listOf("a", "b", "c", "d", "e").all { id ->
                val mask = (candidate.entryTarget(id).rootNode as GuestRoot).mask
                val fresh = ancestry and mask != mask
                ancestry = ancestry or mask
                fresh
            }
        }
        fun check(input: Long) {
            val before = (program.diagnostics().getValue("selfTailReentries") as Number).toLong()
            val loops = (program.diagnostics().getValue("trampolineIterations") as Number).toLong()
            assertEquals(3_000_000_024L, invoke(program, input))
            val after = (program.diagnostics().getValue("selfTailReentries") as Number).toLong()
            assertTrue(after - before >= input / 3)
            assertEquals(loops, (program.diagnostics().getValue("trampolineIterations") as Number).toLong())
            assertReleased(language.handoffState.get())
        }
        check(12_000L)
        compile(program.entryTarget("entry"))
        check(15_000L)
    }

    @Test fun lazyReferencePrefixesAndCapturedEnvironmentsRemainOwnedAcrossResidualCalls() = withLanguage(false) { language ->
        fun parameter(id: String, rep: Map<String, Any?>) = mapOf("id" to id, "name" to id,
            "lifted" to (id == "ignored"), "coercion" to false, "rep" to rep)
        val inner = listOf("lam", listOf(parameter("ignored", closureRep + ("evaluated" to false)), parameter("n", longRep)),
            prim("+#", v("seed"), v("n")), mapOf("rep" to closureRep, "resultRep" to longRep, "entryStrict" to listOf(false, false)))
        val outer = listOf("lam", listOf(parameter("seed", longRep)), inner,
            mapOf("rep" to closureRep, "resultRep" to closureRep, "entryStrict" to listOf(false)))
        val program = Program(language, module(listOf(mapOf("id" to "factory", "name" to "factory", "lifted" to true, "expr" to outer))))
        val worker = Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("factory"), arrayOf(3_000_000_017L))) as Closure
        val bottom = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any? = throw RuntimeFault("unforced PAP prefix")
        }.callTarget, null)
        val pap = worker.pap(arrayOf(bottom))
        fun invoke(input: Long): Any? = Calls.target(program.hostEntryTarget(1), arrayOf(pap, arrayOf(input)))
        assertEquals(3_000_000_020L, invoke(3L))
        compile(worker.target)
        assertEquals(3_000_000_012L, invoke(-5L))
        assertSame(bottom, pap.supplied.single())
        assertEquals(0, bottom.state)
        assertTrue(language.handoffState.get().calls > 0)
        assertReleased(language.handoffState.get())
    }

    @Test fun lexicalForksKeepOuterFormalsAcrossResidualCallsAndCarrierReuse() = withLanguage(false) { language ->
        // The case binder shadows the formal only inside its alternative. Its
        // slots and the entry snapshot share allocation, never lexical names.
        val scoped = listOf("case", prim("+#", v("n"), n(10)), "n",
            listOf(listOf("default", null, emptyList<String>(), call("leaf", v("n")))))
        val data = module(listOf(binding("leaf", prim("+#", v("n"), n(100))),
            binding("worker", prim("+#", scoped, v("n"))),
            binding("entry", prim("-#", call("worker", v("n")), call("leaf", v("n"))))))
        val program = Program(language, data)
        fun check() {
            for (input in listOf(0L, -5L, 3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                assertEquals(input + 10L, invoke(program, input))
                assertReleased(language.handoffState.get())
            }
        }
        check()
        for (id in listOf("leaf", "worker", "entry")) compile(program.entryTarget(id))
        check()
        assertTrue(language.handoffState.get().calls > 0)
    }

    private class RememberFrame(private val argumentSlot: Int, private val saved: MutableList<MaterializedFrame>) : Expr() {
        var compiledBeforeDeopt = false
        init { representation = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep")) }
        override fun execute(frame: VirtualFrame): Any? = executeLong(frame)
        override fun executeLong(frame: VirtualFrame): Long {
            compiledBeforeDeopt = CompilerDirectives.inCompiledCode()
            saved.add(frame.materialize())
            val input = frame.getLong(argumentSlot)
            if (input == 777L) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
            }
            if (input == 999L) throw RuntimeFault("intentional handoff exit")
            return input + 1
        }
    }
    private class InvokeWorker(target: RootCallTarget) : GuestRoot(null, FrameLayout().build()) {
        @Child private var caller = DirectCallerNode(target, Metrics(false))
        override fun bloom(frame: VirtualFrame): Long = 0
        override fun execute(frame: VirtualFrame): Any? = caller.call(frame, frame.arguments, false)
    }

    @Test fun retainedMaterializedFramesAndExceptionalCleanupSurviveReuse() = withLanguage(false) { language ->
        val layout = FrameLayout()
        val argument = layout.bind("n")
        val entry = HandoffEntry.create(language, layout, listOf(proof), proof, false)!!
        val saved = ArrayList<MaterializedFrame>()
        val body = RememberFrame(argument, saved)
        val root = FunctionRoot(language, layout.build(), "retained slab frame", null, intArrayOf(), intArrayOf(argument), intArrayOf(0),
            body, Metrics(false), arrayOf(proof), proof, null, booleanArrayOf(false), entry)
        val caller = InvokeWorker(root.callTarget)
        assertEquals(3_000_000_018L, Calls.target(caller.callTarget, arrayOf(0L, 3_000_000_017L)))
        assertThrows(RuntimeFault::class.java) { Calls.target(caller.callTarget, arrayOf(0L, 999L)) }
        assertReleased(language.handoffState.get())
        assertEquals(11L, Calls.target(caller.callTarget, arrayOf(0L, 10L)))
        assertTrue(saved.all { it.arguments.isEmpty() })
        assertEquals(3_000_000_017L, saved[0].getLong(argument))
        assertEquals(3_000_000_017L, saved[0].getLong(entry.snapshotSlots[1]))
        assertEquals(999L, saved[1].getLong(entry.snapshotSlots[1]))
        compile(root.callTarget)
        assertEquals(778L, Calls.target(caller.callTarget, arrayOf(0L, 777L)))
        assertTrue(body.compiledBeforeDeopt, "The frame must be reconstructed from installed guest code")
        assertEquals(16L, Calls.target(caller.callTarget, arrayOf(0L, 15L)))
        assertEquals(777L, saved[3].getLong(entry.snapshotSlots[1]))
        assertEquals(3_000_000_017L, saved[0].getLong(entry.snapshotSlots[1]))
        assertReleased(language.handoffState.get())
    }
}
