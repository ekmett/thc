// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class CoreRequestTest {
    @TempDir lateinit var directory: Path

    @Test fun embedsCompleteValidatedModulesWithoutChangingTheirMeaning() {
        val documents = listOf(
            """ {"schema":1,"bindings":[{"id":"cold","expr":["unsupported","kept"]}],"sourceCore":"λ\n\uD83D\uDE00","extra":null} """,
            """{"nested":{"escaped":"\u0078","values":[true,false,null,-9223372036854775808,9223372036854775807,1.25,1e100]},"constructors":[]}""")
        val paths = documents.mapIndexed { index, text -> directory.resolve("module$index.json").also { it.writeText(text) }.toString() }
        val request = CoreModules.request(paths, "entry\"\\\nλ", instrument = false,
            diagnosticUnsupported = true, backend = "bytecode", sourceNotesEnabled = false)
        assertEquals(mapOf("modules" to documents.map(Json::parse), "entry" to "entry\"\\\nλ",
            "instrument" to false, "diagnosticUnsupported" to true, "backend" to "bytecode",
            "sourceNotesEnabled" to false), Json.parse(request))
        // Every field survives, including unreachable definitions and decoded text.
        assertTrue(request.all { it.code < 128 })
    }

    @Test fun rejectsMalformedDocumentsBeforeEmbeddingAndCannotInjectAnotherModule() {
        val malformed = listOf(
            "", "[]", "null", "1", "true", "\"object\"", "{} {}", "{},{}", "{}],\"entry\":\"injected\",\"modules\":[{}",
            """{"nested":{"x":1,"\u0078":2}}""", """{"x":null,"x":1}""",
            """{"x":"\uQQQQ"}""", """{"x":"\u12"}""", """{"x":"\q"}""",
            "{\"x\":\"raw\nline\"}", """{"x":9223372036854775808}""", """{"x":1e999}""",
            """{"x":01}""", """{"x":1.}""", """{"x":1e+}""", """{"x":[1,]}""", """{"x":1,}""")
        val path = directory.resolve("invalid.json")
        for (document in malformed) {
            path.writeText(document)
            assertThrows(RuntimeException::class.java, {
                CoreModules.request(listOf(path.toString()), "entry")
            }, document)
        }
    }

    @Test fun validationMatchesTheMaterializingReaderForNestedDocuments() {
        val leaves = listOf("null", "true", "false", "-0", "-9223372036854775808", "9223372036854775807",
            "1.25e-100", "\"λ😀\\uD800\\b\\f\\n\\r\\t\\/\\\\\\\"\"", "[]", "{}")
        val malformed = listOf("[1,]", "{\"x\":1,\"x\":2}", "\"\\uZZZZ\"", "1e999", "9223372036854775808")
        for (leaf in leaves + malformed) for (depth in 0..5) {
            var document = leaf
            repeat(depth) { document = "{\"node\":[null,$document,{\"next\":$document}]}" }
            document = "{\"payload\":$document}"
            val materialized = runCatching { Json.parse(document) }
            val destination = StringBuilder("prefix")
            val validated = runCatching { Json.appendObjectDocument(destination, document) }
            assertEquals(materialized.isSuccess, validated.isSuccess, document)
            if (materialized.isSuccess) assertEquals(materialized.getOrThrow(), Json.parse(destination.substring(6)))
            else assertEquals("prefix", destination.toString(), "Invalid document appended a partial payload")
        }
    }

    @Test fun escapesLateUnicodeInStringsWhilePreservingLegacyNumericTokens() {
        val ascii = "a".repeat(1_000_000)
        val document = "{\"sourceCore\":\"$ascii λ😀\uD800\\\"quoted\\\\text\",\"λ\":\"value\",\"number\":1٢}"
        val destination = StringBuilder()
        Json.appendObjectDocument(destination, document)
        assertEquals(Json.parse(document), Json.parse(destination.toString()))
        assertFalse(destination.contains("λ"))
        assertFalse(destination.contains("😀"))
        assertTrue(destination.contains("\\ud800"))
        assertTrue(destination.contains("\"number\":1٢"))
    }
}
