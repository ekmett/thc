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

    @Test fun bitCountsCoverEveryPositionNeighborsAndAlternatingPatterns() {
        val alternating = 0x5555_5555_5555_5555L
        val inputs = (0 until 64).flatMap { bit ->
            val single = 1L shl bit
            listOf(single - 1L, single, single + 1L)
        }.toSet() + setOf(0L, -1L, alternating, alternating.inv())
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            for (name in listOf("clz#", "ctz#", "popCnt#")) {
                val function = context.eval("thc", request(backend, name, 1))
                for (input in inputs) {
                    // Enumerate set positions independently of the runtime's Long intrinsics.
                    val setBits = (0 until 64).filter { bit -> ((input ushr bit) and 1L) != 0L }
                    val expected = when (name) {
                        "clz#" -> setBits.lastOrNull()?.let { 63 - it } ?: 64
                        "ctz#" -> setBits.firstOrNull() ?: 64
                        else -> setBits.size
                    }.toLong()
                    assertEquals(expected, function.execute(input).asLong(), "$backend $name($input)")
                }
            }
        }
    }

    @Test fun comparisonsFollowUnsignedOrderIncludingEquality() {
        // This literal order is the unsigned order, independent of the runtime comparator.
        val alternating = 0x5555_5555_5555_5555L
        val ordered = listOf(0L, 1L, alternating, Long.MAX_VALUE, Long.MIN_VALUE, alternating.inv(), -1L)
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            for (name in listOf("ltWord#", "leWord#")) {
                val function = context.eval("thc", request(backend, name, 2))
                for ((leftIndex, left) in ordered.withIndex()) {
                    for ((rightIndex, right) in ordered.withIndex()) {
                        val expected = if (leftIndex < rightIndex || name == "leWord#" && leftIndex == rightIndex) 1L else 0L
                        assertEquals(expected, function.execute(left, right).asLong(), "$backend $name($left, $right)")
                    }
                }
            }
        }
    }

    @Test fun wrongAritiesAreRejectedAtLoadEvenInDiagnosticMode() {
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) {
            executionContext().use { context ->
                for ((name, arity) in listOf("clz#" to 1, "ctz#" to 1, "popCnt#" to 1,
                                            "ltWord#" to 2, "leWord#" to 2)) {
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
