// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.utilities.TriState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Byte interop over an original allocation, retaining exact allocation identity. */
@ExportLibrary(InteropLibrary.class)
public final class CbitsBuffer implements TruffleObject {
    private final boolean writable;
    private final CbitsBufferSize logicalSize;
    private final long baseOffset;
    private final Supplier<NativeReadOnlyPointer> nativeImage;
    private final LongSupplier nativeAddress;
    private final Object identity;
    private final ByteBuffer little;
    private final ByteBuffer big;
    private NativeReadOnlyPointer pointer;
    public CbitsBuffer(ByteBuffer bytes, boolean writable) { this(bytes, writable, () -> bytes.capacity()); }
    public CbitsBuffer(ByteBuffer bytes, boolean writable, CbitsBufferSize logicalSize) { this(bytes, writable, logicalSize, 0); }
    public CbitsBuffer(ByteBuffer bytes, boolean writable, CbitsBufferSize logicalSize, long baseOffset) { this(bytes, writable, logicalSize, baseOffset, null); }
    public CbitsBuffer(ByteBuffer bytes, boolean writable, CbitsBufferSize logicalSize, long baseOffset, Supplier<NativeReadOnlyPointer> nativeImage) {
        this(bytes, writable, logicalSize, baseOffset, nativeImage, null);
    }
    public CbitsBuffer(ByteBuffer bytes, boolean writable, CbitsBufferSize logicalSize, long baseOffset, Supplier<NativeReadOnlyPointer> nativeImage, LongSupplier nativeAddress) {
        this(bytes, writable, logicalSize, baseOffset, nativeImage, nativeAddress, bytes);
    }
    public CbitsBuffer(ByteBuffer bytes, boolean writable, CbitsBufferSize logicalSize, long baseOffset,
            Supplier<NativeReadOnlyPointer> nativeImage, LongSupplier nativeAddress, Object identity) {
        this.writable = writable; this.logicalSize = logicalSize; this.baseOffset = baseOffset;
        this.nativeImage = nativeImage; this.nativeAddress = nativeAddress; this.identity = identity;
        little = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        big = bytes.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (writable && nativeImage != null) throw new IllegalArgumentException("Mutable C buffers cannot use immutable native images");
        if (baseOffset < 0 || baseOffset > logicalSize.size()) throw new IllegalArgumentException("C buffer address exceeds its allocation");
    }
    public CbitsBuffer(byte[] bytes, boolean writable) { this(bytes, writable, () -> bytes.length); }
    public CbitsBuffer(byte[] bytes, boolean writable, CbitsBufferSize logicalSize) { this(bytes, writable, logicalSize, 0); }
    public CbitsBuffer(byte[] bytes, boolean writable, CbitsBufferSize logicalSize, long baseOffset) { this(bytes, writable, logicalSize, baseOffset, null); }
    public CbitsBuffer(byte[] bytes, boolean writable, CbitsBufferSize logicalSize, long baseOffset, Supplier<NativeReadOnlyPointer> nativeImage) {
        this(bytes, writable, logicalSize, baseOffset, nativeImage, bytes);
    }
    public CbitsBuffer(byte[] bytes, boolean writable, CbitsBufferSize logicalSize, long baseOffset, Supplier<NativeReadOnlyPointer> nativeImage, Object identity) {
        this(ByteBuffer.wrap(bytes), writable, logicalSize, baseOffset, nativeImage, null, identity);
    }
    @ExportMessage public TriState isIdenticalOrUndefined(Object other) {
        return other instanceof CbitsBuffer buffer ? TriState.valueOf(identity == buffer.identity && baseOffset == buffer.baseOffset) : TriState.UNDEFINED;
    }
    @ExportMessage public int identityHashCode() { return 31 * System.identityHashCode(identity) + Long.hashCode(baseOffset); }
    @ExportMessage public synchronized boolean isPointer() { return nativeAddress != null || pointer != null && pointer.isPointer(); }
    @ExportMessage @TruffleBoundary public synchronized void toNative() { if (nativeImage != null && pointer == null) pointer = nativeImage.get(); }
    @ExportMessage @TruffleBoundary public synchronized long asPointer() throws UnsupportedMessageException {
        if (!isPointer()) throw UnsupportedMessageException.create();
        return (nativeAddress != null ? nativeAddress.getAsLong() : pointer.asPointer()) + baseOffset;
    }
    @ExportMessage public boolean hasBufferElements() { return true; }
    @ExportMessage public boolean isBufferWritable() { return writable; }
    @ExportMessage public long getBufferSize() { return Math.max(0, logicalSize.size() - baseOffset); }
    private int index(long offset, int width) throws InvalidBufferOffsetException {
        if (offset < 0 || offset > logicalSize.size() - baseOffset - width) throw InvalidBufferOffsetException.create(offset, width);
        return Math.toIntExact(baseOffset + offset);
    }
    private void requireWritable() throws UnsupportedMessageException { if (!writable) throw UnsupportedMessageException.create(); }
    private ByteBuffer view(ByteOrder order) { return order == ByteOrder.LITTLE_ENDIAN ? little : big; }
    @ExportMessage @TruffleBoundary public void readBuffer(long offset, byte[] destination, int destinationOffset, int length) throws InvalidBufferOffsetException {
        little.get(index(offset, length), destination, destinationOffset, length);
    }
    @ExportMessage public byte readBufferByte(long offset) throws InvalidBufferOffsetException { return little.get(index(offset, 1)); }
    @ExportMessage public void writeBufferByte(long offset, byte value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); little.put(index(offset, 1), value); }
    @ExportMessage public short readBufferShort(ByteOrder order, long offset) throws InvalidBufferOffsetException { return view(order).getShort(index(offset, 2)); }
    @ExportMessage public void writeBufferShort(ByteOrder order, long offset, short value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); view(order).putShort(index(offset, 2), value); }
    @ExportMessage public int readBufferInt(ByteOrder order, long offset) throws InvalidBufferOffsetException { return view(order).getInt(index(offset, 4)); }
    @ExportMessage public void writeBufferInt(ByteOrder order, long offset, int value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); view(order).putInt(index(offset, 4), value); }
    @ExportMessage public long readBufferLong(ByteOrder order, long offset) throws InvalidBufferOffsetException { return view(order).getLong(index(offset, 8)); }
    @ExportMessage public void writeBufferLong(ByteOrder order, long offset, long value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); view(order).putLong(index(offset, 8), value); }
    @ExportMessage public float readBufferFloat(ByteOrder order, long offset) throws InvalidBufferOffsetException { return view(order).getFloat(index(offset, 4)); }
    @ExportMessage public void writeBufferFloat(ByteOrder order, long offset, float value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); view(order).putFloat(index(offset, 4), value); }
    @ExportMessage public double readBufferDouble(ByteOrder order, long offset) throws InvalidBufferOffsetException { return view(order).getDouble(index(offset, 8)); }
    @ExportMessage public void writeBufferDouble(ByteOrder order, long offset, double value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); view(order).putDouble(index(offset, 8), value); }
}
