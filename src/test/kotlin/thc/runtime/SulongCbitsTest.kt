// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.InvalidBufferOffsetException
import com.oracle.truffle.api.interop.UnsupportedMessageException
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.function.Supplier
import thc.Language

class SulongCbitsTest {
    @Test fun liveBufferBoundsAreReadOncePerOperationAndPropagateFailure() {
        val bytes = ByteArray(16) { it.toByte() }
        var size = 16L
        var reads = 0
        val failure = IllegalStateException("size failure")
        var failed = false
        val view = CbitsBuffer(bytes, true, CbitsBufferSize {
            reads++
            if (failed) throw failure
            size
        }, 4)
        val interop = InteropLibrary.getUncached()
        assertEquals(1, reads, "constructor preflight reads the live size")
        assertEquals(12L, interop.getBufferSize(view)); assertEquals(2, reads)
        assertEquals(4.toByte(), interop.readBufferByte(view, 0)); assertEquals(3, reads)
        size = 7
        assertEquals(3L, interop.getBufferSize(view)); assertEquals(4, reads)
        interop.writeBufferByte(view, 2, 42); assertEquals(5, reads)
        val before = bytes.copyOf()
        assertThrows(InvalidBufferOffsetException::class.java) { interop.writeBufferByte(view, 3, 99) }
        assertEquals(6, reads); assertArrayEquals(before, bytes)
        assertThrows(InvalidBufferOffsetException::class.java) { interop.readBufferByte(view, -1) }
        assertEquals(6, reads, "negative offset retains the original short circuit")
        size = 2
        assertEquals(0L, interop.getBufferSize(view)); assertEquals(7, reads)
        failed = true
        assertSame(failure, assertThrows(IllegalStateException::class.java) { interop.getBufferSize(view) })
        assertEquals(8, reads)
        assertSame(failure, assertThrows(IllegalStateException::class.java) { interop.writeBufferByte(view, 0, 99) })
        assertEquals(9, reads); assertArrayEquals(before, bytes)
    }

    @Test fun nativePromotionSerializesAcquisitionAndPreservesFailureAndOwnerLifetime() {
        val bytes = ByteArray(8)
        val image = NativeReadOnlyImage(bytes, bytes)
        val pointer = NativeReadOnlyPointer(image, bytes)
        val failure = IllegalStateException("promotion failure")
        var acquisitions = 0
        lateinit var view: CbitsBuffer
        view = CbitsBuffer(bytes, false, nativeImage = Supplier {
            assertTrue(Thread.holdsLock(view), "acquisition keeps the buffer monitor")
            if (++acquisitions == 1) throw failure
            pointer
        })
        val interop = InteropLibrary.getUncached()
        val pool = Executors.newFixedThreadPool(4)
        try {
            assertFalse(interop.isPointer(view))
            assertSame(failure, assertThrows(IllegalStateException::class.java) { interop.toNative(view) })
            assertFalse(interop.isPointer(view)); assertFalse(Thread.holdsLock(view))
            val start = CountDownLatch(1)
            val calls = (0 until 16).map {
                pool.submit<Long> { start.await(); interop.toNative(view); interop.asPointer(view) }
            }
            start.countDown()
            calls.forEach { assertEquals(image.base, it.get()) }
            assertEquals(2, acquisitions, "one failed attempt and one published pointer")
            image.close()
            assertFalse(interop.isPointer(view))
            assertThrows(UnsupportedMessageException::class.java) { interop.asPointer(view) }
            interop.toNative(view)
            assertEquals(2, acquisitions, "a closed owner is not silently replaced")
        } finally { pool.shutdownNow(); image.close() }
    }

    @Test fun concurrentAliasesPublishOneLiveBufferView() {
        Context.newBuilder("thc").allowNativeAccess(true).build().use { context ->
            context.initialize("thc"); context.enter()
            val cbits = try { Language.currentState().cbits() } finally { context.leave() }
            val backing = ByteArray(96)
            val owner = cbits.buffer(ManagedAddress.fromByteArray(backing))
            val pool = Executors.newFixedThreadPool(8)
            try {
                val start = CountDownLatch(1)
                val calls = (0 until 64).map {
                    pool.submit<CbitsBuffer> {
                        start.await()
                        cbits.buffer(ManagedAddress.fromByteArray(backing).plus((it % 4).toLong()))
                    }
                }
                start.countDown()
                calls.forEach { assertSame(owner, it.get()) }
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun bufferReadsAndWritesAreTypedViewsOfTheSameBytes() {
        val bytes = ByteArray(32)
        val view = CbitsBuffer(bytes, true)
        val interop = InteropLibrary.getUncached()
        assertTrue(interop.hasBufferElements(view))
        assertFalse(interop.isPointer(view))
        assertEquals(32L, interop.getBufferSize(view))
        interop.writeBufferInt(view, ByteOrder.LITTLE_ENDIAN, 4, 0x89abcdef.toInt())
        assertEquals(0x89abcdef.toInt(), interop.readBufferInt(view, ByteOrder.LITTLE_ENDIAN, 4))
        assertEquals(0xefcdab89.toInt(), interop.readBufferInt(view, ByteOrder.BIG_ENDIAN, 4))
        assertEquals(0xef.toByte(), bytes[4])
        interop.writeBufferLong(view, ByteOrder.BIG_ENDIAN, 8, Long.MIN_VALUE + 17)
        assertEquals(Long.MIN_VALUE + 17, interop.readBufferLong(view, ByteOrder.BIG_ENDIAN, 8))
        interop.writeBufferFloat(view, ByteOrder.LITTLE_ENDIAN, 16, -0.0f)
        assertEquals(Int.MIN_VALUE, interop.readBufferFloat(view, ByteOrder.LITTLE_ENDIAN, 16).toRawBits())
        interop.writeBufferDouble(view, ByteOrder.BIG_ENDIAN, 24, -0.0)
        assertEquals(Long.MIN_VALUE, interop.readBufferDouble(view, ByteOrder.BIG_ENDIAN, 24).toRawBits())
        val copy = ByteArray(4)
        interop.readBuffer(view, 4, copy, 0, 4)
        assertArrayEquals(bytes.copyOfRange(4, 8), copy)
        val before = bytes.copyOf()
        for (offset in listOf(-1L, Long.MIN_VALUE, 29L, Long.MAX_VALUE))
            assertThrows(InvalidBufferOffsetException::class.java) { interop.writeBufferInt(view, ByteOrder.nativeOrder(), offset, 7) }
        assertThrows(UnsupportedMessageException::class.java) { interop.writeBufferByte(CbitsBuffer(bytes, false), 0, 1) }
        assertArrayEquals(before, bytes)
    }

    @Test fun nativePointerOffsetViewRetainsTheOriginalWritableAllocationAndBounds() {
        val bytes = ByteArray(24) { 0x5a }
        val view = CbitsBuffer(bytes, true, { bytes.size.toLong() }, 7)
        val interop = InteropLibrary.getUncached()
        assertEquals(17L, interop.getBufferSize(view))
        interop.writeBufferLong(view, ByteOrder.nativeOrder(), 0, 0x0102030405060708L)
        assertEquals(0x5a.toByte(), bytes[6])
        assertEquals(0x0102030405060708L,
            java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).getLong(7))
        assertThrows(InvalidBufferOffsetException::class.java) {
            interop.writeBufferLong(view, ByteOrder.nativeOrder(), 10, 1)
        }
        assertThrows(UnsupportedMessageException::class.java) {
            interop.writeBufferByte(CbitsBuffer(bytes, false, { bytes.size.toLong() }, 7), 0, 0)
        }
    }

    @Test fun liveAliasesShareOneContextOwnedViewAndLiteralsStayReadOnly() {
        Context.newBuilder("thc").allowNativeAccess(true).build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val cbits = Language.currentState().cbits()
                val bytes = ByteArray(96)
                val first = ManagedAddress.fromByteArray(bytes)
                val owner = cbits.buffer(first)
                assertSame(owner, cbits.buffer(ManagedAddress.fromByteArray(bytes).plus(4)))
                assertNotSame(owner, cbits.buffer(ManagedAddress.fromByteArray(bytes.copyOf())))
                assertFalse(InteropLibrary.getUncached().isBufferWritable(cbits.buffer(ManagedAddress.fromHex("00"))))
                // Original C at zero offset must see byte storage, not struct members.
                ManagedMd5.init(first)
                assertEquals(0x67.toByte(), bytes[3])
            } finally { context.leave() }
        }
    }

    @Test fun nativeRuntimePermissionFailsBeforeGuestMemoryChanges() {
        Context.newBuilder("thc").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val bytes = ByteArray(88) { 0xa5.toByte() }
                val before = bytes.copyOf()
                val error = assertThrows(RuntimeFault::class.java) { ManagedMd5.init(ManagedAddress.fromByteArray(bytes)) }
                assertTrue(error.message.orEmpty().contains("native access"))
                assertArrayEquals(before, bytes)
            } finally { context.leave() }
        }
    }
}
