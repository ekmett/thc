// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

class GenericInputCallInvariantTest {
    private class ReferenceAccess(language: Language, private val write: Boolean) : RootNode(language) {
        private val source = ScalarArrayInputSource(null)
        var entries = 0
        var compiledEntries = 0
        var argumentOrder = 0
        var completed = 0
        private fun argument(frame: VirtualFrame, index: Int): Any? {
            argumentOrder = argumentOrder * 10 + index + 1
            return frame.arguments[index]
        }
        override fun execute(frame: VirtualFrame): Any? {
            entries++
            if (CompilerDirectives.inCompiledCode()) compiledEntries++
            @Suppress("UNCHECKED_CAST")
            val values = argument(frame, 0) as Array<Any?>?
            val index = argument(frame, 1) as Int
            val result = if (write) {
                val value = argument(frame, 2)
                source.setReference(frame, this, values, index, value)
                value
            } else source.reference(frame, this, values, index)
            completed++
            return result
        }
    }

    private fun coldReferenceAccess(write: Boolean, action: (ReferenceAccess, RootCallTarget) -> Unit) =
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val root = ReferenceAccess(language, write)
                    val target = root.callTarget
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    // Restore the shared entry stub without executing a settling guest call.
                    val runtime = Truffle.getRuntime()
                    runtime.javaClass.getMethod("bypassedInstalledCode",
                        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
                    assertEquals(0, root.entries)
                    assertEquals(0, root.argumentOrder)
                    action(root, target)
                    assertSame(target, root.callTarget)
                    assertEquals(1, root.entries, "The operation must execute once, including failure paths")
                    assertEquals(1, root.compiledEntries, "The first call must enter installed code")
                    assertEquals(if (write) 123 else 12, root.argumentOrder)
                } finally { context.leave() }
            }

    @Test fun scalarReferenceReadPreservesNullElementsAndIdentityOnItsFirstInstalledCall() {
        for (value in listOf(Any(), null)) coldReferenceAccess(false) { root, target ->
            val sentinel = Any()
            val values = arrayOf<Any?>(sentinel, value)
            assertSame(value, Calls.target(target, arrayOf(values, 1)))
            assertSame(sentinel, values[0]); assertSame(value, values[1])
            assertEquals(1, root.completed)
            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
        }
    }

    @Test fun scalarReferenceWritePreservesNullElementsAndIdentityOnItsFirstInstalledCall() {
        for (value in listOf(Any(), null)) coldReferenceAccess(true) { root, target ->
            val sentinel = Any()
            val values = arrayOf<Any?>(sentinel, Any())
            assertSame(value, Calls.target(target, arrayOf(values, 1, value)))
            assertSame(sentinel, values[0]); assertSame(value, values[1])
            assertEquals(1, root.completed)
            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
        }
    }

    @Test fun scalarReferenceNullArrayKeepsItsOriginalFailureAfterArgumentEvaluation() {
        for (write in listOf(false, true)) coldReferenceAccess(write) { root, target ->
            // Both operands are invalid: the original null assertion must precede bounds checking.
            val failure = assertThrows(NullPointerException::class.java) {
                Calls.target(target, arrayOf(null, -1, Any()))
            }
            assertEquals(NullPointerException::class.java, failure.javaClass)
            assertNull(failure.message)
            assertEquals(0, root.completed)
        }
    }

    @Test fun scalarReferenceBoundsFailureLeavesTheArrayUntouchedOnItsFirstInstalledCall() {
        for (write in listOf(false, true)) for (index in listOf(-1, 2)) coldReferenceAccess(write) { root, target ->
            val sentinel = Any()
            val values = arrayOf<Any?>(sentinel, null)
            assertThrows(ArrayIndexOutOfBoundsException::class.java) {
                Calls.target(target, arrayOf(values, index, Any()))
            }
            assertSame(sentinel, values[0]); assertNull(values[1])
            assertEquals(0, root.completed)
        }
    }

    private fun dispatch(target: RootCallTarget, values: Array<Any?>): Any? {
        val call = GenericInputCall(ScalarArrayInputSource(null), 1, false, Metrics(false), null, 0)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameLayout().build())
        return call.execute(frame, Closure(null, 1, target), values)
    }

    @Test fun missingRootRetainsItsNullCastFailureBeforeInputPreparation() {
        val target = object : RootCallTarget {
            override fun getRootNode(): RootNode? = null
            override fun call(vararg arguments: Any?): Any? = error("Invalid target reached dispatch")
        }
        val values = arrayOf<Any?>(23L)
        val failure = assertThrows(NullPointerException::class.java) { dispatch(target, values) }
        assertEquals("null cannot be cast to non-null type thc.runtime.GuestRoot", failure.message)
        assertArrayEquals(arrayOf(23L), values)
    }

    @Test fun nonGuestRootRetainsItsClassCastFailureBeforeInputPreparation() {
        val target = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any? = error("Non-guest target reached dispatch")
        }.callTarget
        val values = arrayOf<Any?>(23L)
        assertThrows(ClassCastException::class.java) { dispatch(target, values) }
        assertArrayEquals(arrayOf(23L), values)
    }

    private fun <T : Throwable> preparationFailure(root: RootNode?, type: Class<T>): T =
        executionContext(false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val fields = listOf(CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep")),
                    CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)")))
                val tuple = CoreRepresentation(CoreKind.UNKNOWN, true, true, fields.flatMap { it.primReps!! }, fields)
                val input = TypedInputLayout(language, ArgumentLayout.fromProofs(listOf(tuple))!!, false)
                var reads = 0
                val target = object : RootCallTarget {
                    override fun getRootNode(): RootNode? { reads++; return root }
                    override fun call(vararg arguments: Any?): Any? = error("Invalid target reached dispatch")
                }
                val marker = Any()
                val layout = FrameLayout()
                val slots = intArrayOf(layout.bind("number"), layout.bind("reference"))
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), layout.build())
                FrameAccess.writeLong(frame, slots[0], 23L)
                writeInputReference(frame, slots[1], marker)
                val source = AstInputSource(input.logical, slots)
                val node = object : Node() {}
                val failure = assertThrows(type) {
                    GenericTypedInputsKt.prepareGenericInput(frame, node, Closure(null, 1, target), input,
                        source, null, 1, 0, 1, Force(Metrics(false)))
                }
                assertEquals(1, reads)
                assertEquals(23L, FrameAccess.read(frame, slots[0]))
                assertSame(marker, FrameAccess.read(frame, slots[1]))
                val state = language.handoffState.get()
                assertNull(state.pending)
                assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
                assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
                failure
            } finally { context.leave() }
        }

    @Test fun missingRootFailsBeforeGenericPreparationAcquiresALoan() {
        val failure = preparationFailure(null, NullPointerException::class.java)
        assertEquals("null cannot be cast to non-null type thc.runtime.GuestRoot", failure.message)
    }

    @Test fun nonGuestRootFailsBeforeGenericPreparationAcquiresALoan() {
        val root = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any? = error("Non-guest target reached dispatch")
        }
        preparationFailure(root, ClassCastException::class.java)
    }

    @Test fun invalidTypedTargetLeavesExistingLoansUntouchedBeforeTheAction() =
        executionContext(false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val field = CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)"))
                val tuple = CoreRepresentation(CoreKind.UNKNOWN, true, true, field.primReps, listOf(field))
                val layout = TypedInputLayout(language, ArgumentLayout.fromProofs(listOf(tuple))!!, false)
                val nonGuest = object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any? = error("Non-guest target reached dispatch")
                }
                val untyped = object : GuestRoot(language, FrameLayout().build()) {
                    override fun bloom(frame: VirtualFrame) = 0L
                    override fun execute(frame: VirtualFrame): Any? = error("Untyped target reached dispatch")
                }
                val roots = listOf(null, nonGuest, untyped)
                val failures = listOf(NullPointerException::class.java, ClassCastException::class.java, RuntimeFault::class.java)
                val state = language.handoffState.get()
                for ((root, type) in roots.zip(failures)) {
                    var reads = 0
                    val target = object : RootCallTarget {
                        override fun getRootNode(): RootNode? { reads++; return root }
                        override fun call(vararg arguments: Any?): Any? = error("Invalid target reached dispatch")
                    }
                    val input = state.arguments.acquire(layout.packet).also { it.inputMode = 1 }
                    val output = state.results.acquire(layout.packet)
                    val generation = input.generation
                    val marker = Any()
                    val outputMarker = Any()
                    layout.packet.setObject(input, layout.header, marker)
                    layout.packet.setObject(output, layout.header, outputMarker)
                    val failure = assertThrows(type) {
                        invokeTypedInput(target, input) { error("Invalid target reached the action") }
                    }
                    if (root == null)
                        assertEquals("null cannot be cast to non-null type thc.runtime.GuestRoot", failure.message)
                    if (root === untyped) assertEquals("Target has no typed input entry", failure.message)
                    assertEquals(1, reads)
                    assertTrue(input.live); assertEquals(1, input.inputMode); assertEquals(generation, input.generation)
                    assertSame(marker, layout.packet.getObject(input, layout.header))
                    assertTrue(output.live); assertSame(outputMarker, layout.packet.getObject(output, layout.header))
                    assertEquals(1, state.arguments.depth); assertEquals(1, state.results.depth)
                    assertNull(state.pending)
                    layout.release(input)
                    state.results.release(output, layout.packet)
                    assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
                    assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
                }
            } finally { context.leave() }
        }
}
