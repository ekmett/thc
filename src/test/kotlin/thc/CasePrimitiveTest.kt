package thc

import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private typealias PrimitiveCaseExpression = List<Any?>

/** Primitive consumers must retain Core case semantics across profiling and compilation. */
class CasePrimitiveTest {
    private fun variable(id: String): PrimitiveCaseExpression = listOf("var", id)
    private fun integer(value: Long): PrimitiveCaseExpression = listOf("lit", "int", value.toString())
    private fun apply(function: PrimitiveCaseExpression, arguments: List<PrimitiveCaseExpression>,
                      lifted: List<Boolean>): PrimitiveCaseExpression = listOf("app", function, arguments, lifted)
    private fun primitive(name: String, vararg arguments: PrimitiveCaseExpression): PrimitiveCaseExpression =
        apply(listOf("prim", name), arguments.toList(), List(arguments.size) { false })
    private fun binding(id: String, expression: PrimitiveCaseExpression, lifted: Boolean = true,
                        arity: Int = 0): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "type" to if (lifted) "Synthetic" else "Int#",
        "lifted" to lifted, "arity" to arity, "expr" to expression)
    private fun lambda(body: PrimitiveCaseExpression): PrimitiveCaseExpression = listOf("lam", listOf(mapOf(
        "id" to "input", "name" to "input", "type" to "Int#", "lifted" to false, "coercion" to false)), body)
    private fun constructor(id: String, fields: Int, tag: Int): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "arity" to fields, "tag" to tag, "kind" to "boxed",
        "strictFields" to List(fields) { false }, "fieldLifted" to List(fields) { false },
        "fieldReps" to List(fields) { listOf("IntRep") })
    private fun construct(id: String, vararg fields: PrimitiveCaseExpression): PrimitiveCaseExpression =
        if (fields.isEmpty()) listOf("con", id, 0)
        else apply(listOf("con", id, fields.size), fields.toList(), List(fields.size) { false })
    private fun literalArm(value: Long, body: PrimitiveCaseExpression): List<Any?> =
        listOf("lit", listOf("int", value.toString()), emptyList<String>(), body)
    private fun defaultArm(body: PrimitiveCaseExpression): List<Any?> = listOf("default", null, emptyList<String>(), body)
    private fun dataArm(id: String, fields: List<String>, body: PrimitiveCaseExpression): List<Any?> =
        listOf("data", id, fields, body)
    private fun caseOf(scrutinee: PrimitiveCaseExpression, binder: String, vararg alternatives: List<Any?>): PrimitiveCaseExpression =
        listOf("case", scrutinee, binder, alternatives.toList())
    private fun request(body: PrimitiveCaseExpression, constructors: List<Map<String, Any?>> = emptyList(),
                        bindings: List<Map<String, Any?>> = emptyList()): String = Json.stringify(mapOf(
        "entry" to "entry", "backend" to "ast", "instrument" to true,
        "modules" to listOf(mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.PrimitiveCases",
            "constructors" to constructors, "bindings" to bindings + binding("entry", lambda(body), arity = 1)))))
    private fun diagnostics(function: Value): Map<*, *> = Json.parse(function.getMember("diagnostics").asString()) as Map<*, *>
    private fun count(function: Value, key: String): Long = (diagnostics(function)[key] as Number).toLong()
    private fun evaluations(function: Value, label: String): Long =
        ((diagnostics(function)["thunkEvaluationsByLabel"] as Map<*, *>)[label] as? Number)?.toLong() ?: 0L
    private fun compileWarmArm(function: Value, input: Long, expected: Long) {
        assertEquals("ast", diagnostics(function)["backend"])
        repeat(40) { assertEquals(expected, function.execute(input).asLong()) }
        assertTrue(function.invokeMember("compile").asBoolean())
        val before = count(function, "compiledEntries")
        assertEquals(expected, function.execute(input).asLong())
        assertTrue(count(function, "compiledEntries") > before, "The warm case arm must execute installed guest code")
    }

    @Test fun nestedLiteralAndDefaultCasesFeedPrimitiveOperandsThroughLetAndEvaluation() {
        val inner = caseOf(variable("input"), "original",
            literalArm(0, integer(Long.MIN_VALUE)), defaultArm(variable("original")))
        val selected = caseOf(inner, "selected",
            literalArm(Long.MIN_VALUE, primitive("+#", variable("selected"), integer(17))),
            defaultArm(primitive("-#", variable("selected"), integer(17))))
        val throughLet = listOf("let", false, listOf(binding("saved", selected, lifted = false)),
            caseOf(variable("saved"), "again", defaultArm(variable("again"))))
        val body = primitive("+#", throughLet, integer(1))
        fun expected(input: Long) = if (input == 0L || input == Long.MIN_VALUE) Long.MIN_VALUE + 18L else input - 16L
        executionContext().use { context ->
            val function = context.eval("thc", request(body))
            compileWarmArm(function, 0L, expected(0L))
            val inputs = listOf(1L, Long.MAX_VALUE, Long.MIN_VALUE, 3_000_000_000L, -3_000_000_000L, 128L, -129L, 0L)
            for (input in inputs) assertEquals(expected(input), function.execute(input).asLong(), "Cold/default input $input")
            assertTrue(function.invokeMember("compile").asBoolean())
            val before = count(function, "compiledEntries")
            for (input in inputs) assertEquals(expected(input), function.execute(input).asLong(), "Recompiled input $input")
            assertTrue(count(function, "compiledEntries") > before)
        }
    }

    @Test fun dataAndDefaultArmsPreserveFieldsAndWholeScrutineeBindingsInPrimitiveContext() {
        val constructors = listOf(constructor("Empty", 0, 1), constructor("Pair", 2, 2), constructor("Other", 0, 3))
        val value = caseOf(variable("input"), "choice", literalArm(0, construct("Empty")),
            literalArm(1, construct("Other")),
            defaultArm(construct("Pair", variable("input"), primitive("*#", variable("input"), integer(3)))))
        val rematched = caseOf(variable("whole"), "sameWhole", dataArm("Pair", listOf("againLeft", "againRight"),
            primitive("+#", primitive("+#", variable("left"), variable("right")), variable("againLeft"))))
        val selected = caseOf(value, "whole", dataArm("Empty", emptyList(), integer(99)),
            dataArm("Pair", listOf("left", "right"), rematched), defaultArm(integer(7)))
        val mask = 0x5555_5555_5555_5555L
        val body = primitive("xor#", selected, integer(mask))
        fun expected(input: Long) = (when (input) { 0L -> 99L; 1L -> 7L; else -> input * 5L }) xor mask
        executionContext().use { context ->
            val function = context.eval("thc", request(body, constructors))
            compileWarmArm(function, 0L, expected(0L))
            val inputs = listOf(1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_000L, -3_000_000_000L, 0L)
            for (input in inputs) assertEquals(expected(input), function.execute(input).asLong(), "Cold constructor/default input $input")
            assertTrue(function.invokeMember("compile").asBoolean())
            val before = count(function, "compiledEntries")
            for (input in inputs) assertEquals(expected(input), function.execute(input).asLong())
            assertTrue(count(function, "compiledEntries") > before)
        }
    }

    @Test fun primitiveCaseForcesSharedScrutineesOnceAndKeepsUnselectedFailureLazy() {
        // A lifted case RHS is a fresh shared thunk on each entry. Both later cases demand it.
        val delayed = caseOf(variable("input"), "delayedInput", defaultArm(construct("Box", variable("delayedInput"))))
        val first = caseOf(variable("shared"), "firstBox", dataArm("Box", listOf("firstValue"), variable("firstValue")))
        val second = caseOf(variable("shared"), "secondBox", dataArm("Box", listOf("secondValue"),
            primitive("+#", variable("secondValue"), integer(1))))
        val failure = caseOf(variable("bottom"), "failedBox",
            dataArm("Box", listOf("failedValue"), variable("failedValue")))
        val selected = caseOf(variable("input"), "selector", literalArm(-1, failure),
            defaultArm(primitive("+#", first, second)))
        val body = listOf("let", false, listOf(binding("shared", delayed)), primitive("*#", selected, integer(2)))
        executionContext().use { context ->
            val function = context.eval("thc", request(body, listOf(constructor("Box", 1, 1)),
                listOf(binding("bottom", variable("bottom")))))
            fun checkShared(input: Long, compiled: Boolean = false) {
                val compiledBefore = count(function, "compiledEntries")
                val entered = evaluations(function, "shared")
                val hits = count(function, "thunkHits")
                assertEquals(input * 4L + 2L, function.execute(input).asLong())
                assertEquals(entered + 1L, evaluations(function, "shared"), "One evaluation despite two scrutinee demands")
                assertEquals(hits + 1L, count(function, "thunkHits"), "The second case sees the same updated thunk")
                if (compiled) assertTrue(count(function, "compiledEntries") > compiledBefore,
                    "Repeated demand of a fresh shared thunk must keep entering installed code")
            }
            checkShared(0L)
            compileWarmArm(function, 0L, 2L)
            repeat(8) { checkShared(0L, compiled = true) }
            for (input in listOf(3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE, 0L)) checkShared(input, compiled = true)
            assertEquals(0L, count(function, "blackholes"))
            assertEquals(0L, evaluations(function, "bottom"), "The unselected failure must remain untouched")
            val sharedBeforeFailure = evaluations(function, "shared")
            repeat(2) {
                val failure = assertThrows(PolyglotException::class.java) { function.execute(-1L) }
                assertTrue(failure.message.orEmpty().contains("Blackhole"), failure.message)
            }
            assertEquals(1L, evaluations(function, "bottom"), "The selected failing CAF is entered only once")
            assertEquals(1L, count(function, "blackholes"))
            assertEquals(sharedBeforeFailure, evaluations(function, "shared"), "The failing arm must not demand the other arm's scrutinee")
            checkShared(7L)
        }
    }
}
