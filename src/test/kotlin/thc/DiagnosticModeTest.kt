// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiagnosticModeTest {
    private fun request(diagnostic: Boolean, unsupported: List<Any?> = listOf("var", "Missing.libraryBody")): String {
        val argument = mapOf("id" to "n", "name" to "n", "lifted" to false)
        val body = listOf("case", listOf("var", "n"), "caseN", listOf(
            listOf("lit", listOf("int", "0"), emptyList<String>(), listOf("lit", "int", "41")),
            listOf("default", null, emptyList<String>(), unsupported)))
        val binding = mapOf("id" to "entry", "name" to "entry", "arity" to 1,
            "lifted" to true, "expr" to listOf("lam", listOf(argument), body))
        val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "DiagnosticTest",
            "constructors" to emptyList<Any>(), "bindings" to listOf(binding))
        return Json.stringify(mapOf("modules" to listOf(module), "entry" to "entry",
            "diagnosticUnsupported" to diagnostic))
    }
    @Test fun normalExecutionStillRejectsUnavailableCodeAtLoad() {
        executionContext().use { context ->
            val error = assertThrows(PolyglotException::class.java) { context.eval("thc", request(false)) }
            assertTrue(error.message.orEmpty().contains("Unresolved external binding"))
        }
    }
    @Test fun diagnosticBranchesCompileAndUnavailablePathsTrapExplicitly() {
        executionContext().use { context ->
            val fn = context.eval("thc", request(true))
            assertEquals(41L, fn.execute(0L).asLong())
            assertTrue(fn.invokeMember("compile").asBoolean())
            assertEquals(41L, fn.execute(0L).asLong())
            val before = Json.parse(fn.getMember("diagnostics").asString()) as Map<*, *>
            assertEquals("diagnostic-traps", before["unsupportedPolicy"])
            assertEquals(0L, before["unsupportedTraps"])
            assertTrue((before["deferredUnsupported"] as List<*>).isNotEmpty())
            val error = assertThrows(PolyglotException::class.java) { fn.execute(1L) }
            assertTrue(error.message.orEmpty().contains("Diagnostic unsupported path reached: Unresolved external binding Missing.libraryBody"))
            val after = Json.parse(fn.getMember("diagnostics").asString()) as Map<*, *>
            assertEquals(1L, after["unsupportedTraps"])
        }
    }
    @Test fun diagnosticPrimitiveGapsRemainExplicitAndMalformedCoreStillRejects() {
        val primitive = listOf("app", listOf("prim", "futurePrim#"),
            listOf(listOf("var", "n")), listOf(false))
        executionContext().use { context ->
            val fn = context.eval("thc", request(true, primitive))
            assertEquals(41L, fn.execute(0L).asLong())
            val error = assertThrows(PolyglotException::class.java) { fn.execute(1L) }
            assertTrue(error.message.orEmpty().contains("Unsupported primitive futurePrim#"))
            val malformed = listOf("app", listOf("prim", "+#"),
                listOf(listOf("var", "n")), emptyList<Boolean>())
            val bad = assertThrows(PolyglotException::class.java) { context.eval("thc", request(true, malformed)) }
            assertTrue(bad.message.orEmpty().contains("representation flag count mismatch"))
        }
    }
}
