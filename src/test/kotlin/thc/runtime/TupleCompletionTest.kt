// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.IndirectCallNode
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

/** Adversarial ownership controls using the production completion implementation. */
class TupleCompletionTest {
    private fun valid(target: RootCallTarget): Boolean = target.javaClass.getMethod("isValidLastTier").invoke(target) == true
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertTrue(valid(target))
    }
    private fun withLanguage(action: (Language) -> Unit) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
        }
    private fun shape(language: Language): TupleShape {
        val leaves = listOf(CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep")),
            CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)")))
        return TupleShape(CoreRepresentation(CoreKind.UNKNOWN, true, true,
            leaves.flatMap { it.primReps!! }, leaves), language)
    }
    private class Slots {
        val layout = FrameLayout()
        @field:CompilationFinal(dimensions = 1) val fields = intArrayOf(layout.bind("integer"), layout.bind("lazy pointer"))
    }
    private class Producer(language: Language, val shape: TupleShape, private val slots: Slots = Slots(),
        private val fallback: RootCallTarget? = null, private val environment: CaptureLayout? = null) : GuestRoot(language, slots.layout.build()) {
        @Child private var residual = IndirectCallNode.create()
        init { configureTupleResult(shape); configureEntry(booleanArrayOf(false, false), environment != null) }
        override fun bloom(frame: VirtualFrame) = 0L
        override fun getName() = "tuple ownership producer"
        override fun execute(frame: VirtualFrame): Any {
            val offset = if (environment == null) 1 else 2
            val value = (frame.arguments[offset] as Long) +
                (environment?.readLong(frame.arguments[1] as CapturedFrame, 0) ?: 0L)
            if (fallback != null && value < 0) {
                shape.consume(frame, Calls.indirect(residual, fallback, arrayOf(0L, value, frame.arguments[2])), slots.fields, 0)
            } else {
                FrameAccess.writeLong(frame, slots.fields[0], value + 17L)
                FrameAccess.write(frame, slots.fields[1], frame.arguments[offset + 1])
            }
            return shape.finish(frame, slots.fields)
        }
    }
    private class Consumer(language: Language, private val shape: TupleShape, target: RootCallTarget,
        private val barrier: Boolean, private val slots: Slots = Slots()) : RootNode(language, slots.layout.build()) {
        @Child private var call = DirectCallNode.create(target).also { it.forceInlining() }
        override fun getName() = "tuple ownership consumer"
        override fun execute(frame: VirtualFrame): Any? {
            val value = frame.arguments[0] as Long
            val result = Calls.direct(call, arrayOf(0L, value, frame.arguments[1]))
            if (barrier && Barrier.observe()) CompilerDirectives.transferToInterpreterAndInvalidate()
            if (CompilerDirectives.inInterpreter() && result is HandoffStorage) Barrier.materialized++
            shape.consume(frame, result, slots.fields, 0)
            check(frame.getLong(slots.fields[0]) == value + 17L)
            return frame.getObject(slots.fields[1])
        }
    }
    private class DispatchConsumer(language: Language, shape: TupleShape, private val slots: Slots = Slots()) : RootNode(language, slots.layout.build()) {
        @Child private var dispatch = TupleDispatch(object : TupleDestination(shape) {
            override fun consume(frame: VirtualFrame, node: com.oracle.truffle.api.nodes.Node, result: Any?) =
                shape.consume(frame, result, slots.fields, 0)
        }, Metrics(false), 1, false)
        override fun execute(frame: VirtualFrame): Any? {
            dispatch.execute(frame, frame.arguments[0] as Closure, arrayOf(frame.arguments[1]))
            check(frame.getLong(slots.fields[0]) == frame.arguments[2] as Long)
            return frame.getObject(slots.fields[1])
        }
    }
    private object Barrier {
        @Volatile var requested = false
        @Volatile var observations = 0L
        var materialized = 0L
        @CompilerDirectives.TruffleBoundary fun observe(): Boolean { observations++; return requested }
    }
    private fun released(language: Language) {
        assertEquals(0, language.handoffState.get().results.depth)
        assertEquals(0, language.handoffState.get().results.retainedReferences())
    }
    @Test fun deoptimizationMaterializesAFreshPointerCarrierWithoutInventingALoan() = withLanguage { language ->
        val shape = shape(language)
        val producer = Producer(language, shape).callTarget
        val consumer = Consumer(language, shape, producer, true).callTarget
        val pointer = Any()
        Barrier.requested = false
        repeat(20) { assertSame(pointer, consumer.call(it.toLong(), pointer)); released(language) }
        compile(consumer)
        assertSame(pointer, consumer.call(4097L, pointer)); assertTrue(valid(consumer)); released(language)
        val before = Barrier.materialized
        Barrier.requested = true
        try { assertSame(pointer, consumer.call(8193L, pointer)) } finally { Barrier.requested = false }
        assertEquals(before + 1, Barrier.materialized, "The post-call observer must force actual carrier materialization")
        released(language)
    }
    @Test fun freshAndResidualBranchesNormalizeBeforeTheirResultJoin() = withLanguage { language ->
        val shape = shape(language)
        val residual = Producer(language, shape).callTarget
        val branching = Producer(language, shape, fallback = residual).callTarget
        val consumer = Consumer(language, shape, branching, false).callTarget
        val pointer = Any()
        repeat(20) { assertSame(pointer, consumer.call(it.toLong(), pointer)) }
        compile(residual); compile(consumer)
        assertSame(pointer, consumer.call(4097L, pointer)); assertTrue(valid(consumer))
        // Exercise the previously cold residual branch, then compile both warmed arms.
        assertSame(pointer, consumer.call(-4097L, pointer)); released(language)
        repeat(10) { assertSame(pointer, consumer.call(if (it % 2 == 0) -8193L else 8193L, pointer)) }
        compile(consumer)
        val allocations = language.handoffState.get().results.allocations
        for (value in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
            assertSame(pointer, consumer.call(value, pointer)); assertTrue(valid(consumer)); released(language)
        }
        assertEquals(allocations, language.handoffState.get().results.allocations)
    }
    @Test fun polymorphicTupleCallsKeepPapPrefixesAndCapturedHeadersThroughGenericDispatch() = withLanguage { language ->
        val shape = shape(language)
        val captured = CaptureLayout(language, booleanArrayOf(true), booleanArrayOf(true))
        val producers = List(5) { index -> Producer(language, shape, environment = if (index % 2 == 0) null else captured).callTarget }
        val closures = producers.mapIndexed { index, target ->
            val environment = if (index % 2 == 0) null else captured.captureValues(arrayOf(31L * index))
            Closure(environment, arity = 2, target = target).pap(arrayOf(100L * index))
        }
        val consumer = DispatchConsumer(language, shape).callTarget
        val pointer = Any()
        fun checkAll() = closures.forEachIndexed { index, closure ->
            val expected = 100L * index + 17L + if (index % 2 == 0) 0L else 31L * index
            assertSame(pointer, consumer.call(closure, pointer, expected)); released(language)
        }
        repeat(10) { checkAll() }
        producers.forEach(::compile); compile(consumer)
        val allocations = language.handoffState.get().results.allocations
        repeat(10) { checkAll() }
        assertTrue(valid(consumer))
        assertEquals(allocations, language.handoffState.get().results.allocations)
    }

}
