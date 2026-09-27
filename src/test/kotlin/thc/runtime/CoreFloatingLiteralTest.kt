// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class CoreFloatingLiteralTest {
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun binding(id: String, kind: String, value: Any): Map<String, Any?> {
        val proof = mapOf("kind" to kind, "primReps" to listOf(if (kind == "float") "FloatRep" else "DoubleRep"), "evaluated" to true)
        val parameter = mapOf("id" to "unused", "name" to "unused", "lifted" to false,
            "rep" to mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true))
        return mapOf("id" to id, "name" to id, "lifted" to true, "arity" to 1, "rep" to closure,
            "expr" to listOf("lam", listOf(parameter), listOf("lit", kind, value, mapOf("rep" to proof)),
                mapOf("rep" to closure, "resultRep" to proof)))
    }

    @Test fun rawBitsAndLegacyStringsSurviveInterpretedAndFirstInstalledCalls() {
        val literals = listOf(
            Triple("fzero", "float", CoreFloatingLiteral.Single(Int.MIN_VALUE)),
            Triple("fnan", "float", CoreFloatingLiteral.Single(0x7fc01234)),
            Triple("fsubnormal", "float", CoreFloatingLiteral.Single(1)),
            Triple("dzero", "double", CoreFloatingLiteral.Double(Long.MIN_VALUE)),
            Triple("dnan", "double", CoreFloatingLiteral.Double(0x7ff8000000005678L)),
            Triple("dsubnormal", "double", CoreFloatingLiteral.Double(1)))
        val bindings = literals.map { (id, kind, literal) -> binding(id, kind, literal) } +
            listOf(binding("jsonFloat", "float", "-0.0"), binding("jsonDouble", "double", "1.25"))
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val module = mapOf("bindings" to bindings, "constructors" to emptyList<Any>(), "instrument" to true)
                    val program = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module, false)
                    fun check(id: String, value: Any?) {
                        when (val literal = literals.find { it.first == id }?.third) {
                            is CoreFloatingLiteral.Single -> assertEquals(literal.bits, (value as Float).toRawBits(), "$backend/$id")
                            is CoreFloatingLiteral.Double -> assertEquals(literal.bits, (value as Double).toRawBits(), "$backend/$id")
                            null -> if (id == "jsonFloat") assertEquals(Int.MIN_VALUE, (value as Float).toRawBits()) else assertEquals(1.25, value)
                        }
                    }
                    for (binding in bindings) {
                        val id = binding["id"] as String
                        val target = program.entryTarget(id)
                        check(id, Calls.target(target, arrayOf(0L, 0L)))
                        val targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                        targetType.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        assertEquals(true, targetType.getMethod("isValidLastTier").invoke(target))
                        val runtime = Truffle.getRuntime()
                        runtime.javaClass.getMethod("bypassedInstalledCode", targetType).invoke(runtime, target)
                        val before = (program.diagnostics()["compiledEntries"] as Number).toLong()
                        check(id, Calls.target(target, arrayOf(0L, 0L)))
                        assertSame(target, program.entryTarget(id))
                        assertEquals(true, targetType.getMethod("isValidLastTier").invoke(target))
                        assertEquals(1L, (program.diagnostics()["compiledEntries"] as Number).toLong() - before)
                    }
                } finally { context.leave() }
            }
    }

    @Test fun typedPayloadCannotClaimADifferentFloatingKind() {
        assertThrows(UnsupportedCore::class.java) { CoreFloatingLiteral.Single(0).decode("double") }
        assertThrows(UnsupportedCore::class.java) { CoreFloatingLiteral.Double(0).decode("float") }
        assertThrows(UnsupportedCore::class.java) { CoreFloatingLiteral.Single(0).decode("int") }
    }
}
