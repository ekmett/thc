package thc

import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SelfTailProfileTest {
    private val project = File(System.getProperty("thc.projectRoot"))
    private val modules = listOf("THC.Prim", "THC.Fixtures").map {
        File(project, "build/core/$it.json").path
    }

    private fun count(function: Value, name: String): Long =
        ((Json.parse(function.getMember("diagnostics").asString()) as Map<*, *>)[name] as Number).toLong()

    private fun compileBaseCase(function: Value, result: Long) {
        repeat(40) { assertEquals(result, function.execute(0L).asLong()) }
        assertTrue(function.invokeMember("compile").asBoolean())
        val compiledBefore = count(function, "compiledEntries")
        assertEquals(result, function.execute(0L).asLong())
        assertTrue(count(function, "compiledEntries") > compiledBefore, "Base case must execute installed code")
    }

    @Test fun firstSelfTailAfterCompiledBaseCaseRemainsStackSafeAndRecompiles() {
        executionContext().use { context ->
            val function = loadEntry(context, modules, "sumLoop")
            compileBaseCase(function, 0L)
            val bouncesBefore = count(function, "tailBounces")
            assertEquals(5_000_050_000L, function.execute(100_000L).asLong())
            assertTrue(count(function, "tailBounces") - bouncesBefore >= 100_000L)
            assertTrue(function.invokeMember("compile").asBoolean())
            val compiledBefore = count(function, "compiledEntries")
            assertEquals(55L, function.execute(10L).asLong())
            assertEquals(0L, function.execute(0L).asLong())
            assertTrue(count(function, "compiledEntries") > compiledBefore)
        }
    }

    @Test fun firstSelfTailAfterCompiledBaseCaseRestoresChangingCaptures() {
        executionContext().use { context ->
            val function = loadEntry(context, modules, "capturedChangingEnv")
            compileBaseCase(function, 17L)
            val bouncesBefore = count(function, "tailBounces")
            assertEquals(100_017L, function.execute(100_000L).asLong())
            assertTrue(count(function, "tailBounces") - bouncesBefore >= 100_000L)
            assertTrue(function.invokeMember("compile").asBoolean())
            val compiledBefore = count(function, "compiledEntries")
            assertEquals(24L, function.execute(7L).asLong())
            assertEquals(17L, function.execute(0L).asLong())
            assertEquals(10_017L, function.execute(10_000L).asLong())
            assertTrue(count(function, "compiledEntries") > compiledBefore)
            assertEquals(0L, count(function, "blackholes"))
        }
    }
}
