// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.ContextProfile
import thc.Language
import thc.Main.withContextProfile

/** Small ABI models; the retained original parseDynamicFlagsFull is checked separately. */
class CompilerHeapHintTest {
    private fun scalar(rep: String?, evaluated: Boolean) = mapOf(
        "kind" to if (rep == null) "void" else if (rep.startsWith("BoxedRep")) "closure" else "long",
        "primReps" to listOfNotNull(rep), "evaluated" to evaluated)
    private val int = scalar("IntRep", true)
    private val state = scalar(null, true)
    private val closure = scalar("BoxedRep (Just Lifted)", true)
    private fun tuple(evaluated: Boolean) = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to emptyList<String>(), "evaluated" to evaluated, "components" to listOf(state))
    private val target = mapOf("kind" to "static", "symbol" to "setHeapSize",
        "unit" to "ghc-9.14.1-inplace", "isFunction" to true)
    private val declaration = mapOf("schema" to 1, "target" to target,
        "convention" to "ccall", "safety" to "unsafe", "arity" to 2, "suppliedArity" to 2,
        "argumentReps" to listOf(scalar("IntRep", false), scalar(null, false)), "resultRep" to tuple(false))
    private val metadata get() = mapOf("foreignCall" to declaration, "rep" to tuple(true))
    private val head = listOf("var", "original-setHeapSize-FCallId", mapOf("rep" to closure))

    private fun validate(call: Map<String, Any?> = declaration, arguments: List<*> = listOf(int, state),
        flags: List<*> = listOf(false, false), result: Any? = tuple(true)) =
        CoreGcForeign.validate(mapOf("foreignCall" to call, "rep" to result), arguments, flags, result)

    @Test fun originalCompilerUnitAndExactUnsafeStateAbiAreRequired() {
        assertEquals(GcForeignOp.HEAP_HINT, validate())
        CoreGcForeign.validateHead(head, false)
        assertThrows(RuntimeFault::class.java) { CoreGcForeign.validateHead(head, true) }
        for (wrong in listOf(
            declaration + ("target" to (target + ("unit" to "ghc-internal"))),
            declaration + ("target" to (target + ("unit" to "ghc-9.14.1-other"))),
            declaration + ("target" to (target + ("isFunction" to false))),
            declaration + ("target" to (target + ("kind" to "dynamic"))),
            declaration + ("schema" to true), declaration + ("safety" to "safe"),
            declaration + ("safety" to "interruptible"), declaration + ("convention" to "capi"),
            declaration + ("arity" to 1), declaration + ("suppliedArity" to 1),
            declaration + ("argumentReps" to listOf(scalar("WordRep", false), scalar(null, false))),
            declaration + ("resultRep" to int)))
            assertThrows(RuntimeFault::class.java) { validate(wrong) }
        assertNull(validate(declaration + ("target" to (target + ("symbol" to "setHeapSize_alias")))))
        assertNull(validate(declaration + ("target" to (target + ("symbol" to "enableTimingStats")))))
        assertThrows(RuntimeFault::class.java) { validate(arguments = listOf(scalar("WordRep", true), state)) }
        assertThrows(RuntimeFault::class.java) { validate(arguments = listOf(int, int)) }
        assertThrows(RuntimeFault::class.java) { validate(arguments = listOf(int)) }
        assertThrows(RuntimeFault::class.java) { validate(flags = listOf(true, false)) }
        assertThrows(RuntimeFault::class.java) { validate(result = int) }
    }

    private fun module(call: Map<String, Any?> = declaration,
        hint: Any = listOf("var", "bytes", mapOf("rep" to int))): Map<String, Any?> {
        val application = listOf("app", head,
            listOf(hint, listOf("var", "state", mapOf("rep" to state))),
            listOf(false, false), false, false, metadata + ("foreignCall" to call))
        val body = listOf("case", application, "tuple", listOf(listOf("data", "tuple1", listOf("next"),
            listOf("var", "bytes", mapOf("rep" to int)),
            mapOf("binders" to listOf(mapOf("id" to "next", "lifted" to false, "rep" to state))))),
            mapOf("rep" to int, "binder" to mapOf("id" to "tuple", "lifted" to false, "rep" to tuple(true))))
        return mapOf("instrument" to true,
            "constructors" to listOf(mapOf("id" to "tuple1", "kind" to "unboxed-tuple", "arity" to 1, "tag" to 1)),
            "bindings" to listOf(mapOf("id" to "hint", "name" to "hint", "arity" to 2, "lifted" to true,
                "rep" to closure, "expr" to listOf("lam", listOf(
                    mapOf("id" to "bytes", "lifted" to false, "rep" to int),
                    mapOf("id" to "state", "lifted" to false, "rep" to state)), body,
                    mapOf("rep" to closure, "resultRep" to int)))))
    }

    private fun context() = Context.newBuilder("thc").let { withContextProfile(it, ContextProfile.SYNCHRONOUS_TEST) }.build()

    @Test fun bothBackendsEvaluateTheHintAndPreserveTheStateOnlyResult() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            val threads = Language.currentState().threads
            threads.enterCurrent()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = if (backend == "ast") Program(language, module()) else BytecodeProgram(language, module())
                val target = program.entryTarget("hint")
                for (bytes in listOf(Long.MIN_VALUE, -1L, 0L, 1L, 4095L, 4096L, 4097L, Long.MAX_VALUE))
                    assertEquals(bytes, Calls.target(target, arrayOf(0L, bytes, Unit)), "$backend/$bytes")
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf<Any?>(0L, 42L, "not-state")) }
                assertEquals(42L, Calls.target(target, arrayOf(0L, 42L, Unit)))
                assertEquals(0, language.handoffState.get().results.depth)
                assertEquals(0, language.handoffState.get().arguments.depth)
            } finally { threads.leaveCurrent(); context.leave() }
        }
    }

    @Test fun unknownSymbolsStillRejectThroughBothFullLowerers() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val input = module(declaration + ("target" to (target + ("symbol" to "setHeapSize_alias"))))
                assertThrows(UnsupportedCore::class.java) {
                    val program = if (backend == "ast") Program(language, input) else BytecodeProgram(language, input)
                    program.entryValue("hint")
                }
            } finally { context.leave() }
        }
    }

    @Test fun discardedHintOperandIsStillEvaluated() {
        val quotient = listOf("app", listOf("prim", "quotInt#"),
            listOf(listOf("lit", "int", "1", mapOf("rep" to int)),
                listOf("lit", "int", "0", mapOf("rep" to int))), listOf(false, false),
            false, false, mapOf("rep" to int))
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            val threads = Language.currentState().threads
            threads.enterCurrent()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val input = module(hint = quotient)
                val program = if (backend == "ast") Program(language, input) else BytecodeProgram(language, input)
                val target = program.entryTarget("hint")
                assertThrows(ArithmeticException::class.java) { Calls.target(target, arrayOf(0L, 42L, Unit)) }
            } finally { threads.leaveCurrent(); context.leave() }
        }
    }
}
