package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.EntryValue
import thc.Language

/** Legacy unknown scalar locals can generalize while exact tuple leaves stay typed. */
class TypedInputScalarSourceTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val float = mapOf("kind" to "float", "primReps" to listOf("FloatRep"), "evaluated" to true)
    private val double = mapOf("kind" to "double", "primReps" to listOf("DoubleRep"), "evaluated" to true)
    private val unknown = mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val pair = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to listOf(long, long),
        "primReps" to listOf("IntRep", "IntRep"), "evaluated" to true)
    private fun v(id: String, rep: Map<String, Any?>) = listOf("var", id, mapOf("rep" to rep))
    private fun arg(id: String, rep: Map<String, Any?>) = mapOf("id" to id, "name" to id, "rep" to rep, "lifted" to (rep == closure))
    private fun app(fn: List<Any?>, args: List<List<Any?>>, rep: Map<String, Any?> = long) =
        listOf("app", fn, args, List(args.size) { false }, false, false, mapOf("rep" to rep))
    private fun n(value: Int) = listOf("lit", "int", value.toString(), mapOf("rep" to long))
    private fun plus(a: List<Any?>, b: List<Any?>) = app(listOf("prim", "+#"), listOf(a, b))
    private fun bind(name: String, args: List<Map<String, Any?>>, body: List<Any?>) = mapOf("id" to name,
        "name" to name, "rep" to closure, "lifted" to true, "expr" to listOf("lam", args, body,
            mapOf("rep" to closure, "resultRep" to long, "entryStrict" to List(args.size) { false })))
    private fun worker(name: String, rep: Map<String, Any?>, conversion: String?): Map<String, Any?> {
        val scalar = if (conversion == null) v("x", rep) else app(listOf("prim", conversion), listOf(v("x", rep)))
        return bind(name, listOf(arg("p", pair), arg("x", rep)), listOf("case", v("p", pair), "whole", listOf(
            listOf("data", "Pair", listOf("a", "b"), plus(plus(v("a", long), v("b", long)), scalar),
                mapOf("binders" to listOf(arg("a", long), arg("b", long))))), mapOf("rep" to long, "binder" to arg("whole", pair))))
    }
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    @Test fun unknownScalarBesideTupleSurvivesLongFloatDoubleLocalGeneralization() {
        for (backend in listOf("ast", "bytecode")) for (inline in listOf(true, false)) Context.newBuilder("thc")
            .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inline.toString()).build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val tuple = app(listOf("con", "Pair", 2), listOf(n(1), n(2)), pair)
                    val entry = bind("entry", listOf(arg("f", closure), arg("x", unknown)),
                        app(v("f", closure), listOf(tuple, v("x", unknown))))
                    val module = mapOf("instrument" to true, "constructors" to listOf(
                        mapOf("id" to "Pair", "name" to "Pair", "kind" to "unboxed-tuple", "arity" to 2)),
                        "bindings" to listOf(worker("long", long, null), worker("float", float, "float2Int#"),
                            worker("double", double, "double2Int#"), entry))
                    val p: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    val rows = listOf("long" to Long.MAX_VALUE, "float" to 1.5f, "double" to -2.75,
                        "long" to Long.MIN_VALUE, "float" to 4097f, "double" to 0.0, "long" to 7L)
                    val host = p.hostEntryTarget(2); val original = p.entryTarget("entry")
                    fun active() = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                        .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }.toSet()
                    var phase = "interpreted"
                    fun invoke(row: Pair<String, Number>): Any? = try {
                        Calls.target(host, arrayOf(p.entryValue("entry"), arrayOf(p.entryValue(row.first), row.second)))
                    } catch (failure: Throwable) {
                        throw AssertionError("$backend/inline=$inline/$phase/$row", failure)
                    }
                    fun clear() {
                        val state = language.handoffState.get()
                        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
                        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
                    }
                    for (row in rows) { assertEquals(row.second.toLong() + 3L, invoke(row), "$backend/$inline/$row/interpreted"); clear() }
                    assertTrue(context.asValue(EntryValue(p, "entry", 2)).invokeMember("compile").asBoolean())
                    phase = "compiled"
                    val observed = active(); assertTrue(observed.isNotEmpty())
                    for (row in rows.asReversed()) {
                        val label = "$backend/$inline/$row/compiled"
                        val before = p.diagnostics()["compiledEntries"] as Long
                        assertEquals(row.second.toLong() + 3L, invoke(row), label)
                        assertTrue((p.diagnostics()["compiledEntries"] as Long) > before, label)
                        valid(host, label); valid(original, label); assertEquals(observed, active(), label)
                        observed.forEach { valid(it, label) }; clear()
                    }
                } finally { context.leave() }
            }
    }
}
