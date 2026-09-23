@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.EntryValue
import thc.Language

private typealias InputCore = List<Any?>

/** Backend-local controls; genuine exported native coverage lives in the shared fixture. */
class BytecodeTypedTupleInputTest {
    private val integer = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val floating = mapOf("kind" to "float", "primReps" to listOf("FloatRep"), "evaluated" to true)
    private val double = mapOf("kind" to "double", "primReps" to listOf("DoubleRep"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val reference = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private fun tuple(vararg fields: Map<String, Any?>): Map<String, Any?> = mapOf("kind" to "unknown",
        "primReps" to fields.flatMap { it["primReps"] as List<String> }, "evaluated" to true,
        "aggregate" to "unboxed-tuple", "components" to fields.toList())
    private val pair = tuple(integer, integer)
    private fun v(id: String, rep: Map<String, Any?> = integer): InputCore = listOf("var", id, mapOf("rep" to rep))
    private fun n(x: Long): InputCore = listOf("lit", "int", x.toString(), mapOf("rep" to integer))
    private fun arg(id: String, rep: Map<String, Any?> = integer) = mapOf("id" to id, "name" to id,
        "lifted" to (rep == closure || rep == reference), "rep" to rep)
    private fun app(fn: InputCore, args: List<InputCore>, rep: Map<String, Any?> = integer,
                    flags: List<Boolean> = List(args.size) { false }): InputCore =
        listOf("app", fn, args, flags, false, false, mapOf("rep" to rep))
    private fun call(id: String, args: List<InputCore>, rep: Map<String, Any?> = integer) = app(v(id, closure), args, rep)
    private fun prim(id: String, vararg args: InputCore) = app(listOf("prim", id), args.toList())
    private fun pack(rep: Map<String, Any?>, vararg fields: InputCore): InputCore =
        if (fields.isEmpty()) listOf("con", "T0", 0, mapOf("rep" to rep))
        else app(listOf("con", "T${fields.size}", fields.size), fields.toList(), rep,
            (rep["components"] as List<Map<String, Any?>>).map { it == closure || it == reference })
    private fun unpack(value: InputCore, rep: Map<String, Any?>, ids: List<String>, body: InputCore,
                       result: Map<String, Any?> = integer): InputCore = listOf("case", value, "whole", listOf(
        listOf("data", "T${ids.size}", ids, body,
            mapOf("binders" to ids.zip(rep["components"] as List<Map<String, Any?>>).map { arg(it.first, it.second) }))),
        mapOf("rep" to result, "binder" to arg("whole", rep)))
    private fun lam(args: List<Map<String, Any?>>, body: InputCore, result: Map<String, Any?> = integer): InputCore =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result, "entryStrict" to List(args.size) { false }))
    private fun bind(id: String, expr: InputCore) = mapOf("id" to id, "name" to id, "lifted" to true, "rep" to closure, "expr" to expr)
    private fun module(vararg bindings: Map<String, Any?>) = mapOf("bindings" to bindings.toList(), "instrument" to true,
        "constructors" to (0..4).map { mapOf("id" to "T$it", "name" to "T$it", "kind" to "unboxed-tuple", "arity" to it) })
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun withLanguage(inlining: Boolean = true, action: (Context, Language) -> Unit) = context(inlining).use { context ->
        context.initialize("thc"); context.enter()
        try { action(context, TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
    }
    private fun run(p: ExecutableProgram, name: String, vararg args: Any?): Any? =
        Calls.target(p.hostEntryTarget(args.size), arrayOf(p.entryValue(name), args))
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), target.toString())
    private fun released(language: Language) {
        val s = language.handoffState.get()
        assertNull(s.pending); assertEquals(0, s.arguments.depth); assertEquals(0, s.arguments.retainedReferences())
        assertEquals(0, s.results.depth); assertEquals(0, s.results.retainedReferences())
    }
    private fun checkCompiled(context: Context, language: Language, p: ExecutableProgram, name: String, expected: (Long) -> Long) {
        val values = listOf(Long.MIN_VALUE, -17L, 0L, 1L, Long.MAX_VALUE)
        val fn = context.asValue(EntryValue(p, name, 1))
        for (x in values) assertEquals(expected(x), fn.execute(x).asLong(), "$name/$x interpreted")
        assertTrue(fn.invokeMember("compile").asBoolean())
        val original = p.entryTarget(name); val host = p.hostEntryTarget(1)
        fun active() = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
            .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }.toSet()
        val targets = active()
        for (x in values.asReversed()) {
            val before = p.diagnostics()["compiledEntries"] as Long
            assertEquals(expected(x), fn.execute(x).asLong(), "$name/$x compiled")
            assertTrue(p.diagnostics()["compiledEntries"] as Long > before)
            valid(original); valid(host); assertEquals(targets, active()); targets.forEach(::valid); released(language)
        }
    }

    @Test fun typedPairPapScalarSuffixCapturedOverapplicationAndResultLoans() {
        for (inlining in listOf(true, false)) withLanguage(inlining) { context, language ->
            val data = module(
                bind("worker", lam(listOf(arg("p", pair), arg("z")), unpack(v("p", pair), pair, listOf("a", "b"),
                    prim("+#", prim("+#", v("a"), v("b")), v("z"))))),
                bind("identity", lam(listOf(arg("p", pair)), v("p", pair), pair)),
                bind("make", lam(listOf(arg("p", pair)), unpack(v("p", pair), pair, listOf("a", "b"),
                    lam(listOf(arg("z")), prim("+#", prim("+#", v("a"), v("b")), v("z"))), closure), closure)),
                bind("makeTyped", lam(listOf(arg("captured")),
                    lam(listOf(arg("p", pair)), unpack(v("p", pair), pair, listOf("a", "b"),
                        prim("+#", v("captured"), prim("+#", v("a"), v("b"))))), closure)),
                bind("captured", lam(listOf(arg("x")), call("makeTyped", listOf(n(13), pack(pair, v("x"), n(7)))))),
                bind("exact", lam(listOf(arg("x")), call("worker", listOf(pack(pair, v("x"), n(7)), n(13))))),
                bind("pap", lam(listOf(arg("x")), app(call("worker", listOf(pack(pair, v("x"), n(7))), closure), listOf(n(13))))),
                bind("prefix", lam(listOf(arg("x")), call("worker", listOf(pack(pair, v("x"), n(7))), closure), closure)),
                bind("over", lam(listOf(arg("x")), call("make", listOf(pack(pair, v("x"), n(7)), n(13))))),
                bind("roundTrip", lam(listOf(arg("x")), call("worker", listOf(
                    call("identity", listOf(pack(pair, v("x"), n(7))), pair), n(13))))))
            val p = BytecodeProgram(language, data)
            val pap = run(p, "prefix", 9L) as Closure
            assertEquals(1, pap.suppliedCount); assertEquals(1, pap.arity); assertEquals(0, pap.supplied.size)
            assertNotNull(pap.typedSupplied); assertEquals(2, pap.typedSupplied!!.layout.reps.size)
            val root = p.entryTarget("worker").rootNode as BytecodeRoot
            assertEquals(2, root.inputLayout!!.logicalArity); assertEquals(3, root.inputLayout!!.physicalArity)
            assertNotNull(root.typedInput)
            for (name in listOf("exact", "pap", "over", "roundTrip", "captured")) checkCompiled(context, language, p, name) { it + 20L }
            assertThrows(RuntimeFault::class.java) { Calls.target(p.entryTarget("worker"), arrayOf(0L, 9L, 7L, 13L)) }
            released(language)
        }
    }

    @Test fun lexicalLongProofsTypeBareAndUnknownScalarOperandsBetweenTuplesAndPapSuffixes() {
        fun bare(id: String): InputCore = listOf("var", id)
        fun unknown(id: String): InputCore = listOf("var", id,
            mapOf("rep" to mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)))
        for (inlining in listOf(true, false)) withLanguage(inlining) { context, language ->
            val worker = bind("worker", lam(listOf(arg("before"), arg("p", pair), arg("after")),
                unpack(v("p", pair), pair, listOf("a", "b"), prim("+#", prim("+#", bare("before"), unknown("after")),
                    prim("+#", bare("a"), unknown("b"))))))
            val payload = pack(pair, unknown("x"), n(7))
            val p = BytecodeProgram(language, module(worker,
                bind("interleaved", lam(listOf(arg("x")), call("worker", listOf(bare("x"), payload, unknown("x"))))),
                bind("scalarPrefix", lam(listOf(arg("x")), app(call("worker", listOf(bare("x")), closure), listOf(payload, unknown("x"))))),
                bind("scalarSuffix", lam(listOf(arg("x")), app(call("worker", listOf(bare("x"), payload), closure), listOf(unknown("x")))))))
            for (name in listOf("interleaved", "scalarPrefix", "scalarSuffix"))
                checkCompiled(context, language, p, name) { it * 3L + 7L }
        }
    }

    @Test fun nestedFloatDoubleAndLazyReferenceLeavesStayTyped() {
        val inner = tuple(floating, double)
        val mixed = tuple(integer, inner, reference, state)
        for (inlining in listOf(true, false)) withLanguage(inlining) { context, language ->
            val body = unpack(v("p", mixed), mixed, listOf("x", "fd", "lazy", "s"),
                unpack(v("fd", inner), inner, listOf("f", "d"),
                    prim("+#", v("x"), prim("+#", prim("float2Int#", v("f", floating)), prim("double2Int#", v("d", double))))))
            val p = BytecodeProgram(language, module(
                bind("worker", lam(listOf(arg("p", mixed)), body)),
                bind("entry", lam(listOf(arg("x")), call("worker", listOf(pack(mixed, v("x"),
                    pack(inner, listOf("lit", "float", "1.5", mapOf("rep" to floating)), listOf("lit", "double", "2.75", mapOf("rep" to double))),
                    lam(listOf(arg("unused")), prim("quotInt#", n(1), n(0))), listOf("void", mapOf("rep" to state)))))))))
            checkCompiled(context, language, p, "entry") { it + 3L }
        }
    }

    @Test fun matchingSelfAndMutualTupleTransfersRestoreLocalsAndRetainBloom() = withLanguage { context, language ->
        fun worker(id: String, next: String) = bind(id, lam(listOf(arg("p", pair), arg("n")),
            listOf("case", prim("<=#", v("n"), n(0)), "done", listOf(
                listOf("lit", listOf("int", "1"), emptyList<String>(), v("p", pair)),
                listOf("default", null, emptyList<String>(), call(next, listOf(v("p", pair), prim("-#", v("n"), n(1))), pair))),
                mapOf("rep" to pair, "binder" to arg("done"))), pair))
        val p = BytecodeProgram(language, module(worker("self", "self"), worker("a", "b"), worker("b", "a"),
            bind("entry", lam(listOf(arg("x")), unpack(call("a", listOf(
                call("self", listOf(pack(pair, v("x"), n(7)), n(1000)), pair), n(1000)), pair),
                pair, listOf("a", "b"), prim("+#", v("a"), v("b")))))))
        checkCompiled(context, language, p, "entry") { it + 7L }
        assertTrue(p.diagnostics()["selfTailReentries"] as Long > 0)
    }

    private class OperandEffect(language: Language, private val events: MutableList<Long>, private val shape: TupleShape?) :
        GuestRoot(language, FrameLayout().build()) {
        init { configureEntry(booleanArrayOf(false), false); configureTupleResult(shape) }
        override fun bloom(frame: VirtualFrame) = 0L
        override fun execute(frame: VirtualFrame): Any? {
            val x = frame.arguments[1] as Long
            events += if (shape == null) x + 100L else x
            if (x < 0L) throw GuestException(x, this)
            return shape?.finish(frame, EMPTY_TUPLE_SLOTS) ?: (x + 100L)
        }
    }
    @Test fun zeroWidthStateTupleOperandsExecuteBeforeLaterArgumentsAndLoanAcquisition() = withLanguage { _, language ->
        val zero = tuple(state, tuple())
        val p = BytecodeProgram(language, module(
            bind("worker", lam(listOf(arg("unused", zero), arg("n")), v("n"))),
            bind("entry", lam(listOf(arg("effect", closure), arg("later", closure), arg("x")), call("worker", listOf(
                call("effect", listOf(v("x")), zero), call("later", listOf(v("x")))))))))
        val events = arrayListOf<Long>()
        val effect = Closure(null, arity = 1, target = OperandEffect(language, events, TupleShape(CoreRepresentations.parse(zero), language)).callTarget)
        val later = Closure(null, arity = 1, target = OperandEffect(language, events, null).callTarget)
        assertEquals(107L, run(p, "entry", effect, later, 7L)); assertEquals(listOf(7L, 107L), events); released(language)
        val entry = (p.entryTarget("worker").rootNode as GuestRoot).typedInput!!
        assertEquals(2, entry.logical.logicalArity); assertEquals(1, entry.logical.physicalArity)
        events.clear()
        assertThrows(GuestException::class.java) { run(p, "entry", effect, later, -1L) }
        assertEquals(listOf(-1L), events); released(language)
        events.clear()
        assertEquals(109L, run(p, "entry", effect, later, 9L)); assertEquals(listOf(9L, 109L), events); released(language)
    }

    @Test fun failedReferenceRestoreReleasesInputWithoutTouchingOutstandingResult() = withLanguage { _, language ->
        val data = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val inputProof = tuple(data)
        val p = BytecodeProgram(language, module(bind("worker", lam(listOf(arg("p", inputProof)),
            unpack(v("p", inputProof), inputProof, listOf("d"), v("d", data), data), data))))
        val target = p.entryTarget("worker")
        val entry = (target.rootNode as GuestRoot).typedInput!!
        val state = language.handoffState.get()
        val resultShape = TupleShape(CoreRepresentations.parse(tuple(reference, integer)), language)
        val result = state.results.acquire(resultShape.layout)
        val sentinel = Any()
        resultShape.layout.setObject(result, 0, sentinel); resultShape.layout.setLong(result, 1, 123L)
        val loan = state.arguments.acquire(entry.packet)
        loan.inputMode = 1
        entry.packet.setLong(loan, 0, 0L); entry.packet.setObject(loan, entry.header, "not a constructor")
        assertThrows(RuntimeFault::class.java) {
            invokeTypedInput(target, loan) { packet -> Calls.target(target, packet) }
        }
        assertNull(state.pending); assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(1, state.results.depth); assertSame(sentinel, resultShape.layout.getObject(result, 0))
        assertEquals(123L, resultShape.layout.getLong(result, 1))
        state.results.release(result, resultShape.layout); released(language)
    }

    @Test fun genericDispatchKeepsTypedInputsAfterFiveTargets() {
        for (inlining in listOf(true, false)) withLanguage(inlining) { context, language ->
            val workers = (0..4).map { i -> bind("worker$i", lam(listOf(arg("p", pair)),
                unpack(v("p", pair), pair, listOf("a", "b"), prim("+#", prim("+#", v("a"), v("b")), n(i.toLong()))))) }
            val entry = bind("entry", lam(listOf(arg("f", closure), arg("x")), call("f", listOf(pack(pair, v("x"), n(7))))))
            val p = BytecodeProgram(language, module(*(workers + entry).toTypedArray()))
            val functions = (0..4).map { p.entryValue("worker$it") }
            val values = listOf(Long.MIN_VALUE, -3L, 0L, Long.MAX_VALUE)
            // Prime the finite PIC with every target before exercising its generic path.
            functions.forEachIndexed { i, f -> assertEquals(7L + i, run(p, "entry", f, 0L)) }
            for (x in values) functions.forEachIndexed { i, f -> assertEquals(x + 7L + i, run(p, "entry", f, x)) }
            val target = p.entryTarget("entry")
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true); valid(target)
            for (x in values.asReversed()) functions.forEachIndexed { i, f ->
                val before = p.diagnostics()["compiledEntries"] as Long
                assertEquals(x + 7L + i, run(p, "entry", f, x)); valid(target)
                assertTrue(p.diagnostics()["compiledEntries"] as Long > before); released(language)
            }
            assertTrue(p.diagnostics()["indirectCalls"] as Long > 0)
        }
    }

    @Test fun hiddenTupleCaseProofCannotBeLiftedOrPassedToScalarPrimitive() = withLanguage { _, language ->
        val hidden = listOf("case", n(1), "ignored", listOf(listOf("default", null, emptyList<String>(), pack(pair, n(3), n(5)))))
        val worker = bind("worker", lam(listOf(arg("p", pair)), n(9)))
        val lazyFailure = assertThrows(UnsupportedCore::class.java) {
            BytecodeProgram(language, module(worker, bind("bad", lam(listOf(arg("f", closure), arg("x")),
                app(v("f", closure), listOf(hidden), flags = listOf(true))))))
        }
        assertTrue(lazyFailure.message!!.contains("thunk"))
        val previous = System.getProperty(CALL_DEMANDS_PROPERTY)
        try {
            System.setProperty(CALL_DEMANDS_PROPERTY, "true")
            val demanded = app(v("f", closure), listOf(hidden), flags = listOf(true)).toMutableList()
            demanded[6] = mapOf("rep" to integer, "callDemand" to mapOf("arity" to 1, "strictArgs" to listOf(true)))
            val failure = assertThrows(RuntimeFault::class.java) {
                BytecodeProgram(language, module(worker, bind("badDemand", lam(listOf(arg("f", closure), arg("x")), demanded))))
            }
            assertTrue(failure.message!!.contains("Tuple argument cannot be lifted"))
        } finally {
            if (previous == null) System.clearProperty(CALL_DEMANDS_PROPERTY) else System.setProperty(CALL_DEMANDS_PROPERTY, previous)
        }
        assertThrows(RuntimeException::class.java) {
            BytecodeProgram(language, module(bind("bad", lam(listOf(arg("x")), prim("+#", hidden, n(1))))))
        }
        val unknownArm = listOf("app", v("f", closure), listOf(n(1)), listOf(false), false, false)
        val mixedCase = listOf("case", v("x"), "which", listOf(
            listOf("lit", listOf("int", "0"), emptyList<String>(), pack(pair, n(3), n(5))),
            listOf("default", null, emptyList<String>(), unknownArm)))
        val missingProof = assertThrows(UnsupportedCore::class.java) {
            BytecodeProgram(language, module(bind("badMixed", lam(listOf(arg("f", closure), arg("x")), mixedCase))))
        }
        assertTrue(missingProof.message!!.contains("Missing exact aggregate case result proof"))
        val good = BytecodeProgram(language, module(worker, bind("good", lam(listOf(arg("f", closure), arg("x")), call("f", listOf(hidden))))))
        assertEquals(9L, run(good, "good", good.entryValue("worker"), 0L)); released(language)
    }
}
