// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidBufferOffsetException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

public class SulongCbitsTest {
    @Test public void liveBufferBoundsAreReadOncePerOperationAndPropagateFailure() throws Exception {
        var bytes = new byte[16]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        var size = new long[]{16}; var reads = new int[]{0}; var failed = new boolean[]{false}; var failure = new IllegalStateException("size failure");
        var view = new CbitsBuffer(bytes, true, () -> { reads[0]++; if (failed[0]) throw failure; return size[0]; }, 4);
        var interop = InteropLibrary.getUncached();
        assertEquals(1, reads[0], "constructor preflight reads the live size");
        assertEquals(12L, interop.getBufferSize(view)); assertEquals(2, reads[0]);
        assertEquals((byte) 4, interop.readBufferByte(view, 0)); assertEquals(3, reads[0]);
        size[0] = 7; assertEquals(3L, interop.getBufferSize(view)); assertEquals(4, reads[0]);
        interop.writeBufferByte(view, 2, (byte) 42); assertEquals(5, reads[0]); var before = bytes.clone();
        assertThrows(InvalidBufferOffsetException.class, () -> interop.writeBufferByte(view, 3, (byte) 99)); assertEquals(6, reads[0]); assertArrayEquals(before, bytes);
        assertThrows(InvalidBufferOffsetException.class, () -> interop.readBufferByte(view, -1)); assertEquals(6, reads[0], "negative offset retains the original short circuit");
        size[0] = 2; assertEquals(0L, interop.getBufferSize(view)); assertEquals(7, reads[0]);
        failed[0] = true; assertSame(failure, assertThrows(IllegalStateException.class, () -> interop.getBufferSize(view))); assertEquals(8, reads[0]);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> interop.writeBufferByte(view, 0, (byte) 99))); assertEquals(9, reads[0]); assertArrayEquals(before, bytes);
    }
    @Test public void nativePromotionSerializesAcquisitionAndPreservesFailureAndOwnerLifetime() throws Exception {
        var bytes = new byte[8]; var image = new NativeReadOnlyImage(bytes, bytes); var pointer = new NativeReadOnlyPointer(image, bytes);
        var failure = new IllegalStateException("promotion failure"); var acquisitions = new int[]{0}; var holder = new CbitsBuffer[1];
        holder[0] = new CbitsBuffer(bytes, false, () -> bytes.length, 0, () -> {
            assertTrue(Thread.holdsLock(holder[0]), "acquisition keeps the buffer monitor");
            if (++acquisitions[0] == 1) throw failure; return pointer;
        });
        var view = holder[0]; var interop = InteropLibrary.getUncached(); var pool = Executors.newFixedThreadPool(4);
        try {
            assertFalse(interop.isPointer(view)); assertSame(failure, assertThrows(IllegalStateException.class, () -> interop.toNative(view)));
            assertFalse(interop.isPointer(view)); assertFalse(Thread.holdsLock(view));
            var start = new CountDownLatch(1); var calls = new ArrayList<Future<Long>>();
            for (int i = 0; i < 16; i++) calls.add(pool.submit(() -> { start.await(); interop.toNative(view); return interop.asPointer(view); }));
            start.countDown(); for (var call : calls) assertEquals(image.getBase(), call.get());
            assertEquals(2, acquisitions[0], "one failed attempt and one published pointer"); image.close(); assertFalse(interop.isPointer(view));
            assertThrows(UnsupportedMessageException.class, () -> interop.asPointer(view)); interop.toNative(view);
            assertEquals(2, acquisitions[0], "a closed owner is not silently replaced");
        } finally { pool.shutdownNow(); image.close(); }
    }
    @Test public void concurrentAliasesPublishOneLiveBufferView() throws Exception {
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter(); SulongCbits cbits;
            try { cbits = Language.currentState().cbits(); } finally { context.leave(); }
            var backing = new byte[96]; var owner = cbits.buffer(ManagedAddress.fromByteArray(backing), true); var pool = Executors.newFixedThreadPool(8);
            try {
                var start = new CountDownLatch(1); var calls = new ArrayList<Future<CbitsBuffer>>();
                for (int i = 0; i < 64; i++) { int index = i; calls.add(pool.submit(() -> { start.await(); return cbits.buffer(ManagedAddress.fromByteArray(backing).plus(index % 4), true); })); }
                start.countDown(); for (var call : calls) assertSame(owner, call.get());
            } finally { pool.shutdownNow(); }
        }
    }
    @Test public void bufferReadsAndWritesAreTypedViewsOfTheSameBytes() throws Exception {
        var bytes = new byte[32]; var view = new CbitsBuffer(bytes, true); var interop = InteropLibrary.getUncached();
        assertTrue(interop.hasBufferElements(view)); assertFalse(interop.isPointer(view)); assertEquals(32L, interop.getBufferSize(view));
        interop.writeBufferInt(view, ByteOrder.LITTLE_ENDIAN, 4, 0x89abcdef);
        assertEquals(0x89abcdef, interop.readBufferInt(view, ByteOrder.LITTLE_ENDIAN, 4)); assertEquals(0xefcdab89, interop.readBufferInt(view, ByteOrder.BIG_ENDIAN, 4)); assertEquals((byte) 0xef, bytes[4]);
        interop.writeBufferLong(view, ByteOrder.BIG_ENDIAN, 8, Long.MIN_VALUE + 17); assertEquals(Long.MIN_VALUE + 17, interop.readBufferLong(view, ByteOrder.BIG_ENDIAN, 8));
        interop.writeBufferFloat(view, ByteOrder.LITTLE_ENDIAN, 16, -0.0f); assertEquals(Integer.MIN_VALUE, Float.floatToRawIntBits(interop.readBufferFloat(view, ByteOrder.LITTLE_ENDIAN, 16)));
        interop.writeBufferDouble(view, ByteOrder.BIG_ENDIAN, 24, -0.0); assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(interop.readBufferDouble(view, ByteOrder.BIG_ENDIAN, 24)));
        var copy = new byte[4]; interop.readBuffer(view, 4, copy, 0, 4); assertArrayEquals(Arrays.copyOfRange(bytes, 4, 8), copy); var before = bytes.clone();
        for (long offset : new long[]{-1L, Long.MIN_VALUE, 29L, Long.MAX_VALUE}) assertThrows(InvalidBufferOffsetException.class, () -> interop.writeBufferInt(view, ByteOrder.nativeOrder(), offset, 7));
        assertThrows(UnsupportedMessageException.class, () -> interop.writeBufferByte(new CbitsBuffer(bytes, false), 0, (byte) 1)); assertArrayEquals(before, bytes);
    }
    @Test public void nativePointerOffsetViewRetainsTheOriginalWritableAllocationAndBounds() throws Exception {
        var bytes = new byte[24]; Arrays.fill(bytes, (byte) 0x5a); var view = new CbitsBuffer(bytes, true, () -> bytes.length, 7); var interop = InteropLibrary.getUncached();
        assertEquals(17L, interop.getBufferSize(view)); interop.writeBufferLong(view, ByteOrder.nativeOrder(), 0, 0x0102030405060708L); assertEquals((byte) 0x5a, bytes[6]);
        assertEquals(0x0102030405060708L, ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).getLong(7));
        assertThrows(InvalidBufferOffsetException.class, () -> interop.writeBufferLong(view, ByteOrder.nativeOrder(), 10, 1));
        assertThrows(UnsupportedMessageException.class, () -> interop.writeBufferByte(new CbitsBuffer(bytes, false, () -> bytes.length, 7), 0, (byte) 0));
    }
    @Test public void liveAliasesShareOneContextOwnedViewAndLiteralsStayReadOnly() throws Exception {
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var cbits = Language.currentState().cbits(); var bytes = new byte[96]; var first = ManagedAddress.fromByteArray(bytes); var owner = cbits.buffer(first, true);
                assertSame(owner, cbits.buffer(ManagedAddress.fromByteArray(bytes).plus(4), true)); assertNotSame(owner, cbits.buffer(ManagedAddress.fromByteArray(bytes.clone()), true));
                assertFalse(InteropLibrary.getUncached().isBufferWritable(cbits.buffer(ManagedAddress.fromHex("00"), true)));
            } finally { context.leave(); }
        }
    }
}
