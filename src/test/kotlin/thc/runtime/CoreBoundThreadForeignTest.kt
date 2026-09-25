// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

/** Structural/carrier controls; original-import evidence is acquired separately from installed Core. */
class CoreBoundThreadForeignTest {
    private fun scalar(kind: String, rep: String?, evaluated: Boolean = true) = mapOf(
        "kind" to kind, "primReps" to listOfNotNull(rep), "evaluated" to evaluated)
    private val state = scalar("void", null)
    private val long = scalar("long", "IntRep")
    private val closure = scalar("closure", "BoxedRep (Just Lifted)")
    private fun tuple(evaluated: Boolean = false) = mapOf(
        "kind" to "unknown", "primReps" to listOf("IntRep"), "evaluated" to evaluated,
        "aggregate" to "unboxed-tuple", "components" to listOf(state, long))
    private fun descriptor(): Map<String, Any?> = mapOf(
        "schema" to 1L, "target" to mapOf("kind" to "static", "symbol" to "rtsSupportsBoundThreads",
            "unit" to "ghc-internal", "isFunction" to true), "convention" to "ccall", "safety" to "unsafe",
        "arity" to 1L, "suppliedArity" to 1L, "argumentReps" to listOf(scalar("void", null, false)),
        "resultRep" to tuple())
    private fun proof() = mapOf("rep" to tuple(), "foreignCall" to descriptor())
    private fun variable(id: String, rep: Map<String, Any?>) = listOf("var", id, mapOf("rep" to rep))
    private fun application() = listOf("app", variable("structural-fcall", closure),
        listOf(variable("s", state)), listOf(false), false, false, proof())
    private fun module(stored: Map<String, Any?> = state, expression: List<Any?> = application()): Map<String, Any?> {
        val body = listOf("lam", listOf(mapOf("id" to "s", "name" to "s", "lifted" to false, "rep" to stored)),
            expression, mapOf("rep" to closure, "resultRep" to tuple()))
        return mapOf("bindings" to listOf(mapOf("id" to "query", "name" to "query", "arity" to 1,
            "lifted" to true, "rep" to closure, "expr" to body)), "constructors" to emptyList<Any?>(), "instrument" to true)
    }

    @Test fun exactStateAndHsBoolCertificateRejectsNearMisses() {
        assertTrue(CoreBoundThreadForeign.validate(proof(), listOf(state), listOf(false), tuple()))
        CoreBoundThreadForeign.validateHeads(application())
        for (symbol in listOf("isCurrentThreadBound", "forkOS", "prefix_rtsSupportsBoundThreads")) {
            val call = descriptor() + ("target" to ((descriptor()["target"] as Map<*, *>) + ("symbol" to symbol)))
            assertFalse(CoreBoundThreadForeign.validate(proof() + ("foreignCall" to call), listOf(state), listOf(false), tuple()))
        }
        for ((field, wrong) in listOf("schema" to true, "schema" to 1.0, "convention" to "capi", "safety" to "safe",
                "arity" to 0L, "suppliedArity" to 2L, "extra" to true)) assertThrows(RuntimeFault::class.java) {
            CoreBoundThreadForeign.validate(proof() + ("foreignCall" to (descriptor() + (field to wrong))),
                listOf(state), listOf(false), tuple())
        }
        for ((field, wrong) in listOf("unit" to "base", "kind" to "dynamic", "isFunction" to false, "extra" to 1))
            assertThrows(RuntimeFault::class.java) {
                val target = descriptor()["target"] as Map<*, *>
                val call = descriptor() + ("target" to (target + (field to wrong)))
                CoreBoundThreadForeign.validate(proof() + ("foreignCall" to call), listOf(state), listOf(false), tuple())
            }
        for (wrong in listOf(tuple() + ("components" to listOf(long)),
                tuple() + ("components" to listOf(state, scalar("long", "Int32Rep"))),
                tuple() + ("primReps" to listOf("WordRep")), scalar("data", "BoxedRep (Just Lifted)"))) {
            assertThrows(RuntimeFault::class.java) { CoreBoundThreadForeign.validate(proof(), listOf(state), listOf(false), wrong) }
            assertThrows(RuntimeFault::class.java) {
                CoreBoundThreadForeign.validate(proof() + ("foreignCall" to (descriptor() + ("resultRep" to wrong))),
                    listOf(state), listOf(false), tuple())
            }
        }
        assertThrows(RuntimeFault::class.java) { CoreBoundThreadForeign.validate(proof(), listOf(long), listOf(false), tuple()) }
        assertThrows(RuntimeFault::class.java) { CoreBoundThreadForeign.validate(proof(), listOf(state), listOf(true), tuple()) }
        assertThrows(RuntimeFault::class.java) {
            CoreBoundThreadForeign.validate(proof() + ("foreignCall" to (descriptor() + ("argumentReps" to listOf(state)))),
                listOf(state), listOf(false), tuple())
        }
        assertThrows(RuntimeFault::class.java) { CoreBoundThreadForeign.validateHead(variable("structural-fcall", closure), true) }
        assertThrows(RuntimeFault::class.java) {
            CoreBoundThreadForeign.validateHeads(application().toMutableList().also { it[1] = listOf("prim", "rtsSupportsBoundThreads") })
        }
    }

    @Test fun bothLoadersCheckStoredStateAndRuntimeCarrierBeforeWritingTypedZero() {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun load(value: Map<String, Any?>) = if (backend == "ast") Program(language, value) else BytecodeProgram(language, value)
                assertThrows(RuntimeFault::class.java) { load(module(stored = long)) }
                val forged = application().toMutableList().also {
                    it[2] = listOf(listOf("lit", "int", "7", mapOf("rep" to state)))
                }
                assertThrows(RuntimeFault::class.java) { load(module(expression = forged)) }
                val program = load(module())
                val target = program.entryTarget("query")
                val descriptor = FrameDescriptor.newBuilder().apply { addSlot(com.oracle.truffle.api.frame.FrameSlotKind.Long, null, null) }.build()
                val destination = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                val shape = TupleShape(CoreRepresentations.parse(tuple()), language)
                fun call(token: Any?) {
                    val result = Calls.target(target, arrayOf(0L, token))
                    shape.consume(destination, result, intArrayOf(0), 0)
                    assertEquals(0L, destination.getLong(0))
                }
                assertThrows(RuntimeFault::class.java) { call(1L) }
                repeat(3) { call(Unit) }
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), backend)
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                call(Unit)
                assertEquals(before + 1L, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), backend)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), backend)
                assertSame(target, program.entryTarget("query"))
                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                val handoff = language.handoffState.get()
                assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.arguments.retainedReferences())
                assertEquals(0, handoff.results.depth); assertEquals(0, handoff.results.retainedReferences())
                assertNull(handoff.pending)
            } finally { context.leave() }
        }
    }
}
