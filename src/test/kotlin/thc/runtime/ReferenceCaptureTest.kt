package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias ReferenceCore = List<Any?>

class ReferenceCaptureTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val data = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
    private fun variable(id: String): ReferenceCore = listOf("var", id)
    private fun integer(n: Long): ReferenceCore = listOf("lit", "int", n.toString())
    private fun parameter(id: String, rep: Map<String, Any> = long) = mapOf(
        "id" to id, "name" to id, "lifted" to (rep === data || rep === closure), "coercion" to false, "rep" to rep)
    private fun lambda(args: List<Map<String, Any>>, body: ReferenceCore, result: Map<String, Any> = long): ReferenceCore =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result))
    private fun binding(id: String, rhs: ReferenceCore, rep: Map<String, Any> = closure): Map<String, Any?> =
        parameter(id, rep) + mapOf("expr" to rhs)
    private fun apply(fn: ReferenceCore, args: List<ReferenceCore>, lifted: List<Boolean> = List(args.size) { false }): ReferenceCore =
        listOf("app", fn, args, lifted, false, false)
    private fun primitive(name: String, vararg args: ReferenceCore) = apply(listOf("prim", name), args.toList())
    private fun box(n: ReferenceCore): ReferenceCore = listOf("app", listOf("con", "Box", 1), listOf(n), listOf(false), true, true,
        mapOf("rep" to (data + ("evaluated" to true))))
    private fun unbox(value: ReferenceCore, body: ReferenceCore = variable("payload")): ReferenceCore = listOf("case", value, "boxed",
        listOf(listOf("data", "Box", listOf("payload"), body, mapOf("binders" to listOf(parameter("payload"))))),
        mapOf("binder" to parameter("boxed", data)))
    private fun choose(value: ReferenceCore, zero: ReferenceCore, other: ReferenceCore): ReferenceCore = listOf("case", value, "choice",
        listOf(listOf("lit", listOf("int", "0"), emptyList<String>(), zero), listOf("default", null, emptyList<String>(), other)))
    private fun local(group: List<Map<String, Any?>>, body: ReferenceCore, recursive: Boolean = false): ReferenceCore =
        listOf("let", recursive, group, body)
    private fun count(p: ExecutableProgram, name: String) = (p.diagnostics().getValue(name) as Number).toLong()
    private fun call(p: ExecutableProgram, fn: Any?, vararg args: Any?): Any? =
        Calls.target(p.hostEntryTarget(args.size), arrayOf(fn, arrayOf(*args)))
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private fun inLanguage(action: (Language) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    }
    private fun eachBackend(bindings: List<Map<String, Any?>>, action: (String, ExecutableProgram) -> Unit) = inLanguage { language ->
        for (backend in listOf("ast", "bytecode")) {
            val module = mapOf("bindings" to bindings, "instrument" to true, "constructors" to listOf(
                mapOf("id" to "Box", "name" to "Box", "arity" to 1, "kind" to "boxed", "fieldReps" to listOf(listOf("IntRep")),
                    "strictFields" to listOf(false), "fieldLifted" to listOf(false))))
            action(backend, if (backend == "ast") Program(language, module) else BytecodeProgram(language, module))
        }
    }

    @Test fun preciseReferenceAndPrimitiveCapturesRoundTripThroughBothStorageEntrypoints() = inLanguage { language ->
        val target = object : RootNode(null) { override fun execute(frame: VirtualFrame): Any? = 0L }.callTarget
        val constructor = DataLayout(language, "Box", "Box", arrayOf("IntRep"))
        val boxed = constructor.create(arrayOf(3_000_000_017L))
        val function = Closure(null, arity = 1, target = target)
        val literal = LiteralAddress.fromHex("41ff")
        val thunk = Thunk(target, null)
        val cell = RecCell()
        val values = arrayOf<Any?>(boxed, function, literal, Long.MIN_VALUE, thunk, cell)
        // Legacy inferred reference values may still be primitive-eligible;
        // the exact reference proof supersedes that adaptive representation.
        val captures = CaptureLayout(language, booleanArrayOf(true, true, false, true, false, false),
            booleanArrayOf(false, false, false, true, false, false),
            arrayOf(DataValue::class.java, Closure::class.java, LiteralAddress::class.java, null, null, null))
        val layout = FrameLayout()
        val slots = IntArray(values.size) { layout.bind("capture$it") }
        val descriptor = layout.build()
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
        values.forEachIndexed { index, value -> FrameAccess.write(frame, slots[index], value) }
        for (environment in listOf(captures.capture(frame, slots), captures.captureValues(values))) {
            val restored = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
            for (index in values.indices) {
                captures.restore(environment, index, restored, slots[index])
                if (index == 3) {
                    assertTrue(environment.isLong(index)); assertEquals(Long.MIN_VALUE, restored.getLong(slots[index]))
                } else {
                    assertTrue(environment.isObject(index)); assertSame(values[index], environment.getObject(index))
                    assertSame(values[index], FrameAccess.read(restored, slots[index]))
                }
            }
            assertEquals(255L, (environment.getObject(2) as LiteralAddress).indexChar(1))
            assertFalse(cell.initialized)
            assertEquals(0, thunk.state)
        }
        assertThrows(RuntimeException::class.java) { captures.captureValues(values.copyOf().also { it[0] = thunk }) }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureLayout(language, booleanArrayOf(true), booleanArrayOf(true), arrayOf(DataValue::class.java))
        }
    }

    @Test fun compiledEscapedClosuresKeepDataFunctionsAndLiteralAddressesPrecise() {
        val add = lambda(listOf(parameter("extra")), primitive("+#", variable("input"), variable("extra")))
        val body = primitive("+#", unbox(variable("tree")), primitive("+#",
            apply(variable("add"), listOf(variable("index"))),
            primitive("indexCharOffAddr#", variable("bytes"), primitive("andI#", variable("index"), integer(1)))))
        val maker = lambda(listOf(parameter("input")), local(listOf(
            binding("tree", box(variable("input")), data), binding("add", add),
            binding("bytes", listOf("lit", "string-bytes", "41ff", mapOf("rep" to address)), address)),
            lambda(listOf(parameter("index")), body)), closure)
        eachBackend(listOf(binding("make", maker))) { backend, p ->
            val a = call(p, p.entryValue("make"), 3_000_000_017L) as Closure
            val b = call(p, p.entryValue("make"), -7_000_000_003L) as Closure
            assertSame(a.target, b.target, backend)
            fun check(fn: Closure, base: Long, index: Long) {
                assertEquals(base + base + index + if (index and 1L == 0L) 65 else 255, call(p, fn, index), backend)
            }
            repeat(30) { check(a, 3_000_000_017, it.toLong()); check(b, -7_000_000_003, it.toLong()) }
            compile(a.target)
            for (index in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, 1L)) {
                check(a, 3_000_000_017, index); check(b, -7_000_000_003, index)
            }
            assertEquals(0L, count(p, "thunkEvaluations"), backend)
        }
    }

    @Test fun lazyAndCaseRefinedRecursiveCapturesRetainTheirPhysicalStorage() {
        val nested = lambda(listOf(parameter("delta")), unbox(variable("shared"), primitive("+#", variable("payload"), variable("delta"))))
        val reader = lambda(listOf(parameter("extra")), unbox(variable("shared"),
            local(listOf(binding("nested", nested)), apply(variable("nested"), listOf(variable("extra"))))))
        val recursiveGroup = listOf(binding("shared", box(variable("input")), data), binding("reader", reader))
        val makeRecursive = binding("recursive", lambda(listOf(parameter("input")),
            local(recursiveGroup, variable("reader"), true), closure))
        val makePublished = binding("published", lambda(listOf(parameter("input")),
            local(recursiveGroup, unbox(variable("shared"), nested), true), closure))
        val ignoreOrForce = lambda(listOf(parameter("n")), choose(variable("n"), integer(7), unbox(variable("tree"))))
        val lazyFactory = binding("lazyFactory", lambda(listOf(parameter("tree", data)), ignoreOrForce, closure))
        val unsafeBox = apply(listOf("con", "Box", 1), listOf(primitive("quotInt#", integer(1), variable("input"))))
        val lazyEntry = binding("lazy", lambda(listOf(parameter("input")),
            apply(variable("lazyFactory"), listOf(unsafeBox), listOf(true)), closure))
        eachBackend(listOf(makeRecursive, makePublished, lazyFactory, lazyEntry)) { backend, p ->
            val a = call(p, p.entryValue("recursive"), 3_000_000_017L) as Closure
            val b = call(p, p.entryValue("recursive"), -7_000_000_003L) as Closure
            assertEquals(0L, count(p, "thunkEvaluations"), backend)
            repeat(30) {
                assertEquals(3_000_000_017L + it, call(p, a, it.toLong()), backend)
                assertEquals(-7_000_000_003L + it, call(p, b, it.toLong()), backend)
            }
            assertEquals(2L, count(p, "thunkEvaluations"), backend)
            compile(a.target)
            assertEquals(3_000_000_017L + Long.MAX_VALUE, call(p, a, Long.MAX_VALUE), backend)
            val published = call(p, p.entryValue("published"), Long.MIN_VALUE) as Closure
            assertEquals(3L, count(p, "thunkEvaluations"), backend)
            repeat(30) { assertEquals(Long.MIN_VALUE + it, call(p, published, it.toLong()), backend) }
            compile(published.target)
            assertEquals(-1L, call(p, published, Long.MAX_VALUE), backend)
            val lazy = call(p, p.entryValue("lazy"), 0L) as Closure
            repeat(30) { assertEquals(7L, call(p, lazy, 0L), backend) }
            compile(lazy.target)
            assertEquals(3L, count(p, "thunkEvaluations"), "$backend must not force a captured bottom")
            assertThrows(ArithmeticException::class.java) { call(p, lazy, 1L) }
        }
    }
}
