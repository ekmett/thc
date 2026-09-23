@file:Suppress("UNCHECKED_CAST")
package thc

import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class Int64ConversionTest {
    private val root = File(System.getProperty("thc.projectRoot"))

    @Test fun realCorePreservesTypedLongArgumentsAndResults() {
        val module = Json.parse(File(root,
            "build/corpus/groups/int64-conversions/core/THC.Int64Conversions.json").readText()) as Map<String, Any?>
        val bindings = module["bindings"] as List<Map<String, Any?>>
        for ((name, argumentRep, resultRep) in listOf(
            Triple("toInt64", "IntRep", "Int64Rep"), Triple("fromInt64", "Int64Rep", "IntRep"))) {
            val lambda = bindings.single { it["name"] == name }["expr"] as List<Any?>
            assertEquals("lam", lambda[0])
            val parameter = (lambda[1] as List<Map<String, Any?>>).single()
            val parameterProof = parameter["rep"] as Map<String, Any?>
            val resultProof = (lambda[3] as Map<String, Any?>)["resultRep"] as Map<String, Any?>
            assertEquals("long", parameterProof["kind"])
            assertEquals("long", resultProof["kind"])
            assertEquals(listOf(argumentRep), parameterProof["primReps"])
            assertEquals(listOf(resultRep), resultProof["primReps"])
            for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                val function = context.eval("thc", Json.stringify(mapOf("modules" to listOf(module),
                    "entry" to name, "backend" to backend)))
                val values = listOf(0L, 1L, -1L, Int.MIN_VALUE.toLong() - 1, Int.MAX_VALUE.toLong() + 1,
                    Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE)
                values.forEach { assertEquals(it, function.execute(it).asLong()) }
                assertTrue(function.invokeMember("compile").asBoolean())
                fun compiled() = ((Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>)
                    ["compiledEntries"] as Number).toLong()
                val before = compiled()
                values.asReversed().forEach { assertEquals(it, function.execute(it).asLong()) }
                assertTrue(compiled() > before, "$backend $name installed code")
            }
        }
    }

    private fun request(backend: String, diagnostic: Boolean, body: List<Any?>): String {
        val parameter = mapOf("id" to "x", "type" to "Int64#", "lifted" to false, "coercion" to false)
        val entry = mapOf("id" to "entry", "name" to "entry", "arity" to 1, "lifted" to true,
            "expr" to listOf("lam", listOf(parameter), body))
        val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.Int64",
            "constructors" to emptyList<Any?>(), "bindings" to listOf(entry))
        return Json.stringify(mapOf("entry" to "entry", "backend" to backend,
            "diagnosticUnsupported" to diagnostic, "modules" to listOf(module)))
    }

    @Test fun int64LiteralsAndAlternativesRejectNoncanonicalOrOutOfRangeValues() {
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) {
            executionContext().use { context ->
                for (alternative in listOf(false, true)) {
                    fun body(value: String): List<Any?> = if (!alternative) listOf("lit", "int64", value)
                    else listOf("case", listOf("var", "x"), "scrutinee", listOf(
                        listOf("lit", listOf("int64", value), emptyList<String>(), listOf("lit", "int", "1")),
                        listOf("default", null, emptyList<String>(), listOf("lit", "int", "0"))))
                    for (value in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
                        val function = context.eval("thc", request(backend, diagnostic, body(value.toString())))
                        assertEquals(if (alternative) 1L else value, function.execute(value).asLong())
                        if (alternative) assertEquals(0L, function.execute(value xor 1).asLong())
                    }
                    for (value in listOf("-9223372036854775809", "9223372036854775808", "", "+1", "01", "-0", " 1", "1.0")) {
                        val error = assertThrows(PolyglotException::class.java) {
                            context.eval("thc", request(backend, diagnostic, body(value)))
                        }
                        assertTrue(error.message.orEmpty().contains("Invalid int64 literal"), error.message)
                    }
                }
                for (primitive in listOf("intToInt64#", "int64ToInt#")) for (arity in listOf(0, 2)) {
                    val body = listOf("app", listOf("prim", primitive),
                        List(arity) { listOf("var", "x") }, List(arity) { false })
                    val error = assertThrows(PolyglotException::class.java) {
                        context.eval("thc", request(backend, diagnostic, body))
                    }
                    assertTrue(error.message.orEmpty().contains("Primitive arity mismatch: $primitive"), error.message)
                }
            }
        }
    }
}
