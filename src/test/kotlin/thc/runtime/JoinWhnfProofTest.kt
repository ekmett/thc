package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.NodeUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias WhnfCore = List<Any?>

/** WHNF describes values actually returned, not control transfers to another join body. */
class JoinWhnfProofTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val data = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun variable(id: String): WhnfCore = listOf("var", id)
    private fun integer(n: Long): WhnfCore = listOf("lit", "int", n.toString())
    private fun parameter(id: String, rep: Map<String, Any> = long) = mapOf(
        "id" to id, "name" to id, "lifted" to (rep !== long), "coercion" to false, "rep" to rep)
    private fun lambda(args: List<Map<String, Any>>, body: WhnfCore, result: Map<String, Any> = long): WhnfCore =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result))
    private fun binding(id: String, rhs: WhnfCore, rep: Map<String, Any> = closure): Map<String, Any?> =
        parameter(id, rep) + mapOf("expr" to rhs)
    private fun apply(fn: WhnfCore, args: List<WhnfCore>, lifted: List<Boolean> = List(args.size) { false },
                      result: Map<String, Any> = long): WhnfCore = listOf("app", fn, args, lifted, false, false, mapOf("rep" to result))
    private fun primitive(name: String, vararg args: WhnfCore) = apply(listOf("prim", name), args.toList())
    private fun box(n: WhnfCore): WhnfCore = listOf("app", listOf("con", "Box", 1), listOf(n), listOf(false), true, true, mapOf("rep" to data))
    private fun unbox(value: WhnfCore): WhnfCore = listOf("case", value, "boxed",
        listOf(listOf("data", "Box", listOf("payload"), variable("payload"), mapOf("binders" to listOf(parameter("payload"))))),
        mapOf("rep" to long, "binder" to parameter("boxed", data)))
    private fun join(id: String, parameters: List<Map<String, Any>>, body: WhnfCore) =
        binding(id, lambda(parameters, body, data)) + mapOf("joinValueArity" to parameters.size, "joinResultRep" to data)
    private fun region(join: Map<String, Any?>, entry: WhnfCore): WhnfCore =
        listOf("let", true, listOf(join), entry, mapOf("rep" to data))
    private fun call(p: ExecutableProgram, name: String, input: Long): Any? =
        Calls.target(p.hostEntryTarget(1), arrayOf(p.entryValue(name), arrayOf<Any?>(input)))
    private fun count(p: ExecutableProgram, name: String) = (p.diagnostics().getValue(name) as Number).toLong()
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }

    @Test fun recursiveDataJoinsKeepWhnfWhileLazyReturningJoinsRemainLazy() {
        val step = listOf("case", variable("remaining"), "choice", listOf(
            listOf("lit", listOf("int", "0"), emptyList<String>(), box(variable("acc"))),
            listOf("default", null, emptyList<String>(), apply(variable("loop"), listOf(
                primitive("-#", variable("remaining"), integer(1)), primitive("+#", variable("acc"), integer(1))), result = data))),
            mapOf("rep" to data, "binder" to parameter("choice")))
        val returning = region(join("loop", listOf(parameter("remaining"), parameter("acc")), step),
            apply(variable("loop"), listOf(integer(1_001), variable("input")), result = data))
        val lazy = region(join("identity", listOf(parameter("value", data)), variable("value")),
            apply(variable("identity"), listOf(variable("tree")), listOf(true), data))
        val unsafe = apply(listOf("con", "Box", 1), listOf(primitive("quotInt#", integer(1), variable("input"))), result = data)
        val lazyCall = apply(variable("lazyJoin"), listOf(unsafe), listOf(true), data)
        val ignored = listOf("let", false, listOf(binding("unused", lazyCall, data)), integer(7))
        val bindings = listOf(
            binding("returning", lambda(listOf(parameter("input")), returning, data)),
            binding("lazyJoin", lambda(listOf(parameter("tree", data)), lazy, data)),
            binding("ignored", lambda(listOf(parameter("input")), ignored)),
            binding("demanded", lambda(listOf(parameter("input")), unbox(lazyCall))))
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (backend in listOf("ast", "bytecode")) {
                    val module = mapOf("bindings" to bindings, "instrument" to true, "constructors" to listOf(
                        mapOf("id" to "Box", "name" to "Box", "arity" to 1, "kind" to "boxed", "fieldReps" to listOf(listOf("IntRep")),
                            "strictFields" to listOf(false), "fieldLifted" to listOf(false))))
                    val p = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    if (p is Program) {
                        val positive = NodeUtil.findAllNodeInstances(p.entryTarget("returning").rootNode, LocalJoinRegion::class.java).single()
                        assertEquals(CoreKind.DATA, positive.representation.kind)
                        assertTrue(positive.representation.evaluated, "A recursive transfer contributes no lazy return")
                        val negative = NodeUtil.findAllNodeInstances(p.entryTarget("lazyJoin").rootNode, LocalJoinRegion::class.java).single()
                        assertFalse(negative.representation.evaluated, "A returned lazy formal must weaken the whole region")
                    }
                    fun check(n: Long) {
                        val result = call(p, "returning", n) as DataValue
                        assertEquals(n + 1_001, result.layout.readLong(result, 0), backend)
                    }
                    repeat(30) { check(it.toLong()) }
                    compile(p.entryTarget("returning"))
                    for (n in listOf(3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE)) check(n)
                    val evaluations = count(p, "thunkEvaluations")
                    repeat(30) { assertEquals(7L, call(p, "ignored", 0), backend) }
                    compile(p.entryTarget("ignored"))
                    assertEquals(7L, call(p, "ignored", 0), backend)
                    assertEquals(evaluations, count(p, "thunkEvaluations"), "$backend must not force an ignored lazy join")
                    assertThrows(ArithmeticException::class.java) { call(p, "demanded", 0) }
                    assertTrue(count(p, "thunkEvaluations") > evaluations, backend)
                }
            } finally { context.leave() }
        }
    }
}
