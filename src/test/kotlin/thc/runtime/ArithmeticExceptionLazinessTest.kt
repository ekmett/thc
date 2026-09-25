// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*

/** Fixture-independent check that implicit original payloads remain lazy. */
class ArithmeticExceptionLazinessTest {
    private fun context(): Context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
        .option("compiler.Inlining", "false")
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000")
        .option("engine.CompilationFailureAction", "Throw").build()
    @Test fun implicitPayloadIsLazyAndFailureMemoizationSharesItInBothBackends() {
        val boxed = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
        val empty = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "aggregate" to "unboxed-tuple",
            "components" to emptyList<Any>(), "evaluated" to true)
        for (name in listOf("raiseDivZero#", "raiseOverflow#", "raiseUnderflow#")) for (backend in listOf("ast", "bytecode")) {
            val payloadId = CoreArithmeticExceptions.payload(name)!!
            fun binding(id: String, expression: List<Any?>) = mapOf("id" to id, "name" to id, "type" to "SomeException",
                "lifted" to true, "arity" to 0, "rep" to boxed, "expr" to expression)
            // This deliberate bottom is a strictness control only; the native
            // fixture above validates the actual exported exception dictionaries.
            val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Test.ArithmeticLaziness",
                "bindings" to listOf(binding(payloadId, listOf("var", payloadId, mapOf("rep" to boxed))),
                    binding("entry", listOf("app", listOf("prim", name),
                        listOf(listOf("con", "Empty", 0, mapOf("rep" to empty))), listOf(false), false, false, mapOf("rep" to boxed)))),
                "constructors" to listOf(mapOf("id" to "Empty", "name" to "(# #)", "arity" to 0,
                    "kind" to "unboxed-tuple", "tag" to 1, "strictFields" to emptyList<Boolean>(),
                    "fieldLifted" to emptyList<Boolean>(), "fieldReps" to emptyList<List<String>>())))
            context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module, "entry", true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    val payload = program.entryValue(payloadId) as Thunk
                    fun invoke() = Calls.target(program.hostEntryTarget(), arrayOf(program.entryValue("entry"), emptyArray<Any?>()))
                    val first = assertThrows(GuestException::class.java) { invoke() }
                    val second = assertThrows(GuestException::class.java) { invoke() }
                    assertNotSame(first, second)
                    assertSame(payload, first.payload)
                    assertSame(first.payload, second.payload)
                    assertEquals(0, payload.state, "$backend/$name never enters its implicit exception CAF")
                    assertEquals(1L, program.diagnostics()["thunkEvaluations"])
                    assertEquals(0L, program.diagnostics()["blackholes"])
                } finally { context.leave() }
            }
        }
    }
}
