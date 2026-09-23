package thc

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*

private typealias TailCore = List<Any?>

/** Tail cycles must reenter the matching active root, not merely survive on the host trampoline. */
class TailCycleTest {
    private fun variable(id: String): TailCore = listOf("var", id)
    private fun integer(value: Long): TailCore = listOf("lit", "int", value.toString())
    private fun application(function: TailCore, arguments: List<TailCore>, lifted: List<Boolean>): TailCore =
        listOf("app", function, arguments, lifted)
    private fun call(name: String, vararg arguments: TailCore): TailCore =
        application(variable(name), arguments.toList(), List(arguments.size) { false })
    private fun primitive(name: String, vararg arguments: TailCore): TailCore =
        application(listOf("prim", name), arguments.toList(), List(arguments.size) { false })
    private fun lambda(parameters: List<String>, body: TailCore): TailCore = listOf("lam", parameters.map {
        mapOf("id" to it, "name" to it, "type" to "Int#", "lifted" to false, "coercion" to false)
    }, body)
    private fun binding(name: String, parameters: List<String>, body: TailCore): Map<String, Any?> = mapOf(
        "id" to name, "name" to name, "type" to "Synthetic", "lifted" to true,
        "arity" to parameters.size, "expr" to lambda(parameters, body))
    private fun caseDefault(value: TailCore, binder: String, body: TailCore): TailCore =
        listOf("case", value, binder, listOf(listOf("default", null, emptyList<String>(), body)))
    private fun choose(condition: TailCore, yes: TailCore, no: TailCore): TailCore = listOf("case", condition, "condition", listOf(
        listOf("lit", listOf("int", "1"), emptyList<String>(), yes),
        listOf("default", null, emptyList<String>(), no)))
    private fun module(bindings: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.TailCycle",
        "instrument" to true, "bindings" to bindings, "constructors" to emptyList<Any>())
    private fun closure(program: ExecutableProgram, name: String): Closure = program.entryValue(name) as Closure
    private fun target(program: ExecutableProgram, name: String): RootCallTarget = closure(program, name).target
    private fun count(program: ExecutableProgram, key: String): Long = (program.diagnostics().getValue(key) as Number).toLong()
    private fun invoke(program: ExecutableProgram, name: String, vararg arguments: Any?): Any? =
        Calls.target(program.hostEntryTarget(arguments.size), arrayOf(program.entryValue(name), arguments))
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target), "Guest code must be installed")
    }

    /** Bloom collisions are valid, but these fixtures specifically exercise active-ancestor reentry. */
    private fun collisionFree(paths: List<List<RootCallTarget>>): Boolean = paths.all { path ->
        var ancestry = 0L
        path.all { target ->
            val mask = (target.rootNode as GuestRoot).mask
            val fresh = ancestry and mask != mask
            ancestry = ancestry or mask
            fresh
        }
    }

    private fun bothBackends(data: Map<String, Any?>,
                             paths: (ExecutableProgram) -> List<List<RootCallTarget>>,
                             action: (ExecutableProgram, String) -> Unit) {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun create(): ExecutableProgram = if (backend == "ast") Program(language, data) else BytecodeProgram(language, data)
                var program: ExecutableProgram? = null
                for (attempt in 0 until 64) {
                    val candidate = create()
                    if (collisionFree(paths(candidate))) { program = candidate; break }
                }
                action(requireNotNull(program) { "Could not select collision-free bloom masks for $backend fixture" }, backend)
            } finally { context.leave() }
        }
    }

    private fun handled(program: ExecutableProgram, backend: String, minimumReentries: Long,
                        expected: Long, action: () -> Any?) {
        val reentries = count(program, "selfTailReentries")
        val trampolines = count(program, "trampolineIterations")
        assertEquals(expected, action(), backend)
        assertTrue(count(program, "selfTailReentries") - reentries >= minimumReentries,
            "$backend must consume tail transfers in the matching active root")
        assertEquals(trampolines, count(program, "trampolineIterations"),
            "$backend must not route this collision-free cycle through the outer trampoline")
    }

    private fun countingCycle(edges: List<Pair<String, String>>): Map<String, Any?> {
        val n = variable("remaining")
        val total = variable("total")
        return module(edges.map { (name, next) -> binding(name, listOf("remaining", "total"),
            choose(primitive("<=#", n, integer(0)), total,
                call(next, primitive("-#", n, integer(1)), primitive("+#", total, n)))) })
    }

    @Test fun twoRootCyclesReenterLocallyBeforeAndAfterCompilation() {
        bothBackends(countingCycle(listOf("a" to "b", "b" to "a")),
            { p -> listOf(listOf(target(p, "a"), target(p, "b"))) }) { program, backend ->
            val n = 12_000L
            val seed = 3_000_000_017L
            val expected = seed + n * (n + 1) / 2
            handled(program, backend, n / 2, expected) { invoke(program, "a", n, seed) }
            repeat(40) { assertEquals(seed + 36, invoke(program, "a", 8L, seed)) }
            compile(target(program, "a"))
            val compiled = count(program, "compiledEntries")
            handled(program, backend, n / 2, expected) { invoke(program, "a", n, seed) }
            assertTrue(count(program, "compiledEntries") > compiled)
            assertEquals(seed, invoke(program, "a", 0L, seed), "A fresh entry must not retain loop arguments")
        }
    }

    @Test fun anInteriorRootHandlesABCDECReturnsAndCanRetargetOutwardToB() {
        val n = variable("remaining")
        val total = variable("total")
        fun step(next: String) = call(next, primitive("-#", n, integer(1)), primitive("+#", total, n))
        fun worker(name: String, next: TailCore) = binding(name, listOf("remaining", "total"),
            choose(primitive("<=#", n, integer(0)), total, next))
        val data = module(listOf(
            binding("a", listOf("remaining", "total"), call("b", n, total)),
            binding("b", listOf("remaining", "total"), call("c", n, total)),
            worker("c", step("d")), worker("d", step("e")),
            worker("e", choose(primitive("<=#", n, integer(5)), step("b"), step("c")))))
        bothBackends(data, { p -> listOf(listOf("a", "b", "c", "d", "e").map { target(p, it) }) }) { program, backend ->
            val n = 12_000L
            val expected = n * (n + 1) / 2
            handled(program, backend, n / 3, expected) { invoke(program, "a", n, 0L) }
            repeat(40) { assertEquals(78L, invoke(program, "a", 12L, 0L)) }
            compile(target(program, "a"))
            val compiled = count(program, "compiledEntries")
            handled(program, backend, n / 3, expected) { invoke(program, "a", n, 0L) }
            assertTrue(count(program, "compiledEntries") > compiled)
            for (small in 0L..8L) assertEquals(small * (small + 1) / 2, invoke(program, "a", small, 0L))
        }
    }

    @Test fun reentryRestoresChangedCapturesAndPapPrefixesAcrossDifferentArities() {
        val n = variable("remaining")
        val total = variable("total")
        val seed = 3_000_000_017L
        val capturedBody = choose(primitive("<=#", n, integer(0)), primitive("+#", total, variable("base")),
            call("b", primitive("-#", n, integer(1)), primitive("+#", total, n), primitive("+#", variable("base"), integer(1))))
        val pap = application(variable("next"), listOf(n), listOf(false))
        val resume = caseDefault(call("make", variable("base")), "next",
            caseDefault(pap, "partial", application(variable("partial"), listOf(total), listOf(false))))
        val entry = caseDefault(call("make", integer(seed)), "first",
            application(variable("first"), listOf(variable("input"), integer(0)), listOf(false, false)))
        val data = module(listOf(
            binding("make", listOf("base"), lambda(listOf("remaining", "total"), capturedBody)),
            binding("b", listOf("remaining", "total", "base"), resume), binding("entry", listOf("input"), entry)))
        bothBackends(data, { p ->
            val made = invoke(p, "make", seed) as Closure
            listOf(listOf(target(p, "entry"), made.target, target(p, "b")))
        }) { program, backend ->
            val n = 4_000L
            val expected = seed + n + n * (n + 1) / 2
            val paps = count(program, "papAllocations")
            handled(program, backend, n, expected) { invoke(program, "entry", n) }
            assertTrue(count(program, "papAllocations") - paps >= n, "Each B→A transfer must exercise a supplied prefix")
            repeat(40) { assertEquals(seed + 44L, invoke(program, "entry", 8L)) }
            compile(target(program, "entry"))
            handled(program, backend, n, expected) { invoke(program, "entry", n) }
            assertEquals(seed + 35L, invoke(program, "entry", 7L), "Fresh captures must replace the previous loop's environment")
            assertEquals(seed, invoke(program, "entry", 0L))
        }
    }

    @Test fun overapplicationAndNonTailWorkSurviveAnInnerTailCycle() {
        val n = variable("remaining")
        val done = lambda(listOf("extra"), primitive("+#", variable("extra"), integer(42)))
        val data = module(listOf(
            binding("a", listOf("remaining"), choose(primitive("<=#", n, integer(0)), done,
                call("b", primitive("-#", n, integer(1))))),
            binding("b", listOf("remaining"), call("a", n)),
            binding("entry", listOf("input"), primitive("+#", call("a", variable("input"), integer(17)),
                primitive("+#", variable("input"), integer(1000))))))
        bothBackends(data, { p -> listOf(listOf(target(p, "a"), target(p, "b"))) }) { program, backend ->
            val n = 4_000L
            handled(program, backend, n, n + 1059L) { invoke(program, "entry", n) }
            repeat(40) { assertEquals(1067L, invoke(program, "entry", 8L)) }
            compile(target(program, "entry"))
            handled(program, backend, n, n + 1059L) { invoke(program, "entry", n) }
            assertEquals(1059L, invoke(program, "entry", 0L))
        }
    }

    @Test fun aNonTailEdgeRetainsEveryPendingAddition() {
        val n = variable("remaining")
        val data = module(listOf(
            binding("a", listOf("remaining"), choose(primitive("<=#", n, integer(0)), integer(0),
                primitive("+#", integer(1), call("b", primitive("-#", n, integer(1)))))),
            binding("b", listOf("remaining"), choose(primitive("<=#", n, integer(0)), integer(0),
                call("a", primitive("-#", n, integer(1)))))))
        bothBackends(data, { p -> listOf(listOf(target(p, "b"), target(p, "a"))) }) { program, _ ->
            val before = count(program, "selfTailReentries")
            for (n in listOf(0L, 1L, 2L, 17L, 120L)) assertEquals((n + 1) / 2, invoke(program, "a", n))
            repeat(40) { assertEquals(4L, invoke(program, "a", 8L)) }
            compile(target(program, "a"))
            for (n in listOf(0L, 1L, 2L, 17L, 120L)) assertEquals((n + 1) / 2, invoke(program, "a", n))
            assertEquals(before, count(program, "selfTailReentries"),
                "A non-tail A→B edge starts new ancestry; B→A must not discard A's pending addition")
        }
    }

    private class CloneCaller(target: RootCallTarget) : RootNode(null) {
        @Child private var call = DirectCallNode.create(target)
        override fun execute(frame: VirtualFrame): Any? = Calls.direct(call, frame.arguments)
        fun cloneTarget(): RootCallTarget {
            callTarget // Adopt the call node before requesting a clone.
            assertTrue(call.isCallTargetCloningAllowed)
            assertTrue(call.cloneCallTarget(), "Exercise an actual Truffle target clone")
            return call.clonedCallTarget as RootCallTarget
        }
    }

    @Test fun aClonedRootConsumesTransfersTargetingItsOriginalBody() {
        bothBackends(countingCycle(listOf("a" to "b", "b" to "a")),
            { p -> listOf(listOf(target(p, "a"), target(p, "b"))) }) { program, backend ->
            val original = target(program, "a")
            val driver = CloneCaller(original)
            val cloned = driver.cloneTarget()
            assertNotSame(original, cloned)
            assertTrue((cloned.rootNode as GuestRoot).isSelf(original))
            assertFalse((cloned.rootNode as GuestRoot).isSelf(target(program, "b")))
            val n = 4_000L
            val expected = n * (n + 1) / 2
            fun invokeClone(input: Long): Any? = Calls.target(driver.callTarget, arrayOf(0L, input, 0L))
            handled(program, backend, n / 2, expected) { invokeClone(n) }
            repeat(40) { assertEquals(36L, invokeClone(8L)) }
            compile(cloned)
            val compiled = count(program, "compiledEntries")
            handled(program, backend, n / 2, expected) { invokeClone(n) }
            assertTrue(count(program, "compiledEntries") > compiled)
            assertEquals(0L, invokeClone(0L))
        }
    }

    /** A controlled false-positive bloom hit still has to reach the legitimate outer fallback. */
    private class FalsePositiveCaller(target: RootCallTarget, private val incomingBloom: Long) : RootNode(null) {
        @Child private var call = DirectCallNode.create(target)
        private val metrics = Metrics(true)
        @Child private var fallback = TailCallLoop(metrics)
        val iterations: Long get() = metrics.trampolineIterations
        override fun execute(frame: VirtualFrame): Any? {
            val packet = arrayOf<Any?>(incomingBloom, frame.arguments[0], frame.arguments[1])
            return try { Calls.direct(call, packet) } catch (tail: TailCall) { fallback.execute(tail) }
        }
    }

    @Test fun falsePositiveAncestryStillUsesTheTrampolineWhenNoActiveRootMatches() {
        bothBackends(countingCycle(listOf("a" to "b", "b" to "a")),
            { p -> listOf(listOf(target(p, "a"), target(p, "b")), listOf(target(p, "b"), target(p, "a"))) }) { program, _ ->
            val bMask = (target(program, "b").rootNode as GuestRoot).mask
            val driver = FalsePositiveCaller(target(program, "a"), bMask)
            val n = 400L
            assertEquals(n * (n + 1) / 2, Calls.target(driver.callTarget, arrayOf(n, 0L)))
            assertEquals(1L, driver.iterations, "A false hit must escape A, reset ancestry, and then resume locally")
            assertTrue(count(program, "selfTailReentries") > 0)
        }
    }
}
