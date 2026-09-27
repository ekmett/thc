// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TailCallFrameTest {
    @Test fun missingTargetAndTransferFailBeforeClearingOrDispatchingTheFrame() {
        val descriptor = FrameLayout().build()
        val repeating = TailCallRepeatingNode(descriptor, Metrics(false))
        val target = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any? = error("Invalid trampoline frame reached dispatch")
        }.callTarget
        val transfer = TailCall(target, arrayOf(0L))
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
        val sentinel = Any()
        frame.setObject(FrameLayout.TAIL_RESULT, sentinel)
        frame.setObject(FrameLayout.TAIL_ARGUMENTS, transfer)
        val missingTarget = assertThrows(NullPointerException::class.java) { repeating.executeRepeating(frame) }
        assertEquals("null cannot be cast to non-null type com.oracle.truffle.api.RootCallTarget", missingTarget.message)
        assertSame(transfer, frame.getObject(FrameLayout.TAIL_ARGUMENTS))
        assertSame(sentinel, frame.getObject(FrameLayout.TAIL_RESULT))
        frame.setObject(FrameLayout.TAIL_FUNCTION, target)
        frame.setObject(FrameLayout.TAIL_ARGUMENTS, null)
        val missingTransfer = assertThrows(NullPointerException::class.java) { repeating.executeRepeating(frame) }
        assertEquals("null cannot be cast to non-null type thc.runtime.TailCall", missingTransfer.message)
        assertSame(target, frame.getObject(FrameLayout.TAIL_FUNCTION))
        assertSame(sentinel, frame.getObject(FrameLayout.TAIL_RESULT))
    }
}
