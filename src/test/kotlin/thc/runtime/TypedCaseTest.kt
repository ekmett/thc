// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias CaseCore = List<Any?>

class TypedCaseTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private fun reference(kind: String) = mapOf("kind" to kind,
        "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val data = reference("data")
    private val closure = reference("closure")
    private val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
    private fun param(id: String, rep: Map<String, Any>? = null): Map<String, Any> = mapOf(
        "id" to id, "name" to id, "lifted" to (rep !== long && rep !== address), "coercion" to false) +
        (rep?.let { mapOf("rep" to it) } ?: emptyMap())
    private fun variable(id: String): CaseCore = listOf("var", id)
    private fun number(n: Long): CaseCore = listOf("lit", "int", n.toString())
    private fun binding(id: String, body: CaseCore) = mapOf("id" to id, "name" to id, "lifted" to true, "expr" to body)
    private fun lambda(args: List<Map<String, Any>>, body: CaseCore, result: Map<String, Any>? = long): CaseCore =
        listOf("lam", args, body, mapOf("rep" to closure) + (result?.let { mapOf("resultRep" to it) } ?: emptyMap()))
    private fun primitive(name: String, vararg args: CaseCore): CaseCore =
        listOf("app", listOf("prim", name), args.toList(), List(args.size) { false })
    private fun box(value: CaseCore): CaseCore = listOf("app", listOf("con", "Box", 1), listOf(value), listOf(false), true, true)
    private fun arm(value: Long, body: CaseCore): CaseCore = listOf("lit", listOf("int", value.toString()), emptyList<String>(), body)
    private fun otherwise(body: CaseCore): CaseCore = listOf("default", null, emptyList<String>(), body)
    private fun dataArm(id: String, fields: List<String>, body: CaseCore): CaseCore = listOf("data", id, fields, body)
    private fun case(scrutinee: CaseCore, binder: String, proof: Map<String, Any>?, alternatives: List<CaseCore>,
                     result: Map<String, Any>? = long): CaseCore = listOf("case", scrutinee, binder, alternatives,
        (proof?.let { mapOf("binder" to param(binder, it)) } ?: emptyMap()) +
            (result?.let { mapOf("rep" to it) } ?: emptyMap()))
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private fun call(p: ExecutableProgram, id: String, vararg args: Any?): Any? =
        Calls.target(p.hostEntryTarget(args.size), arrayOf(p.entryValue(id), arrayOf(*args)))

    private fun each(action: (Boolean, String, ExecutableProgram) -> Unit) {
        val previous = System.getProperty(TYPED_CASES_PROPERTY)
        try {
            for (enabled in listOf(false, true)) {
                System.setProperty(TYPED_CASES_PROPERTY, enabled.toString())
                executionContext().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        for (backend in listOf("ast", "bytecode")) {
                            val module = mapOf("instrument" to true, "bindings" to fixtures(), "constructors" to listOf(
                                mapOf("id" to "Box", "name" to "Box", "arity" to 1, "kind" to "boxed", "fieldReps" to listOf(listOf("IntRep")),
                                    "fieldLifted" to listOf(false), "strictFields" to listOf(false)),
                                mapOf("id" to "End", "name" to "End", "arity" to 0, "kind" to "boxed", "fieldReps" to emptyList<Any>(),
                                    "fieldLifted" to emptyList<Boolean>(), "strictFields" to emptyList<Boolean>()),
                                mapOf("id" to "Other", "name" to "Other", "arity" to 0, "kind" to "boxed", "fieldReps" to emptyList<Any>(),
                                    "fieldLifted" to emptyList<Boolean>(), "strictFields" to emptyList<Boolean>())))
                            action(enabled, backend, if (backend == "ast") Program(language, module) else BytecodeProgram(language, module))
                        }
                    } finally { context.leave() }
                }
            }
        } finally {
            if (previous == null) System.clearProperty(TYPED_CASES_PROPERTY) else System.setProperty(TYPED_CASES_PROPERTY, previous)
        }
    }

    private fun fixtures(): List<Map<String, Any>> {
        val select = primitive("+#", case(variable("input"), "whole", data, listOf(
            dataArm("Box", listOf("payload"), case(variable("payload"), "n", long, listOf(
                arm(-1, case(number(9), "unmatched", long, emptyList())), arm(Long.MIN_VALUE, primitive("+#", variable("n"), number(3))),
                otherwise(primitive("+#", variable("n"), number(7)))))),
            dataArm("End", emptyList(), number(99)), otherwise(number(-17)))), number(1))
        fun identity(id: String, rep: Map<String, Any>) = binding(id, lambda(listOf(param("input")),
            case(variable("input"), "whole", rep, listOf(otherwise(variable("whole"))), rep), rep))
        return listOf(binding("make", lambda(listOf(param("n", long)), box(variable("n")), data)),
            binding("end", listOf("con", "End", 0)), binding("other", listOf("con", "Other", 0)),
            binding("select", lambda(listOf(param("input")), select)),
            binding("partial", lambda(listOf(param("input")), case(variable("input"), "whole", data,
                listOf(dataArm("Box", listOf("n"), variable("n")))))),
            binding("generic", lambda(listOf(param("input")), case(variable("input"), "whole", null,
                listOf(arm(0, number(11)), dataArm("End", emptyList(), number(12)), otherwise(number(13)))))),
            binding("plus", lambda(listOf(param("n", long)), primitive("+#", variable("n"), number(5)))),
            identity("dataIdentity", data), identity("functionIdentity", closure), identity("addressIdentity", address))
    }

    @Test fun typedCasesKeepWidePrimitiveResultsColdArmsAndGeneralCaseFallback() = each { enabled, backend, p ->
        fun selected(value: Long): Any? = call(p, "select", call(p, "make", value))
        repeat(30) { assertEquals(10L, selected(2)) }
        compile(p.entryTarget("select"))
        for (n in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_017L, -3_000_000_017L))
            assertEquals(if (n == Long.MIN_VALUE) n + 4 else n + 8, selected(n), "$enabled/$backend/$n")
        assertEquals(100L, call(p, "select", call(p, "end")))
        assertEquals(-16L, call(p, "select", call(p, "other")), "DATA is not proof of a particular constructor family")
        assertEquals(11L, call(p, "generic", 0L)); assertEquals(12L, call(p, "generic", call(p, "end")))
        assertEquals(13L, call(p, "generic", 5L)); assertEquals(13L, call(p, "generic", call(p, "other")))
        val failure = assertThrows(RuntimeFault::class.java) { call(p, "partial", call(p, "end")) }
        assertTrue(failure.message.orEmpty().contains("Non-exhaustive Core case"))
        assertEquals(0L, p.diagnostics()["blackholes"])
        repeat(2) {
            val failureArm = assertThrows(RuntimeFault::class.java) { selected(-1) }
            assertTrue(failureArm.message.orEmpty().contains("Non-exhaustive Core case"))
        }
        assertEquals(0L, p.diagnostics()["blackholes"])
        if (backend == "ast") {
            val names = mutableSetOf<String>()
            p.entryTarget("select").rootNode.accept { names += it.javaClass.simpleName; true }
            assertEquals(enabled, "DataCase" in names)
            assertEquals(enabled, "LongCase" in names)
        } else assertEquals(enabled, "MatchDataValue" in (p as BytecodeProgram).bytecodeDump())
    }

    @Test fun declaredCaseResultsRejectKnownColdContradictionsAndKeepUnknownFallback() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (backend in listOf("ast", "bytecode")) {
                    fun load(body: CaseCore, input: Map<String, Any>? = null): ExecutableProgram {
                        val module = mapOf("bindings" to listOf(binding("select",
                            lambda(listOf(param("input", input)), body, result = null))))
                        return if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    }
                    // This arm is never selected, but a known Long cannot inhabit
                    // the declared boxed result even beside a compatible arm.
                    val contradictory = case(number(0), "scrutinee", long,
                        listOf(arm(0, variable("input")), otherwise(number(1))), result = data)
                    assertThrows(RuntimeFault::class.java, { load(contradictory, data) }, backend)

                    // Missing result/formal metadata retains generic transport;
                    // the known Long sibling must not refine the unknown arm.
                    val unknown = case(number(0), "scrutinee", long,
                        listOf(arm(0, variable("input")), otherwise(number(1))), result = null)
                    val program = load(unknown)
                    val sentinel = Any()
                    assertSame(sentinel, call(program, "select", sentinel), backend)
                    assertEquals(42L, call(program, "select", 42L), backend)
                }
            } finally { context.leave() }
        }
    }

    @Test fun defaultOnlyCasesForwardConcreteReferencesAndForceSharedScrutineeOnce() = each { _, _, p ->
        val value = call(p, "make", Long.MIN_VALUE)
        val function = p.entryValue("plus")
        val literal = ManagedAddress.fromHex("41ff")
        repeat(30) {
            assertSame(value, call(p, "dataIdentity", value))
            assertSame(function, call(p, "functionIdentity", function))
            assertSame(literal, call(p, "addressIdentity", literal))
        }
        for (id in listOf("dataIdentity", "functionIdentity", "addressIdentity")) compile(p.entryTarget(id))
        assertSame(value, call(p, "dataIdentity", value)); assertSame(function, call(p, "functionIdentity", function))
        assertSame(literal, call(p, "addressIdentity", literal))
        var evaluations = 0
        val target = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any? { evaluations++; return value }
        }.callTarget
        val thunk = Thunk(target, null)
        repeat(2) { assertSame(value, call(p, "dataIdentity", thunk)) }
        assertEquals(1, evaluations)
        assertEquals(2, thunk.state)
        assertNull(thunk.target)
        val failed = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Nothing { evaluations++; throw RuntimeFault("case scrutinee failed") }
        }.callTarget, null)
        repeat(2) {
            assertEquals("case scrutinee failed", assertThrows(RuntimeFault::class.java) {
                call(p, "dataIdentity", failed)
            }.message)
        }
        assertEquals(2, evaluations, "Default-only case still evaluates and memoizes a failing scrutinee")
    }
}
