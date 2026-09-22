package thc

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.Calls
import thc.runtime.DataValue
import thc.runtime.GuestException
import thc.runtime.Program
import thc.runtime.Thunk

class GuestExceptionsTest {
    private fun variable(id: String): List<Any?> = listOf("var", id)
    private fun integer(value: Long): List<Any?> = listOf("lit", "int", value.toString())
    private fun raised(payload: List<Any?>): List<Any?> = listOf("app", listOf("prim", "raise#"), listOf(payload), listOf(true))
    private fun binding(id: String, expression: List<Any?>, arity: Int = 0): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "type" to "Synthetic", "lifted" to true, "arity" to arity, "expr" to expression)
    private fun module(bindings: List<Map<String, Any?>>, constructors: List<Map<String, Any?>> = emptyList()): Map<String, Any?> = mapOf(
        "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.Raise", "bindings" to bindings, "constructors" to constructors)
    private fun runWithProgram(data: Map<String, Any?>, action: (Program) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                action(Program(language, data))
            } finally { context.leave() }
        }
    }
    private fun invoke(program: Program): Any? = Calls.target(program.hostEntryTarget(),
        arrayOf(program.entryValue("entry"), emptyArray<Any?>()))

    @Test fun raiseKeepsBottomPayloadUnevaluatedAndMemoizesTheSameGuestException() {
        val data = module(listOf(binding("payload", variable("payload")), binding("entry", raised(variable("payload")))))
        runWithProgram(data) { program ->
            val payload = program.entryValue("payload") as Thunk
            val first = assertThrows(GuestException::class.java) { invoke(program) }
            assertSame(payload, first.payload)
            assertEquals(0, payload.state, "raise# must not force its exception argument")
            val second = assertThrows(GuestException::class.java) { invoke(program) }
            assertSame(first, second, "A failed thunk rethrows its memoized guest exception")
            assertEquals(1L, program.diagnostics()["thunkEvaluations"])
            assertEquals(0L, program.diagnostics()["blackholes"])
        }
    }

    @Test fun raisingAnEvaluatedConstructorPreservesItsPayloadIdentity() {
        val constructor = mapOf("id" to "Payload", "name" to "Payload", "arity" to 0, "tag" to 1,
            "kind" to "boxed", "strictFields" to emptyList<Boolean>(), "fieldLifted" to emptyList<Boolean>(),
            "fieldReps" to emptyList<List<String>>())
        val data = module(listOf(binding("payload", listOf("con", "Payload", 0)), binding("entry", raised(variable("payload")))),
            listOf(constructor))
        runWithProgram(data) { program ->
            val payload = program.entryValue("payload") as DataValue
            val exception = assertThrows(GuestException::class.java) { invoke(program) }
            assertSame(payload, exception.payload)
            assertEquals("Payload", (exception.payload as DataValue).layout.name)
        }
    }

    @Test fun coldRaiseBranchStaysLazyCompilesAndReportsAGuestException() {
        val body = listOf("case", variable("input"), "choice", listOf(
            listOf("default", null, emptyList<String>(), variable("input")),
            listOf("lit", listOf("int", "0"), emptyList<String>(), raised(variable("payload")))))
        val input = mapOf("id" to "input", "name" to "input", "type" to "Int#", "lifted" to false, "coercion" to false)
        val data = module(listOf(binding("payload", variable("payload")), binding("entry", listOf("lam", listOf(input), body), 1)))
        val request = Json.stringify(mapOf("entry" to "entry", "modules" to listOf(data)))
        executionContext().use { context ->
            val function = context.eval("thc", request)
            repeat(40) { assertEquals(17L, function.execute(17L).asLong()) }
            assertTrue(function.invokeMember("compile").asBoolean())
            assertEquals(23L, function.execute(23L).asLong())
            val exception = assertThrows(PolyglotException::class.java) { function.execute(0L) }
            assertTrue(exception.isGuestException)
            assertFalse(exception.isHostException)
            @Suppress("UNCHECKED_CAST")
            val metrics = Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>
            assertEquals(0L, metrics["blackholes"])
            assertEquals(0L, metrics["thunkEvaluations"], "The bottom exception payload is still not entered")
            assertTrue((metrics["compiledEntries"] as Number).toLong() > 0)
        }
    }
}
