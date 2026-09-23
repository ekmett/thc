package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias SelfEntryCore = List<Any?>

/** Root reentry must uphold the same saturated contract as an ordinary call. */
class EntrySelfCallTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val data = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun variable(id: String): SelfEntryCore = listOf("var", id)
    private fun integer(n: Long): SelfEntryCore = listOf("lit", "int", n.toString())
    private fun parameter(id: String, rep: Map<String, Any> = long) = mapOf(
        "id" to id, "name" to id, "lifted" to (rep !== long), "coercion" to false, "rep" to rep)
    private fun lambda(args: List<Map<String, Any>>, body: SelfEntryCore, strict: List<Boolean> = List(args.size) { false },
                       result: Map<String, Any> = long): SelfEntryCore =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result, "entryStrict" to strict))
    private fun binding(id: String, rhs: SelfEntryCore): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "lifted" to true, "expr" to rhs)
    private fun apply(fn: SelfEntryCore, args: List<SelfEntryCore>, lifted: List<Boolean> = List(args.size) { false }): SelfEntryCore =
        listOf("app", fn, args, lifted, false, false)
    private fun primitive(name: String, vararg args: SelfEntryCore) = apply(listOf("prim", name), args.toList())
    private fun box(n: SelfEntryCore): SelfEntryCore = listOf("app", listOf("con", "Box", 1), listOf(n), listOf(false), true, true)
    private fun choose(value: SelfEntryCore, zero: SelfEntryCore, other: SelfEntryCore): SelfEntryCore = listOf("case", value, "choice",
        listOf(listOf("lit", listOf("int", "0"), emptyList<String>(), zero), listOf("default", null, emptyList<String>(), other)))
    private fun caseDefault(value: SelfEntryCore, id: String, body: SelfEntryCore): SelfEntryCore =
        listOf("case", value, id, listOf(listOf("default", null, emptyList<String>(), body)))
    private fun delayedBox(n: SelfEntryCore) = choose(n, box(integer(9)), box(n))
    private fun count(p: ExecutableProgram, name: String) = (p.diagnostics().getValue(name) as Number).toLong()
    private fun call(p: ExecutableProgram, name: String, vararg args: Any?): Any? =
        Calls.target(p.hostEntryTarget(args.size), arrayOf(p.entryValue(name), arrayOf(*args)))
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private fun eachBackend(bindings: List<Map<String, Any?>>, collisionPath: List<String> = emptyList(),
                            action: (String, ExecutableProgram) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (backend in listOf("ast", "bytecode")) {
                    val module = mapOf("bindings" to bindings, "instrument" to true, "constructors" to listOf(
                        mapOf("id" to "Box", "name" to "Box", "arity" to 1, "kind" to "boxed", "fieldReps" to listOf(listOf("IntRep")),
                            "strictFields" to listOf(false), "fieldLifted" to listOf(false))))
                    var selected: ExecutableProgram? = null
                    repeat(64) {
                        if (selected == null) {
                            val candidate = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                            var ancestry = 0L
                            if (collisionPath.all { name ->
                                val mask = (candidate.entryTarget(name).rootNode as GuestRoot).mask
                                val fresh = ancestry and mask != mask
                                ancestry = ancestry or mask
                                fresh
                            }) selected = candidate
                        }
                    }
                    action(backend, requireNotNull(selected) { "Could not select collision-free roots for $backend" })
                }
            } finally { context.leave() }
        }
    }

    @Test fun directSelfPapPrefixForcesEvenUnusedStrictFormalsBeforeReentry() {
        fun worker(name: String, prefix: SelfEntryCore): Map<String, Any?> {
            val partial = apply(variable(name), listOf(prefix), listOf(true))
            val again = apply(variable("partial"), listOf(primitive("-#", variable("remaining"), integer(1))))
            return binding(name, lambda(listOf(parameter("unused", data), parameter("remaining")),
                choose(variable("remaining"), integer(42), caseDefault(partial, "partial", again)), listOf(true, false)))
        }
        val safe = worker("worker", delayedBox(variable("remaining")))
        // Unsafe construction must remain suspended during partial application,
        // then fail at saturation even though the marked parameter is unused.
        val failing = worker("failing", apply(listOf("con", "Box", 1),
            listOf(primitive("quotInt#", integer(1), primitive("-#", variable("remaining"), integer(1))))))
        val makeBox = binding("makeBox", lambda(listOf(parameter("input")), box(variable("input")), result = data))
        eachBackend(listOf(safe, failing, makeBox)) { backend, p ->
            val initial = call(p, "makeBox", Long.MAX_VALUE)
            fun check(n: Long) {
                val evaluations = count(p, "thunkEvaluations")
                val paps = count(p, "papAllocations")
                val trampolines = count(p, "trampolineIterations")
                assertEquals(42L, call(p, "worker", initial, n), backend)
                assertEquals(evaluations + n, count(p, "thunkEvaluations"), "$backend must force every unused PAP prefix")
                assertEquals(paps + n, count(p, "papAllocations"), backend)
                assertEquals(trampolines, count(p, "trampolineIterations"), backend)
            }
            repeat(30) { check(8L) }
            compile(p.entryTarget("worker"))
            val compiled = count(p, "compiledEntries")
            check(4_000L)
            assertTrue(count(p, "compiledEntries") > compiled, backend)
            check(0L)
            assertThrows(ArithmeticException::class.java) { call(p, "failing", initial, 1L) }
        }
    }

    @Test fun ancestorReentryReceivesForcedArgumentsThroughUnknownCalls() {
        val remaining = variable("remaining")
        val a = binding("a", lambda(listOf(parameter("unused", data), parameter("remaining")),
            choose(remaining, integer(73), apply(variable("b"), listOf(primitive("-#", remaining, integer(1))))), listOf(true, false)))
        // A case-bound function has no immutable entry contract in the compiler;
        // Dispatch must enforce A's contract before the ancestor bounce packet.
        val b = binding("b", lambda(listOf(parameter("remaining")), caseDefault(variable("a"), "unknown",
            apply(variable("unknown"), listOf(delayedBox(remaining), remaining), listOf(true, false)))))
        val makeBox = binding("makeBox", lambda(listOf(parameter("input")), box(variable("input")), result = data))
        eachBackend(listOf(a, b, makeBox), listOf("a", "b")) { backend, p ->
            val initial = call(p, "makeBox", Long.MIN_VALUE)
            fun check(n: Long) {
                val evaluations = count(p, "thunkEvaluations")
                val reentries = count(p, "selfTailReentries")
                val trampolines = count(p, "trampolineIterations")
                assertEquals(73L, call(p, "a", initial, n), backend)
                assertEquals(evaluations + n, count(p, "thunkEvaluations"), "$backend must enforce the returning packet's contract")
                assertEquals(reentries + n, count(p, "selfTailReentries"), backend)
                assertEquals(trampolines, count(p, "trampolineIterations"), backend)
            }
            repeat(30) { check(8L) }
            compile(p.entryTarget("a"))
            val compiled = count(p, "compiledEntries")
            check(4_000L)
            assertTrue(count(p, "compiledEntries") > compiled, backend)
            check(0L)
        }
    }
}
