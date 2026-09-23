package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias CellCore = List<Any?>

/** Physical recursive cells and already published guest values have different lifetimes. */
class BindingCellMetadataTest {
    private fun variable(id: String): CellCore = listOf("var", id)
    private fun integer(value: Long): CellCore = listOf("lit", "int", value.toString())
    private fun parameter(id: String, lifted: Boolean = false): Map<String, Any?> =
        mapOf("id" to id, "name" to id, "type" to "Synthetic", "lifted" to lifted, "coercion" to false)
    private fun lambda(parameters: List<Map<String, Any?>>, body: CellCore): CellCore = listOf("lam", parameters, body)
    private fun lambda(id: String, body: CellCore): CellCore = lambda(listOf(parameter(id)), body)
    private fun binding(id: String, body: CellCore): Map<String, Any?> = parameter(id, true) +
        mapOf("expr" to body, "arity" to if (body[0] == "lam") (body[1] as List<*>).size else 0)
    private fun apply(function: CellCore, arguments: List<CellCore>, lifted: List<Boolean> = List(arguments.size) { false }): CellCore =
        listOf("app", function, arguments, lifted)
    private fun call(id: String, vararg arguments: CellCore): CellCore = apply(variable(id), arguments.toList())
    private fun primitive(name: String, vararg arguments: CellCore): CellCore = apply(listOf("prim", name), arguments.toList())
    private fun add(left: CellCore, right: CellCore) = primitive("+#", left, right)
    private fun local(group: List<Map<String, Any?>>, body: CellCore, recursive: Boolean = false): CellCore =
        listOf("let", recursive, group, body)
    private fun local(id: String, rhs: CellCore, body: CellCore): CellCore = local(listOf(binding(id, rhs)), body)
    private fun box(value: CellCore): CellCore = listOf("app", listOf("con", "Box", 1), listOf(value), listOf(false), true, true)
    private fun unbox(value: CellCore, binder: String, field: String, body: CellCore): CellCore =
        listOf("case", value, binder, listOf(listOf("data", "Box", listOf(field), body)))
    private fun chooseZero(value: CellCore, zero: CellCore, other: CellCore): CellCore =
        listOf("case", value, "choice", listOf(listOf("lit", listOf("int", "0"), emptyList<String>(), zero),
            listOf("default", null, emptyList<String>(), other)))
    private fun module(body: CellCore): Map<String, Any?> = mapOf(
        "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.BindingCells", "instrument" to true,
        "bindings" to listOf(binding("entry", lambda("input", body))),
        "constructors" to listOf(mapOf("id" to "Box", "name" to "Box", "arity" to 1, "tag" to 1,
            "kind" to "boxed", "strictFields" to listOf(false), "fieldLifted" to listOf(false),
            "fieldReps" to listOf(listOf("IntRep")))))
    private fun eachBackend(body: CellCore, action: (String, ExecutableProgram) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (backend in listOf("ast", "bytecode")) {
                    val data = module(body)
                    action(backend, if (backend == "ast") Program(language, data) else BytecodeProgram(language, data))
                }
            } finally { context.leave() }
        }
    }
    private fun call(program: ExecutableProgram, function: Any?, input: Long): Any? =
        Calls.target(program.hostEntryTarget(1), arrayOf(function, arrayOf<Any?>(input)))
    private fun run(program: ExecutableProgram, input: Long): Any? = call(program, program.entryValue("entry"), input)
    private fun count(program: ExecutableProgram, name: String): Long = (program.diagnostics().getValue(name) as Number).toLong()
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }

    @Test fun ordinaryObjectParametersFieldsAndCapturesNeedNoRecursiveCellReads() {
        // The ordinary boxed argument, case alias and its later capture are values,
        // although none has an exported static Long proof.
        val captured = lambda("delta", unbox(variable("alias"), "again", "saved",
            add(add(variable("saved"), variable("delta")), variable("extra"))))
        val worker = lambda(listOf(parameter("tree", true), parameter("extra")),
            unbox(variable("tree"), "matched", "field",
                local("alias", variable("matched"), local("captured", captured, call("captured", variable("field"))))))
        val body = local("boxed", box(variable("input")), local("worker", worker,
            apply(variable("worker"), listOf(variable("boxed"), variable("input")), listOf(true, false))))
        eachBackend(body) { backend, program ->
            if (program is BytecodeProgram) assertFalse(program.bytecodeDump().contains("c.ReadCellIfNeeded"),
                "Ordinary locals and captures must not emit recursive-cell resolution")
            repeat(30) { assertEquals(21L, run(program, 7L), backend) }
            compile(program.entryTarget("entry"))
            val compiled = count(program, "compiledEntries")
            for (n in listOf(3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE, 0L)) assertEquals(n * 3, run(program, n), backend)
            assertTrue(count(program, "compiledEntries") > compiled, backend)
            assertEquals(0L, count(program, "blackholes"), backend)
        }
    }

    @Test fun recursiveCapturesKeepCellsWhileClosuresCreatedAfterPublicationCaptureValues() {
        fun worker(other: String) = lambda("remaining", chooseZero(variable("remaining"),
            unbox(variable("shared"), "answer", "value", variable("value")),
            call(other, primitive("-#", variable("remaining"), integer(1)))))
        val group = listOf(binding("shared", box(add(variable("input"), integer(1)))),
            binding("left", worker("right")), binding("right", worker("left")))
        val later = lambda("extra", unbox(variable("shared"), "lateBox", "lateValue", add(variable("lateValue"), variable("extra"))))
        val depth = chooseZero(variable("input"), integer(0), integer(10_001))
        val body = local(group, local("later", later, add(call("left", depth), call("later", variable("input")))), true)
        eachBackend(body) { backend, program ->
            fun check(n: Long) {
                val evaluations = count(program, "thunkEvaluations")
                assertEquals(n * 3 + 2, run(program, n), backend)
                assertEquals(evaluations + 1, count(program, "thunkEvaluations"), "$backend shares the recursive payload")
            }
            repeat(30) { check(0L) }
            compile(program.entryTarget("entry"))
            val compiled = count(program, "compiledEntries")
            check(0L)
            assertTrue(count(program, "compiledEntries") > compiled, backend)
            // The recursive branch is cold when the entry is first compiled.
            for (n in listOf(3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE, 7L)) check(n)
            assertEquals(0L, count(program, "blackholes"), backend)
        }
    }

    @Test fun escapingRecursiveClosuresRetainCellsAfterCaseRefinementAndAcrossInvocations() {
        // second captures shared's cell before publication. A successful case
        // establishes WHNF, but the nested closure must still capture that cell.
        val nested = lambda("delta", unbox(variable("shared"), "again", "saved", add(variable("saved"), variable("delta"))))
        val second = lambda("extra", unbox(variable("shared"), "evaluated", "field",
            local("nested", nested, call("nested", variable("extra")))))
        val body = local(listOf(binding("shared", box(variable("input"))),
            binding("first", lambda("extra", call("second", variable("extra")))), binding("second", second)), variable("first"), true)
        eachBackend(body) { backend, program ->
            val a = run(program, 3_000_000_017L) as Closure
            val b = run(program, -7_000_000_003L) as Closure
            assertEquals(0L, count(program, "thunkEvaluations"), "$backend leaves escaped payloads lazy")
            repeat(30) {
                assertEquals(3_000_000_017L + it, call(program, a, it.toLong()), backend)
                assertEquals(-7_000_000_003L + it, call(program, b, it.toLong()), backend)
            }
            assertEquals(2L, count(program, "thunkEvaluations"), "$backend evaluates each retained payload once")
            compile(a.target)
            val compiled = count(program, "compiledEntries")
            for (n in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L)) {
                assertEquals(3_000_000_017L + n, call(program, a, n), backend)
                assertEquals(-7_000_000_003L + n, call(program, b, n), backend)
            }
            assertTrue(count(program, "compiledEntries") > compiled, backend)
            assertEquals(2L, count(program, "thunkEvaluations"), backend)
            assertEquals(0L, count(program, "blackholes"), backend)
        }
    }
}
