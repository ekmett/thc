package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

private typealias RefCore = List<Any?>

class HandoffReferenceTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val data = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val obj = data + ("kind" to "object")
    private val closure = data + ("kind" to "closure")
    private fun v(id: String): RefCore = listOf("var", id)
    private fun n(value: Long): RefCore = listOf("lit", "int", value.toString())
    private fun param(id: String, rep: Map<String, Any> = data) = mapOf("id" to id, "name" to id, "rep" to rep,
        "lifted" to (rep !== long), "coercion" to false)
    private fun lambda(args: List<Map<String, Any>>, body: RefCore, result: Map<String, Any> = data): RefCore =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result, "entryStrict" to List(args.size) { false }))
    private fun bind(id: String, args: List<Map<String, Any>>, body: RefCore, result: Map<String, Any> = data) =
        mapOf("id" to id, "name" to id, "lifted" to true, "expr" to lambda(args, body, result))
    private fun call(id: String, vararg args: RefCore): RefCore = listOf("app", v(id), args.toList(), args.map { it.firstOrNull() == "var" && it.getOrNull(1) != "n" })
    private fun prim(id: String, vararg args: RefCore): RefCore = listOf("app", listOf("prim", id), args.toList(), List(args.size) { false })
    private fun con(id: String, vararg args: RefCore): RefCore = listOf("app", listOf("con", id, args.size), args.toList(), List(args.size) { false }, true, true)
    private fun let(value: RefCore, id: String, body: RefCore): RefCore = listOf("case", value, id, listOf(listOf("default", null, emptyList<String>(), body)))
    private fun choose(test: RefCore, yes: RefCore, no: RefCore): RefCore = listOf("case", test, "test", listOf(
        listOf("lit", listOf("int", "1"), emptyList<String>(), yes), listOf("default", null, emptyList<String>(), no)))
    private fun module(bindings: List<Map<String, Any>>) = mapOf("bindings" to bindings, "instrument" to true,
        "constructors" to listOf(
            mapOf("id" to "Box", "name" to "Box", "arity" to 1, "kind" to "boxed", "fieldReps" to listOf(listOf("IntRep")), "strictFields" to listOf(false), "fieldLifted" to listOf(false)),
            mapOf("id" to "Pair", "name" to "Pair", "arity" to 2, "kind" to "boxed", "fieldReps" to List(2) { listOf("BoxedRep (Just Lifted)") }, "strictFields" to listOf(false, false), "fieldLifted" to listOf(true, true))))
    private fun withLanguage(action: (Language) -> Unit) {
        val old = System.getProperty(HANDOFF_PROPERTY)
        System.setProperty(HANDOFF_PROPERTY, "true")
        try {
            Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", "false")
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
                    finally { context.leave() }
                }
        } finally { if (old == null) System.clearProperty(HANDOFF_PROPERTY) else System.setProperty(HANDOFF_PROPERTY, old) }
    }
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private fun host(p: ExecutableProgram, name: String, vararg args: Any?): Any? =
        Calls.target(p.hostEntryTarget(args.size), arrayOf(p.entryValue(name), arrayOf(*args)))
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertNull(state.pending); assertEquals(0, state.arguments.retainedReferences())
    }
    private fun bottom() = Thunk(object : RootNode(null) {
        override fun execute(frame: VirtualFrame): Any? = throw RuntimeFault("unused reference handoff argument")
    }.callTarget, null)

    @Test fun referenceResultsPreserveIdentityNestedValuesAndLazyInputs() = withLanguage { language ->
        val args = listOf("a", "b", "c", "ignored").map { param(it) }
        val values = args.map { v(it.getValue("id") as String) }.toTypedArray()
        val fixtures = module(listOf(
            bind("box", listOf(param("n", long)), con("Box", v("n"))),
            bind("select", args, v("a")),
            bind("nested", args, let(call("select", *values), "first", let(call("select", v("b"), v("a"), v("c"), v("ignored")), "second", con("Pair", v("first"), v("second"))))),
            bind("objectIdentity", listOf(param("value", obj)), v("value"), obj),
            bind("number", listOf(param("n", long)), prim("+#", v("n"), n(1)), long),
            bind("objectViaLong", listOf(param("n", long)), call("number", v("n")), obj),
            bind("longViaObject", listOf(param("n", long)), call("objectViaLong", v("n")), long)))
        for (backend in listOf("ast", "bytecode")) {
            val p: ExecutableProgram = if (backend == "ast") Program(language, fixtures) else BytecodeProgram(language, fixtures)
            val a = host(p, "box", 3_000_000_017L); val b = host(p, "box", -7_000_000_003L); val c = host(p, "box", 11L)
            val lazy = bottom(); val boxed: Any = 8_000_000_019L
            fun check() {
                assertSame(a, host(p, "select", a, b, c, lazy), backend)
                val pair = host(p, "nested", a, b, c, lazy) as DataValue
                assertSame(a, pair.layout.read(pair, 0)); assertSame(b, pair.layout.read(pair, 1))
                language.handoffState.get().returnLong = -913L
                assertEquals(boxed, host(p, "objectIdentity", boxed), "An ordinary Long result must not read the scalar register")
                assertEquals(3_000_000_018L, host(p, "objectViaLong", 3_000_000_017L))
                assertEquals(3_000_000_018L, host(p, "longViaObject", 3_000_000_017L))
                assertEquals(0, lazy.state); released(language)
            }
            repeat(20) { check() }
            for (name in listOf("select", "nested", "objectIdentity", "number", "objectViaLong", "longViaObject")) compile(p.entryTarget(name))
            check()
        }
        assertTrue(language.handoffState.get().calls > 0)
    }

    @Test fun referenceTailCyclesAndCapturedPapPrefixesKeepNaturalResults() = withLanguage { language ->
        val args = listOf(param("n", long), param("a"), param("b"), param("c"), param("ignored"))
        fun next(id: String) = call(id, prim("-#", v("n"), n(1)), v("a"), v("b"), v("c"), v("ignored"))
        fun worker(id: String, body: RefCore) = bind(id, args, choose(prim("<=#", v("n"), n(0)), v("a"), body))
        val inner = lambda(listOf(param("ignored"), param("n", long)), v("captured"))
        val fixtures = module(listOf(bind("box", listOf(param("n", long)), con("Box", v("n"))),
            worker("self", next("self")), worker("rootA", next("rootB")), worker("rootB", next("rootC")), worker("rootC", next("rootD")),
            worker("rootD", next("rootE")), worker("rootE", choose(prim("<=#", v("n"), n(5)), next("rootB"), next("rootC"))),
            bind("factory", listOf(param("captured")), inner, closure)))
        for (backend in listOf("ast", "bytecode")) {
            val p: ExecutableProgram = if (backend == "ast") Program(language, fixtures) else BytecodeProgram(language, fixtures)
            val value = host(p, "box", Long.MAX_VALUE); val lazy = bottom()
            for (name in listOf("self", "rootA")) {
                for (depth in listOf(0L, 1L, 12L)) assertSame(value, host(p, name, depth, value, value, value, lazy))
                compile(p.entryTarget(name))
                assertSame(value, host(p, name, 15_000L, value, value, value, lazy)); released(language)
            }
            val worker = host(p, "factory", value) as Closure
            val pap = worker.pap(arrayOf(lazy))
            fun invoke() = Calls.target(p.hostEntryTarget(1), arrayOf(pap, arrayOf(3_000_000_017L)))
            assertSame(value, invoke()); compile(worker.target); assertSame(value, invoke())
            assertSame(lazy, pap.supplied.single()); assertEquals(0, lazy.state); released(language)
        }
        assertTrue(language.handoffState.get().tailTransfers > 0)
    }

    private class RememberReference(private val slot: Int, private val saved: MutableList<MaterializedFrame>) : Expr() {
        var compiledBeforeDeopt = false
        val boxedResult: Any = 8_000_000_019L
        override fun execute(frame: VirtualFrame): Any? {
            compiledBeforeDeopt = CompilerDirectives.inCompiledCode()
            saved.add(frame.materialize())
            val value = FrameAccess.read(frame, slot)
            if (value == "deopt") CompilerDirectives.transferToInterpreterAndInvalidate()
            if (value == "fail") throw RuntimeFault("reference handoff failure")
            return if (value == "boxed") boxedResult else value
        }
    }
    private class InvokeWorker(target: RootCallTarget) : GuestRoot(null, FrameLayout().build()) {
        @Child private var caller = DirectCallerNode(target, Metrics(false))
        override fun bloom(frame: VirtualFrame) = 0L
        override fun execute(frame: VirtualFrame): Any? = caller.call(frame, frame.arguments, false)
    }
    @Test fun referenceSnapshotsSurviveReleaseFailureDeoptimizationAndReuse() = withLanguage { language ->
        val proof = CoreRepresentation(CoreKind.OBJECT, true, true, listOf("BoxedRep (Just Lifted)"))
        val layout = FrameLayout(); val slot = layout.bind("value")
        val entry = HandoffEntry.create(language, layout, listOf(proof), proof, false)!!
        val saved = ArrayList<MaterializedFrame>(); val body = RememberReference(slot, saved).also { it.representation = proof }
        val root = FunctionRoot(language, layout.build(), "reference snapshots", null, intArrayOf(), intArrayOf(slot), intArrayOf(0),
            body, Metrics(false), arrayOf(proof), proof, null, booleanArrayOf(false), entry)
        val caller = InvokeWorker(root.callTarget)
        val original = Any(); assertSame(original, Calls.target(caller.callTarget, arrayOf(0L, original)))
        assertThrows(RuntimeFault::class.java) { Calls.target(caller.callTarget, arrayOf<Any?>(0L, "fail")) }; released(language)
        compile(root.callTarget)
        assertEquals("deopt", Calls.target(caller.callTarget, arrayOf<Any?>(0L, "deopt")))
        assertTrue(body.compiledBeforeDeopt)
        val replacement = Any(); assertSame(replacement, Calls.target(caller.callTarget, arrayOf(0L, replacement)))
        assertTrue(saved.all { it.arguments.isEmpty() })
        assertSame(original, saved[0].getObject(entry.snapshotSlots[1]))
        assertEquals("fail", saved[1].getObject(entry.snapshotSlots[1])); assertEquals("deopt", saved[2].getObject(entry.snapshotSlots[1]))
        language.handoffState.get().returnLong = -913L
        assertSame(body.boxedResult, Calls.target(caller.callTarget, arrayOf<Any?>(0L, "boxed")))
        released(language)
    }
}
