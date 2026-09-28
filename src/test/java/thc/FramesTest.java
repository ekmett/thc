// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import thc.runtime.FrameAccess;
import thc.runtime.RuntimeFault;
import static org.junit.jupiter.api.Assertions.*;

class FramesTest {
    private FrameDescriptor newDescriptor() {
        var builder = FrameDescriptor.newBuilder(); builder.addSlot(FrameSlotKind.Illegal, "local", null); return builder.build();
    }
    private VirtualFrame frame(FrameDescriptor descriptor) { return Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor); }
    @Test void objectStoragePreservesExistingBoxesAfterAnotherActivationWidensDescriptor() {
        for (Object value : List.of(3_000_000_000L, Long.MIN_VALUE, 1.25f, -0.0f,
                Float.intBitsToFloat(0x7fa12345), 2.5, -0.0, Double.longBitsToDouble(0x7ff0123456789abcL), true)) {
            var descriptor = newDescriptor(); var outer = frame(descriptor);
            FrameAccess.write(outer, 0, value);
            assertNotEquals(FrameSlotKind.Object, descriptor.getSlotKind(0));
            var inner = frame(descriptor); var marker = new Object();
            FrameAccess.write(inner, 0, marker);
            assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0));
            // Older activations keep primitive tags; the generic store already owns a box.
            FrameAccess.write(outer, 0, value);
            assertTrue(outer.isObject(0));
            assertSame(value, FrameAccess.read(outer, 0)); assertSame(marker, FrameAccess.read(inner, 0));
            var later = frame(descriptor);
            FrameAccess.write(later, 0, value); FrameAccess.write(inner, 0, value);
            assertSame(value, FrameAccess.read(later, 0)); assertSame(value, FrameAccess.read(inner, 0));
        }
    }
    @Test void livePrimitiveFramesSurviveDescriptorWideningInAnotherActivation() {
        for (Object primitive : List.of(Long.MIN_VALUE, 3_000_000_000L, true, false)) {
            var descriptor = newDescriptor(); var outer = frame(descriptor);
            FrameAccess.write(outer, 0, primitive);
            assertEquals(primitive instanceof Long ? FrameSlotKind.Long : FrameSlotKind.Boolean, descriptor.getSlotKind(0));
            var inner = frame(descriptor); var marker = new Object(); FrameAccess.write(inner, 0, marker);
            assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0)); assertSame(marker, FrameAccess.read(inner, 0));
            assertEquals(primitive, FrameAccess.read(outer, 0), "A suspended caller retains its own primitive tag after a callee widens the descriptor");
            assertTrue(primitive instanceof Long ? outer.isLong(0) : outer.isBoolean(0));
            FrameAccess.write(outer, 0, primitive); assertTrue(outer.isObject(0)); assertEquals(primitive, FrameAccess.read(outer, 0));
        }
    }
    @Test void typedLongWritesRespectDescriptorWideningAcrossActivations() throws Exception {
        var descriptor = newDescriptor(); var outer = frame(descriptor);
        for (long value : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_000L}) {
            FrameAccess.writeLong(outer, 0, value); assertEquals(FrameSlotKind.Long, descriptor.getSlotKind(0));
            assertTrue(outer.isLong(0)); assertEquals(value, outer.getLong(0));
        }
        var inner = frame(descriptor); var marker = new Object(); FrameAccess.write(inner, 0, marker);
        assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0));
        assertTrue(outer.isLong(0), "Widening the descriptor must not erase a suspended primitive value");
        assertEquals(3_000_000_000L, FrameAccess.read(outer, 0));
        FrameAccess.writeLong(outer, 0, Long.MIN_VALUE);
        assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0));
        assertTrue(outer.isObject(0), "A later typed write must obey the widened descriptor");
        assertEquals(Long.MIN_VALUE, FrameAccess.read(outer, 0)); assertSame(marker, FrameAccess.read(inner, 0));
        FrameAccess.writeLong(inner, 0, Long.MAX_VALUE); assertTrue(inner.isObject(0));
        assertEquals(Long.MAX_VALUE, FrameAccess.read(inner, 0)); assertEquals(Long.MIN_VALUE, FrameAccess.read(outer, 0));
    }
    @Test void typedLongWritesWidenBooleanSlotsWithoutChangingSuspendedFrames() {
        var descriptor = newDescriptor(); var outer = frame(descriptor);
        FrameAccess.write(outer, 0, true); assertEquals(FrameSlotKind.Boolean, descriptor.getSlotKind(0));
        var inner = frame(descriptor); FrameAccess.writeLong(inner, 0, Long.MIN_VALUE);
        assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0)); assertTrue(inner.isObject(0));
        assertEquals(Long.MIN_VALUE, FrameAccess.read(inner, 0)); assertTrue(outer.isBoolean(0)); assertEquals(true, FrameAccess.read(outer, 0));
        FrameAccess.writeLong(outer, 0, Long.MAX_VALUE); assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0));
        assertTrue(outer.isObject(0)); assertEquals(Long.MAX_VALUE, FrameAccess.read(outer, 0));
    }
    @Test void supportedTagsAndUnwrittenNullRetainTheirValues() {
        var descriptor = newDescriptor(); var local = frame(descriptor);
        assertEquals(FrameSlotKind.Illegal, descriptor.getSlotKind(0)); assertTrue(local.isObject(0), "Truffle's default unwritten value uses the Object tag");
        assertNull(FrameAccess.read(local, 0)); FrameAccess.write(local, 0, Long.MAX_VALUE); assertEquals(Long.MAX_VALUE, FrameAccess.read(local, 0));
        FrameAccess.write(local, 0, null); assertNull(FrameAccess.read(local, 0));
        var marker = new Object(); FrameAccess.write(local, 0, marker); assertSame(marker, FrameAccess.read(local, 0));
        var booleans = frame(newDescriptor());
        for (boolean value : List.of(true, false)) { FrameAccess.write(booleans, 0, value); assertTrue(booleans.isBoolean(0)); assertEquals(value, FrameAccess.read(booleans, 0)); }
    }
    @Test void unrelatedTruffleNumericTagsAreRejectedInsteadOfSilentlyBoxed() {
        var narrow = frame(newDescriptor()); narrow.setInt(0, 7);
        assertEquals(7, FrameAccess.read(narrow, 0));
        var local = frame(newDescriptor()); local.setByte(0, (byte) 7);
        var error = assertThrows(RuntimeFault.class, () -> FrameAccess.read(local, 0));
        assertEquals("Unsupported runtime frame slot tag", error.getMessage());
        var floating = frame(newDescriptor()); floating.setFloat(0, 7.0f); assertEquals(7.0f, FrameAccess.read(floating, 0));
        floating.setDouble(0, 7.0); assertEquals(7.0, FrameAccess.read(floating, 0));
    }
}
