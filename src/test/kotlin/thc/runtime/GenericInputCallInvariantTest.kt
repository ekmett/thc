// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

class GenericInputCallInvariantTest {
    private fun dispatch(target: RootCallTarget, values: Array<Any?>): Any? {
        val call = GenericInputCall(ScalarArrayInputSource(null), 1, false, Metrics(false), null, 0)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameLayout().build())
        return call.execute(frame, Closure(null, arity = 1, target = target), values)
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
                    prepareGenericInput(frame, node, Closure(null, arity = 1, target = target), input,
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
}
