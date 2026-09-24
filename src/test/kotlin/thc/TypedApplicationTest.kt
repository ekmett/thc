// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private typealias ApplicationCore = List<Any?>

/** Typed expression paths remain correct across the Object-valued guest call boundary. */
class TypedApplicationTest {
    private fun variable(id: String): ApplicationCore = listOf("var", id)
    private fun integer(value: Long): ApplicationCore = listOf("lit", "int", value.toString())
    private fun parameter(id: String, lifted: Boolean = false): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "type" to if (lifted) "a" else "Int#",
        "lifted" to lifted, "coercion" to false)
    private fun lambda(parameters: List<Map<String, Any?>>, body: ApplicationCore): ApplicationCore =
        listOf("lam", parameters, body)
    private fun lambda(id: String, body: ApplicationCore): ApplicationCore = lambda(listOf(parameter(id)), body)
    private fun apply(function: ApplicationCore, arguments: List<ApplicationCore>,
                      lifted: List<Boolean>, knownWhnf: Boolean = false): ApplicationCore =
        if (knownWhnf) listOf("app", function, arguments, lifted, true, true)
        else listOf("app", function, arguments, lifted)
    private fun primitive(name: String, vararg arguments: ApplicationCore): ApplicationCore =
        apply(listOf("prim", name), arguments.toList(), List(arguments.size) { false })
    private fun binding(id: String, expression: ApplicationCore, arity: Int = 0,
                        lifted: Boolean = true): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "type" to if (lifted) "Synthetic" else "Int#",
        "lifted" to lifted, "arity" to arity, "expr" to expression)
    private fun literalArm(value: Long, body: ApplicationCore): List<Any?> =
        listOf("lit", listOf("int", value.toString()), emptyList<String>(), body)
    private fun defaultArm(body: ApplicationCore): List<Any?> = listOf("default", null, emptyList<String>(), body)
    private fun caseOf(scrutinee: ApplicationCore, name: String, vararg alternatives: List<Any?>): ApplicationCore =
        listOf("case", scrutinee, name, alternatives.toList())
    private fun request(body: ApplicationCore, bindings: List<Map<String, Any?>> = emptyList(),
                        constructors: List<Map<String, Any?>> = emptyList()): String = Json.stringify(mapOf(
        "entry" to "entry", "backend" to "ast", "instrument" to true,
        "modules" to listOf(mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.TypedApplications",
            "constructors" to constructors, "bindings" to bindings + binding("entry", lambda("input", body), 1)))))
    private fun count(function: Value, name: String): Long =
        ((Json.parse(function.getMember("diagnostics").asString()) as Map<*, *>)[name] as Number).toLong()
    private fun warmAndCompile(function: Value, input: Long, expected: Long) {
        repeat(40) { assertEquals(expected, function.execute(input).asLong()) }
        assertTrue(function.invokeMember("compile").asBoolean())
        val before = count(function, "compiledEntries")
        assertEquals(expected, function.execute(input).asLong())
        assertTrue(count(function, "compiledEntries") > before)
    }
    private val wideInputs = listOf(1L, -1L, 3_000_000_000L, -3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE, 128L, 0L)

    @Test fun closedAndCapturedLambdasForwardNumericCaseAndLetResultsToPrimitiveConsumers() {
        val directBody = caseOf(variable("direct"), "directValue",
            literalArm(0, integer(Long.MIN_VALUE)), defaultArm(variable("directValue")))
        val direct = apply(lambda("direct", directBody), listOf(variable("input")), listOf(false))
        val selected = caseOf(variable("delta"), "deltaValue",
            literalArm(0, primitive("+#", variable("input"), integer(17))),
            defaultArm(primitive("+#", variable("input"), variable("deltaValue"))))
        val capturedBody = listOf("let", false, listOf(binding("saved", selected, lifted = false)),
            caseOf(variable("saved"), "result", defaultArm(variable("result"))))
        val captured = binding("captured", lambda("delta", capturedBody), 1)
        val call = apply(variable("captured"), listOf(variable("input")), listOf(false))
        val body = listOf("let", false, listOf(captured), primitive("+#", direct, call))
        fun expected(input: Long): Long = if (input == 0L) Long.MIN_VALUE + 17L else input * 3L
        executionContext().use { context ->
            val function = context.eval("thc", request(body))
            warmAndCompile(function, 0L, expected(0L))
            for (input in wideInputs) assertEquals(expected(input), function.execute(input).asLong(), "Cold arm/capture $input")
            assertTrue(function.invokeMember("compile").asBoolean())
            val before = count(function, "compiledEntries")
            for (input in wideInputs.reversed()) assertEquals(expected(input), function.execute(input).asLong(), "Fresh capture $input")
            assertTrue(count(function, "compiledEntries") > before)
        }
    }

    @Test fun partialAndOverapplicationReachTheNumericResultAfterAnIntermediateCapturedFunction() {
        val numeric = caseOf(variable("extra"), "extraValue",
            literalArm(0, primitive("+#", variable("base"), variable("adjustment"))),
            defaultArm(primitive("+#", primitive("-#", variable("base"), variable("adjustment")), variable("extraValue"))))
        val maker = binding("maker", lambda(listOf(parameter("base"), parameter("adjustment")), lambda("extra", numeric)), 2)
        // One argument leaves a PAP; two later arguments saturate it and apply the returned closure.
        val partial = apply(variable("maker"), listOf(variable("input")), listOf(false), knownWhnf = true)
        val over = apply(variable("partial"), listOf(integer(1), variable("input")), listOf(false, false))
        val body = listOf("let", false, listOf(binding("partial", partial)), primitive("+#", over, integer(7)))
        fun expected(input: Long): Long = if (input == 0L) 8L else input * 2L + 6L
        executionContext().use { context ->
            val function = context.eval("thc", request(body, listOf(maker)))
            warmAndCompile(function, 0L, expected(0L))
            assertTrue(count(function, "papAllocations") > 0L, "The test must exercise an actual supplied prefix")
            for (input in wideInputs) assertEquals(expected(input), function.execute(input).asLong(), "Surplus argument $input")
            assertTrue(function.invokeMember("compile").asBoolean())
            val before = count(function, "compiledEntries")
            for (input in wideInputs.reversed()) assertEquals(expected(input), function.execute(input).asLong(), "Preserved prefix/captures $input")
            assertTrue(count(function, "compiledEntries") > before)
        }
    }

    @Test fun aPolymorphicLiftedResultCanChangeFromDataToClosureAfterCompilation() {
        // Erasing two different instantiations of id :: forall a. a -> a is legitimate:
        // both Box and a function are lifted, while the final result remains Int#.
        val identity = binding("identity", lambda(listOf(parameter("value", lifted = true)), variable("value")), 1)
        val box = mapOf("id" to "Box", "name" to "Box", "arity" to 1, "tag" to 1, "kind" to "boxed",
            "strictFields" to listOf(false), "fieldLifted" to listOf(false), "fieldReps" to listOf(listOf("IntRep")))
        val boxed = apply(listOf("con", "Box", 1), listOf(variable("input")), listOf(false))
        val throughIdentity = apply(variable("identity"), listOf(boxed), listOf(true))
        val unboxed = caseOf(throughIdentity, "box", listOf("data", "Box", listOf("payload"),
            primitive("+#", variable("payload"), integer(11))))
        val captured = lambda("argument", primitive("+#", variable("input"), variable("argument")))
        val returnedFunction = apply(variable("identity"), listOf(captured), listOf(true))
        val applied = apply(returnedFunction, listOf(variable("input")), listOf(false))
        val selected = caseOf(variable("input"), "choice", literalArm(0, unboxed), defaultArm(applied))
        val body = primitive("+#", selected, integer(1))
        fun expected(input: Long): Long = if (input == 0L) 12L else input * 2L + 1L
        executionContext().use { context ->
            val function = context.eval("thc", request(body, listOf(identity), listOf(box)))
            warmAndCompile(function, 0L, expected(0L))
            for (input in wideInputs) assertEquals(expected(input), function.execute(input).asLong(), "Cold result category $input")
            assertTrue(function.invokeMember("compile").asBoolean())
            val before = count(function, "compiledEntries")
            repeat(4) {
                assertEquals(12L, function.execute(0L).asLong())
                assertEquals(expected(3_000_000_000L + it), function.execute(3_000_000_000L + it).asLong())
            }
            assertTrue(count(function, "compiledEntries") > before)
        }
    }
}
