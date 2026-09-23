package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

/** Adversarial lifetime controls for the shared typed input protocol. */
class TypedInputOwnershipTest {
    private fun withLanguage(action: (Language) -> Unit) = Context.newBuilder("thc")
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    private fun input(language: Language): TypedInputLayout {
        val fields = listOf(CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep")),
            CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)")))
        val tuple = CoreRepresentation(CoreKind.UNKNOWN, true, true, fields.flatMap { it.primReps!! }, fields)
        return TypedInputLayout(language, ArgumentLayout.fromProofs(listOf(tuple))!!, false)
    }
    private fun clear(language: Language) {
        val state = language.handoffState.get()
        assertNull(state.pending)
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }
    private fun valid(target: RootCallTarget) = target.javaClass.getMethod("isValidLastTier").invoke(target) == true
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertTrue(valid(target))
    }
    private object Barrier {
        @Volatile var action = 0
        @Volatile var observations = 0L
        var materializedFresh = 0L
        var clearedFresh = 0L
        @CompilerDirectives.TruffleBoundary fun observe(): Int { observations++; return action }
    }
    private class Receiver(language: Language, private val input: TypedInputLayout) : GuestRoot(language, FrameLayout().build()) {
        init { configureEntry(booleanArrayOf(false), false); configureInput(input.logical); configureTypedInput(input) }
        override fun bloom(frame: VirtualFrame) = 0L
        override fun execute(frame: VirtualFrame): Any? {
            val carrier = input.take(frame.arguments)
            try {
                val number = input.packet.getLong(carrier, input.header)
                // The side effect prevents deoptimization from merely replaying
                // entry before the fresh carrier was constructed and consumed.
                val action = Barrier.observe()
                if (action != 0) CompilerDirectives.transferToInterpreterAndInvalidate()
                if (CompilerDirectives.inInterpreter() && carrier.inputMode == 2) {
                    check(!carrier.live)
                    Barrier.materializedFresh++
                }
                val pointer = input.packet.getObject(carrier, input.header + 1)
                check(number == 4097L)
                if (action == 2) throw GuestException("after partial typed restore", this)
                return pointer
            } finally {
                val fresh = carrier.inputMode == 2
                input.releaseChecked(carrier)
                if (CompilerDirectives.inInterpreter() && fresh) {
                    check(carrier.inputMode == 0 && !carrier.live)
                    check(input.packet.getObject(carrier, input.header + 1) == null)
                    Barrier.clearedFresh++
                }
            }
        }
    }
    private class Slots {
        val layout = FrameLayout()
        @field:CompilationFinal(dimensions = 1) val fields = intArrayOf(layout.bind("number"), layout.bind("lazy pointer"))
    }
    private class Caller(language: Language, input: TypedInputLayout, target: RootCallTarget,
        private val slots: Slots = Slots()) : RootNode(language, slots.layout.build()) {
        private val function = Closure(null, arity = 1, target = target)
        @Child private var dispatch = InputDispatch(AstInputSource(input.logical, slots.fields), 1, false, Metrics(false))
        override fun execute(frame: VirtualFrame): Any? {
            FrameAccess.writeLong(frame, slots.fields[0], frame.arguments[0] as Long)
            writeInputReference(frame, slots.fields[1], frame.arguments[1])
            return dispatch.execute(frame, function)
        }
    }
    @Test fun actualDeoptimizationAfterPartialRestoreClearsFreshReferenceWithoutPoolLoan() = withLanguage { language ->
        for (action in listOf(1, 2)) {
            val layout = input(language)
            val target = Caller(language, layout, Receiver(language, layout).callTarget).callTarget
            val bottom = object : RootNode(language) {
                override fun execute(frame: VirtualFrame): Any = error("Lifted input was forced")
            }
            val pointer = Thunk(bottom.callTarget, null)
            Barrier.action = 0
            repeat(3) { assertSame(pointer, target.call(4097L, pointer)); clear(language) }
            compile(target)
            val beforeNormal = Barrier.materializedFresh
            assertSame(pointer, target.call(4097L, pointer)); assertTrue(valid(target)); clear(language)
            assertEquals(beforeNormal, Barrier.materializedFresh, "Normal compiled restore must not use the interpreter path")
            val before = Barrier.materializedFresh
            val cleared = Barrier.clearedFresh
            val observations = Barrier.observations
            Barrier.action = action
            try {
                if (action == 1) assertSame(pointer, target.call(4097L, pointer))
                else assertThrows(GuestException::class.java) { target.call(4097L, pointer) }
            } finally { Barrier.action = 0 }
            assertEquals(observations + 1, Barrier.observations, "Observer must not replay after deoptimization")
            assertEquals(before + 1, Barrier.materializedFresh, "Actual fresh carrier must reach interpreter restore")
            assertEquals(cleared + 1, Barrier.clearedFresh)
            clear(language) // Check failure cleanup before any recovery can conceal retained fields.
            assertSame(pointer, target.call(4097L, pointer)); clear(language)
        }
    }
    @Test fun staleCallerCleanupCannotReleaseReusedInputOrIndependentOutputLoan() = withLanguage { language ->
        val layout = input(language); val state = language.handoffState.get()
        val old = state.arguments.acquire(layout.packet).also { it.inputMode = 1 }
        val oldGeneration = old.generation
        layout.packet.setObject(old, layout.header + 1, Any())
        layout.release(old)
        val next = state.arguments.acquire(layout.packet).also { it.inputMode = 1 }
        val pointer = Any()
        layout.packet.setObject(next, layout.header + 1, pointer)
        val output = state.results.acquire(layout.packet)
        val outputPointer = Any()
        layout.packet.setObject(output, layout.header + 1, outputPointer)
        assertNotSame(next, output)
        assertSame(old, next); assertNotEquals(oldGeneration, next.generation)
        layout.releaseIfOwned(old, oldGeneration)
        assertEquals(1, state.arguments.depth); assertSame(pointer, layout.packet.getObject(next, layout.header + 1))
        layout.release(next)
        assertEquals(0, state.arguments.depth)
        assertEquals(1, state.results.depth)
        assertSame(outputPointer, layout.packet.getObject(output, layout.header + 1))
        state.results.release(output, layout.packet); clear(language)
    }
}
