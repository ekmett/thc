// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

/** Independent protocol controls; authentic exported/native calls live in PinnedPointerCellsTest. */
class CoreTouchTest {
    private fun proof(kind: String, rep: String?, evaluated: Boolean = true): Map<String, Any?> =
        mapOf("kind" to kind, "primReps" to listOfNotNull(rep), "evaluated" to evaluated)
    private val state = proof("void", null)
    private val lifted = "BoxedRep (Just Lifted)"
    private val unlifted = "BoxedRep (Just Unlifted)"
    private val closure = proof("closure", lifted)
    private fun variable(id: String, rep: Map<String, Any?>): List<Any?> = listOf("var", id, mapOf("rep" to rep))
    private fun parameter(id: String, rep: Map<String, Any?>) = mapOf("id" to id, "name" to id,
        "lifted" to (rep["primReps"] == listOf(lifted)), "rep" to rep)
    private fun application(kept: Map<String, Any?>, token: Map<String, Any?> = state,
        result: Map<String, Any?> = state, flags: List<Any?> = listOf(kept["primReps"] == listOf(lifted), false),
        payload: List<Any?> = variable("kept", kept)): List<Any?> =
        listOf("app", listOf("prim", "touch#"), listOf(payload, variable("s", token)), flags, false, false, mapOf("rep" to result))
    private fun module(kept: Map<String, Any?>, body: List<Any?> = application(kept),
        stored: Map<String, Any?> = kept): Map<String, Any?> = mapOf("instrument" to true, "constructors" to emptyList<Any>(),
        "bindings" to listOf(mapOf("id" to "main", "name" to "main", "arity" to 2, "lifted" to true, "rep" to closure,
            "expr" to listOf("lam", listOf(parameter("kept", stored), parameter("s", state)), body,
                mapOf("rep" to closure, "resultRep" to state)))))
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun contexts(block: (Language, String, Boolean) -> Unit) {
        for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true))
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try { block(TruffleLanguage.LanguageReference.create(Language::class.java).get(null), backend, inlining) }
                    finally { context.leave() }
                }
    }
    private fun released(language: Language) {
        val pools = language.handoffState.get()
        assertEquals(0, pools.arguments.depth); assertEquals(0, pools.results.depth)
        assertEquals(0, pools.arguments.retainedReferences()); assertEquals(0, pools.results.retainedReferences())
    }
    private fun bottom() = Thunk(object : RootNode(null) {
        override fun execute(frame: VirtualFrame): Nothing = throw AssertionError("touch# entered its payload")
    }.callTarget, null)

    @Test fun exactReferenceLevityFlagsAndBareStateProofsAreRequired() {
        for (kind in listOf("object", "data", "closure")) for (rep in listOf(lifted, unlifted))
            for (evaluated in listOf(false, true)) {
                val kept = proof(kind, rep, evaluated)
                val flags = listOf(rep == lifted, false)
                assertDoesNotThrow { CoreTouch.validateRaw(listOf(kept, state), flags, state) }
                assertDoesNotThrow { CoreTouch.validate(listOf(kept, state).map(CoreRepresentations::parse), flags,
                    CoreRepresentations.parse(state)) }
            }
        val owner = proof("object", lifted, false)
        val invalid = listOf(null, proof("long", "IntRep"), proof("address", "AddrRep"),
            proof("object", "BoxedRep Nothing"), owner - "evaluated", owner + ("evaluated" to 1),
            owner + ("extra" to null), owner + ("vector" to null), owner + ("aggregate" to "unboxed-tuple"),
            owner + ("components" to emptyList<Any>()))
        for (bad in invalid) assertThrows(RuntimeFault::class.java) {
            CoreTouch.validateRaw(listOf(bad, state), listOf(true, false), state)
        }
        for (bad in listOf(null, owner, state - "evaluated", state + ("extra" to null),
                state + ("aggregate" to "unboxed-tuple"), state + ("primReps" to listOf("IntRep")))) {
            assertThrows(RuntimeFault::class.java) { CoreTouch.validateRaw(listOf(owner, bad), listOf(true, false), state) }
            assertThrows(RuntimeFault::class.java) { CoreTouch.validateRaw(listOf(owner, state), listOf(true, false), bad) }
        }
        for (flags in listOf(emptyList(), listOf(true), listOf(false, false), listOf(true, true), listOf(1, false), listOf(true, false, false)))
            assertThrows(RuntimeFault::class.java) { CoreTouch.validateRaw(listOf(owner, state), flags, state) }
        for (args in listOf(emptyList(), listOf(owner), listOf(owner, state, state)))
            assertThrows(RuntimeFault::class.java) { CoreTouch.validateRaw(args, listOf(true, false), state) }
    }

    @Test fun bothLoadersRejectRawAndLexicalProofSubstitution() = contexts { language, backend, _ ->
        val owner = proof("object", lifted, false)
        val bodies = listOf(application(owner, flags = listOf(false, false)),
            application(owner + ("extra" to 0)), application(owner, state + ("evaluated" to 1)),
            application(owner, result = state + ("extra" to 0)),
            application(owner, result = proof("unknown", null) + mapOf("aggregate" to "unboxed-tuple", "components" to listOf(state))),
            application(proof("long", "IntRep")), application(owner, token = proof("long", "IntRep")))
        for (body in bodies) assertThrows(RuntimeFault::class.java, {
            program(language, backend, module(owner, body))
        }, "$backend/$body")
        for (stored in listOf(proof("long", "IntRep"), proof("address", "AddrRep"), proof("object", unlifted)))
            assertThrows(RuntimeFault::class.java) { program(language, backend, module(owner, stored = stored)) }
    }

    @Test fun liftedBottomAndUnliftedAllocationStayLazyThroughFirstInstalledCalls() = contexts { language, backend, inlining ->
        for (kind in listOf("object", "data", "closure", "unlifted-object")) {
            val unliftedCase = kind == "unlifted-object"
            val rep = proof(if (unliftedCase) "object" else kind, if (unliftedCase) unlifted else lifted, unliftedCase)
            val program = program(language, backend, module(rep))
            val target = program.entryTarget("main")
            val lazy = bottom()
            val allocation = ManagedAllocation.mutable(16, 8)
            val value = if (unliftedCase) allocation else lazy
            fun call(compiled: Boolean) {
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                assertSame(Unit, Calls.target(target, arrayOf(0L, value, Unit)))
                assertEquals(0, lazy.state)
                if (compiled) {
                    assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "$backend/$inlining/$kind")
                }
                released(language)
            }
            repeat(3) { call(false) }
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
            call(true) // First invocation after installation, without settling/recompilation.
            call(true)
            assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, value, 1L)) }
            assertEquals(0, lazy.state); released(language)
        }
    }

    @Test fun nonreturningLiftedExpressionIsDelayedRatherThanEntered() = contexts { language, backend, _ ->
        val owner = proof("object", lifted, false)
        val raise = listOf("app", listOf("prim", "raise#"), listOf(variable("kept", owner)),
            listOf(true), false, false, mapOf("rep" to owner))
        val program = program(language, backend, module(owner, application(owner, payload = raise)))
        assertSame(Unit, Calls.target(program.entryTarget("main"), arrayOf(0L, Any(), Unit)))
        released(language)
    }

    @Test fun stateValidationPrecedesFenceAndAstOperandsAreEvaluatedOnce() {
        val lazy = bottom()
        assertSame(Unit, Touch.preserve(lazy, Unit))
        for (invalid in listOf(null, 1L, Any(), bottom()))
            assertThrows(RuntimeFault::class.java) { Touch.preserve(lazy, invalid) }
        assertEquals(0, lazy.state)
        val calls = mutableListOf<String>()
        fun operand(label: String, value: Any?) = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? { calls += label; return value }
        }
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameLayout().build())
        val node = TouchExpression(operand("kept", lazy), operand("state", Unit), CoreRepresentations.parse(state))
        assertSame(Unit, node.execute(frame)); assertEquals(listOf("kept", "state"), calls)
        assertEquals(0, lazy.state)
        calls.clear()
        val invalid = TouchExpression(operand("kept", lazy), operand("state", 1L), CoreRepresentations.parse(state))
        assertThrows(RuntimeFault::class.java) { invalid.execute(frame) }
        assertEquals(listOf("kept", "state"), calls); assertEquals(0, lazy.state)
    }
}
