package thc

import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Word primitives preserve all 64 bits; malformed applications remain load errors. */
class WordPrimitiveTest {
    private fun request(backend: String, name: String, arity: Int, supplied: Int = arity,
                        diagnostic: Boolean = false): String {
        val parameters = List(arity) { index -> mapOf(
            "id" to "x$index", "name" to "x$index", "type" to "Word#",
            "lifted" to false, "coercion" to false) }
        val operands = List(supplied) { index ->
            if (index < arity) listOf("var", "x$index") else listOf("lit", "word", "0")
        }
        val body = listOf("app", listOf("prim", name), operands, List(supplied) { false })
        val entry = mapOf("id" to "entry", "name" to "entry", "type" to "Synthetic",
            "lifted" to true, "arity" to arity, "expr" to listOf("lam", parameters, body))
        return Json.stringify(mapOf("entry" to "entry", "backend" to backend,
            "diagnosticUnsupported" to diagnostic, "modules" to listOf(mapOf(
                "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.WordPrimitives",
                "constructors" to emptyList<Any?>(), "bindings" to listOf(entry)))))
    }

    @Test fun leadingZerosIncludesZeroAndTheUnsignedSignBoundary() {
        val cases = listOf(0L to 64L, 1L to 63L, 2L to 62L, (1L shl 32) to 31L,
            Long.MAX_VALUE to 1L, Long.MIN_VALUE to 0L, (Long.MIN_VALUE + 1L) to 0L, -1L to 0L)
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val function = context.eval("thc", request(backend, "clz#", 1))
            for ((input, expected) in cases) {
                assertEquals(expected, function.execute(input).asLong(), "$backend clz#($input)")
            }
        }
    }

    @Test fun lessThanFollowsUnsignedOrderIncludingEquality() {
        // This literal order is the unsigned order, independent of the runtime comparator.
        val ordered = listOf(0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE, -1L)
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val function = context.eval("thc", request(backend, "ltWord#", 2))
            for ((leftIndex, left) in ordered.withIndex()) {
                for ((rightIndex, right) in ordered.withIndex()) {
                    val expected = if (leftIndex < rightIndex) 1L else 0L
                    assertEquals(expected, function.execute(left, right).asLong(), "$backend ltWord#($left, $right)")
                }
            }
        }
    }

    @Test fun wrongAritiesAreRejectedAtLoadEvenInDiagnosticMode() {
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) {
            executionContext().use { context ->
                for ((name, arity) in listOf("clz#" to 1, "ltWord#" to 2)) {
                    for (supplied in listOf(arity - 1, arity + 1)) {
                        val failure = assertThrows(PolyglotException::class.java) {
                            context.eval("thc", request(backend, name, arity, supplied, diagnostic))
                        }
                        assertTrue(failure.message.orEmpty().contains("Primitive arity mismatch: $name"),
                            "$backend diagnostic=$diagnostic $name/$supplied: ${failure.message}")
                    }
                }
            }
        }
    }
}
