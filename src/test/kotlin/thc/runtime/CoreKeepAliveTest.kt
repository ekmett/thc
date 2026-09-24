// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias KeepCore = List<Any?>
private typealias KeepProof = Map<String, Any?>

/** Independent conformance tests, not native/compiled-entry coverage claims. */
class CoreKeepAliveTest {
    private val state = proof("void", emptyList())
    private val int = proof("long", listOf("IntRep"))
    private val word = proof("long", listOf("WordRep"))
    private val word8 = proof("long", listOf("Word8Rep"))
    private val owner = proof("object", listOf("BoxedRep (Just Lifted)"), false)
    private val data = proof("data", listOf("BoxedRep (Just Lifted)"), false)
    private val closure = proof("closure", listOf("BoxedRep (Just Lifted)"))
    private val lazyClosure = closure + ("evaluated" to false)
    private fun proof(kind: String, reps: List<String>, evaluated: Boolean = true): KeepProof =
        mapOf("kind" to kind, "primReps" to reps, "evaluated" to evaluated)
    private fun parameter(id: String, rep: KeepProof): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "rep" to rep,
        "lifted" to (rep["primReps"] == listOf("BoxedRep (Just Lifted)")), "coercion" to false)
    private fun variable(id: String, rep: KeepProof): KeepCore = listOf("var", id, mapOf("rep" to rep))
    private fun integer(value: Long): KeepCore = listOf("lit", "int", value.toString(), mapOf("rep" to int))
    private fun void(): KeepCore = listOf("void", mapOf("rep" to state))
    private fun lambda(args: List<Map<String, Any?>>, body: KeepCore, result: KeepProof): KeepCore =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result))
    private fun binding(id: String, body: KeepCore): Map<String, Any?> =
        parameter(id, closure) + mapOf("expr" to body, "arity" to (body[1] as List<*>).size)
    private fun app(function: KeepCore, args: List<KeepCore>, flags: List<Boolean>, result: KeepProof): KeepCore =
        listOf("app", function, args, flags, false, false, mapOf("rep" to result))
    private fun keep(action: KeepCore, result: KeepProof): KeepCore = app(listOf("prim", "keepAlive#"),
        listOf(variable("owner", owner), variable("state", state), action), listOf(true, false, true), result)
    private fun tuple(vararg fields: KeepProof): KeepProof = mapOf("kind" to "unknown", "evaluated" to false,
        "aggregate" to "unboxed-tuple", "components" to fields.toList(),
        "primReps" to fields.flatMap { (it["primReps"] as List<*>).map { rep -> rep as String } })
    private fun sum(): KeepProof = mapOf("kind" to "unknown", "evaluated" to false, "aggregate" to "unboxed-sum",
        "alternatives" to listOf(int, int), "primReps" to listOf("WordRep", "WordRep"),
        "tagSlot" to 0, "alternativeSlots" to listOf(listOf(1), listOf(1)))
    private val constructors = listOf(
        mapOf("id" to "Tuple2", "name" to "Tuple2", "kind" to "unboxed-tuple", "arity" to 2,
            "fieldReps" to listOf(null, null), "strictFields" to listOf(false, false), "fieldLifted" to listOf(null, null)),
        mapOf("id" to "Left", "name" to "Left", "kind" to "unboxed-sum", "arity" to 1, "sumArity" to 2, "tag" to 1),
        mapOf("id" to "Right", "name" to "Right", "kind" to "unboxed-sum", "arity" to 1, "sumArity" to 2, "tag" to 2))
    private fun module(bindings: List<Map<String, Any?>>): Map<String, Any?> =
        mapOf("bindings" to bindings, "constructors" to constructors, "instrument" to true)
    private fun program(language: Language, backend: String, bindings: List<Map<String, Any?>>): ExecutableProgram =
        if (backend == "ast") Program(language, module(bindings)) else BytecodeProgram(language, module(bindings))
    private fun eachBackend(action: (Language, String) -> Unit) {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null), backend) }
            finally { context.leave() }
        }
    }
    private fun call(program: ExecutableProgram, vararg arguments: Any?): Any? =
        Calls.target(program.hostEntryTarget(arguments.size), arrayOf(program.entryValue("main"), arrayOf(*arguments)))
    private fun released(language: Language) {
        val pools = language.handoffState.get()
        assertEquals(0, pools.arguments.depth); assertEquals(0, pools.results.depth)
        assertEquals(0, pools.arguments.retainedReferences()); assertEquals(0, pools.results.retainedReferences())
    }

    @Test fun exactScalarTupleAndExistingSumResultsKeepTheirLogicalIdentity() {
        val inputs = listOf(owner, state, closure).map(CoreRepresentations::parse)
        for (result in listOf(int, word8, data, tuple(state, data), tuple(state, word8), sum())) {
            val actual = CoreRepresentations.parse(result)
            assertDoesNotThrow { CoreKeepAlive.validate(inputs, listOf(true, false, true), actual,
                listOf(CoreRepresentations.parse(state)) to actual) }
        }
        for ((declared, actual) in listOf(int to state, word to word8, tuple(state, word) to tuple(state, word8),
                int to sum(), tuple(int, int) to sum())) {
            assertThrows(RuntimeFault::class.java) {
                CoreKeepAlive.validate(inputs, listOf(true, false, true), CoreRepresentations.parse(declared),
                    listOf(CoreRepresentations.parse(state)) to CoreRepresentations.parse(actual))
            }
        }
        assertThrows(RuntimeFault::class.java) {
            CoreKeepAlive.validate(inputs, listOf(true, false, true),
                CoreRepresentations.parse(mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)), null)
        }
        assertThrows(UnsupportedCore::class.java) { CoreRepresentations.parse(tuple(sum(), int)) }
    }

    @Test fun bothLoadersRejectWrongKnownContinuationInputsResultsAndPartialApplicationResults() = eachBackend { language, backend ->
        val wrongInput = lambda(listOf(parameter("notState", int)), integer(7), int)
        val wrongResult = lambda(listOf(parameter("s", state)), void(), state)
        val partial = lambda(listOf(parameter("s", state), parameter("x", int)), variable("x", int), int)
        val global = binding("callback", wrongInput)
        val alias = parameter("alias", closure) + mapOf("expr" to variable("callback", closure))
        val prefixed = binding("prefixed", lambda(listOf(parameter("prefix", int), parameter("notState", int)), integer(7), int))
        val pap = app(variable("prefixed", closure), listOf(integer(1)), listOf(false), closure)
        val variants = listOf(wrongInput to emptyList(), wrongResult to emptyList(), partial to emptyList(),
            variable("callback", closure) to listOf(global), variable("alias", closure) to listOf(global, alias), pap to listOf(prefixed))
        for ((action, additional) in variants) {
            val main = binding("main", lambda(listOf(parameter("owner", owner), parameter("state", state)), keep(action, int), int))
            assertThrows(RuntimeFault::class.java, { program(language, backend, listOf(main) + additional) }, "$backend/$action")
        }
    }

    @Test fun liftedKeptBottomStaysLazyAndScalarCallbackResultIsReturnedInWhnf() = eachBackend { language, backend ->
        val action = lambda(listOf(parameter("s", state)), variable("value", data), data)
        val main = binding("main", lambda(listOf(parameter("owner", owner), parameter("state", state), parameter("value", data)),
            keep(action, data), data))
        val p = program(language, backend, listOf(main))
        var forced = 0
        val value = DataLayout(language, "review.Box", "Box", arrayOf("IntRep")).create(arrayOf(91L))
        val lazyValue = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any { forced++; return value }
        }.callTarget, null)
        val bottom = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Nothing = throw AssertionError("kept argument was forced")
        }.callTarget, null)
        assertSame(value, call(p, bottom, Unit, lazyValue), backend)
        assertEquals(1, forced); assertEquals(2, lazyValue.state); assertEquals(0, bottom.state)
        released(language)
    }

    @Test fun tupleCallbackDoesNotStrengthenLiftedPayloadEvaluatedness() = eachBackend { language, backend ->
        val result = tuple(state, data)
        val pair = app(listOf("con", "Tuple2", 2), listOf(void(), variable("value", data)), listOf(false, true), result)
        val action = lambda(listOf(parameter("s", state)), pair, result)
        val fields = listOf(parameter("nextState", state), parameter("unusedValue", data))
        val body: KeepCore = listOf("case", keep(action, result), "pair", listOf(listOf("data", "Tuple2",
            listOf("nextState", "unusedValue"), integer(7), mapOf("binders" to fields))),
            mapOf("rep" to int, "binder" to parameter("pair", result + ("evaluated" to true))))
        val main = binding("main", lambda(listOf(parameter("owner", owner), parameter("state", state), parameter("value", data)), body, int))
        val p = program(language, backend, listOf(main))
        val bottom = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Nothing = throw AssertionError("lazy tuple field was forced")
        }.callTarget, null)
        assertEquals(7L, call(p, bottom, Unit, bottom), backend)
        assertEquals(0, bottom.state); released(language)
    }

    @Test fun existingBinarySumResultCanBeConsumedWithoutChangingItsAbi() = eachBackend { language, backend ->
        val result = sum()
        val left = app(listOf("con", "Left", 1), listOf(integer(77)), listOf(false), result)
        val action = lambda(listOf(parameter("s", state)), left, result)
        val alternatives = listOf("Left", "Right").map { constructor ->
            listOf("data", constructor, listOf("payload"), variable("payload", int),
                mapOf("binders" to listOf(parameter("payload", int))))
        }
        val body: KeepCore = listOf("case", keep(action, result), "sum", alternatives,
            mapOf("rep" to int, "binder" to parameter("sum", result + ("evaluated" to true))))
        val main = binding("main", lambda(listOf(parameter("owner", owner), parameter("state", state)), body, int))
        assertEquals(77L, call(program(language, backend, listOf(main)), Any(), Unit), backend)
        released(language)
    }

    @Test fun partialContinuationReturnsTheRemainingLiftedFunction() = eachBackend { language, backend ->
        val action = lambda(listOf(parameter("s", state), parameter("x", int)), variable("x", int), int)
        val main = binding("main", lambda(listOf(parameter("owner", owner), parameter("state", state)),
            keep(action, closure), closure))
        val p = program(language, backend, listOf(main))
        val result = call(p, Any(), Unit)
        assertTrue(result is Closure, backend)
        assertEquals(1, (result as Closure).arity)
        assertEquals(123L, Calls.target(p.hostEntryTarget(1), arrayOf(result, arrayOf(123L))), backend)
        released(language)
    }

    @Test fun invalidStatePrecedesCallbackThunkForcingAndExceptionsReleaseAllLoans() = eachBackend { language, backend ->
        val main = binding("main", lambda(listOf(parameter("owner", owner), parameter("state", state),
            parameter("callback", lazyClosure)), keep(variable("callback", lazyClosure), int), int))
        val p = program(language, backend, listOf(main))
        val exceptionPayload = Any()
        var forces = 0; var calls = 0; var throwing = true
        val callback = Closure(null, arity = 1, target = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any {
                assertSame(Unit, frame.arguments[1]); calls++
                if (throwing) throw GuestException(exceptionPayload, this)
                return 77L
            }
        }.callTarget)
        val lazyCallback = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any { forces++; return callback }
        }.callTarget, null)
        val failure = assertThrows(RuntimeFault::class.java) { call(p, Any(), 1L, lazyCallback) }
        assertTrue(failure.message.orEmpty().contains("zero-width"), "$backend: $failure")
        assertEquals(0, forces); assertEquals(0, calls); assertEquals(0, lazyCallback.state); released(language)
        var failedForces = 0
        val failedCallback = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Nothing { failedForces++; throw RuntimeFault("callback thunk failed") }
        }.callTarget, null)
        val stateFailure = assertThrows(RuntimeFault::class.java) { call(p, Any(), 1L, failedCallback) }
        assertTrue(stateFailure.message.orEmpty().contains("zero-width"), "$backend: $stateFailure")
        assertEquals(0, failedForces); assertEquals(0, failedCallback.state)
        val thunkFailure = assertThrows(RuntimeFault::class.java) { call(p, Any(), Unit, failedCallback) }
        assertEquals("callback thunk failed", thunkFailure.message); assertEquals(1, failedForces); released(language)
        val guest = assertThrows(GuestException::class.java) { call(p, Any(), Unit, lazyCallback) }
        assertSame(exceptionPayload, guest.payload); assertEquals(1, forces); assertEquals(1, calls); released(language)
        throwing = false
        assertEquals(77L, call(p, Any(), Unit, lazyCallback), backend)
        assertEquals(1, forces); assertEquals(2, calls); released(language)
    }
}
