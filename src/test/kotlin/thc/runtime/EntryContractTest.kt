// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias EntryCore = List<Any?>

/** CBV is a saturated calling convention, not permission to force a partial application. */
class EntryContractTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val data = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private fun variable(id: String): EntryCore = listOf("var", id)
    private fun integer(n: Long): EntryCore = listOf("lit", "int", n.toString())
    private fun parameter(id: String, rep: Map<String, Any> = long) = mapOf(
        "id" to id, "name" to id, "lifted" to (rep !== long), "coercion" to false, "rep" to rep)
    private fun lambda(args: List<Map<String, Any>>, body: EntryCore, strict: List<Boolean> = List(args.size) { false },
                       result: Map<String, Any> = long): EntryCore =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result, "entryStrict" to strict))
    private fun binding(id: String, rhs: EntryCore): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "lifted" to true, "expr" to rhs)
    private fun apply(fn: EntryCore, args: List<EntryCore>, lifted: List<Boolean> = List(args.size) { false }): EntryCore =
        listOf("app", fn, args, lifted, false, false)
    private fun primitive(name: String, vararg args: EntryCore) = apply(listOf("prim", name), args.toList())
    private fun box(n: EntryCore): EntryCore = listOf("app", listOf("con", "Box", 1), listOf(n), listOf(false), true, true)
    private fun unbox(value: EntryCore, body: EntryCore = variable("payload")): EntryCore = listOf("case", value, "boxed",
        listOf(listOf("data", "Box", listOf("payload"), body, mapOf("binders" to listOf(parameter("payload"))))),
        mapOf("rep" to long, "binder" to parameter("boxed", data)))
    private fun choose(value: EntryCore, zero: EntryCore, other: EntryCore): EntryCore = listOf("case", value, "choice",
        listOf(listOf("lit", listOf("int", "0"), emptyList<String>(), zero), listOf("default", null, emptyList<String>(), other)))
    private fun delayedBox(value: EntryCore) = choose(value, box(integer(9)), box(value))
    private fun local(group: List<Map<String, Any?>>, body: EntryCore): EntryCore = listOf("let", false, group, body)
    private fun count(p: ExecutableProgram, name: String) = (p.diagnostics().getValue(name) as Number).toLong()
    private fun call(p: ExecutableProgram, function: Any?, vararg args: Any?): Any? =
        Calls.target(p.hostEntryTarget(args.size), arrayOf(function, arrayOf(*args)))
    private fun run(p: ExecutableProgram, name: String, vararg args: Any?) = call(p, p.entryValue(name), *args)
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private fun eachBackend(bindings: List<Map<String, Any?>>, action: (String, ExecutableProgram) -> Unit) {
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
    }

    @Test fun knownWorkerCompilesStrictOperandsDirectlyWhileUnknownCallsEnforceTheSameContract() {
        val worker = binding("worker", lambda(listOf(parameter("tree", data), parameter("extra")),
            unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra"))), listOf(true, false)))
        val indirect = binding("indirect", lambda(listOf(parameter("fn", closure), parameter("tree", data), parameter("extra")),
            apply(variable("fn"), listOf(variable("tree"), variable("extra")), listOf(true, false))))
        val directEntry = binding("direct", lambda(listOf(parameter("input")),
            apply(variable("worker"), listOf(delayedBox(variable("input")), variable("input")), listOf(true, false))))
        val indirectEntry = binding("generic", lambda(listOf(parameter("input")),
            apply(variable("indirect"), listOf(variable("worker"), delayedBox(variable("input")), variable("input")), listOf(true, true, false))))
        eachBackend(listOf(worker, indirect, directEntry, indirectEntry)) { backend, p ->
            repeat(30) { assertEquals(14L, run(p, "direct", 7L), backend) }
            assertEquals(0L, count(p, "thunkEvaluations"), "$backend known CBV operands need no suspension")
            repeat(30) { assertEquals(14L, run(p, "generic", 7L), backend) }
            assertEquals(30L, count(p, "thunkEvaluations"), "$backend unknown call forces its delayed operand")
            compile(p.entryTarget("direct")); compile(p.entryTarget("generic"))
            for (n in listOf(0L, 3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                val expected = (if (n == 0L) 9L else n) + n
                assertEquals(expected, run(p, "direct", n), backend)
                assertEquals(expected, run(p, "generic", n), backend)
            }
        }
    }

    @Test fun papPrefixStaysLazyUntilSaturationAndItsThunkIsSharedAfterward() {
        val worker = binding("worker", lambda(listOf(parameter("tree", data), parameter("extra")),
            unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra"))), listOf(true, false)))
        val partial = binding("partial", lambda(listOf(parameter("input")),
            apply(variable("worker"), listOf(delayedBox(variable("input"))), listOf(true)), result = closure))
        val failing = binding("failing", lambda(listOf(parameter("input")),
            apply(variable("worker"), listOf(apply(listOf("con", "Box", 1),
                listOf(primitive("quotInt#", integer(1), variable("input"))))), listOf(true)), result = closure))
        eachBackend(listOf(worker, partial, failing)) { backend, p ->
            val a = run(p, "partial", 3_000_000_017L) as Closure
            val b = run(p, "partial", -7_000_000_003L) as Closure
            assertEquals(0L, count(p, "thunkEvaluations"), backend)
            assertEquals(3_000_000_018L, call(p, a, 1L), backend)
            assertEquals(-7_000_000_001L, call(p, b, 2L), backend)
            assertEquals(2L, count(p, "thunkEvaluations"), backend)
            repeat(30) { assertEquals(3_000_000_020L, call(p, a, 3L), backend) }
            compile(a.target)
            assertEquals(3_000_000_017L + Long.MAX_VALUE, call(p, a, Long.MAX_VALUE), backend)
            assertEquals(2L, count(p, "thunkEvaluations"), "$backend PAP prefixes retain sharing")
            // Explicitly unsafe-to-speculate construction: merely making the PAP must not divide by zero.
            val failure = run(p, "failing", 0L) as Closure
            assertThrows(ArithmeticException::class.java) { call(p, failure, 1L) }
        }
    }

    @Test fun overapplicationEnforcesOnlyTheSaturatedFirstFunctionBeforeApplyingItsResult() {
        val worker = binding("worker", lambda(listOf(parameter("tree", data)),
            lambda(listOf(parameter("extra")), unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra")))),
            listOf(true), closure))
        val generic = binding("generic", lambda(listOf(parameter("fn", closure), parameter("tree", data), parameter("input")),
            apply(variable("fn"), listOf(variable("tree"), variable("input")), listOf(true, false))))
        val entry = binding("entry", lambda(listOf(parameter("input")), apply(variable("generic"),
            listOf(variable("worker"), delayedBox(variable("input")), variable("input")), listOf(true, true, false))))
        eachBackend(listOf(worker, generic, entry)) { backend, p ->
            repeat(30) { assertEquals(14L, run(p, "entry", 7L), backend) }
            compile(p.entryTarget("entry"))
            for (n in listOf(0L, 3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE))
                assertEquals((if (n == 0L) 9L else n) + n, run(p, "entry", n), backend)
            assertEquals(34L, count(p, "thunkEvaluations"), backend)
        }
    }

    @Test fun strictJoinPrefixDoesNotStrictifyTheReturnedFunctionSuffix() {
        val joinBody = unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra")))
        val join = binding("join", lambda(listOf(parameter("tree", data), parameter("extra")), joinBody, listOf(true, false))) +
            mapOf("joinValueArity" to 1, "joinResultRep" to closure)
        val region = local(listOf(join), apply(variable("join"), listOf(delayedBox(variable("input"))), listOf(true)))
        val entry = binding("entry", lambda(listOf(parameter("input")), apply(region, listOf(variable("input")))))
        eachBackend(listOf(entry)) { backend, p ->
            repeat(30) { assertEquals(14L, run(p, "entry", 7L), backend) }
            compile(p.entryTarget("entry"))
            assertEquals(9L, run(p, "entry", 0L), backend)
            assertEquals(0L, count(p, "thunkEvaluations"), backend)
            assertTrue(count(p, "localJoinTransfers") > 0, backend)
        }
    }

    @Test fun malformedEntryContractsFailAtLoad() {
        val rhs = lambda(listOf(parameter("input")), variable("input"), listOf(true))
        assertThrows(RuntimeFault::class.java) { CoreEntries.lambda(rhs.take(3) + mapOf("entryStrict" to listOf("strict"))) }
        assertThrows(RuntimeFault::class.java) { CoreEntries.lambda(rhs.take(3) + mapOf("entryStrict" to emptyList<Boolean>())) }
        assertThrows(RuntimeFault::class.java) { CoreEntries.binding(binding("bad", rhs) + mapOf("entryStrict" to listOf(false))) }
    }

    @Test fun asyncBytecodeCalleeDemandsUnusedStrictFormalsAndPapPrefixes() {
        val worker = binding("worker", lambda(listOf(parameter("ignored", data), parameter("answer")),
            variable("answer"), listOf(true, false)))
        val indirect = binding("indirect", lambda(listOf(parameter("fn", closure), parameter("tree", data), parameter("answer")),
            apply(variable("fn"), listOf(variable("tree"), variable("answer")), listOf(true, false))))
        val generic = binding("generic", lambda(listOf(parameter("input")),
            apply(variable("indirect"), listOf(variable("worker"), delayedBox(variable("input")), variable("input")),
                listOf(true, true, false))))
        val partial = binding("partial", lambda(listOf(parameter("input")),
            apply(variable("worker"), listOf(delayedBox(variable("input"))), listOf(true)), result = closure))
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = mapOf("bindings" to listOf(worker, indirect, generic, partial), "instrument" to true,
                    "constructors" to listOf(mapOf("id" to "Box", "name" to "Box", "arity" to 1,
                        "kind" to "boxed", "fieldReps" to listOf(listOf("IntRep")),
                        "strictFields" to listOf(false), "fieldLifted" to listOf(false))))
                val program = BytecodeProgram(language, module, true)
                assertEquals(7L, run(program, "generic", 7L))
                assertEquals(1L, count(program, "thunkEvaluations"), "Unused strict formal must still be demanded")
                val pap = run(program, "partial", 3_000_000_017L) as Closure
                assertEquals(1L, count(program, "thunkEvaluations"), "PAP construction remains lazy")
                assertEquals(5L, call(program, pap, 5L))
                assertEquals(2L, count(program, "thunkEvaluations"), "Saturation demands the stored prefix once")
                assertEquals(6L, call(program, pap, 6L))
                assertEquals(2L, count(program, "thunkEvaluations"), "The evaluated PAP prefix is shared")
                compile(program.entryTarget("worker"))
                assertEquals(11L, run(program, "generic", 11L))
            } finally { context.leave() }
        }
    }

    @Test fun megamorphicCallsEnforceAndShareStrictPapPrefixes() {
        val workers = (0..3).map { index -> binding("worker$index",
            lambda(listOf(parameter("tree", data), parameter("extra")),
                unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra"))), listOf(true, false))) }
        val partials = (0..3).map { index -> binding("partial$index", lambda(listOf(parameter("input")),
            apply(variable("worker$index"), listOf(delayedBox(variable("input"))), listOf(true)), result = closure)) }
        eachBackend(workers + partials) { backend, p ->
            val functions = (0..3).map { run(p, "partial$it", 3_000_000_000L + it) as Closure }
            assertEquals(0L, count(p, "thunkEvaluations"), backend)
            repeat(30) { round -> functions.forEachIndexed { index, fn ->
                assertEquals(3_000_000_000L + index + round, call(p, fn, round.toLong()), backend)
            } }
            assertTrue(count(p, "indirectCalls") > 0, "$backend must exercise the generic saturated path")
            assertEquals(4L, count(p, "thunkEvaluations"), backend)
            functions.forEach { compile(it.target) }
            functions.forEachIndexed { index, fn ->
                assertEquals(3_000_000_000L + index + Long.MAX_VALUE, call(p, fn, Long.MAX_VALUE), backend)
            }
            assertEquals(4L, count(p, "thunkEvaluations"), backend)
        }
    }
}
