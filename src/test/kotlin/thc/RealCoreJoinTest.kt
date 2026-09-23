@file:Suppress("UNCHECKED_CAST")
package thc

import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** Exercise GHC's actual join annotations, including erased type arguments and result lambdas. */
class RealCoreJoinTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun exported(): Map<String, Any?> =
        Json.parse(File(root, "build/source-core/RepresentationAudit.json").readText()) as Map<String, Any?>
    private fun count(function: Value, key: String): Long =
        ((Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>)[key] as Number).toLong()
    private fun variable(id: String): List<Any?> = listOf("var", id)
    private fun integer(value: Long): List<Any?> = listOf("lit", "int", value.toString())
    private fun apply(fn: List<Any?>, args: List<List<Any?>>, lifted: List<Boolean>): List<Any?> =
        listOf("app", fn, args, lifted)

    @Test fun exportedRecursiveJoinIsALocalLoopBeforeAndAfterCompilation() {
        val module = exported()
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val function = context.eval("thc", Json.stringify(mapOf("modules" to listOf(module),
                "entry" to "joinLoop", "backend" to backend)))
            fun check(n: Long) = assertEquals(if (n <= 0) 0L else n * (n + 1) / 2,
                function.execute(n).asLong(), "$backend joinLoop($n)")
            repeat(8) { check(it.toLong()) }
            check(100_000)
            assertTrue(function.invokeMember("compile").asBoolean())
            val compiledBefore = count(function, "compiledEntries")
            for (n in listOf(-1L, 0L, 1L, 100_000L)) check(n)
            assertTrue(count(function, "compiledEntries") > compiledBefore)
            assertTrue(count(function, "localJoinTransfers") >= 100_000L, "Must execute the exported local join")
            assertEquals(0L, count(function, "tailBounces"), "Local joins do not use function tail transfers")
            assertEquals(0L, count(function, "trampolineIterations"))
        }
    }

    @Test fun erasedTypeJoinAndFunctionReturningJoinPreserveTheirValueArity() {
        val module = exported()
        val bindings = module["bindings"] as List<Map<String, Any?>>
        val constructors = module["constructors"] as List<Map<String, Any?>>
        val end = constructors.single { it["name"] == "End" }["id"] as String
        val box = constructors.single { it["name"] == "Box" }["id"] as String
        for (name in listOf("polyJoin", "functionJoin")) {
            val id = bindings.single { it["name"] == name }["id"] as String
            val empty: List<Any?> = listOf("con", end, 0)
            val nonempty = apply(listOf("con", box, 2), listOf(variable("input"), empty), listOf(false, true))
            fun invocation(value: List<Any?>): List<Any?> {
                val args = mutableListOf(variable("input"), value)
                val lifted = mutableListOf(false, true)
                if (name == "functionJoin") {
                    args += apply(listOf("prim", "+#"), listOf(variable("input"), integer(5)), listOf(false, false))
                    lifted += false
                }
                return apply(variable(id), args, lifted)
            }
            val body = listOf("case", variable("input"), "choice", listOf(
                listOf("lit", listOf("int", "0"), emptyList<String>(), invocation(empty)),
                listOf("default", null, emptyList<String>(), invocation(nonempty))))
            val lambda = listOf("lam", listOf(mapOf("id" to "input", "name" to "input",
                "type" to "Int#", "lifted" to false, "coercion" to false)), body)
            val driver = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.RealJoinDriver",
                "constructors" to emptyList<Any?>(), "bindings" to listOf(mapOf("id" to "driver",
                    "name" to "driver", "type" to "Int# -> Int#", "lifted" to true, "arity" to 1, "expr" to lambda)))
            for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                val function = context.eval("thc", Json.stringify(mapOf("modules" to listOf(module, driver),
                    "entry" to "driver", "backend" to backend)))
                fun check(n: Long) = assertEquals(if (name == "polyJoin") n + 7 else n + n + 5,
                    function.execute(n).asLong(), "$backend $name($n)")
                repeat(8) { check(it.toLong()) }
                assertTrue(function.invokeMember("compile").asBoolean())
                val compiledBefore = count(function, "compiledEntries")
                for (n in listOf(0L, 1L, -1L, 3_000_000_000L, Long.MAX_VALUE)) check(n)
                assertTrue(count(function, "compiledEntries") > compiledBefore)
                assertEquals(0L, count(function, "unsupportedTraps"))
            }
        }
    }
}
