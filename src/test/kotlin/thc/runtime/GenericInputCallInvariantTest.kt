// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

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
}
