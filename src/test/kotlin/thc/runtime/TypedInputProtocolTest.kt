@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.EntryValue
import thc.Language

/** Cross-backend ownership and nontrivial bloom cycles, separate from native arithmetic. */
class TypedInputProtocolTest {
    private val integer = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val ref = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val closure = ref + ("kind" to "closure") + ("evaluated" to true)
    private val pair = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "evaluated" to true,
        "primReps" to listOf("IntRep", "IntRep"), "components" to listOf(integer, integer))
    private fun v(id: String, rep: Map<String, Any?> = integer) = listOf("var", id, mapOf("rep" to rep))
    private fun n(value: Long) = listOf("lit", "int", value.toString(), mapOf("rep" to integer))
    private fun arg(id: String, rep: Map<String, Any?> = integer) = mapOf("id" to id, "name" to id,
        "rep" to rep, "lifted" to (rep == ref || rep == closure))
    private fun app(fn: List<Any?>, args: List<List<Any?>>, rep: Map<String, Any?> = integer,
        flags: List<Boolean> = List(args.size) { false }): List<Any?> = listOf("app", fn, args, flags, false, false, mapOf("rep" to rep))
    private fun call(id: String, args: List<List<Any?>>, rep: Map<String, Any?> = integer) = app(v(id, closure), args, rep)
    private fun prim(name: String, left: List<Any?>, right: List<Any?>) = app(listOf("prim", name), listOf(left, right))
    private fun pack(a: List<Any?>, b: List<Any?>) = app(listOf("con", "Pair", 2), listOf(a, b), pair)
    private fun unpack(value: List<Any?>, body: List<Any?>): List<Any?> = listOf("case", value, "whole", listOf(
        listOf("data", "Pair", listOf("a", "b"), body, mapOf("binders" to listOf(arg("a"), arg("b"))))),
        mapOf("rep" to integer, "binder" to arg("whole", pair)))
    private fun lam(args: List<Map<String, Any?>>, body: List<Any?>, result: Map<String, Any?> = integer,
        strict: List<Boolean> = List(args.size) { false }): List<Any?> = listOf("lam", args, body,
        mapOf("rep" to closure, "resultRep" to result, "entryStrict" to strict))
    private fun bind(id: String, body: List<Any?>) = mapOf("id" to id, "name" to id, "rep" to closure, "lifted" to true, "expr" to body)
    private fun module(bindings: List<Map<String, Any?>>) = mapOf("bindings" to bindings, "instrument" to true,
        "constructors" to listOf(mapOf("id" to "Pair", "name" to "Pair", "kind" to "unboxed-tuple", "arity" to 2)))
    private fun withLanguage(inlining: Boolean, action: (Context, Language) -> Unit) = Context.newBuilder("thc")
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inlining.toString()).build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(context, TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
        }
    private fun program(language: Language, backend: String, bindings: List<Map<String, Any?>>): ExecutableProgram =
        if (backend == "ast") Program(language, module(bindings)) else BytecodeProgram(language, module(bindings))
    private fun run(program: ExecutableProgram, name: String, vararg values: Any?) =
        Calls.target(program.hostEntryTarget(values.size), arrayOf(program.entryValue(name), values))
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), "$label/$target")
    private fun clear(language: Language) {
        val state = language.handoffState.get()
        assertNull(state.pending)
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }

    @Test fun threeTargetAndPrefixedThreeTargetCyclesReturnTypedResults() {
        for (backend in listOf("ast", "bytecode")) for (inlining in listOf(true, false)) withLanguage(inlining) { context, language ->
            fun worker(id: String, next: String) = bind(id, lam(listOf(arg("p", pair), arg("depth")),
                listOf("case", prim("<=#", v("depth"), n(0)), "done", listOf(
                    listOf("lit", listOf("int", "1"), emptyList<String>(), v("p", pair)),
                    listOf("default", null, emptyList<String>(), call(next,
                        listOf(v("p", pair), prim("-#", v("depth"), n(1))), pair))),
                    mapOf("rep" to pair, "binder" to arg("done"))), pair))
            fun entry(name: String, first: String) = bind(name, lam(listOf(arg("x")),
                unpack(call(first, listOf(pack(v("x"), n(7)), n(17)), pair), prim("+#", v("a"), v("b")))))
            val bindings = listOf(worker("a", "b"), worker("b", "c"), worker("c", "a"),
                worker("pa", "pb"), worker("pb", "pc"), worker("pc", "pd"), worker("pd", "pe"), worker("pe", "pc"),
                entry("cycle", "a"), entry("prefixCycle", "pa"))
            val p = program(language, backend, bindings)
            for (name in listOf("cycle", "prefixCycle")) {
                val label = "$backend/$name/inline=$inlining"
                val fn = context.asValue(EntryValue(p, name, 1))
                val inputs = listOf(Long.MIN_VALUE, -1L, 0L, 11L, Long.MAX_VALUE)
                for (x in inputs) { assertEquals(x + 7L, fn.execute(x).asLong(), "$label/interpreted"); clear(language) }
                assertTrue(fn.invokeMember("compile").asBoolean(), label)
                val original = p.entryTarget(name); val host = p.hostEntryTarget(1)
                fun active() = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                    .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }.toSet()
                val observed = active()
                assertTrue(observed.isNotEmpty(), "$label actual host linkage")
                for (x in inputs.asReversed()) {
                    val before = p.diagnostics()["compiledEntries"] as Long
                    assertEquals(x + 7L, fn.execute(x).asLong(), "$label/compiled/$x")
                    assertTrue((p.diagnostics()["compiledEntries"] as Long) > before, "$label missing compiled guest entry")
                    valid(original, label); valid(host, label); assertEquals(observed, active(), label)
                    observed.forEach { valid(it, label) }; clear(language)
                }
                assertTrue((p.diagnostics()["selfTailReentries"] as Long) > 0, label)
            }
        }
    }

    private class PrefixThunk(private val language: Language, private val fail: Boolean) : GuestRoot(language, FrameLayout().build()) {
        var calls = 0
        init { configureEntry(booleanArrayOf(), false) }
        override fun bloom(frame: VirtualFrame) = 0L
        override fun execute(frame: VirtualFrame): Any {
            val state = language.handoffState.get()
            check(state.arguments.depth == 0 && state.arguments.retainedReferences() == 0)
            calls++
            if (fail) throw GuestException("strict prefix", this)
            return 123L
        }
    }
    @Test fun durableTypedPapPrefixSurvivesReuseAndStrictPrefixFailureBeforeLoan() {
        for (backend in listOf("ast", "bytecode")) withLanguage(false) { _, language ->
            val worker = bind("worker", lam(listOf(arg("prefix", ref), arg("p", pair)),
                unpack(v("p", pair), prim("+#", v("a"), v("b"))), strict = listOf(true, false)))
            val p = program(language, backend, listOf(worker,
                bind("make", lam(listOf(arg("prefix", ref)),
                    app(v("worker", closure), listOf(v("prefix", ref)), closure, listOf(true)), closure)),
                bind("apply", lam(listOf(arg("f", closure), arg("x")), call("f", listOf(pack(v("x"), n(7))))))))
            val goodRoot = PrefixThunk(language, false); val good = Thunk(goodRoot.callTarget, null)
            val badRoot = PrefixThunk(language, true); val bad = Thunk(badRoot.callTarget, null)
            val goodPap = run(p, "make", good) as Closure
            val badPap = run(p, "make", bad) as Closure
            assertEquals(0, goodRoot.calls); assertEquals(0, badRoot.calls)
            val goodStorage = goodPap.typedSupplied!!; val badStorage = badPap.typedSupplied!!
            assertEquals(0, goodStorage.inputMode); assertFalse(goodStorage.live)
            assertSame(good, goodStorage.layout.getObject(goodStorage, 0))
            assertEquals(18L, run(p, "apply", goodPap, 11L)); clear(language)
            assertThrows(GuestException::class.java) { run(p, "apply", badPap, 99L) }; clear(language)
            assertSame(bad, badStorage.layout.getObject(badStorage, 0)); assertEquals(1, badRoot.calls)
            assertEquals(-2L, run(p, "apply", goodPap, -9L)); clear(language)
            assertSame(good, goodStorage.layout.getObject(goodStorage, 0)); assertEquals(1, goodRoot.calls)
            assertThrows(GuestException::class.java) { run(p, "apply", badPap, 99L) }; clear(language)
            assertEquals(1, badRoot.calls)
        }
    }
}
