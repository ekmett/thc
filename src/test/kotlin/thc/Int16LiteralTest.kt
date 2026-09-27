// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Int16LiteralTest {
    private fun integer(rep: String) = mapOf("kind" to "long", "primReps" to listOf(rep), "evaluated" to true)
    private fun request(backend: String, body: List<Any?>, diagnostic: Boolean,
                        resultRep: String, inputRep: String = "IntRep"): String {
        val parameter = mapOf("id" to "x", "name" to "x", "type" to "${inputRep.removeSuffix("Rep")}#",
            "lifted" to false, "coercion" to false, "rep" to integer(inputRep))
        val entry = mapOf("id" to "entry", "name" to "entry", "lifted" to true, "arity" to 1,
            "expr" to listOf("lam", listOf(parameter), body, mapOf("resultRep" to integer(resultRep))))
        val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.Int16Literal",
            "constructors" to emptyList<Any?>(), "bindings" to listOf(entry))
        return Json.stringify(mapOf("entry" to "entry", "backend" to backend,
            "diagnosticUnsupported" to diagnostic, "modules" to listOf(module)))
    }

    @Test fun canonicalSigned16LiteralsAndCaseAlternativesEnforceRangeInBothLoadModes() {
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) executionContext().use { context ->
            fun body(text: String, alternative: Boolean): List<Any?> = if (!alternative) listOf("lit", "int16", text)
                else listOf("case", listOf("var", "x", mapOf("rep" to integer("Int16Rep"))), "scrutinee", listOf(
                    listOf("lit", listOf("int16", text), emptyList<String>(), listOf("lit", "int", "99")),
                    listOf("default", null, emptyList<String>(), listOf("lit", "int", "17"))))
            for (alternative in listOf(false, true)) {
                for (text in listOf("-32768", "-1", "0", "1", "32767")) {
                    val fn = context.eval("thc", request(backend, body(text, alternative), diagnostic,
                        if (alternative) "IntRep" else "Int16Rep", "Int16Rep"))
                    assertEquals(if (alternative) 99L else text.toLong(), fn.execute(text.toLong()).asLong())
                    if (alternative) assertEquals(17L, fn.execute(text.toLong() xor 1L).asLong())
                }
                for (text in listOf("-32769", "32768", "", "+1", "01", "-0", " 1", "1.0", "18446744073709551616")) {
                    val error = assertThrows(PolyglotException::class.java) {
                        context.eval("thc", request(backend, body(text, alternative), diagnostic,
                            if (alternative) "IntRep" else "Int16Rep", "Int16Rep"))
                    }
                    assertTrue(error.message.orEmpty().contains("Invalid int16 literal"), error.message)
                }
            }
        }
    }

    @Test fun intCarrierMetadataAliasesPreserveLiteralValuesButOtherCarriersFail() {
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) executionContext().use { context ->
            for ((kind, value) in listOf("int16" to "-32768", "word16" to "65535")) {
                val resultRep = if (kind == "int16") "Int16Rep" else "Word16Rep"
                for (rep in listOf("Int16Rep", "Word16Rep", "Int8Rep", "Word8Rep", "Int32Rep", "Word32Rep", "IntRep", "WordRep", "Int64Rep", "Word64Rep"))
                    for (carrier in listOf("long", "unknown")) {
                        val metadata = mapOf("rep" to mapOf("kind" to carrier, "primReps" to listOf(rep), "evaluated" to true))
                        val body = listOf("lit", kind, value, metadata)
                        if (carrier == "long" && rep in setOf("Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep")) {
                            assertEquals(value.toLong(), context.eval("thc", request(backend, body, diagnostic, resultRep)).execute(0L).asLong())
                        } else {
                            val error = assertThrows(PolyglotException::class.java) {
                                context.eval("thc", request(backend, body, diagnostic, resultRep))
                            }
                            assertTrue(error.message.orEmpty().contains("literal requires a scalar Int carrier"), error.message)
                        }
                    }
            }
        }
    }

    @Test fun intrinsicNarrowLiteralsRefineUnconstrainedButNotMalformedProofs() {
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) executionContext().use { context ->
            for (kind in listOf("int16", "word16")) {
                val resultRep = if (kind == "int16") "Int16Rep" else "Word16Rep"
                for (evaluated in listOf(false, true)) for (registers in listOf(emptyMap(), mapOf("primReps" to null))) {
                    val proof = mapOf("kind" to "unknown", "evaluated" to evaluated) + registers
                    assertEquals(1L, context.eval("thc", request(backend,
                        listOf("lit", kind, "1", mapOf("rep" to proof)), diagnostic, resultRep)).execute(0L).asLong())
                    for ((operation, result) in listOf("int16ToInt#" to "IntRep", "word16ToWord#" to "WordRep")) {
                        val body = listOf("app", listOf("prim", operation), listOf(listOf("lit", kind, "1", mapOf("rep" to proof))),
                            listOf(false), false, false, mapOf("rep" to mapOf("kind" to "long", "primReps" to listOf(result), "evaluated" to true)))
                        assertEquals(1L, context.eval("thc", request(backend, body, diagnostic, result)).execute(0L).asLong())
                    }
                }
                val malformed = listOf(emptyList<Any?>(), "unknown", mapOf("kind" to "unknown", "primReps" to null),
                    mapOf("kind" to "float", "primReps" to listOf("FloatRep"), "evaluated" to true),
                    mapOf("kind" to "double", "primReps" to listOf("DoubleRep"), "evaluated" to true),
                    mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true),
                    mapOf("kind" to "unknown", "primReps" to null, "evaluated" to "false"),
                    mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to false),
                    mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to false,
                        "aggregate" to "unboxed-tuple", "components" to emptyList<Any?>()),
                    mapOf("kind" to "unknown", "primReps" to listOf("WordRep"), "evaluated" to true,
                        "aggregate" to "unboxed-sum", "tagSlot" to 0,
                        "alternativeSlots" to listOf(emptyList<Int>(), emptyList<Int>()),
                        "alternatives" to List(2) { mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true) }))
                for (proof in malformed) assertThrows(PolyglotException::class.java, {
                    context.eval("thc", request(backend, listOf("lit", kind, "1", mapOf("rep" to proof)), diagnostic, resultRep))
                }, "$backend/$kind/diagnostic=$diagnostic/proof=$proof")
            }
        }
    }
}
