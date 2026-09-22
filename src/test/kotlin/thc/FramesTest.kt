package thc

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.FrameAccess
import thc.runtime.RuntimeFault

class FramesTest {
    private fun newDescriptor(): FrameDescriptor = FrameDescriptor.newBuilder().also {
        it.addSlot(FrameSlotKind.Illegal, "local", null)
    }.build()

    private fun frame(descriptor: FrameDescriptor): VirtualFrame =
        Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)

    @Test fun livePrimitiveFramesSurviveDescriptorWideningInAnotherActivation() {
        for (primitive in listOf<Any>(Long.MIN_VALUE, 3_000_000_000L, true, false)) {
            val descriptor = newDescriptor()
            val outer = frame(descriptor)
            FrameAccess.write(outer, 0, primitive)
            val primitiveKind = if (primitive is Long) FrameSlotKind.Long else FrameSlotKind.Boolean
            assertEquals(primitiveKind, descriptor.getSlotKind(0))

            val inner = frame(descriptor)
            val marker = Any()
            FrameAccess.write(inner, 0, marker)
            assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0))
            assertSame(marker, FrameAccess.read(inner, 0))
            assertEquals(primitive, FrameAccess.read(outer, 0),
                "A suspended caller retains its own primitive tag after a callee widens the descriptor")
            assertTrue(if (primitive is Long) outer.isLong(0) else outer.isBoolean(0))

            // A later write obeys monotonic descriptor widening, without losing
            // values previously held in the older frame's primitive storage.
            FrameAccess.write(outer, 0, primitive)
            assertTrue(outer.isObject(0))
            assertEquals(primitive, FrameAccess.read(outer, 0))
        }
    }

    @Test fun supportedTagsAndUnwrittenNullRetainTheirValues() {
        val descriptor = newDescriptor()
        val local = frame(descriptor)
        assertEquals(FrameSlotKind.Illegal, descriptor.getSlotKind(0))
        assertTrue(local.isObject(0), "Truffle's default unwritten value uses the Object tag")
        assertNull(FrameAccess.read(local, 0))
        FrameAccess.write(local, 0, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, FrameAccess.read(local, 0))
        FrameAccess.write(local, 0, null)
        assertNull(FrameAccess.read(local, 0))
        val marker = Any()
        FrameAccess.write(local, 0, marker)
        assertSame(marker, FrameAccess.read(local, 0))

        val booleans = frame(newDescriptor())
        for (value in listOf(true, false)) {
            FrameAccess.write(booleans, 0, value)
            assertTrue(booleans.isBoolean(0))
            assertEquals(value, FrameAccess.read(booleans, 0))
        }
    }

    @Test fun unrelatedTruffleNumericTagsAreRejectedInsteadOfSilentlyBoxed() {
        val writers: List<(VirtualFrame) -> Unit> = listOf(
            { it.setInt(0, 7) }, { it.setByte(0, 7) },
            { it.setFloat(0, 7.0f) }, { it.setDouble(0, 7.0) })
        for (write in writers) {
            val local = frame(newDescriptor())
            write(local)
            val error = assertThrows(RuntimeFault::class.java) { FrameAccess.read(local, 0) }
            assertEquals("Unsupported runtime frame slot tag", error.message)
        }
    }
}
