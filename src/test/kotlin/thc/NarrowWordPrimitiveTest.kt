package thc

import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.io.File

/** Narrow unsigned words use zero-extended Long carriers, never signed Byte/Short/Int values. */
class NarrowWordPrimitiveTest {
    private fun request(backend: String, body: List<Any?>, arity: Int,
                        diagnostic: Boolean = false): String {
        val parameters = List(arity) { index -> mapOf("id" to "x$index", "name" to "x$index",
            "type" to "Word#", "lifted" to false, "coercion" to false) }
        val entry = mapOf("id" to "entry", "name" to "entry", "lifted" to true,
            "arity" to arity, "expr" to listOf("lam", parameters, body))
        return Json.stringify(mapOf("entry" to "entry", "backend" to backend, "instrument" to true,
            "diagnosticUnsupported" to diagnostic, "modules" to listOf(mapOf("schema" to 1,
                "ghc" to "9.14.1", "module" to "Synthetic.NarrowWord", "constructors" to emptyList<Any?>(),
                "bindings" to listOf(entry)))))
    }
    private fun primitive(name: String, supplied: Int): List<Any?> = listOf("app", listOf("prim", name),
        List(supplied) { index -> listOf("var", "x$index") }, List(supplied) { false })
    private fun mask(width: Int) = BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE)
    private fun inputs(width: Int): List<Long> {
        val maximum = mask(width).toLong()
        return listOf(0L, 1L, 17L, maximum / 2, maximum / 2 + 1, maximum - 1, maximum)
    }

    @Test fun ordinaryNativeExamplesAgreeWithIndependentUnsignedArithmeticModel() {
        fun arithmetic(input: Long, width: Int): Long {
            val modulus = BigInteger.ONE.shiftLeft(width)
            val a = BigInteger.valueOf(input).mod(modulus)
            val b = BigInteger.valueOf(input / 257 + 11).mod(modulus)
            val wrapped = ((a + BigInteger.valueOf(17)) * (b + BigInteger.valueOf(3)) -
                BigInteger.valueOf(29)).mod(modulus)
            val flags = (if (a < b) 1 else 0) + (if (a == b) 2 else 0) + (if (wrapped <= a) 4 else 0)
            return (wrapped + (modulus + BigInteger.ONE) * BigInteger.valueOf(flags.toLong())).toLong()
        }
        fun records(input: Long): Long {
            var value = BigInteger.valueOf(input)
            val samples = List(8) {
                val fields = listOf(value.mod(BigInteger.ONE.shiftLeft(8)),
                    (value + BigInteger.valueOf(129)).mod(BigInteger.ONE.shiftLeft(16)),
                    (value * BigInteger.valueOf(65537) + BigInteger.ONE).mod(BigInteger.ONE.shiftLeft(32)))
                value = BigInteger.valueOf((value * BigInteger.valueOf(257) + BigInteger.valueOf(129)).toLong())
                fields
            }
            fun weighted(items: List<List<BigInteger>>, multiplier: Long, weights: List<Long>) =
                items.fold(BigInteger.ZERO) { acc, fields ->
                    acc * BigInteger.valueOf(multiplier) + fields.zip(weights).fold(BigInteger.ZERO) { sum, (field, weight) ->
                        sum + field * BigInteger.valueOf(weight)
                    }
                }
            return (weighted(samples, 17, listOf(3, 5, 7)) +
                weighted(samples.reversed(), 33, listOf(1, 11, 13))).toLong()
        }
        val root = File(System.getProperty("thc.projectRoot"))
        val rows = File(root, "build/corpus/oracle.tsv").readLines()
            .map { it.split('\t') }.filter { it[0].startsWith("narrow-words/") }
        val names = setOf("word8Arithmetic", "word16Arithmetic", "word32Arithmetic", "narrowWordRecordChecksum")
        assertEquals(names, rows.map { it[0].substringAfter('/') }.toSet())
        for ((entry, rawInput, rawExpected) in rows) {
            val input = rawInput.toLong()
            val expected = when (entry.substringAfter('/')) {
                "word8Arithmetic" -> arithmetic(input, 8)
                "word16Arithmetic" -> arithmetic(input, 16)
                "word32Arithmetic" -> arithmetic(input, 32)
                else -> records(input)
            }
            assertEquals(expected, rawExpected.toLong(), "Independent model $entry($input)")
        }
    }

    @Test fun conversionsTruncateAndZeroExtendAllThreeWidths() {
        val raw = listOf(0L, 1L, -1L, 127L, 128L, 255L, 256L, -129L, 32767L, 32768L,
            65535L, 65536L, -32769L, 2147483647L, 2147483648L, 4294967295L, 4294967296L,
            -2147483649L, Long.MIN_VALUE, Long.MAX_VALUE)
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            for (width in listOf(8, 16, 32)) for (name in listOf("wordToWord$width#", "word${width}ToWord#")) {
                val values = if (name.startsWith("wordTo")) raw else inputs(width)
                val fn = context.eval("thc", request(backend, primitive(name, 1), 1))
                repeat(2) { pass ->
                    if (pass == 1) assertTrue(fn.invokeMember("compile").asBoolean())
                    for (value in values) assertEquals(BigInteger.valueOf(value).and(mask(width)).toLong(),
                        fn.execute(value).asLong(), "$backend $name($value), pass $pass")
                }
            }
        }
    }

    @Test fun wrappingArithmeticMatchesIndependentBigIntegerResultsIncludingWord32ProductOverflow() {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            for (width in listOf(8, 16, 32)) for (operation in listOf("plus", "sub", "times")) {
                val name = "${operation}Word$width#"
                val fn = context.eval("thc", request(backend, primitive(name, 2), 2))
                repeat(2) { pass ->
                    if (pass == 1) assertTrue(fn.invokeMember("compile").asBoolean())
                    for (left in inputs(width)) for (right in inputs(width)) {
                        val x = BigInteger.valueOf(left); val y = BigInteger.valueOf(right)
                        val mathematical = when (operation) { "plus" -> x + y; "sub" -> x - y; else -> x * y }
                        assertEquals(mathematical.and(mask(width)).toLong(), fn.execute(left, right).asLong(),
                            "$backend $name($left, $right), pass $pass")
                    }
                }
            }
        }
    }

    @Test fun comparisonsKeepUnsignedOrderAcrossEachWidthsSignBitAndEquality() {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            for (width in listOf(8, 16, 32)) for (operation in listOf("lt", "le")) {
                val name = "${operation}Word$width#"
                val fn = context.eval("thc", request(backend, primitive(name, 2), 2))
                repeat(2) { pass ->
                    if (pass == 1) assertTrue(fn.invokeMember("compile").asBoolean())
                    for ((i, left) in inputs(width).withIndex()) for ((j, right) in inputs(width).withIndex()) {
                        val expected = if (i < j || operation == "le" && i == j) 1L else 0L
                        assertEquals(expected, fn.execute(left, right).asLong(), "$backend $name($left, $right)")
                    }
                }
            }
        }
    }

    @Test fun narrowLiteralsAndCaseAlternativesEnforceCanonicalUnsignedRangesEvenInDiagnosticMode() {
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) {
            executionContext().use { context ->
                for (width in listOf(8, 16, 32)) {
                    val maximum = mask(width).toLong()
                    fun body(text: String, alternative: Boolean): List<Any?> = if (!alternative)
                        listOf("lit", "word$width", text)
                    else listOf("case", listOf("var", "x0"), "scrutinee", listOf(
                        listOf("lit", listOf("word$width", text), emptyList<String>(), listOf("lit", "int", "99")),
                        listOf("default", null, emptyList<String>(), listOf("lit", "int", "17"))))
                    for (alternative in listOf(false, true)) {
                        for (text in listOf("0", maximum.toString())) {
                            val fn = context.eval("thc", request(backend, body(text, alternative), 1, diagnostic))
                            assertEquals(if (alternative) 99L else text.toLong(), fn.execute(text.toLong()).asLong())
                            if (alternative) assertEquals(17L, fn.execute(text.toLong() xor 1L).asLong())
                        }
                        for (text in listOf("-1", (maximum + 1).toString(), "+1", "01", "-0", "1.0", " 1", "",
                            "18446744073709551616")) {
                            val error = assertThrows(PolyglotException::class.java) {
                                context.eval("thc", request(backend, body(text, alternative), 1, diagnostic))
                            }
                            assertTrue(error.message.orEmpty().contains("Invalid word$width literal"), error.message)
                        }
                    }
                }
            }
        }
    }

    @Test fun allNewPrimitivesRejectWrongAritiesAtLoad() {
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) {
            executionContext().use { context ->
                for (width in listOf(8, 16, 32)) {
                    val names = listOf("wordToWord$width#" to 1, "word${width}ToWord#" to 1) +
                        listOf("plus", "sub", "times", "lt", "le").map { "${it}Word$width#" to 2 }
                    for ((name, arity) in names) for (supplied in listOf(arity - 1, arity + 1)) {
                        val error = assertThrows(PolyglotException::class.java) {
                            context.eval("thc", request(backend, primitive(name, supplied), supplied, diagnostic))
                        }
                        assertTrue(error.message.orEmpty().contains("Primitive arity mismatch: $name"), error.message)
                    }
                }
            }
        }
    }
}
