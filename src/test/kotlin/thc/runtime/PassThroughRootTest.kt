// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

class PassThroughRootTest {
    private val long = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
    private val int = CoreRepresentation(CoreKind.LONG, true, true, listOf("Int8Rep"))
    private fun withLanguage(action: (Language) -> Unit) = executionContext().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target)
    }
    private class Value(private val value: Any?, proof: CoreRepresentation) : Expr() {
        init { representation = proof }
        override fun execute(frame: VirtualFrame) = value
    }
    private class Function : Expr() {
        lateinit var value: Closure
        init { representation = CoreRepresentation(CoreKind.CLOSURE, true) }
        override fun execute(frame: VirtualFrame) = value
        override fun executeClosure(frame: VirtualFrame) = value
    }

    @Test fun firstCompiledEntryPreservesExactScalarCarriersAndCloneRole() = withLanguage { language ->
        val float = CoreRepresentation(CoreKind.FLOAT, true, true, listOf("FloatRep"))
        val double = CoreRepresentation(CoreKind.DOUBLE, true, true, listOf("DoubleRep"))
        for ((proof, value) in listOf<Pair<CoreRepresentation, Any>>(int to -128, long to Long.MIN_VALUE,
            float to Float.fromBits(0x80000000.toInt()), double to Double.fromBits(0x7ff8000000000001L))) {
            val layout = FrameLayout(); val slot = layout.bind("argument")
            val metrics = Metrics(true)
            val body = object : Expr() {
                init { representation = proof }
                override fun execute(frame: VirtualFrame): Any? = FrameAccess.read(frame, slot)
                override fun executeInt(frame: VirtualFrame) = frame.getInt(slot)
                override fun executeLong(frame: VirtualFrame) = frame.getLong(slot)
                override fun executeFloat(frame: VirtualFrame) = frame.getFloat(slot)
                override fun executeDouble(frame: VirtualFrame) = frame.getDouble(slot)
            }
            val root = FunctionRoot(language, layout.build(), "pass scalar", null, intArrayOf(),
                intArrayOf(slot), intArrayOf(0), body, metrics, arrayOf(proof), proof,
                role = FunctionRootRole.PASS_THROUGH)
            val target = root.callTarget
            compile(target)
            assertEquals(0L, metrics.compiledEntries)
            val result = Calls.target(target, arrayOf(0L, value))
            assertEquals(value.javaClass, result!!.javaClass)
            if (value is Float) assertEquals(value.toRawBits(), (result as Float).toRawBits())
            else if (value is Double) assertEquals(value.toRawBits(), (result as Double).toRawBits())
            else assertEquals(value, result)
            assertEquals(1L, metrics.compiledEntries)
            assertSame(target, root.callTarget)
            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
            val clone = NodeUtil.cloneNode(root)
            assertEquals(FunctionRootRole.PASS_THROUGH, clone.role)
            val clonedTarget = clone.callTarget
            compile(clonedTarget)
            val before = metrics.compiledEntries
            Calls.target(clonedTarget, arrayOf(0L, value))
            assertEquals(before + 1, metrics.compiledEntries)
            assertEquals(true, clonedTarget.javaClass.getMethod("isValidLastTier").invoke(clonedTarget))
        }
    }

    @Test fun explicitSelfTailAndDenseTailEscapeUnchangedWithoutInterceptionOrLoanConsumption() = withLanguage { language ->
        val metrics = Metrics(true)
        val body = object : Expr() {
            lateinit var transfer: com.oracle.truffle.api.nodes.ControlFlowException
            var entries = 0
            override fun execute(frame: VirtualFrame): Any? {
                entries++
                check(entries <= 1) { "Side root must not repeat" }
                throw transfer
            }
        }
        val root = FunctionRoot(language, FrameLayout().build(), "pass self", null, intArrayOf(),
            intArrayOf(), intArrayOf(), body, metrics, role = FunctionRootRole.PASS_THROUGH)
        val target = root.callTarget
        val packet = arrayOf<Any?>(17L, Any())
        val tail = TailCall(target, packet)
        body.transfer = tail
        assertSame(tail, assertThrows(TailCall::class.java) { Calls.target(target, arrayOf(0L)) })
        assertSame(packet, tail.args); assertEquals(17L, packet[0])
        assertEquals(1, body.entries); assertEquals(0L, metrics.selfTailReentries)
        val layout = HandoffLayout(language, 0, listOf("long", "reference"))
        val state = language.handoffState.get()
        val input = state.arguments.acquire(layout)
        val marker = Any(); layout.setObject(input, 1, marker)
        val generation = input.generation
        try {
            val dense = HandoffTailCall(target, input)
            body.transfer = dense; body.entries = 0
            assertSame(dense, assertThrows(HandoffTailCall::class.java) { Calls.target(target, arrayOf(0L)) })
            assertEquals(1, body.entries); assertTrue(input.live); assertEquals(generation, input.generation)
            assertSame(marker, layout.getObject(input, 1)); assertEquals(1, state.arguments.depth)
        } finally { state.arguments.release(input, layout) }
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0L, metrics.trampolineIterations)
    }

    @Test fun inheritedBloomBackedgeCrossesSideRootAndClosesAtOuterOwner() = withLanguage { language ->
        val outerLayout = FrameLayout(); val n = outerLayout.bind("n")
        val sideLayout = FrameLayout(); val sideN = sideLayout.bind("n")
        val outerMetrics = Metrics(true); val sideMetrics = Metrics(true)
        lateinit var outer: FunctionRoot
        val sideBody = object : Expr() {
            @Child var tail = TailCheck(sideMetrics)
            var entries = 0
            var ancestryPreserved = true
            init { representation = long }
            override fun execute(frame: VirtualFrame): Any? = executeLong(frame)
            override fun executeLong(frame: VirtualFrame): Long {
                entries++
                val inherited = (rootNode as GuestRoot).bloom(frame)
                ancestryPreserved = ancestryPreserved && inherited and outer.mask == outer.mask
                val remaining = frame.getLong(sideN)
                if (remaining == 0L) return 73L
                tail.check(frame, outer.callTarget, arrayOf(0L, remaining - 1))
                error("Inherited outer Bloom must bounce")
            }
        }
        val side = FunctionRoot(language, sideLayout.build(), "D", null, intArrayOf(),
            intArrayOf(sideN), intArrayOf(0), sideBody, sideMetrics, arrayOf(long), long,
            role = FunctionRootRole.PASS_THROUGH)
        val outerBody = object : Expr() {
            @Child var call = DirectCallNode.create(side.callTarget)
            var entries = 0
            init { representation = long }
            override fun execute(frame: VirtualFrame): Any? = executeLong(frame)
            override fun executeLong(frame: VirtualFrame): Long {
                entries++
                // An outlined arm inherits ancestry; this is NOT call(false).
                return Calls.direct(call, arrayOf((rootNode as GuestRoot).bloom(frame), frame.getLong(n))) as Long
            }
        }
        outer = FunctionRoot(language, outerLayout.build(), "A", null, intArrayOf(), intArrayOf(n),
            intArrayOf(0), outerBody, outerMetrics, arrayOf(long), long)
        assertEquals(73L, Calls.target(outer.callTarget, arrayOf(0L, 4L)))
        assertEquals(5, outerBody.entries); assertEquals(5, sideBody.entries)
        assertTrue(sideBody.ancestryPreserved)
        assertEquals(4L, outerMetrics.selfTailReentries); assertEquals(0L, sideMetrics.selfTailReentries)
        assertEquals(0L, outerMetrics.trampolineIterations); assertEquals(0L, sideMetrics.trampolineIterations)
    }

    @Test fun scalarSelfShortcutCannotMutateSideFrameOrEmitTargetlessAstSelfCall() = withLanguage { language ->
        val layout = FrameLayout(); val slot = layout.bind("argument"); val temporary = layout.bind("temporary")
        val function = Function()
        val self = AstSelfLayout(null, intArrayOf(), intArrayOf(slot), arrayOf(long), booleanArrayOf(false))
        val app = AstTailApplication(function, arrayOf(Value(22L, long)), self, intArrayOf(temporary), Metrics(false))
        var originalArgument: Any? = null
        val body = object : Expr() {
            @Child var child = app
            override fun execute(frame: VirtualFrame): Any? = try { child.execute(frame) }
                finally { originalArgument = FrameAccess.read(frame, slot) }
        }
        val root = FunctionRoot(language, layout.build(), "scalar self side", null, intArrayOf(),
            intArrayOf(slot), intArrayOf(0), body, Metrics(false), arrayOf(long), long,
            role = FunctionRootRole.PASS_THROUGH)
        function.value = Closure(null, 1, root.callTarget)
        val transfer = assertThrows(TailCall::class.java) { Calls.target(root.callTarget, arrayOf(0L, 11L)) }
        assertSame(root.callTarget, transfer.target); assertEquals(22L, transfer.args[1])
        assertEquals(11L, originalArgument)
    }

    @Test fun typedSelfShortcutUsesOwnedExplicitPacketWithoutMutatingSideFrame() = withLanguage { language ->
        val layout = FrameLayout(); val slot = layout.bind("argument")
        val function = Function()
        val input = ArgumentLayout.fromProofs(listOf(int))!!
        val app = AstTypedApplication(function, arrayOf(Value(22, int)), layout, true, Metrics(false), selfTransfer = true)
        var originalArgument: Any? = null
        val body = object : Expr() {
            @Child var child = app
            override fun execute(frame: VirtualFrame): Any? = try { child.execute(frame) }
                finally { originalArgument = FrameAccess.read(frame, slot) }
        }
        val root = FunctionRoot(language, layout.build(), "typed self side", null, intArrayOf(),
            intArrayOf(slot), intArrayOf(0), body, Metrics(false), arrayOf(int), int, inputLayout = input,
            role = FunctionRootRole.PASS_THROUGH)
        val typed = TypedInputLayout.create(language, input, false)!!
        root.configureTypedInput(typed)
        function.value = Closure(null, 1, root.callTarget)
        val incoming = typed.packet.create().also {
            it.inputMode = 2; typed.packet.setLong(it, 0, 0); typed.packet.setInt(it, 1, 11)
        }
        val transfer = assertThrows(TailCall::class.java) { Calls.target(root.callTarget, arrayOf(incoming)) }
        assertSame(root.callTarget, transfer.target); assertEquals(11, originalArgument)
        assertEquals(0, incoming.inputMode)
        val outgoing = transfer.input!!
        try { assertEquals(22, typed.packet.getInt(outgoing, 1)); assertTrue(outgoing.inputMode in 1..3) }
        finally { discardTypedInput(language, outgoing) }
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
    }

    @Test fun ordinaryNontailCallInsideSideBodyRetainsItsOwnReturningContinuation() = withLanguage { language ->
        val metrics = Metrics(true)
        val result = FunctionRoot(language, FrameLayout().build(), "ordinary tail destination", null,
            intArrayOf(), intArrayOf(), intArrayOf(), Value(41L, long), metrics)
        val childBody = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? {
                assertEquals(0L, frame.arguments[0], "Only the ordinary non-tail call resets ancestry")
                throw TailCall(result.callTarget, arrayOf(0L))
            }
        }
        val child = FunctionRoot(language, FrameLayout().build(), "ordinary child", null,
            intArrayOf(), intArrayOf(), intArrayOf(), childBody, metrics)
        val body = object : Expr() {
            @Child var caller = DirectCallerNode(child.callTarget, metrics)
            init { representation = long }
            override fun execute(frame: VirtualFrame): Any? = executeLong(frame)
            override fun executeLong(frame: VirtualFrame): Long {
                val before = (rootNode as GuestRoot).bloom(frame)
                val answer = caller.call(frame, arrayOf(0L), false) as Long
                assertEquals(before, (rootNode as GuestRoot).bloom(frame))
                return answer + 1
            }
        }
        val side = FunctionRoot(language, FrameLayout().build(), "returning side", null,
            intArrayOf(), intArrayOf(), intArrayOf(), body, metrics, role = FunctionRootRole.PASS_THROUGH)
        assertEquals(42L, Calls.target(side.callTarget, arrayOf(0x12345678L)))
        assertEquals(1L, metrics.trampolineIterations)
    }
}
