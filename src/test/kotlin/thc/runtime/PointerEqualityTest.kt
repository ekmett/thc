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

private typealias PointerCore = List<Any?>

/** Raw object identity is intentionally tested separately from the native value oracle. */
class PointerEqualityTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val reference = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private fun variable(id: String): PointerCore = listOf("var", id)
    private fun integer(value: Long): PointerCore = listOf("lit", "int", value.toString())
    private fun pointer(vararg args: PointerCore): PointerCore =
        listOf("app", listOf("prim", "reallyUnsafePtrEquality#"), args.toList(), List(args.size) { true },
            false, false, mapOf("rep" to long, "callDemand" to mapOf("arity" to 2, "strictArgs" to List(args.size) { false })))
    private fun arithmetic(name: String, vararg args: PointerCore): PointerCore =
        listOf("app", listOf("prim", name), args.toList(), List(args.size) { false })
    private fun demand(value: PointerCore, name: String, body: PointerCore): PointerCore =
        listOf("case", value, name, listOf(listOf("default", null, emptyList<String>(), body)))
    private fun module(body: PointerCore, diagnostic: Boolean = false): Map<String, Any?> {
        val parameters = listOf("left", "right").map { id -> mapOf(
            "id" to id, "name" to id, "type" to "a", "lifted" to true, "coercion" to false, "rep" to reference) }
        val lambda = listOf("lam", parameters, body, mapOf("resultRep" to long, "entryStrict" to listOf(false, false)))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.PointerEquality",
            "instrument" to true, "diagnosticUnsupported" to diagnostic, "constructors" to emptyList<Any>(),
            "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "type" to "a -> b -> Int#",
                "lifted" to true, "arity" to 2, "expr" to lambda)))
    }
    private fun count(program: ExecutableProgram, key: String): Long =
        (program.diagnostics().getValue(key) as Number).toLong()
    private fun invoke(program: ExecutableProgram, left: Any, right: Any): Any? =
        Calls.target(program.hostEntryTarget(2), arrayOf(program.entryValue("entry"), arrayOf(left, right)))
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private fun eachBackend(body: PointerCore, action: (String, ExecutableProgram, Language) -> Unit) {
        val previous = System.getProperty(CALL_DEMANDS_PROPERTY)
        System.setProperty(CALL_DEMANDS_PROPERTY, "true")
        try {
            executionContext().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (backend in listOf("ast", "bytecode")) {
                        val program = if (backend == "ast") Program(language, module(body)) else BytecodeProgram(language, module(body))
                        action(backend, program, language)
                        assertEquals(0L, count(program, "unsupportedTraps"), backend)
                        assertEquals(0L, count(program, "blackholes"), backend)
                    }
                } finally { context.leave() }
            }
        } finally {
            if (previous == null) System.clearProperty(CALL_DEMANDS_PROPERTY)
            else System.setProperty(CALL_DEMANDS_PROPERTY, previous)
        }
    }
    private fun bottom(): Thunk = Thunk(object : RootNode(null) {
        override fun execute(frame: VirtualFrame): Any = throw RuntimeFault("pointer test bottom entered")
    }.callTarget, null)
    private data class EqualPayload(val value: Long)

    @Test fun sharedAndDistinctReferencesIncludeUnevaluatedAndUpdatedThunks() = eachBackend(
        pointer(variable("left"), variable("right"))) { backend, program, language ->
        val layout = DataLayout(language, "PointerBox", "PointerBox", arrayOf("IntRep"))
        val first = layout.create(arrayOf(3_000_000_017L))
        val equal = layout.create(arrayOf(3_000_000_017L))
        val leftBottom = bottom(); val rightBottom = bottom()
        val updated = bottom().also { it.state = 2; it.value = first; it.target = null }
        val updatedAlias = bottom().also { it.state = 2; it.value = first; it.target = null }
        val equalObject = EqualPayload(7); val otherEqualObject = EqualPayload(7)
        assertEquals(equalObject, otherEqualObject)
        val cases = listOf(
            Triple(first, first, 1L), Triple(first, equal, 0L),
            Triple(equalObject, otherEqualObject, 0L), Triple(equalObject, equalObject, 1L),
            Triple(leftBottom, leftBottom, 1L), Triple(leftBottom, rightBottom, 0L),
            Triple(leftBottom, first, 0L), Triple(first, rightBottom, 0L),
            Triple(updated, first, 0L), Triple(updated, updated, 1L), Triple(updated, updatedAlias, 0L))
        fun check(index: Int, compiled: Boolean) {
            val (left, right, expected) = cases[index]
            val before = count(program, "compiledEntries")
            val result = invoke(program, left, right)
            assertInstanceOf(java.lang.Long::class.java, result, "$backend Int# uses a full-width long")
            assertEquals(expected, result, "$backend case $index")
            if (compiled) assertTrue(count(program, "compiledEntries") > before, "$backend case $index enters installed code")
        }
        repeat(44) { check(it % cases.size, false) }
        compile(program.entryTarget("entry"))
        cases.indices.reversed().forEach { check(it, true) }
        assertEquals(0L, count(program, "thunkEvaluations"), backend)
        assertEquals(0L, count(program, "thunkHits"), "$backend must not follow an updated thunk either")
        assertEquals(0, leftBottom.state); assertEquals(0, rightBottom.state)
    }

    @Test fun resultFeedsFullWidthArithmeticOnBothIdentityBranches() = eachBackend(
        arithmetic("+#", arithmetic("*#", pointer(variable("left"), variable("right")), integer(4_294_967_311L)),
            integer(3_000_000_007L))) { backend, program, _ ->
        val left = Any(); val right = Any()
        fun check(equal: Boolean, compiled: Boolean) {
            val before = count(program, "compiledEntries")
            assertEquals(if (equal) 7_294_967_318L else 3_000_000_007L,
                invoke(program, left, if (equal) left else right), backend)
            if (compiled) assertTrue(count(program, "compiledEntries") > before, backend)
        }
        repeat(40) { check(it % 2 == 0, false) }
        compile(program.entryTarget("entry"))
        check(false, true); check(true, true)
    }

    @Test fun forcingOneLocalDoesNotFollowASeparateAliasUntilThatAliasIsDemanded() {
        val pair = pointer(variable("left"), variable("right"))
        val encoded = arithmetic("+#", arithmetic("*#", variable("before"), integer(8)),
            arithmetic("+#", arithmetic("*#", variable("afterLeft"), integer(4)),
                arithmetic("*#", pair, integer(2))))
        val body = demand(pair, "before", demand(variable("left"), "leftValue",
            demand(pair, "afterLeft", demand(variable("right"), "rightValue", encoded))))
        eachBackend(body) { backend, program, _ ->
            val answer = Any()
            val producer = object : RootNode(null) {
                override fun execute(frame: VirtualFrame): Any = answer
            }.callTarget
            fun check(shared: Boolean, compiled: Boolean) {
                val left = Thunk(producer, null); val right = if (shared) left else Thunk(producer, null)
                val evaluations = count(program, "thunkEvaluations"); val hits = count(program, "thunkHits")
                val entered = count(program, "compiledEntries")
                assertEquals(if (shared) 10L else 2L, invoke(program, left, right), backend)
                assertEquals(evaluations + if (shared) 1 else 2, count(program, "thunkEvaluations"), backend)
                assertEquals(hits + if (shared) 1 else 0, count(program, "thunkHits"), backend)
                assertSame(answer, left.value); assertSame(answer, right.value)
                if (compiled) assertTrue(count(program, "compiledEntries") > entered, backend)
            }
            repeat(40) { check(it % 2 == 0, false) }
            compile(program.entryTarget("entry"))
            check(false, true); check(true, true)
        }
    }

    @Test fun onlyTheChosenFallbackBranchMayEnterABottom() {
        val body = listOf("case", pointer(variable("left"), variable("right")), "same",
            listOf(listOf("lit", listOf("int", "1"), emptyList<String>(), integer(3_000_000_007L)),
                listOf("default", null, emptyList<String>(), demand(variable("right"), "value", integer(7)))))
        eachBackend(body) { backend, program, _ ->
            val untouched = bottom(); val answer = Any()
            repeat(20) {
                assertEquals(3_000_000_007L, invoke(program, untouched, untouched), backend)
                assertEquals(7L, invoke(program, untouched, answer), backend)
            }
            compile(program.entryTarget("entry"))
            val entered = count(program, "compiledEntries")
            assertEquals(3_000_000_007L, invoke(program, untouched, untouched), backend)
            assertEquals(7L, invoke(program, untouched, answer), backend)
            assertTrue(count(program, "compiledEntries") > entered, backend)
            assertEquals(0L, count(program, "thunkEvaluations"), backend)
            assertEquals(0, untouched.state)
            val demanded = bottom()
            val failure = assertThrows(RuntimeFault::class.java) { invoke(program, answer, demanded) }
            assertTrue(failure.message.orEmpty().contains("pointer test bottom entered"), backend)
            assertEquals(1L, count(program, "thunkEvaluations"), backend)
            assertEquals(3, demanded.state, "$backend only the selected fallback enters the bottom")
        }
    }

    @Test fun malformedAritiesRemainLoadErrorsEvenInDiagnosticMode() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) {
                    for (arity in listOf(0, 1, 3)) {
                        val body = pointer(*Array(arity) { variable("left") })
                        val failure = assertThrows(RuntimeFault::class.java) {
                            if (backend == "ast") Program(language, module(body, diagnostic))
                            else BytecodeProgram(language, module(body, diagnostic))
                        }
                        assertTrue(failure.message.orEmpty().contains("Primitive arity mismatch: reallyUnsafePtrEquality#"),
                            "$backend diagnostic=$diagnostic arity=$arity: ${failure.message}")
                    }
                }
            } finally { context.leave() }
        }
    }
}
