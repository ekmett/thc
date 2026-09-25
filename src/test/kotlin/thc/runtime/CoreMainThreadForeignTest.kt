// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

/** The declaration is GHC.Internal.TopHandler.runMainIO1's original unsafe ccall. */
class CoreMainThreadForeignTest {
    private fun scalar(kind: String, rep: String?, evaluated: Boolean) = mapOf(
        "kind" to kind, "primReps" to listOfNotNull(rep), "evaluated" to evaluated)
    private val weak = scalar("object", "BoxedRep (Just Unlifted)", true)
    private val state = scalar("void", null, true)
    private val closure = scalar("closure", "BoxedRep (Just Lifted)", true)
    private fun tuple(evaluated: Boolean) = mapOf(
        "kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to evaluated,
        "aggregate" to "unboxed-tuple", "components" to listOf(state))
    private fun descriptor(): Map<String, Any?> = mapOf(
        "schema" to 1L,
        "target" to mapOf("kind" to "static", "symbol" to "rts_setMainThread",
            "unit" to "ghc-internal", "isFunction" to true),
        "convention" to "ccall", "safety" to "unsafe", "arity" to 2L, "suppliedArity" to 2L,
        "argumentReps" to listOf(scalar("object", "BoxedRep (Just Unlifted)", false),
            scalar("void", null, false)), "resultRep" to tuple(false))
    private fun proof() = mapOf("rep" to tuple(false), "foreignCall" to descriptor())
    private fun variable(id: String, rep: Map<String, Any?>) = listOf("var", id, mapOf("rep" to rep))
    private fun application() = listOf("app", variable("original-fcall", closure),
        listOf(variable("w", weak), variable("s", state)), listOf(false, false), false, false, proof())
    private fun module(): Map<String, Any?> {
        val body = listOf("lam", listOf(
            mapOf("id" to "w", "name" to "w", "lifted" to false, "rep" to weak),
            mapOf("id" to "s", "name" to "s", "lifted" to false, "rep" to state)),
            application(), mapOf("rep" to closure, "resultRep" to tuple(false)))
        val binding = mapOf("id" to "register", "name" to "register", "arity" to 2,
            "lifted" to true, "rep" to closure, "expr" to body)
        return mapOf("bindings" to listOf(binding), "constructors" to emptyList<Any?>(), "instrument" to true)
    }

    @Test fun exactOriginalWeakAndStateAbiRejectsNearMisses() {
        val good = proof()
        assertTrue(CoreMainThreadForeign.validate(good, listOf(weak, state), listOf(false, false), tuple(false)))
        CoreMainThreadForeign.validateHeads(application())
        val otherTarget = (descriptor()["target"] as Map<*, *>) + ("symbol" to "stg_sig_install")
        val otherCall = descriptor() + ("target" to otherTarget)
        assertFalse(CoreMainThreadForeign.validate(good + ("foreignCall" to otherCall),
            listOf(weak, state), listOf(false, false), tuple(false)))
        for ((field, wrong) in listOf("schema" to 1.0, "convention" to "capi", "safety" to "safe",
            "arity" to 1L, "suppliedArity" to 3L, "extra" to true))
            assertThrows(RuntimeFault::class.java) {
                CoreMainThreadForeign.validate(good + ("foreignCall" to (descriptor() + (field to wrong))),
                    listOf(weak, state), listOf(false, false), tuple(false))
            }
        for ((field, wrong) in listOf("unit" to "base", "kind" to "dynamic", "isFunction" to false))
            assertThrows(RuntimeFault::class.java) {
                val target = descriptor()["target"] as Map<*, *>
                val call = descriptor() + ("target" to (target + (field to wrong)))
                CoreMainThreadForeign.validate(good + ("foreignCall" to call),
                    listOf(weak, state), listOf(false, false), tuple(false))
            }
        for ((args, flags, result) in listOf(
            Triple(listOf(state, weak), listOf(false, false), tuple(false)),
            Triple(listOf(weak, state), listOf(true, false), tuple(false)),
            Triple(listOf(weak, state), listOf(false, false), scalar("void", null, true))))
            assertThrows(RuntimeFault::class.java) { CoreMainThreadForeign.validate(good, args, flags, result) }
        assertThrows(RuntimeFault::class.java) {
            CoreMainThreadForeign.validateHead(variable("original-fcall", closure), true)
        }
        val forged = application().toMutableList().also { it[1] = listOf("prim", "rts_setMainThread") }
        assertThrows(RuntimeFault::class.java) { CoreMainThreadForeign.validateHeads(forged) }
    }

    @Test fun bothLoadersRegisterTheWeakKeyWithoutRetainingItsValueOrAThreadIdSnapshot() {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = if (backend == "ast") Program(language, module()) else BytecodeProgram(language, module())
                val runtime = Language.currentState()
                val threads = runtime.threads
                threads.enterCurrent()
                try {
                    val target = program.entryTarget("register")
                    val key = threads.currentIdentity()
                    val wrongValue = Any()
                    val first = runtime.weaks.make(key, wrongValue, null)
                    val second = runtime.weaks.make(key, Any(), null)
                    fun call(handle: Any?, token: Any? = Unit): Any? =
                        Calls.target(target, arrayOf(0L, handle, token))
                    call(first)
                    val initial = threads.mainThreadRegistration()!!
                    assertEquals(Thread.currentThread().threadId(), initial.liveJavaId())
                    assertThrows(RuntimeFault::class.java) { call(first, 1L) }
                    assertSame(initial, threads.mainThreadRegistration(), "invalid State# must not replace registration")
                    assertThrows(RuntimeFault::class.java) { call(Any()) }
                    assertSame(initial, threads.mainThreadRegistration(), "invalid Weak# must not replace registration")
                    repeat(3) { call(first) }
                    val warmed = threads.mainThreadRegistration()!!
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), backend)
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    call(second)
                    assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(),
                        "$backend first installed guest entry")
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), backend)
                    assertSame(target, program.entryTarget("register"))
                    val replacement = threads.mainThreadRegistration()!!
                    assertNotSame(warmed, replacement)
                    assertEquals(0L, runtime.weaks.finalize(first).flag)
                    assertNull(initial.liveJavaId())
                    assertEquals(Thread.currentThread().threadId(), replacement.liveJavaId())
                    assertEquals(0L, runtime.weaks.finalize(second).flag)
                    assertNull(replacement.liveJavaId(), "registration may retain only the now-dead capability")
                    val handoff = language.handoffState.get()
                    assertEquals(0, handoff.arguments.depth)
                    assertEquals(0, handoff.arguments.retainedReferences())
                    assertEquals(0, handoff.results.depth)
                    assertEquals(0, handoff.results.retainedReferences())
                    assertNull(handoff.pending)
                } finally { threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }
}
