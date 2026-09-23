package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias DemandCore = List<Any?>

/** A saturated call may demand an operand without changing the callee's entry convention. */
class CallDemandTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val data = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val void = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private fun variable(id: String): DemandCore = listOf("var", id)
    private fun integer(n: Long): DemandCore = listOf("lit", "int", n.toString())
    private fun parameter(id: String, rep: Map<String, Any> = long, coercion: Boolean = false) = mapOf(
        "id" to id, "name" to id, "lifted" to (rep !== long && rep !== void), "coercion" to coercion, "rep" to rep)
    private fun lambda(args: List<Map<String, Any>>, body: DemandCore,
                       result: Map<String, Any> = long): DemandCore =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result,
            "entryStrict" to List(args.size) { false }))
    private fun binding(id: String, rhs: DemandCore): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "lifted" to true, "expr" to rhs)
    private fun apply(fn: DemandCore, args: List<DemandCore>, lifted: List<Boolean> = List(args.size) { false },
                      demand: Pair<Int, List<Boolean>>? = null): DemandCore {
        val expr = listOf("app", fn, args, lifted, false, false)
        return if (demand == null) expr else expr + mapOf("callDemand" to
            mapOf("arity" to demand.first, "strictArgs" to demand.second))
    }
    private fun primitive(name: String, vararg args: DemandCore) = apply(listOf("prim", name), args.toList())
    private fun box(n: DemandCore): DemandCore = listOf("app", listOf("con", "Box", 1), listOf(n), listOf(false), true, true)
    private fun unbox(value: DemandCore, body: DemandCore = variable("payload"),
                      result: Map<String, Any> = long): DemandCore = listOf("case", value, "boxed",
        listOf(listOf("data", "Box", listOf("payload"), body, mapOf("binders" to listOf(parameter("payload"))))),
        mapOf("rep" to result, "binder" to parameter("boxed", data)))
    private fun choose(value: DemandCore, zero: DemandCore, other: DemandCore): DemandCore = listOf("case", value, "choice",
        listOf(listOf("lit", listOf("int", "0"), emptyList<String>(), zero), listOf("default", null, emptyList<String>(), other)))
    private fun delayedBox(n: DemandCore) = choose(n, box(integer(9)), box(n))
    private fun failingBox(n: DemandCore) = apply(listOf("con", "Box", 1), listOf(primitive("quotInt#", integer(1), n)))
    private fun local(group: List<Map<String, Any?>>, body: DemandCore): DemandCore = listOf("let", false, group, body)
    private fun consumer() = binding("consumer", lambda(listOf(parameter("tree", data), parameter("extra")),
        unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra")))))
    private fun count(p: ExecutableProgram, name: String) = (p.diagnostics().getValue(name) as Number).toLong()
    private fun call(p: ExecutableProgram, fn: Any?, vararg args: Any?): Any? =
        Calls.target(p.hostEntryTarget(args.size), arrayOf(fn, arrayOf(*args)))
    private fun run(p: ExecutableProgram, name: String, vararg args: Any?) = call(p, p.entryValue(name), *args)
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private val coldInputs = listOf(0L, 3_000_000_017L, -7_000_000_003L, Long.MIN_VALUE, Long.MAX_VALUE)
    private fun eachBackend(bindings: List<Map<String, Any?>>, callDemands: Boolean? = true,
                            action: (String, ExecutableProgram) -> Unit) {
        val previous = System.getProperty(CALL_DEMANDS_PROPERTY)
        try {
            if (callDemands == null) System.clearProperty(CALL_DEMANDS_PROPERTY)
            else System.setProperty(CALL_DEMANDS_PROPERTY, callDemands.toString())
            executionContext().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (backend in listOf("ast", "bytecode")) {
                        val module = mapOf("bindings" to bindings, "instrument" to true, "constructors" to listOf(
                            mapOf("id" to "Box", "name" to "Box", "arity" to 1, "kind" to "boxed", "fieldReps" to listOf(listOf("IntRep")),
                                "strictFields" to listOf(false), "fieldLifted" to listOf(false))))
                        action(backend, if (backend == "ast") Program(language, module) else BytecodeProgram(language, module))
                    }
                } finally { context.leave() }
            }
        } finally {
            if (previous == null) System.clearProperty(CALL_DEMANDS_PROPERTY)
            else System.setProperty(CALL_DEMANDS_PROPERTY, previous)
        }
    }

    @Test fun callerDemandIsOptInAndDoesNotChangeAnAlreadyLoweredProgram() {
        val direct = binding("direct", lambda(listOf(parameter("input")), apply(variable("consumer"),
            listOf(delayedBox(variable("input")), variable("input")), listOf(true, false), 2 to listOf(true, false))))
        eachBackend(listOf(consumer(), direct), callDemands = null) { backend, p ->
            // Enabling later must not alter this program's already chosen lowering.
            System.setProperty(CALL_DEMANDS_PROPERTY, "true")
            try {
                repeat(3) { assertEquals(14L, run(p, "direct", 7L), backend) }
                assertEquals(3L, count(p, "thunkEvaluations"), "$backend absent property retains argument suspensions")
            } finally { System.clearProperty(CALL_DEMANDS_PROPERTY) }
        }
    }

    @Test fun ordinaryDemandEliminatesArgumentThunksWithoutChangingUnknownCallsOrFormalContracts() {
        val direct = binding("direct", lambda(listOf(parameter("input")), apply(variable("consumer"),
            listOf(delayedBox(variable("input")), variable("input")), listOf(true, false), 2 to listOf(true, false))))
        val generic = binding("generic", lambda(listOf(parameter("fn", closure), parameter("input")), apply(variable("fn"),
            listOf(delayedBox(variable("input")), variable("input")), listOf(true, false))))
        eachBackend(listOf(consumer(), direct, generic)) { backend, p ->
            assertFalse((p.entryTarget("consumer").rootNode as GuestRoot).entryStrict.any { it }, backend)
            repeat(12) { assertEquals(14L, run(p, "direct", 7L), backend) }
            assertEquals(0L, count(p, "thunkEvaluations"), "$backend ordinary direct call needs no argument suspension")
            repeat(12) { assertEquals(14L, run(p, "generic", p.entryValue("consumer"), 7L), backend) }
            assertEquals(12L, count(p, "thunkEvaluations"), "$backend unknown call has no caller demand certificate")
            compile(p.entryTarget("direct")); compile(p.entryTarget("generic"))
            val compiled = count(p, "compiledEntries")
            for (n in coldInputs) {
                val expected = (if (n == 0L) 9L else n) + n
                val before = count(p, "thunkEvaluations")
                assertEquals(expected, run(p, "direct", n), backend)
                assertEquals(before, count(p, "thunkEvaluations"), backend)
                assertEquals(expected, run(p, "generic", p.entryValue("consumer"), n), backend)
                assertEquals(before + 1, count(p, "thunkEvaluations"), backend)
            }
            assertTrue(count(p, "compiledEntries") > compiled, backend)
            assertFalse((p.entryTarget("consumer").rootNode as GuestRoot).entryStrict.any { it }, backend)
        }
    }

    @Test fun undersaturatedDemandKeepsThePapPrefixLazyAndSharesItAfterSaturation() {
        // A true stored bit deliberately tests the runtime's independent saturation guard.
        val partial = binding("partial", lambda(listOf(parameter("input")), apply(variable("consumer"),
            listOf(delayedBox(variable("input"))), listOf(true), 2 to listOf(true)), closure))
        val failing = binding("failing", lambda(listOf(parameter("input")), apply(variable("consumer"),
            listOf(failingBox(variable("input"))), listOf(true), 2 to listOf(true)), closure))
        eachBackend(listOf(consumer(), partial, failing)) { backend, p ->
            val first = run(p, "partial", 3_000_000_017L) as Closure
            val second = run(p, "partial", -7_000_000_003L) as Closure
            assertEquals(0L, count(p, "thunkEvaluations"), backend)
            assertEquals(3_000_000_018L, call(p, first, 1L), backend)
            assertEquals(-7_000_000_001L, call(p, second, 2L), backend)
            repeat(12) { assertEquals(3_000_000_020L, call(p, first, 3L), backend) }
            assertEquals(2L, count(p, "thunkEvaluations"), "$backend PAP prefix is shared")
            repeat(12) { assertTrue(run(p, "failing", 1L) is Closure, backend) }
            assertEquals(2L, count(p, "thunkEvaluations"), backend)
            compile(first.target); compile(p.entryTarget("failing"))
            assertEquals(3_000_000_017L + Long.MAX_VALUE, call(p, first, Long.MAX_VALUE), backend)
            assertEquals(2L, count(p, "thunkEvaluations"), backend)
            val failure = run(p, "failing", 0L) as Closure
            assertEquals(2L, count(p, "thunkEvaluations"), "$backend making a failing PAP does not enter its argument")
            assertThrows(ArithmeticException::class.java) { call(p, failure, 1L) }
            assertTrue(count(p, "papAllocations") > 0, backend)
        }
    }

    @Test fun overapplicationDemandsOnlyTheSignaturePrefix() {
        val maker = binding("maker", lambda(listOf(parameter("tree", data)),
            unbox(variable("tree"), lambda(listOf(parameter("ignored", data)), variable("payload")), closure), closure))
        val entry = binding("entry", lambda(listOf(parameter("input")), apply(variable("maker"),
            listOf(delayedBox(variable("input")), failingBox(integer(0))), listOf(true, true), 1 to listOf(true, false))))
        eachBackend(listOf(maker, entry)) { backend, p ->
            repeat(12) { assertEquals(7L, run(p, "entry", 7L), backend) }
            compile(p.entryTarget("entry"))
            for (n in coldInputs) assertEquals(if (n == 0L) 9L else n, run(p, "entry", n), backend)
            assertEquals(0L, count(p, "thunkEvaluations"), "$backend ignored surplus argument remains lazy")
        }
    }

    @Test fun strictVariableArgumentsForceOneSharedThunkAndKeepLazyEnclosingBindings() {
        val add = binding("add", lambda(listOf(parameter("left", data), parameter("right", data)),
            unbox(variable("left"), primitive("+#", variable("payload"), unbox(variable("right"))))))
        val demanded = apply(variable("add"), listOf(variable("shared"), variable("shared")), listOf(true, true), 2 to listOf(true, true))
        val entry = binding("entry", lambda(listOf(parameter("input")), local(
            listOf(binding("shared", delayedBox(variable("input")))), demanded)))
        val unusedCall = apply(variable("add"), listOf(failingBox(integer(0)), failingBox(integer(0))),
            listOf(true, true), 2 to listOf(true, true))
        val unused = binding("unused", lambda(listOf(parameter("input")), local(
            listOf(binding("ignored", unusedCall)), integer(41))))
        eachBackend(listOf(add, entry, unused)) { backend, p ->
            repeat(12) { assertEquals(14L, run(p, "entry", 7L), backend) }
            assertEquals(12L, count(p, "thunkEvaluations"), "$backend one evaluation per shared binding")
            repeat(12) { assertEquals(41L, run(p, "unused", 7L), backend) }
            assertEquals(12L, count(p, "thunkEvaluations"), backend)
            compile(p.entryTarget("entry")); compile(p.entryTarget("unused"))
            for (n in coldInputs) {
                val before = count(p, "thunkEvaluations")
                assertEquals((if (n == 0L) 9L else n) * 2, run(p, "entry", n), backend)
                assertEquals(before + 1, count(p, "thunkEvaluations"), backend)
                assertEquals(41L, run(p, "unused", n), backend)
                assertEquals(before + 1, count(p, "thunkEvaluations"), "$backend demand stays inside its lazy enclosing RHS")
            }
        }
    }

    @Test fun localJoinDemandRetainsTheCoercionSlotWithoutAddingAnEntryContract() {
        val join = binding("join", lambda(listOf(parameter("co", void, coercion = true), parameter("tree", data), parameter("extra")),
            unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra"))))) +
            mapOf("joinValueArity" to 3, "joinResultRep" to long)
        val jump = apply(variable("join"), listOf(listOf("void"), delayedBox(variable("input")), variable("input")),
            listOf(false, true, false), 3 to listOf(false, true, false))
        val entry = binding("entry", lambda(listOf(parameter("input")), local(listOf(join), jump)))
        eachBackend(listOf(entry)) { backend, p ->
            repeat(12) { assertEquals(14L, run(p, "entry", 7L), backend) }
            compile(p.entryTarget("entry"))
            for (n in coldInputs) assertEquals((if (n == 0L) 9L else n) + n, run(p, "entry", n), backend)
            assertEquals(0L, count(p, "thunkEvaluations"), "$backend join operands use the same demand path")
            assertTrue(count(p, "localJoinTransfers") > 0, backend)
        }
    }

    @Test fun demandedFunctionExpressionsAreForcedToClosureWhnf() {
        val invoke = binding("invoke", lambda(listOf(parameter("fn", closure), parameter("n")),
            apply(variable("fn"), listOf(variable("n")))))
        val delayedFunction = choose(variable("input"),
            lambda(listOf(parameter("x")), primitive("+#", variable("x"), integer(9))),
            lambda(listOf(parameter("x")), primitive("+#", variable("x"), variable("input"))))
        val entry = binding("entry", lambda(listOf(parameter("input")), apply(variable("invoke"),
            listOf(delayedFunction, variable("input")), listOf(true, false), 2 to listOf(true, false))))
        eachBackend(listOf(invoke, entry)) { backend, p ->
            repeat(12) { assertEquals(14L, run(p, "entry", 7L), backend) }
            compile(p.entryTarget("entry"))
            for (n in coldInputs) assertEquals(if (n == 0L) 9L else n + n, run(p, "entry", n), backend)
            assertEquals(0L, count(p, "thunkEvaluations"), "$backend demanded closure producer needs no argument suspension")
        }
    }

    @Test fun malformedCallDemandFailsClosedAndMissingMetadataRemainsConservative() {
        val base = apply(variable("consumer"), listOf(variable("tree")), listOf(true))
        fun metadata(arity: Any?, marks: Any?): DemandCore = base + mapOf("callDemand" to mapOf("arity" to arity, "strictArgs" to marks))
        assertArrayEquals(booleanArrayOf(false), CoreCallDemands.application(base))
        assertArrayEquals(booleanArrayOf(true), CoreCallDemands.application(metadata(1, listOf(true))))
        assertThrows(RuntimeFault::class.java) {
            CoreCallDemands.lowerApplication(metadata(0, listOf(true)), false)
        }
        assertArrayEquals(booleanArrayOf(false), CoreCallDemands.application(metadata(2, listOf(true))))
        for (bad in listOf(
            base + mapOf("callDemand" to "strict"),
            metadata(-1, listOf(false)), metadata(1.5, listOf(true)), metadata(2_147_483_648L, listOf(false)),
            metadata(1, emptyList<Boolean>()), metadata(1, listOf("strict")), metadata(0, listOf(true)))) {
            assertThrows(RuntimeFault::class.java) { CoreCallDemands.application(bad) }
        }
    }
}
