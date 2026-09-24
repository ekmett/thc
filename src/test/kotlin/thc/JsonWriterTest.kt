// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class JsonWriterTest {
    @Test fun exactEscapingAndContainerFormatting() {
        val controls = (0..31).map(Int::toChar).joinToString("")
        val value = linkedMapOf<String, Any?>(
            "\"\\\n" to arrayOf<Any?>(controls, "λ😀\uD800", null, true, false, -42L, 1.25, 1.0e100),
            "empty" to emptyList<Any?>(), "object" to emptyMap<String, Any?>())
        val expected = "{\"\\\"\\\\\\n\":[\"" +
            "\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007\\u0008\\t\\n\\u000b\\u000c\\r" +
            "\\u000e\\u000f\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017\\u0018\\u0019\\u001a\\u001b\\u001c\\u001d\\u001e\\u001f\",\"λ😀\uD800\",null,true,false,-42,1.25,1.0E100],\"empty\":[],\"object\":{}}"
        assertEquals(expected, Json.stringify(value))
        assertEquals(Json.parse(expected), Json.parse(Json.stringify(value)))
    }

    @Test fun matchesPreviousTransportForNestedCoreShapedDocuments() {
        val random = Random(1931)
        fun tree(depth: Int): Any? = if (depth == 0) when (random.nextInt(5)) {
            0 -> null
            1 -> random.nextLong()
            2 -> random.nextBoolean()
            3 -> random.nextDouble()
            else -> "id:${random.nextInt()}\n\\\"\u0000λ"
        } else when (random.nextInt(3)) {
            0 -> List(random.nextInt(6)) { tree(depth - 1) }
            1 -> Array(random.nextInt(6)) { tree(depth - 1) }
            else -> (0 until random.nextInt(6)).associate { "key$it" to tree(depth - 1) }
        }
        repeat(100) {
            val document = tree(5)
            assertEquals(previous(document), Json.stringify(document), "document $it")
        }
        var deep: Any? = "payload".repeat(1024)
        repeat(100) { deep = mapOf("expr" to listOf("node", deep)) }
        assertEquals(previous(deep), Json.stringify(deep))
    }

    @Test fun traversesAnIterableOnceAndPreservesRejection() {
        var iterations = 0
        val values = Iterable { check(iterations++ == 0); listOf(1L, "two", null).iterator() }
        assertEquals("[1,\"two\",null]", Json.stringify(values))
        assertEquals(1, iterations)
        val keyFailure = assertThrows(IllegalArgumentException::class.java) { Json.stringify(mapOf(1 to "x")) }
        assertEquals("JSON object key must be a string", keyFailure.message)
        assertThrows(IllegalStateException::class.java) { Json.stringify(listOf(Any())) }
    }

    // Frozen transport reference: catches format changes as well as round-trip changes.
    private fun previous(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
            require(it.key is String) { "JSON object key must be a string" }
            previous(it.key) + ":" + previous(it.value)
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { previous(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { previous(it) }
        else -> error("Unsupported JSON value: ${value.javaClass.name}")
    }
}
