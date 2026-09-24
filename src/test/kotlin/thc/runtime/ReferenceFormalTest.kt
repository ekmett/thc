// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias FormalCore = List<Any?>

class ReferenceFormalTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val data = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
    private fun variable(id: String): FormalCore = listOf("var", id)
    private fun integer(n: Long): FormalCore = listOf("lit", "int", n.toString())
    private fun parameter(id: String, rep: Map<String, Any> = long) = mapOf(
        "id" to id, "name" to id, "lifted" to (rep === data || rep === closure), "coercion" to false, "rep" to rep)
    private fun lambda(args: List<Map<String, Any>>, body: FormalCore, strict: List<Boolean> = List(args.size) { false },
                       result: Map<String, Any> = long): FormalCore =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result, "entryStrict" to strict))
    private fun binding(id: String, rhs: FormalCore) = mapOf("id" to id, "name" to id, "lifted" to true, "expr" to rhs)
    private fun apply(fn: FormalCore, args: List<FormalCore>, lifted: List<Boolean> = List(args.size) { false }): FormalCore =
        listOf("app", fn, args, lifted, false, false)
    private fun primitive(name: String, vararg args: FormalCore) = apply(listOf("prim", name), args.toList())
    private fun box(n: FormalCore): FormalCore = listOf("app", listOf("con", "Box", 1), listOf(n), listOf(false), true, true)
    private fun unbox(value: FormalCore): FormalCore = listOf("case", value, "boxed",
        listOf(listOf("data", "Box", listOf("payload"), variable("payload"), mapOf("binders" to listOf(parameter("payload"))))),
        mapOf("rep" to long, "binder" to parameter("boxed", data)))
    private fun choose(value: FormalCore, zero: FormalCore, other: FormalCore): FormalCore = listOf("case", value, "choice",
        listOf(listOf("lit", listOf("int", "0"), emptyList<String>(), zero), listOf("default", null, emptyList<String>(), other)))
    private fun count(p: ExecutableProgram, name: String) = (p.diagnostics().getValue(name) as Number).toLong()
    private fun call(p: ExecutableProgram, name: String, vararg args: Any?): Any? =
        Calls.target(p.hostEntryTarget(args.size), arrayOf(p.entryValue(name), arrayOf(*args)))
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private fun eachBackend(bindings: List<Map<String, Any>>, action: (String, ExecutableProgram) -> Unit) {
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
                            val a = candidate.entryTarget("a").rootNode as GuestRoot
                            val b = candidate.entryTarget("b").rootNode as GuestRoot
                            if (a.mask and b.mask != b.mask) selected = candidate
                        }
                    }
                    action(backend, requireNotNull(selected))
                }
            } finally { context.leave() }
        }
    }

    private fun fixtures(): List<Map<String, Any>> {
        val parameters = listOf(parameter("n"), parameter("left", data), parameter("right", data),
            parameter("f", closure), parameter("g", closure), parameter("p", address), parameter("q", address))
        val flags = listOf(false, true, true, true, true, false, false)
        val strict = listOf(false, true, true, true, true, false, false)
        val result = primitive("+#", primitive("+#", unbox(variable("left")), primitive("*#", integer(2), unbox(variable("right")))),
            primitive("+#", apply(variable("f"), listOf(primitive("indexCharOffAddr#", variable("p"), integer(0)))),
                primitive("*#", integer(3), apply(variable("g"), listOf(primitive("indexCharOffAddr#", variable("q"), integer(0)))))))
        val swapped = listOf(primitive("-#", variable("n"), integer(1)), variable("right"), variable("left"),
            variable("g"), variable("f"), variable("q"), variable("p"))
        fun loop(name: String, next: String) = binding(name, lambda(parameters,
            choose(variable("n"), result, apply(variable(next), swapped, flags)), strict))
        val forward = apply(variable("a"), parameters.map { variable(it["id"] as String) }, flags)
        val lazySelect = binding("lazy", lambda(listOf(parameter("tree", data), parameter("n")),
            choose(variable("n"), integer(7), unbox(variable("tree")))))
        return listOf(loop("self", "self"), loop("a", "b"), binding("b", lambda(parameters, forward, strict)),
            binding("box", lambda(listOf(parameter("input")), box(variable("input")), result = data)),
            binding("add", lambda(listOf(parameter("base")), lambda(listOf(parameter("input")),
                primitive("+#", variable("base"), variable("input"))), result = closure)), lazySelect,
            binding("strictData", lambda(listOf(parameter("value", data), parameter("n")),
                choose(variable("n"), integer(7), unbox(variable("value"))), listOf(true, false))),
            binding("strictClosure", lambda(listOf(parameter("value", closure), parameter("n")),
                choose(variable("n"), integer(7), apply(variable("value"), listOf(variable("n")))), listOf(true, false))),
            binding("strictAddress", lambda(listOf(parameter("value", address), parameter("n")),
                choose(variable("n"), integer(7), primitive("indexCharOffAddr#", variable("value"), integer(0))))))
    }

    @Test fun concreteReferenceFormalsSurviveDirectAndAncestorParallelMoves() = eachBackend(fixtures()) { backend, p ->
        val left = call(p, "box", 3_000_000_017L)
        val right = call(p, "box", -7_000_000_003L)
        val f = call(p, "add", 1_001L)
        val g = call(p, "add", 2_002L)
        val addressA = ManagedAddress.fromHex("41")
        val addressB = ManagedAddress.fromHex("ff")
        fun check(name: String, n: Long) {
            val swapped = n % 2 != 0L
            val x = if (swapped) -7_000_000_003L else 3_000_000_017L
            val y = if (swapped) 3_000_000_017L else -7_000_000_003L
            val first = if (swapped) 2_002L + 255 else 1_001L + 65
            val second = if (swapped) 1_001L + 65 else 2_002L + 255
            val bounces = count(p, "tailBounces")
            assertEquals(x + 2 * y + first + 3 * second, call(p, name, n, left, right, f, g, addressA, addressB), backend)
            if (name == "self") assertEquals(bounces, count(p, "tailBounces"), backend)
            assertEquals(0L, count(p, "trampolineIterations"), backend)
        }
        for (name in listOf("self", "a")) {
            repeat(30) { check(name, 8) }
            compile(p.entryTarget(name))
            val compiled = count(p, "compiledEntries")
            for (n in listOf(0L, 1L, 2L, 4_000L, 4_001L)) check(name, n)
            assertTrue(count(p, "compiledEntries") > compiled, backend)
        }
    }

    @Test fun checkedReferenceEntryRejectsNullButDoesNotStrictifyLazyData() = eachBackend(fixtures()) { backend, p ->
        val boxed = call(p, "box", Long.MAX_VALUE)
        val fn = call(p, "add", 3_000_000_017L)
        val literal = ManagedAddress.fromHex("ff")
        for ((name, value) in listOf("strictData" to boxed, "strictClosure" to fn, "strictAddress" to literal)) {
            repeat(30) { assertEquals(7L, call(p, name, value, 0L), backend) }
            compile(p.entryTarget(name))
            assertThrows(RuntimeFault::class.java) { call(p, name, null, 0L) }
            assertEquals(7L, call(p, name, value, 0L), backend)
        }
        // A denoted DATA type with no entry obligation must still accept a thunk.
        val bottom = Thunk(object : com.oracle.truffle.api.nodes.RootNode(null) {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): Any? = throw RuntimeFault("captured bottom")
            override fun getName(): String = "reference formal bottom"
        }.callTarget, null)
        repeat(30) { assertEquals(7L, call(p, "lazy", bottom, 0L), backend) }
        compile(p.entryTarget("lazy"))
        assertEquals(0, bottom.state, backend)
        assertThrows(RuntimeFault::class.java) { call(p, "lazy", bottom, 1L) }
        assertEquals(3, bottom.state, backend)
    }
}
