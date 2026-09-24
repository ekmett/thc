// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OperandRetentionTest {
    private fun frame(slots: Int): VirtualFrame {
        val descriptor = FrameDescriptor.newBuilder().also { builder ->
            repeat(slots) { builder.addSlot(FrameSlotKind.Illegal, "slot $it", null) }
        }.build()
        return Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
    }
    private fun expression(action: (VirtualFrame) -> Any?): Expr = object : Expr() {
        override fun execute(frame: VirtualFrame): Any? = action(frame)
    }
    private val long = CoreRepresentation(CoreKind.LONG, evaluated = true)

    private fun assertCleared(frame: VirtualFrame, slots: IntArray) {
        // Public reads reject a cleared (Illegal) slot. Inspect the pinned
        // frame's reference storage to verify release without GC timing.
        val field = frame.javaClass.getDeclaredField("indexedLocals").also { it.isAccessible = true }
        val references = field.get(frame) as Array<*>
        for (slot in slots) {
            assertEquals(FrameSlotKind.Illegal.tag, frame.getTag(slot))
            assertNull(references[slot], "Completed transfer must release scratch slot $slot")
        }
    }

    @Test fun selfTransferReleasesEveryOperandIncludingUnusedFormalsWithoutWideningSlots() {
        val frame = frame(7)
        val first = Any()
        val second = Any()
        val unused = Any()
        val temporaries = intArrayOf(3, 4, 5, 6)
        val layout = AstSelfLayout(null, intArrayOf(), intArrayOf(0, 1, 2, -1),
            arrayOf(CoreRepresentation.UNKNOWN, CoreRepresentation.UNKNOWN, long, CoreRepresentation.UNKNOWN),
            BooleanArray(4))
        val target = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any? = error("Self transfer must not call its target")
        }.callTarget
        val function = Closure(null, arity = 4, target = target)
        FrameAccess.write(frame, 0, first)
        FrameAccess.write(frame, 1, second)
        FrameAccess.writeLong(frame, 2, Long.MIN_VALUE)
        for (value in longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE, 3_000_000_017L)) {
            val oldFirst = FrameAccess.read(frame, 0)
            val oldSecond = FrameAccess.read(frame, 1)
            FrameAccess.write(frame, 3, oldSecond)
            FrameAccess.write(frame, 4, oldFirst)
            FrameAccess.writeLong(frame, 5, value)
            FrameAccess.write(frame, 6, unused)
            val kinds = temporaries.map { frame.frameDescriptor.getSlotKind(it) }

            assertSame(AstSelfCall, assertThrows(AstSelfCall::class.java) {
                layout.transfer(frame, function, temporaries)
            })
            assertSame(oldSecond, FrameAccess.read(frame, 0))
            assertSame(oldFirst, FrameAccess.read(frame, 1))
            assertEquals(value, frame.getLong(2))
            assertCleared(frame, temporaries)
            assertEquals(kinds, temporaries.map { frame.frameDescriptor.getSlotKind(it) })
            assertEquals(FrameSlotKind.Long, frame.frameDescriptor.getSlotKind(5))
        }
    }

    @Test fun joinTransferReleasesCompletedOperandsButDoesNotMoveOrClearOnOperandFailure() {
        val frame = frame(6)
        val first = Any()
        val second = Any()
        val failure = RuntimeFault("join operand failure")
        var fail = false
        val order = mutableListOf<Int>()
        val target = LocalJoinTarget(Any(), 0, intArrayOf(0, 1, 2),
            arrayOf(CoreRepresentation.UNKNOWN, CoreRepresentation.UNKNOWN, long))
        val temporaries = intArrayOf(3, 4, 5)
        val metrics = Metrics(true)
        val call = LocalJoinCall(target, arrayOf(
            expression { order += 0; FrameAccess.read(it, 1) },
            expression { order += 1; FrameAccess.read(it, 0) },
            expression { order += 2; if (fail) throw failure; it.getLong(2) + 1 }),
            temporaries, metrics)
        FrameAccess.write(frame, 0, first)
        FrameAccess.write(frame, 1, second)
        FrameAccess.writeLong(frame, 2, Long.MAX_VALUE)
        assertSame(target.jump, assertThrows(LocalJoinJump::class.java) { call.execute(frame) })
        assertEquals(listOf(0, 1, 2), order)
        assertSame(second, FrameAccess.read(frame, 0))
        assertSame(first, FrameAccess.read(frame, 1))
        assertEquals(Long.MIN_VALUE, frame.getLong(2))
        assertCleared(frame, temporaries)
        assertEquals(FrameSlotKind.Long, frame.frameDescriptor.getSlotKind(5))
        assertEquals(1L, metrics.localJoinTransfers)

        fail = true
        order.clear()
        assertSame(failure, assertThrows(RuntimeFault::class.java) { call.execute(frame) })
        assertEquals(listOf(0, 1, 2), order)
        assertSame(second, FrameAccess.read(frame, 0), "No destination changes before all operands finish")
        assertSame(first, FrameAccess.read(frame, 1))
        assertEquals(Long.MIN_VALUE, frame.getLong(2))
        assertSame(first, FrameAccess.read(frame, 3), "An incomplete transfer has not cleared its operands")
        assertSame(second, FrameAccess.read(frame, 4))
        assertEquals(1L, metrics.localJoinTransfers)
    }
}
