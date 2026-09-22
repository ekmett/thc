package thc

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.Calls
import thc.runtime.DataValue
import thc.runtime.Program
import thc.runtime.Thunk
import java.io.File

private typealias CoreExpression = List<Any?>

/** Isolate the CBV obligations of constructor workers, before CorePrep inserts cases. */
class StrictFieldsTest {
    private fun variable(id: String): CoreExpression = listOf("var", id)
    private fun integer(value: Long): CoreExpression = listOf("lit", "int", value.toString())
    private fun constructorValue(id: String, arity: Int): CoreExpression = listOf("con", id, arity)
    private fun apply(function: CoreExpression, args: List<CoreExpression>, lifted: List<Boolean>): CoreExpression =
        listOf("app", function, args, lifted)
    private fun binding(id: String, expression: CoreExpression, arity: Int = 0): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "type" to "Synthetic", "lifted" to true, "arity" to arity, "expr" to expression)
    private fun lambda(id: String, body: CoreExpression): CoreExpression = listOf("lam", listOf(mapOf(
        "id" to id, "name" to id, "type" to "Int#", "lifted" to false, "coercion" to false)), body)
    private fun constructor(id: String, strict: List<Boolean>, lifted: List<Boolean?>): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "arity" to strict.size, "tag" to 1, "kind" to "boxed",
        "strictFields" to strict, "fieldLifted" to lifted,
        "fieldReps" to lifted.map { listOf(if (it == false) "IntRep" else "BoxedRep (Just Lifted)") })
    private fun module(constructors: List<Map<String, Any?>>, bindings: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.StrictFields",
        "constructors" to constructors, "bindings" to bindings)
    private fun request(module: Map<String, Any?>): String = requestModules(listOf(module))
    private fun requestModules(modules: List<Map<String, Any?>>): String = Json.stringify(mapOf(
        "entry" to "entry", "modules" to modules))
    private fun ignore(scrutinee: CoreExpression, answer: Long = 41): CoreExpression =
        listOf("case", scrutinee, "ignored", listOf(listOf("default", null, emptyList<String>(), integer(answer))))
    @Suppress("UNCHECKED_CAST")
    private fun diagnostics(value: Value): Map<String, Any?> =
        Json.parse(value.getMember("diagnostics").asString()) as Map<String, Any?>
    private fun count(value: Value, key: String): Long = (diagnostics(value)[key] as Number).toLong()
    private fun assertBlackhole(function: Value) {
        val error = assertThrows(PolyglotException::class.java) { function.execute() }
        assertTrue(error.message.orEmpty().contains("Blackhole"), error.message)
        assertEquals(1L, count(function, "blackholes"))
    }

    @Test fun strictWorkerForcesIgnoredFieldsInDirectAndFirstClassApplications() {
        val strict = constructor("Strict", listOf(true), listOf(true))
        val lazy = constructor("Lazy", listOf(false), listOf(true))
        executionContext().use { context ->
            for (firstClass in listOf(false, true)) {
                fun example(id: String): Map<String, Any?> {
                    val function = if (firstClass) variable("worker") else constructorValue(id, 1)
                    return module(listOf(strict, lazy), listOf(
                        binding("bottom", variable("bottom")),
                        binding("worker", constructorValue(id, 1), 1),
                        binding("entry", ignore(apply(function, listOf(variable("bottom")), listOf(true))))))
                }
                val lazyFunction = context.eval("thc", request(example("Lazy")))
                assertEquals(41L, lazyFunction.execute().asLong())
                assertEquals(0L, count(lazyFunction, "blackholes"))
                val strictFunction = context.eval("thc", request(example("Strict")))
                assertBlackhole(strictFunction)
                assertBlackhole(strictFunction) // The failed thunk is updated, not recomputed.
            }
        }
    }

    @Test fun partialConstructorRetainsStrictArgumentLazilyUntilItIsSaturated() {
        val pair = constructor("Pair", listOf(true, false), listOf(true, true))
        val box = constructor("Box", listOf(false), listOf(false))
        val partial = apply(constructorValue("Pair", 2), listOf(variable("bottom")), listOf(true))
        fun example(saturate: Boolean): Map<String, Any?> {
            val continuation = if (saturate) ignore(apply(variable("pap"), listOf(
                apply(constructorValue("Box", 1), listOf(integer(7)), listOf(false))), listOf(true))) else integer(41)
            val body = listOf("case", partial, "pap", listOf(listOf("default", null, emptyList<String>(), continuation)))
            return module(listOf(pair, box), listOf(binding("bottom", variable("bottom")), binding("entry", body)))
        }
        executionContext().use { context ->
            val partialFunction = context.eval("thc", request(example(false)))
            assertEquals(41L, partialFunction.execute().asLong())
            assertTrue(count(partialFunction, "papAllocations") > 0L)
            assertEquals(0L, count(partialFunction, "blackholes"))
            val saturated = context.eval("thc", request(example(true)))
            assertBlackhole(saturated)
            assertTrue(count(saturated, "papAllocations") > 0L)
        }
    }

    @Test fun strictFieldsShareTheirWhnfWhileAnOrdinaryFieldKeepsItsUnevaluatedThunk() {
        val box = constructor("Box", listOf(false), listOf(false))
        val mixed = constructor("Mixed", listOf(true, false, true), listOf(true, true, true))
        val shared = apply(constructorValue("Box", 1), listOf(integer(3_000_000_000L)), listOf(false))
        val built = apply(constructorValue("Mixed", 3), listOf(variable("shared"), variable("bottom"), variable("shared")),
            listOf(true, true, true))
        val data = module(listOf(box, mixed), listOf(binding("shared", shared), binding("bottom", variable("bottom")),
            binding("entry", built)))
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = Program(language, data)
                val result = Calls.target(program.hostEntryTarget(), arrayOf(program.entryValue("entry"), emptyArray<Any?>())) as DataValue
                val first = result.layout.read(result, 0)
                val second = result.layout.read(result, 2)
                assertTrue(first is DataValue, "Strict fields store WHNF, rather than an updated thunk wrapper")
                assertSame(first, second, "Forcing a shared thunk must preserve its value identity")
                val boxed = first as DataValue
                assertEquals(3_000_000_000L, boxed.layout.readLong(boxed, 0))
                val ordinary = result.layout.read(result, 1)
                assertTrue(ordinary is Thunk)
                assertEquals(0, (ordinary as Thunk).state, "Ordinary lifted fields remain unentered")
                val metrics = program.diagnostics()
                assertEquals(1L, (metrics["thunkEvaluationsByLabel"] as Map<*, *>)["shared"])
                assertEquals(0L, metrics["blackholes"])
            } finally { context.leave() }
        }
    }

    @Test fun realGhcStrictConstructorPreservesPrimitiveValuesBeforeAndAfterCompilation() {
        val root = File(System.getProperty("thc.projectRoot"))
        @Suppress("UNCHECKED_CAST")
        val exported = Json.parse(File(root, "build/core/StrictFields.json").readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val bindings = exported["bindings"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val constructors = exported["constructors"] as List<Map<String, Any?>>
        val strictFunctionId = bindings.single { it["name"] == "strictConstruct" }["id"] as String
        val strictId = constructors.single { it["name"] == "Strict" }["id"] as String
        val boxId = constructors.single { it["name"] == "Box" }["id"] as String
        val value = apply(constructorValue(boxId, 1), listOf(variable("input")), listOf(false))
        val strict = apply(variable(strictFunctionId), listOf(value), listOf(true))
        val unbox = listOf("case", variable("boxed"), "inner",
            listOf(listOf("data", boxId, listOf("number"), variable("number"))))
        val body = listOf("case", strict, "outer", listOf(listOf("data", strictId, listOf("boxed"), unbox)))
        val driver = module(emptyList(), listOf(binding("entry", lambda("input", body), 1)))
        executionContext().use { context ->
            val function = context.eval("thc", requestModules(listOf(exported, driver)))
            val inputs = listOf(3_000_000_000L, -3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE, 0L)
            repeat(8) { for (input in inputs) assertEquals(input, function.execute(input).asLong()) }
            assertTrue(function.invokeMember("compile").asBoolean())
            val before = count(function, "compiledEntries")
            for (input in inputs) assertEquals(input, function.execute(input).asLong())
            assertTrue(count(function, "compiledEntries") > before)
        }
    }

    @Test fun directSaturatedStrictOperandsDoNotAllocateAnImmediatelyForcedThunk() {
        val box = constructor("Box", listOf(false), listOf(false))
        val strict = constructor("Strict", listOf(true), listOf(true))
        val makeBox = binding("makeBox", lambda("number",
            apply(constructorValue("Box", 1), listOf(variable("number")), listOf(false))), 1)
        val operand = apply(variable("makeBox"), listOf(variable("input")), listOf(false))
        val constructed = apply(constructorValue("Strict", 1), listOf(operand), listOf(true))
        val unbox = listOf("case", variable("boxed"), "inner",
            listOf(listOf("data", "Box", listOf("value"), variable("value"))))
        val body = listOf("case", constructed, "outer", listOf(listOf("data", "Strict", listOf("boxed"), unbox)))
        val data = module(listOf(box, strict), listOf(makeBox, binding("entry", lambda("input", body), 1)))
        executionContext().use { context ->
            val function = context.eval("thc", request(data))
            for (input in listOf(0L, 3_000_000_000L, -3_000_000_000L)) {
                assertEquals(input, function.execute(input).asLong())
            }
            assertEquals(0L, count(function, "thunkEvaluations"),
                "A strict constructor operand must use direct CBV evaluation")
        }
    }

    @Test fun unknownStrictFieldLevityStillFailsClosed() {
        val uncertain = constructor("Uncertain", listOf(true), listOf(null))
        val data = module(listOf(uncertain), listOf(binding("bottom", variable("bottom")), binding("entry",
            ignore(apply(constructorValue("Uncertain", 1), listOf(variable("bottom")), listOf(true))))))
        executionContext().use { context ->
            val error = assertThrows(PolyglotException::class.java) { context.eval("thc", request(data)) }
            assertTrue(error.message.orEmpty().contains("Unknown strict constructor field levity"), error.message)
        }
    }
}
