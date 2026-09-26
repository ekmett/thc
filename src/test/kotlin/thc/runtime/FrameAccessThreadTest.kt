// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FrameAccessThreadTest {
    @Test fun genericWritesKeepExactScalarKindsAndOtherNumbersAsReferences() {
        val customNumber = object : Number() {
            override fun toByte(): Byte = error("not a guest scalar")
            override fun toShort(): Short = error("not a guest scalar")
            override fun toInt(): Int = error("not a guest scalar")
            override fun toLong(): Long = error("not a guest scalar")
            override fun toFloat(): Float = error("not a guest scalar")
            override fun toDouble(): Double = error("not a guest scalar")
        }
        val values = listOf(Long.MIN_VALUE, Long.MAX_VALUE, -0.0f, -0.0, true, false,
            1.toByte(), 2.toShort(), 3, java.math.BigInteger.ONE.shiftLeft(80),
            java.math.BigDecimal("1.125"), customNumber, null)
        for (value in values) {
            val builder = FrameDescriptor.newBuilder()
            val slot = builder.addSlot(FrameSlotKind.Illegal, "value", null)
            val descriptor = builder.build()
            val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
            FrameAccess.write(frame, slot, value)
            val expectedKind = when (value) {
                is Long -> FrameSlotKind.Long
                is Float -> FrameSlotKind.Float
                is Double -> FrameSlotKind.Double
                is Boolean -> FrameSlotKind.Boolean
                else -> FrameSlotKind.Object
            }
            assertEquals(expectedKind, descriptor.getSlotKind(slot))
            if (expectedKind == FrameSlotKind.Object) assertSame(value, FrameAccess.read(frame, slot))
            else assertEquals(value, FrameAccess.read(frame, slot))
            FrameAccess.writeObject(frame, slot, Any())
            FrameAccess.write(frame, slot, value)
            assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(slot))
            assertEquals(value, FrameAccess.read(frame, slot))
        }
    }

    @Test fun concurrentPrimitiveClaimsCannotUndoObjectWidening() {
        val workers = Executors.newFixedThreadPool(5)
        try {
            repeat(200) {
                val builder = FrameDescriptor.newBuilder()
                val slot = builder.addSlot(FrameSlotKind.Illegal, "shared", null)
                val descriptor = builder.build()
                val barrier = CyclicBarrier(5)
                val marker = Any()
                val results = (0 until 5).map { worker -> workers.submit(Callable {
                    val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                    barrier.await(10, TimeUnit.SECONDS)
                    when (worker) {
                        0 -> FrameAccess.writeLong(frame, slot, 41L)
                        1 -> FrameAccess.writeFloat(frame, slot, 3.5f)
                        2 -> FrameAccess.writeDouble(frame, slot, -2.25)
                        3 -> FrameAccess.write(frame, slot, true)
                        else -> writeInputReference(frame, slot, marker)
                    }
                    FrameAccess.read(frame, slot)
                }) }
                assertEquals(41L, results[0].get(10, TimeUnit.SECONDS))
                assertEquals(3.5f, results[1].get(10, TimeUnit.SECONDS))
                assertEquals(-2.25, results[2].get(10, TimeUnit.SECONDS))
                assertEquals(true, results[3].get(10, TimeUnit.SECONDS))
                assertSame(marker, results[4].get(10, TimeUnit.SECONDS))
                assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(slot))
                val later = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                FrameAccess.writeLong(later, slot, 99L)
                assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(slot))
                assertEquals(99L, FrameAccess.read(later, slot))
                FrameAccess.writeFloat(later, slot, 4.5f)
                FrameAccess.writeDouble(later, slot, 8.25)
                FrameAccess.write(later, slot, false)
                assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(slot))
                assertEquals(false, FrameAccess.read(later, slot))
            }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Frame workers did not terminate")
        }
    }
}
