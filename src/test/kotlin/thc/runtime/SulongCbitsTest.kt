package thc.runtime

import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.InvalidBufferOffsetException
import com.oracle.truffle.api.interop.UnsupportedMessageException
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteOrder
import thc.Language

class SulongCbitsTest {
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
