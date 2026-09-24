// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc

import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.CALL_DEMANDS_PROPERTY
import java.io.File

/** Original GHC demand signatures license individual calls without upgrading ordinary function entries. */
class RealCoreCallDemandTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun exported(): Map<String, Any?> =
        Json.parse(File(root, "build/core/DemandAudit.json").readText()) as Map<String, Any?>
    private fun definitions(module: Map<String, Any?>) =
        (module["bindings"] as List<Map<String, Any?>>).associateBy { it["name"] as String }
    private fun applications(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::applications)
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as List<Any?>) else emptyList()) +
            value.flatMap(::applications)
        else -> emptyList()
    }
    private fun call(binding: Map<String, Any?>, name: String): List<Any?> = applications(binding).single {
        val head = it[1] as List<Any?>
        head.firstOrNull() == "var" && (head[1] as String).endsWith(".$name")
    }
    private fun demand(app: List<Any?>): Map<String, Any?> =
        (app[6] as Map<String, Any?>)["callDemand"] as Map<String, Any?>
    private fun marks(app: List<Any?>): List<Boolean> = demand(app)["strictArgs"] as List<Boolean>
    private fun count(function: Value, name: String): Long =
        ((Json.parse(function.getMember("diagnostics").asString()) as Map<*, *>)[name] as Number).toLong()
    private fun treeResult(n: Long) = if (n <= 0L) 0L else n + 7L
    private fun withCallDemands(action: () -> Unit) {
        val previous = System.getProperty(CALL_DEMANDS_PROPERTY)
        try {
            System.setProperty(CALL_DEMANDS_PROPERTY, "true")
            action()
        } finally {
            if (previous == null) System.clearProperty(CALL_DEMANDS_PROPERTY)
            else System.setProperty(CALL_DEMANDS_PROPERTY, previous)
        }
    }

    @Test fun exportedCallDemandIsSeparateFromWhnfEntryContractsAndSpeculation() {
        val bindings = definitions(exported())
        val strict = bindings.getValue("strictTree")
        assertEquals(listOf(false), strict["entryStrict"])
        val formal = ((strict["expr"] as List<Any?>)[1] as List<Map<String, Any?>>).single()
        assertEquals(false, (formal["rep"] as Map<String, Any?>)["evaluated"])
        val ordinary = call(bindings.getValue("ordinaryEntry"), "strictTree")
        assertEquals(1, (demand(ordinary)["arity"] as Number).toInt())
        assertEquals(listOf(true), marks(ordinary))
        val producer = call(bindings.getValue("ordinaryEntry"), "makeTree")
        assertEquals(false, producer[5], "Caller demand must work for an operand that cannot be speculated")
        assertEquals(listOf(false), marks(call(bindings.getValue("lazyBarrier"), "strictTree")))
        assertEquals(listOf(false), marks(call(bindings.getValue("absentEntry"), "ignore")))
        val partial = call(bindings.getValue("polyFunctionEntry"), "strictPair")
        assertEquals(2, (demand(partial)["arity"] as Number).toInt())
        assertEquals(listOf(false), marks(partial), "The signature's arity must survive an undersaturated application")
    }

    @Test fun genuineOrdinaryPolymorphicLazyAndPartialCallsSurviveCompilation() = withCallDemands {
        val module = exported()
        val entries = listOf("ordinaryEntry", "polyDataEntry", "polyFunctionEntry", "absentEntry", "lazyBarrier", "bottomPAPEntry")
        for (backend in listOf("ast", "bytecode")) for (entry in entries) executionContext().use { context ->
            val function = context.eval("thc", Json.stringify(mapOf("modules" to listOf(module),
                "entry" to entry, "backend" to backend, "instrument" to true)))
            fun check(n: Long) {
                val expected = if (entry == "ordinaryEntry" || entry == "lazyBarrier") treeResult(n) else 41L
                assertEquals(expected, function.execute(n).asLong(), "$backend $entry($n)")
            }
            repeat(12) { check(7L) }
            assertTrue(function.invokeMember("compile").asBoolean(), "$backend $entry compiled")
            val compiled = count(function, "compiledEntries")
            check(11L)
            assertTrue(count(function, "compiledEntries") > compiled, "$backend $entry ran compiled code")
            for (n in listOf(0L, -1L, 3_000_000_017L, -7_000_000_003L, Long.MIN_VALUE, Long.MAX_VALUE)) check(n)
            assertEquals(0L, count(function, "blackholes"), "$backend $entry")
            assertEquals(0L, count(function, "unsupportedTraps"), "$backend $entry")
            if (entry == "lazyBarrier") assertTrue(count(function, "thunkEvaluations") > 0,
                "$backend lazy wrapper prevents the caller from evaluating its operand directly")
            else assertEquals(0L, count(function, "thunkEvaluations"),
                "$backend $entry either evaluates a demanded producer directly or leaves it unused")
        }
    }
}
