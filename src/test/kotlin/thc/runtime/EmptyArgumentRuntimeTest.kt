// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias EmptyCore = List<Any?>

class EmptyArgumentRuntimeTest {
    private val integer = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val reference = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val empty = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true,
        "aggregate" to "unboxed-tuple", "components" to emptyList<Any>())
    private fun v(id: String, rep: Map<String, Any?> = integer): EmptyCore = listOf("var", id, mapOf("rep" to rep))
    private fun n(value: Long): EmptyCore = listOf("lit", "int", value.toString(), mapOf("rep" to integer))
    private fun zero(): EmptyCore = listOf("con", "E", 0, mapOf("rep" to empty))
    private fun arg(id: String, rep: Map<String, Any?> = integer) = mapOf("id" to id, "name" to id,
        "lifted" to (rep == closure || rep == reference), "rep" to rep)
    private fun app(fn: EmptyCore, args: List<EmptyCore>, rep: Map<String, Any?> = integer,
                    flags: List<Boolean> = List(args.size) { false }): EmptyCore =
        listOf("app", fn, args, flags, false, false, mapOf("rep" to rep))
    private fun call(id: String, args: List<EmptyCore>, rep: Map<String, Any?> = integer,
                     flags: List<Boolean> = List(args.size) { false }) = app(v(id, closure), args, rep, flags)
    private fun prim(id: String, vararg args: EmptyCore) = app(listOf("prim", id), args.toList())
    private fun lam(args: List<Map<String, Any?>>, body: EmptyCore, result: Map<String, Any?> = integer,
                    strict: List<Boolean> = List(args.size) { false }): EmptyCore =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result, "entryStrict" to strict))
    private fun bind(id: String, expr: EmptyCore) = mapOf("id" to id, "name" to id, "lifted" to true, "rep" to closure, "expr" to expr)
    private fun module(vararg bindings: Map<String, Any?>) = mapOf("bindings" to bindings.toList(), "instrument" to true,
        "constructors" to listOf(mapOf("id" to "E", "name" to "E", "kind" to "unboxed-tuple", "arity" to 0),
            mapOf("id" to "T", "name" to "T", "kind" to "unboxed-tuple", "arity" to 2)))
    private fun program(language: Language, backend: String, data: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, data) else BytecodeProgram(language, data)
    private fun run(p: ExecutableProgram, name: String, vararg args: Any?): Any? =
        Calls.target(p.hostEntryTarget(args.size), arrayOf(p.entryValue(name), args))
    private fun withLanguage(action: (Language) -> Unit) = executionContext().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
    }
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences()); assertNull(state.pending)
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }

    @Test fun emptyOnlyPapPrefixesAndOverapplicationKeepLogicalArityWithoutPayloadSlots() = withLanguage { language ->
        val parameters = listOf(arg("u", empty), arg("x"), arg("v", empty), arg("y"), arg("w", empty))
        val worker = bind("worker", lam(parameters, prim("+#", v("x"), v("y"))))
        val data = module(worker,
            bind("prefix", lam(listOf(arg("x")), call("worker", listOf(zero(), v("x")), closure), closure)),
            bind("zeroPrefix", lam(listOf(arg("ignored")), call("worker", listOf(zero()), closure), closure)),
            bind("exact", lam(listOf(arg("x")), call("worker", listOf(zero(), v("x"), zero(), n(17), zero())))),
            bind("pap", lam(listOf(arg("x")), app(call("worker", listOf(zero(), v("x")), closure), listOf(zero(), n(17), zero())))),
            bind("make", lam(listOf(arg("u", empty), arg("x")),
                lam(listOf(arg("v", empty), arg("y"), arg("w", empty)), prim("+#", v("x"), v("y"))), closure)),
            bind("over", lam(listOf(arg("x")), call("make", listOf(zero(), v("x"), zero(), n(17), zero())))))
        for (backend in listOf("ast", "bytecode")) {
            val p = program(language, backend, data)
            val prefix = run(p, "prefix", 31L) as Closure
            assertEquals(2, prefix.suppliedCount); assertEquals(3, prefix.arity); assertArrayEquals(arrayOf<Any?>(31L), prefix.supplied)
            val noPayload = run(p, "zeroPrefix", 0L) as Closure
            assertEquals(1, noPayload.suppliedCount); assertEquals(4, noPayload.arity); assertEquals(0, noPayload.supplied.size)
            val root = p.entryTarget("worker").rootNode as GuestRoot
            assertEquals(5, root.inputLayout!!.logicalArity); assertEquals(2, root.inputLayout!!.physicalArity)
            (root as? FunctionRoot)?.handoff?.let { assertEquals(listOf("long", "long", "long"), it.arguments.reps) }
            for (name in listOf("exact", "pap", "over")) {
                repeat(10) { assertEquals(it.toLong() + 17L, run(p, name, it.toLong())) }
                compile(p.entryTarget(name))
                for (x in listOf(Long.MIN_VALUE, -4097L, 0L, Long.MAX_VALUE)) {
                    val before = p.diagnostics()["compiledEntries"] as Long
                    assertEquals(x + 17L, run(p, name, x), "$backend/$name/$x")
                    assertTrue(p.diagnostics()["compiledEntries"] as Long > before); valid(p.entryTarget(name)); released(language)
                }
            }
        }
    }

    private class EffectRoot(language: Language, private val events: MutableList<Long>, private val empty: TupleShape?) :
        GuestRoot(language, FrameLayout().build()) {
        init { configureEntry(booleanArrayOf(false), false); configureTupleResult(empty) }
        override fun bloom(frame: VirtualFrame) = 0L
        override fun execute(frame: VirtualFrame): Any? {
            val value = frame.arguments[1] as Long
            events += if (empty == null) value + 100L else value
            if (value < 0) throw GuestException(value, this)
            return empty?.finish(frame, EMPTY_TUPLE_SLOTS) ?: value
        }
    }
    @Test fun emptyComputationsRunBeforeLaterOperandsAndPapPublicationAndReleaseResultsOnThrow() = withLanguage { language ->
        val data = module(bind("worker", lam(listOf(arg("u", empty), arg("x")), v("x"))),
            bind("entry", lam(listOf(arg("effect", closure), arg("later", closure), arg("x")),
                call("worker", listOf(call("effect", listOf(v("x")), empty), call("later", listOf(v("x"))))))),
            bind("pap", lam(listOf(arg("effect", closure), arg("x")),
                call("worker", listOf(call("effect", listOf(v("x")), empty)), closure), closure)))
        for (backend in listOf("ast", "bytecode")) {
            val p = program(language, backend, data); val events = arrayListOf<Long>()
            val effect = Closure(null, arity = 1, target = EffectRoot(language, events, TupleShape(CoreRepresentations.parse(empty), language)).callTarget)
            val later = Closure(null, arity = 1, target = EffectRoot(language, events, null).callTarget)
            assertEquals(7L, run(p, "entry", effect, later, 7L)); assertEquals(listOf(7L, 107L), events); released(language)
            events.clear(); val pap = run(p, "pap", effect, 11L) as Closure
            assertEquals(listOf(11L), events); assertEquals(1, pap.suppliedCount); assertEquals(0, pap.supplied.size); released(language)
            events.clear(); val allocations = p.diagnostics()["papAllocations"]
            assertThrows(GuestException::class.java) { run(p, "pap", effect, -3L) }
            assertEquals(listOf(-3L), events); assertEquals(allocations, p.diagnostics()["papAllocations"]); released(language)
            events.clear(); assertThrows(GuestException::class.java) { run(p, "entry", effect, later, -5L) }
            assertEquals(listOf(-5L), events); released(language)
            assertEquals(13L, run(p, "entry", effect, later, 13L)); released(language)
        }
    }

    @Test fun selfAndMutualTailCallsForwardUsedEmptyFormalsWithoutHostStackGrowth() = withLanguage { language ->
        fun worker(id: String, next: String) = bind(id, lam(listOf(arg("u", empty), arg("n")), listOf("case",
            prim("<=#", v("n"), n(0)), "condition", listOf(
                listOf("lit", listOf("int", "1"), emptyList<String>(), v("n")),
                listOf("default", null, emptyList<String>(), call(next, listOf(v("u", empty), prim("-#", v("n"), n(1)))))),
            mapOf("rep" to integer, "binder" to arg("condition")))))
        val data = module(worker("self", "self"), worker("a", "b"), worker("b", "a"),
            bind("entry", lam(listOf(arg("n")), prim("+#", call("self", listOf(zero(), v("n"))), call("a", listOf(zero(), v("n")))))))
        for (backend in listOf("ast", "bytecode")) {
            val p = program(language, backend, data)
            for (n in listOf(0L, 1L, 32L, 20_000L)) assertEquals(0L, run(p, "entry", n), backend)
            compile(p.entryTarget("entry")); assertEquals(0L, run(p, "entry", 20_000L)); valid(p.entryTarget("entry")); released(language)
            assertTrue(p.diagnostics()["selfTailReentries"] as Long > 0)
        }
    }

    @Test fun dynamicMasksRejectScalarStateAndBoxedUnitWithoutInventingEmptyProofs() = withLanguage { language ->
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        val data = module(bind("empty", lam(listOf(arg("u", empty)), n(3))),
            bind("state", lam(listOf(arg("s", state)), n(5))),
            bind("boxed", lam(listOf(arg("p", reference)), n(7))),
            bind("emptyCaller", lam(listOf(arg("f", closure)), call("f", listOf(zero())))),
            bind("stateCaller", lam(listOf(arg("f", closure)), call("f", listOf(listOf("void", mapOf("rep" to state)))))))
        for (backend in listOf("ast", "bytecode")) {
            val p = program(language, backend, data)
            assertEquals(3L, run(p, "emptyCaller", p.entryValue("empty")))
            assertEquals(5L, run(p, "stateCaller", p.entryValue("state")))
            for (target in listOf("state", "boxed")) assertThrows(RuntimeFault::class.java) { run(p, "emptyCaller", p.entryValue(target)) }
            assertThrows(RuntimeFault::class.java) { run(p, "stateCaller", p.entryValue("empty")) }; released(language)
        }
    }
    @Test fun mixedTargetPrefixesAndOverapplicationUsePhysicalSlicesAfterThePicBecomesGeneric() = withLanguage { language ->
        val bindings = arrayListOf<Map<String, Any?>>()
        for (i in 0..4) {
            val prefix = if (i % 2 == 0) listOf(arg("prefixEmpty", empty), arg("prefix")) else listOf(arg("prefix"))
            val prefixValues = if (i % 2 == 0) listOf(zero(), n(i * 100L)) else listOf(n(i * 100L))
            bindings += bind("worker$i", lam(prefix + listOf(arg("u", empty), arg("x")),
                lam(listOf(arg("v", empty), arg("y")), prim("+#", prim("+#", v("prefix"), v("x")), v("y"))), closure))
            bindings += bind("make$i", lam(listOf(arg("ignored")), call("worker$i", prefixValues, closure), closure))
        }
        bindings += bind("apply", lam(listOf(arg("f", closure), arg("x")),
            call("f", listOf(zero(), v("x"), zero(), n(17)))))
        for (backend in listOf("ast", "bytecode")) {
            val p = program(language, backend, module(*bindings.toTypedArray()))
            val functions = (0..4).map { run(p, "make$it", 0L) as Closure }
            repeat(10) { for ((i, f) in functions.withIndex()) assertEquals(i * 100L + 24L, run(p, "apply", f, 7L)) }
            compile(p.entryTarget("apply"))
            for (x in listOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)) for ((i, f) in functions.withIndex()) {
                val before = p.diagnostics()["compiledEntries"] as Long
                assertEquals(x + i * 100L + 17L, run(p, "apply", f, x), backend)
                assertTrue(p.diagnostics()["compiledEntries"] as Long > before)
                assertEquals(true, p.entryTarget("apply").javaClass.getMethod("isValidLastTier").invoke(p.entryTarget("apply")), "$backend/$x/target$i")
                released(language)
            }
            assertTrue(p.diagnostics()["indirectCalls"] as Long > 0)
        }
    }

    @Test fun rawLiftedEmptyFlagCannotBeHiddenByStrictDemandAndRecursiveAliasesKeepInputProofs() = withLanguage { language ->
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        for (backend in listOf("ast", "bytecode")) {
            val worker = bind("worker", lam(listOf(arg("e", empty)), n(3), strict = listOf(true)))
            val forged = module(worker, bind("entry", lam(listOf(arg("x")), call("worker", listOf(zero()), flags = listOf(true)))))
            assertThrows(RuntimeFault::class.java) { program(language, backend, forged) }
            val correct = module(worker, bind("entry", lam(listOf(arg("x")), call("worker", listOf(zero())))))
            assertEquals(3L, run(program(language, backend, correct), "entry", 0L))
            val missingProof = module(worker, bind("entry", lam(listOf(arg("x")), call("worker", listOf(listOf("lit", "int", "1"))))))
            assertThrows(RuntimeFault::class.java) { program(language, backend, missingProof) }
            val lexicalProof = module(worker, bind("forward", lam(listOf(arg("e", empty)), call("worker", listOf(listOf("var", "e"))))),
                bind("entry", lam(listOf(arg("x")), call("forward", listOf(zero())))))
            assertEquals(3L, run(program(language, backend, lexicalProof), "entry", 0L))
            fun aliases(rep: Map<String, Any?>) = module(bind("entry", lam(listOf(arg("x")),
                listOf("let", true, listOf(bind("alias", v("f", closure)),
                    bind("f", lam(listOf(arg("a", rep)), n(9)))),
                    call("alias", listOf(zero())), mapOf("rep" to integer)))))
            assertThrows(RuntimeFault::class.java) { program(language, backend, aliases(state)) }
            assertEquals(9L, run(program(language, backend, aliases(empty)), "entry", 0L))
        }
    }

    @Test fun exactEmptyInputsRemainDistinctFromStateContractsAndSupportedNestedZeroWidthTuples() = withLanguage { language ->
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        val nested = empty + ("components" to listOf(empty))
        val singletonState = empty + ("components" to listOf(state))
        for (backend in listOf("ast", "bytecode")) {
            for (supported in listOf(nested, singletonState)) {
                val worker = bind("worker", lam(listOf(arg("u", supported)), n(0)))
                val p = program(language, backend, module(worker))
                val layout = (p.entryTarget("worker").rootNode as GuestRoot).inputLayout!!
                assertEquals(1, layout.logicalArity); assertEquals(0, layout.physicalArity)
                assertFalse(layout.isEmpty(0)); assertTrue(layout.requiresTyped)
                // Equal zero payload width does not make these logical shapes (# #).
                assertThrows(RuntimeFault::class.java) {
                    program(language, backend, module(worker,
                        bind("entry", lam(emptyList(), call("worker", listOf(zero()))))))
                }
            }
            assertThrows(RuntimeFault::class.java) {
                program(language, backend, module(bind("worker",
                    lam(listOf(arg("u", empty + ("components" to null))), n(0)))))
            }
            // These are statically known contradictory formals, including a known PAP prefix.
            val stateWorker = bind("worker", lam(listOf(arg("x"), arg("s", state)), n(0)))
            for (fn in listOf(v("worker", closure), call("worker", listOf(n(3)), closure))) {
                val args = if (fn[0] == "var") listOf(n(3), zero()) else listOf(zero())
                assertThrows(RuntimeFault::class.java) {
                    program(language, backend, module(stateWorker, bind("entry", lam(emptyList(), app(fn, args)))))
                }
            }
            assertThrows(RuntimeFault::class.java) {
                program(language, backend, module(bind("entry", lam(emptyList(), prim("+#", zero(), n(1))))))
            }
            val boxed = v("payload", reference)
            val newResult = empty + mapOf("components" to listOf(state, mapOf("kind" to "object", "evaluated" to true,
                "primReps" to listOf("BoxedRep (Just Unlifted)"))), "primReps" to listOf("BoxedRep (Just Unlifted)"))
            val invalidNew = app(listOf("prim", "newMutVar#"), listOf(boxed, zero()), newResult, listOf(true, false))
            assertThrows(RuntimeFault::class.java) {
                program(language, backend, module(bind("entry", lam(listOf(arg("payload", reference)), invalidNew, newResult))))
            }
        }
    }

    @Test fun emptyInputAndLazyReferenceTupleResultUseSeparateLoansAndRecoverAfterThrow() = withLanguage { language ->
        val pair = empty + mapOf("components" to listOf(integer, reference),
            "primReps" to listOf("IntRep", "BoxedRep (Just Lifted)"))
        val producer = bind("producer", lam(listOf(arg("u", empty), arg("x"), arg("ref", reference)),
            app(listOf("con", "T", 2), listOf(v("x"), v("ref", reference)), pair, listOf(false, true)), pair))
        val result = call("producer", listOf(call("effect", listOf(v("x")), empty), v("x"), v("ref", reference)),
            pair, listOf(false, false, true))
        val body: EmptyCore = listOf("case", result, "tuple", listOf(listOf("data", "T", listOf("a", "b"), v("a"),
            mapOf("binders" to listOf(arg("a"), arg("b", reference))))), mapOf("rep" to integer, "binder" to arg("tuple", pair)))
        val data = module(producer, bind("entry", lam(listOf(arg("effect", closure), arg("x"), arg("ref", reference)), body)))
        for (backend in listOf("ast", "bytecode")) {
            val p = program(language, backend, data); val events = arrayListOf<Long>()
            val effect = Closure(null, arity = 1, target = EffectRoot(language, events, TupleShape(CoreRepresentations.parse(empty), language)).callTarget)
            val bottom = Thunk(EffectRoot(language, events, null).callTarget, null)
            assertEquals(17L, run(p, "entry", effect, 17L, bottom)); released(language)
            assertTrue(language.handoffState.get().results.allocations > 0)
            assertEquals(0, bottom.state, "Copying a lifted tuple leaf must not force it")
            assertThrows(GuestException::class.java) { run(p, "entry", effect, -7L, bottom) }; released(language)
            assertEquals(19L, run(p, "entry", effect, 19L, bottom)); released(language)
            assertEquals(0, bottom.state)
        }
    }

}
