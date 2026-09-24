@file:Suppress("UNCHECKED_CAST")
package thc

import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** Genuine GHC worker/join CBV marks must survive export, execution and compilation. */
class RealCoreEntryContractTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun exported(name: String): Map<String, Any?> =
        Json.parse(File(root, "build/core/$name.json").readText()) as Map<String, Any?>

    private fun definitions(value: Any?): List<Map<String, Any?>> = when (value) {
        is Map<*, *> -> {
            val own = if (value.containsKey("expr") && value.containsKey("entryStrict"))
                listOf(value as Map<String, Any?>) else emptyList()
            own + value.values.flatMap(::definitions)
        }
        is List<*> -> value.flatMap(::definitions)
        else -> emptyList()
    }

    private fun count(function: Value, name: String): Long =
        ((Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>)[name] as Number).toLong()

    private fun checkEntry(module: Map<String, Any?>, entry: String, joins: Boolean = false,
                           expected: (Long) -> Long) {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val function = context.eval("thc", Json.stringify(mapOf("modules" to listOf(module),
                "entry" to entry, "backend" to backend)))
            fun check(input: Long) = assertEquals(expected(input), function.execute(input).asLong(),
                "$backend $entry($input)")
            // Keep nonpositive case alternatives cold until compilation has succeeded.
            repeat(30) { check(it.toLong() + 1) }
            assertTrue(function.invokeMember("compile").asBoolean(), "$backend $entry compiled")
            val compiled = count(function, "compiledEntries")
            check(7L)
            assertTrue(count(function, "compiledEntries") > compiled, "$backend $entry ran compiled code")
            for (input in listOf(0L, -1L, 3_000_000_017L, -7_000_000_003L, Long.MIN_VALUE, Long.MAX_VALUE)) check(input)
            // Recompile after the cold branches and use different full-width inputs.
            assertTrue(function.invokeMember("compile").asBoolean(), "$backend $entry recompiled")
            val recompiled = count(function, "compiledEntries")
            check(4_294_967_311L)
            assertTrue(count(function, "compiledEntries") > recompiled, "$backend $entry reran compiled code")
            check(Long.MIN_VALUE + 13)
            check(Long.MAX_VALUE - 11)
            assertEquals(0L, count(function, "blackholes"), backend)
            assertEquals(0L, count(function, "unsupportedTraps"), backend)
            if (joins) assertTrue(count(function, "localJoinTransfers") > 0, "$backend executed the actual exported join")
        }
    }

    @Test fun genuineWorkerContractPreservesFullWidthResultsAndColdBranches() {
        val module = exported("CBVAudit")
        val marked = definitions(module).filter { (it["entryStrict"] as List<Boolean>).any { mark -> mark } }
        assertTrue(marked.any { "walk" in it["name"].toString() && it["entryStrictSource"] == "ghc-tidy-proposal" })
        val ordinary = definitions(module).single { it["name"] == "plainStrict" }
        assertFalse((ordinary["entryStrict"] as List<Boolean>).any { it }, "Strict demand alone must not add a contract")
        checkEntry(module, "workerEntry") { n -> if (n <= 0) n + 7 else n - 1 }
    }

    @Test fun genuinePolymorphicJoinUsesItsErasedValuePrefix() {
        val module = exported("CBVJoinAudit")
        val join = definitions(module).single { it["name"] == "done" }
        assertEquals(2, (join["joinValueArity"] as Number).toInt())
        assertEquals(listOf(false, true), join["entryStrict"])
        checkEntry(module, "joinEntry", joins = true) { n -> n + 7 }
    }

    @Test fun genuineFunctionReturningJoinKeepsTheReturnedArgumentOutsideItsContract() {
        val module = exported("CBVJoinAudit")
        val join = definitions(module).single { it["name"] == "doneFunction" }
        assertEquals(1, (join["joinValueArity"] as Number).toInt())
        assertEquals(listOf(false, false), join["entryStrict"])
        checkEntry(module, "functionJoinEntry", joins = true) { n -> n + 7 }
    }

    @Test fun genuineWorkerRetainsTheCoercionSlotBeforeItsMarkedBoxedArgument() {
        val module = exported("CBVCoercionAudit")
        val worker = definitions(module).single { it["name"] == "\$wwitnessed" }
        val parameters = (worker["expr"] as List<Any?>)[1] as List<Map<String, Any?>>
        assertEquals(listOf(false, false, true), worker["entryStrict"])
        assertEquals(true, parameters[0]["coercion"])
        assertEquals(true, parameters[2]["lifted"])
        assertEquals(false, (parameters[2]["rep"] as Map<String, Any?>)["evaluated"],
            "The CBV obligation must not rewrite GHC's pre-entry WHNF fact")
        checkEntry(module, "coercionEntry") { n -> n + 7 }
    }
}
