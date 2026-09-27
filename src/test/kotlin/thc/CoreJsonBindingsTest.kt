// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc

import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*

class CoreJsonBindingsTest {
    private val formal = mapOf("id" to "x", "name" to "x", "type" to "Int#",
        "lifted" to false, "coercion" to false)
    private fun lambda(body: Any? = listOf("var", "x"), metadata: Map<String, Any?> = emptyMap()) =
        listOf("lam", listOf(formal), body, metadata)
    private fun binding(id: String, body: Any? = lambda(), extra: Map<String, Any?> = emptyMap()) =
        mapOf("id" to id, "name" to id, "type" to "Synthetic", "lifted" to true,
            "arity" to 1, "expr" to body) + extra
    private fun source(value: Any?) = CoreJsonIndex.fromBytes(Json.stringify(value).toByteArray())
    private fun bothBackends(action: (Language, String, Boolean) -> Unit) {
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) {
            executionContext().use { context ->
                context.initialize("thc"); context.enter()
                try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null), backend, async) }
                finally { context.leave() }
            }
        }
    }
    private fun program(language: Language, backend: String, async: Boolean, bindings: List<Map<String, Any?>>): ExecutableProgram {
        val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.JsonBinding",
            "sourceNotesEnabled" to false, "instrument" to true, "bindings" to bindings,
            "constructors" to emptyList<Any>())
        return if (backend == "ast") Program(language, module, async) else BytecodeProgram(language, module, async)
    }
    private fun count(program: ExecutableProgram, name: String) = (program.diagnostics().getValue(name) as Number).toLong()
    private fun invoke(program: ExecutableProgram, value: Any?, vararg arguments: Any?): Any? =
        Calls.target(program.hostEntryTarget(arguments.size), arrayOf(value, arguments))

    @Test fun jsonProjectionDoesNotLowerUnusedBodiesAtEitherSize() = bothBackends { language, backend, async ->
        for (size in listOf(32, 64)) source(List(size) { binding("f$it") }).use { input ->
            val adapter = CoreJsonBindings(false)
            val bindings = adapter.bindings(input.root)
            val program = program(language, backend, async, bindings)
            val before = adapter.statistics()
            assertEquals(size.toLong(), before.bindingHeaders)
            assertEquals(0L, before.bodyMaterializations)
            assertEquals(0L, before.expressionViews)
            assertEquals(size * 2L, before.summaryExpressionsVisited, "the policy scan is real eager work")
            assertEquals(0L, count(program, "initializedBindingCount"))
            assertEquals(0L, count(program, "loweredRootCount"))
            assertEquals(0L, count(program, "hostEntryRootCount"))
            val first = program.entryValue("f0")
            assertEquals(1L, adapter.statistics().bodyMaterializations)
            assertEquals(1L, count(program, "initializedBindingCount"))
            assertEquals(1L, count(program, "loweredRootCount"))
            val afterFirst = input.statistics().decodedSpanCount
            assertSame(first, program.entryValue("f0"))
            assertEquals(afterFirst, input.statistics().decodedSpanCount)
            val second = program.entryValue("f${size - 1}")
            assertEquals(2L, adapter.statistics().bodyMaterializations)
            assertEquals(2L, count(program, "loweredRootCount"))
            assertEquals(Long.MIN_VALUE, invoke(program, first, Long.MIN_VALUE))
            assertEquals(Long.MAX_VALUE, invoke(program, second, Long.MAX_VALUE))
            assertEquals(2L, adapter.statistics().bodyMaterializations)
        }
    }

    @Test fun diagnosticsAreSkippedWithoutConcealingRequiredProofFields() {
        val huge = "unused ".repeat(32768)
        val foreign = mapOf("schema" to 1, "target" to mapOf("symbol" to "f"), "unexpected" to true)
        val metadata = mapOf("entryStrict" to listOf(false), "entryStrictSource" to huge,
            "source" to huge, "sourceNotes" to listOf(huge), "foreignCall" to foreign,
            "exceptionPayload" to mapOf("schema" to 1, "type" to "T", "unexpected" to true))
        source(binding("f", lambda(metadata = metadata), mapOf("info" to mapOf("strictness" to huge),
            "entryStrictSource" to huge, "source" to huge))).use { input ->
            val projected = CoreJsonBindings(false).binding(input.root)
            assertFalse(projected.containsKey("info"))
            assertFalse(projected.containsKey("entryStrictSource"))
            assertFalse(projected.containsKey("source"))
            val body = projected["expr"] as CoreBindingBody
            val meta = body[3] as Map<String, Any?>
            assertEquals(setOf("entryStrict", "foreignCall", "exceptionPayload"), meta.keys)
            assertEquals(setOf("schema", "target", "unexpected"), (meta["foreignCall"] as Map<*, *>).keys)
            assertEquals(setOf("schema", "type", "unexpected"), (meta["exceptionPayload"] as Map<*, *>).keys)
            assertEquals(0L, body.decodeAttempts())
            assertTrue(input.statistics().decodedByteCount < 512,
                "neither huge diagnostic values nor the complete body should be parsed")
            assertEquals(huge, (input.validateDocument() as Map<*, *>)["source"], "inspection retains original JSON")
        }
    }

    @Test fun canonicalConsumedStringsAndVectorsDoNotMergeOccurrenceEvidence() {
        val reps = { evaluated: Boolean -> mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to evaluated) }
        source(listOf(binding("a", extra = mapOf("rep" to reps(false), "entryStrict" to listOf(false))),
            binding("b", extra = mapOf("rep" to reps(true), "entryStrict" to listOf(false))))).use { input ->
            val bindings = CoreJsonBindings(false).bindings(input.root)
            assertSame(bindings[0]["type"], bindings[1]["type"])
            assertSame(bindings[0]["entryStrict"], bindings[1]["entryStrict"])
            val first = bindings[0]["rep"] as Map<*, *>
            val second = bindings[1]["rep"] as Map<*, *>
            assertNotSame(first, second)
            assertSame(first["kind"], second["kind"])
            assertSame(first["primReps"], second["primReps"])
            assertEquals(false, first["evaluated"])
            assertEquals(true, second["evaluated"])
            assertThrows(UnsupportedOperationException::class.java) {
                (first["primReps"] as MutableList<Any?>).add("WordRep")
            }
        }
    }

    @Test fun delimitedSummaryTraversesBodiesButNotInspectionMetadata() {
        val fake = mapOf("info" to listOf("prim", "control0#"), "sourceNotes" to listOf("prim", "prompt#"))
        val nested = listOf("case", listOf("var", "x"), "s", listOf(
            listOf("default", null, emptyList<String>(), listOf("let", false, listOf(
                binding("inner", listOf("prim", "control0#"))), listOf("var", "inner")))))
        source(listOf(binding("plain", lambda(), fake), binding("control", lambda(nested)))).use { input ->
            val bindings = CoreJsonBindings(false).bindings(input.root)
            assertFalse((bindings[0]["expr"] as CoreBindingBody).header.containsDelimitedControl)
            assertTrue((bindings[1]["expr"] as CoreBindingBody).header.containsDelimitedControl)
            assertEquals(0L, (bindings[1]["expr"] as CoreBindingBody).decodeAttempts())
        }
    }

    @Test fun malformedColdBodyFailsOnlyOnDemandAndMemoizesItsError() = bothBackends { language, backend, async ->
        val raw = Json.stringify(listOf(binding("good"), binding("bad", lambda(
            listOf("var", "x", mapOf("rep" to mapOf("kind" to "INVALID_NUMBER")))))))
            .replace("\"INVALID_NUMBER\"", "1e999")
        CoreJsonIndex.fromBytes(raw.toByteArray()).use { input ->
            val adapter = CoreJsonBindings(false)
            val program = program(language, backend, async, adapter.bindings(input.root))
            assertEquals(0L, adapter.statistics().bodyMaterializations)
            assertEquals(19L, invoke(program, program.entryValue("good"), 19L))
            val failure = assertThrows(Exception::class.java) { program.entryValue("bad") }
            val decoded = input.statistics().decodedSpanCount
            assertSame(failure, assertThrows(Exception::class.java) { program.entryValue("bad") })
            assertEquals(decoded, input.statistics().decodedSpanCount)
            assertEquals(2L, adapter.statistics().bodyMaterializations)
            assertEquals(1L, count(program, "initializedBindingCount"))
        }
    }

    @Test fun exactKeysDuplicateKeysAndDisabledOptimizationProofsStillReject() {
        val app = listOf("app", listOf("prim", "raise#"), listOf(listOf("var", "x")), listOf(true), false, false,
            mapOf("exceptionPayload" to mapOf("schema" to 1, "type" to CoreExceptionPayload.TYPE, "extra" to 0),
                "callDemand" to mapOf("arity" to 1, "strictArgs" to listOf("not Boolean"))))
        source(binding("f", lambda(app))).use { input ->
            val outer = CoreJsonBindings(false).binding(input.root)["expr"] as CoreBindingBody
            val body = outer[2] as List<Any?>
            assertThrows(RuntimeFault::class.java) { CoreExceptionPayload.validate(body) }
            assertThrows(RuntimeFault::class.java) { CoreCallDemands.lowerApplication(body, false) }
        }
        CoreJsonIndex.fromBytes("{\"id\":\"a\",\"id\":\"b\",\"expr\":[\"void\"]}".toByteArray()).use { input ->
            val projected = CoreJsonBindings(false).binding(input.root)
            val failure = assertThrows(IllegalArgumentException::class.java) { projected["id"] }
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { projected["id"] })
        }
    }

    @Test fun closeKeepsDecodedScalarsButPreventsUnpreparedBodyAdmission() {
        val input = source(binding("f", extra = mapOf("source" to "source-id")))
        val projected = CoreJsonBindings(true).binding(input.root)
        val name = projected["name"]
        assertEquals("source-id", projected["source"])
        val body = projected["expr"] as CoreBindingBody
        input.close()
        assertSame(name, projected["name"])
        val failure = assertThrows(IllegalStateException::class.java) { body.materialize() }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { body.materialize() })
        assertEquals("lam", body[0], "owned shallow header survives source closure")
        assertEquals(1L, body.decodeAttempts())
    }
}
