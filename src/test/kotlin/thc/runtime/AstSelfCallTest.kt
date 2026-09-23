package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias AstSelfCore = List<Any?>

class AstSelfCallTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun variable(id: String): AstSelfCore = listOf("var", id)
    private fun integer(n: Long): AstSelfCore = listOf("lit", "int", n.toString())
    private fun parameter(id: String) = mapOf("id" to id, "name" to id, "lifted" to false, "coercion" to false, "rep" to long)
    private fun lambda(ids: List<String>, body: AstSelfCore, result: Map<String, Any> = long): AstSelfCore =
        listOf("lam", ids.map(::parameter), body, mapOf("rep" to closure, "resultRep" to result))
    private fun binding(id: String, rhs: AstSelfCore): Map<String, Any?> = mapOf("id" to id, "name" to id, "lifted" to true, "expr" to rhs)
    private fun apply(fn: AstSelfCore, vararg args: AstSelfCore): AstSelfCore = listOf("app", fn, args.toList(), List(args.size) { false })
    private fun primitive(name: String, vararg args: AstSelfCore) = apply(listOf("prim", name), *args)
    private fun choose(value: AstSelfCore, zero: AstSelfCore, other: AstSelfCore): AstSelfCore = listOf("case", value, "choice",
        listOf(listOf("lit", listOf("int", "0"), emptyList<String>(), zero), listOf("default", null, emptyList<String>(), other)))
    private fun caseDefault(value: AstSelfCore, id: String, body: AstSelfCore): AstSelfCore =
        listOf("case", value, id, listOf(listOf("default", null, emptyList<String>(), body)))
    private fun count(p: ExecutableProgram, name: String) = (p.diagnostics().getValue(name) as Number).toLong()
    private fun call(p: ExecutableProgram, function: Any?, vararg args: Any?): Any? =
        Calls.target(p.hostEntryTarget(args.size), arrayOf(function, arrayOf(*args)))
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private fun withProgram(bindings: List<Map<String, Any?>>, action: (Program) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                action(Program(language, mapOf("bindings" to bindings, "instrument" to true)))
            } finally { context.leave() }
        }
    }

    @Test fun selfTransferSwapsWidePrimitiveArgumentsAndSurvivesColdCompiledRecursion() {
        val n = variable("remaining"); val x = variable("x"); val y = variable("y")
        val body = choose(n, primitive("-#", x, y),
            apply(variable("swap"), primitive("-#", n, integer(1)), y, x))
        withProgram(listOf(binding("swap", lambda(listOf("remaining", "x", "y"), body)))) { p ->
            val fn = p.entryValue("swap") as Closure
            fun check(depth: Long, first: Long, second: Long) {
                val reentries = count(p, "selfTailReentries")
                val bounces = count(p, "tailBounces")
                val expected = if (depth % 2 == 0L) first - second else second - first
                assertEquals(expected, call(p, fn, depth, first, second))
                assertEquals(reentries + depth, count(p, "selfTailReentries"))
                assertEquals(bounces, count(p, "tailBounces"), "Self transfers must never build a tail packet")
                assertEquals(0L, count(p, "trampolineIterations"))
            }
            repeat(30) { check(0, 3_000_000_017, -7_000_000_003) }
            compile(fn.target)
            check(100_001, Long.MIN_VALUE, Long.MAX_VALUE)
            compile(fn.target)
            val compiled = count(p, "compiledEntries")
            for (depth in listOf(0L, 1L, 2L, 10_000L, 10_001L)) check(depth, 3_000_000_017, -7_000_000_003)
            assertTrue(count(p, "compiledEntries") > compiled)
        }
    }

    @Test fun selfTransferRestoresDistinctEnvironmentsAndTheirRecursiveCells() {
        val n = variable("remaining"); val x = variable("x"); val y = variable("y")
        val decrement = primitive("-#", n, integer(1))
        val self = apply(variable("worker"), decrement, y, x)
        val fresh = caseDefault(apply(variable("make"), primitive("+#", variable("base"), integer(1))), "next",
            apply(variable("next"), decrement, y, x))
        val worker = lambda(listOf("remaining", "x", "y"), choose(n,
            primitive("+#", variable("base"), primitive("-#", x, y)),
            choose(primitive("-#", n, integer(1)), self, fresh)))
        val group: AstSelfCore = listOf("let", true, listOf(binding("worker", worker)), variable("worker"))
        withProgram(listOf(binding("make", lambda(listOf("base"), group, closure)))) { p ->
            val a = call(p, p.entryValue("make"), 3_000_000_017L) as Closure
            val b = call(p, p.entryValue("make"), -7_000_000_003L) as Closure
            assertSame(a.target, b.target)
            assertNotSame(a.environment, b.environment)
            fun check(fn: Closure, base: Long, depth: Long, first: Long, second: Long) {
                val reentries = count(p, "selfTailReentries")
                val bounces = count(p, "tailBounces")
                val expected = base + maxOf(0, depth - 1) + if (depth % 2 == 0L) first - second else second - first
                assertEquals(expected, call(p, fn, depth, first, second))
                assertEquals(reentries + depth, count(p, "selfTailReentries"))
                assertEquals(bounces, count(p, "tailBounces"))
            }
            repeat(30) { check(a, 3_000_000_017, 8, 3_000_000_019, -7_000_000_005) }
            compile(a.target)
            val compiled = count(p, "compiledEntries")
            for (depth in listOf(0L, 1L, 2L, 1_000L, 1_001L)) {
                check(b, -7_000_000_003, depth, Long.MIN_VALUE, Long.MAX_VALUE)
                check(a, 3_000_000_017, depth, Long.MAX_VALUE, Long.MIN_VALUE)
            }
            assertTrue(count(p, "compiledEntries") > compiled)
            assertEquals(0L, count(p, "blackholes"))
            assertEquals(0L, count(p, "trampolineIterations"))
        }
    }

    @Test fun polymorphicTailSiteRetainsSelfTransferAfterThreeNegativeTargetCacheEntries() {
        val args = listOf(
            mapOf("id" to "next", "name" to "next", "lifted" to true, "coercion" to false,
                "rep" to (closure + ("evaluated" to false))),
            parameter("remaining"), parameter("answer"))
        fun callable(body: AstSelfCore): AstSelfCore =
            listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to long))
        val body = choose(variable("remaining"), variable("answer"),
            apply(variable("next"), variable("next"),
                primitive("-#", variable("remaining"), integer(1)), variable("answer")))
        val bindings = listOf(binding("worker", callable(body))) + (1L..4L).map { marker ->
            binding("other$marker", callable(primitive("+#", variable("answer"), integer(marker))))
        }
        withProgram(bindings) { p ->
            val worker = p.entryValue("worker") as Closure
            val others = (1L..4L).map { p.entryValue("other$it") as Closure }
            fun nonSelf(seed: Long) {
                val reentries = count(p, "selfTailReentries")
                others.forEachIndexed { index, target ->
                    assertEquals(seed + index + 1, call(p, worker, target, 1L, seed))
                }
                assertEquals(reentries, count(p, "selfTailReentries"))
            }
            // Fill all three negative self-target entries and cross the normal
            // Dispatch cache limit before the site ever sees its own target.
            repeat(30) { nonSelf(3_000_000_017) }
            compile(worker.target)
            fun self(depth: Long, seed: Long) {
                val reentries = count(p, "selfTailReentries")
                val bounces = count(p, "tailBounces")
                val trampolines = count(p, "trampolineIterations")
                assertEquals(seed, call(p, worker, worker, depth, seed))
                assertEquals(reentries + depth, count(p, "selfTailReentries"))
                assertEquals(bounces, count(p, "tailBounces"))
                assertEquals(trampolines, count(p, "trampolineIterations"))
            }
            self(10_001, Long.MIN_VALUE)
            compile(worker.target)
            val compiled = count(p, "compiledEntries")
            nonSelf(Long.MAX_VALUE)
            self(10_000, Long.MAX_VALUE)
            nonSelf(-7_000_000_003)
            self(1, 3_000_000_017)
            assertTrue(count(p, "compiledEntries") > compiled)
        }
    }

    private class CloneCaller(target: RootCallTarget) : RootNode(null) {
        @Child private var call = DirectCallNode.create(target)
        override fun execute(frame: VirtualFrame): Any? = Calls.direct(call, frame.arguments)
        fun cloneTarget(): RootCallTarget {
            callTarget
            assertTrue(call.cloneCallTarget())
            return call.clonedCallTarget as RootCallTarget
        }
    }

    @Test fun clonedRootsShareBodyIdentityWhileKeepingIndependentSelfTargetCaches() {
        val n = variable("remaining")
        val body = choose(n, integer(91), apply(variable("worker"), primitive("-#", n, integer(1))))
        withProgram(listOf(binding("worker", lambda(listOf("remaining"), body)))) { p ->
            val original = p.entryTarget("worker")
            val caller = CloneCaller(original)
            val cloned = caller.cloneTarget()
            assertNotSame(original, cloned)
            fun check(target: RootCallTarget, n: Long) {
                val reentries = count(p, "selfTailReentries")
                assertEquals(91L, Calls.target(target, arrayOf(0L, n)))
                assertEquals(reentries + n, count(p, "selfTailReentries"))
                assertEquals(0L, count(p, "tailBounces"))
            }
            // Warm the clone first, then the original: cache insertions must not
            // mutate an array retained by the other root or its compiled graph.
            repeat(30) { check(cloned, 8) }
            compile(cloned)
            repeat(30) { check(original, 8) }
            compile(original)
            check(cloned, 10_001)
            check(original, 10_000)
        }
    }
}
