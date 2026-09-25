// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidBufferOffsetException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Byte interop over the original allocation; immutable views may acquire an owned native image. */
@ExportLibrary(InteropLibrary.class)
public final class CbitsBuffer implements TruffleObject {
    private final ByteBuffer little;
    private final ByteBuffer big;
    private final boolean writable;
    private final LongSupplier logicalSize;
    private final long baseOffset;
    private final Supplier<NativeReadOnlyPointer> nativeImage;
    private NativeReadOnlyPointer pointer;

    public CbitsBuffer(byte[] bytes, boolean writable) {
        this(bytes, writable, () -> bytes.length);
    }
    public CbitsBuffer(byte[] bytes, boolean writable, LongSupplier logicalSize) {
        this(bytes, writable, logicalSize, 0);
    }
    public CbitsBuffer(byte[] bytes, boolean writable, LongSupplier logicalSize, long baseOffset) {
        this(bytes, writable, logicalSize, baseOffset, null);
    }
    public CbitsBuffer(byte[] bytes, boolean writable, LongSupplier logicalSize, long baseOffset,
                       Supplier<NativeReadOnlyPointer> nativeImage) {
        if (writable && nativeImage != null)
            throw new IllegalArgumentException("Mutable C buffers cannot use immutable native images");
        if (baseOffset < 0 || baseOffset > logicalSize.getAsLong())
            throw new IllegalArgumentException("C buffer address exceeds its allocation");
        this.little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        this.big = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        this.writable = writable;
        this.logicalSize = logicalSize;
        this.baseOffset = baseOffset;
        this.nativeImage = nativeImage;
    }
    @ExportMessage synchronized boolean isPointer() { return pointer != null && pointer.isPointer(); }
    @ExportMessage synchronized void toNative() {
        if (nativeImage != null && pointer == null) pointer = nativeImage.get();
    }
    @ExportMessage synchronized long asPointer() throws UnsupportedMessageException {
        if (!isPointer()) throw UnsupportedMessageException.create();
        return pointer.asPointer() + baseOffset;
    }
    @ExportMessage boolean hasBufferElements() { return true; }
    @ExportMessage boolean isBufferWritable() { return writable; }
    @ExportMessage long getBufferSize() { return logicalSize.getAsLong() - baseOffset; }
    private int index(long offset, int width) throws InvalidBufferOffsetException {
        if (offset < 0 || offset > logicalSize.getAsLong() - baseOffset - width)
            throw InvalidBufferOffsetException.create(offset, width);
        return Math.toIntExact(baseOffset + offset);
    }
    private void requireWritable() throws UnsupportedMessageException {
        if (!writable) throw UnsupportedMessageException.create();
    }
    private ByteBuffer view(ByteOrder order) { return order == ByteOrder.LITTLE_ENDIAN ? little : big; }
    @ExportMessage void readBuffer(long offset, byte[] destination, int destinationOffset, int length)
            throws InvalidBufferOffsetException {
        int start = index(offset, length);
        little.get(start, destination, destinationOffset, length);
    }
    @ExportMessage byte readBufferByte(long offset) throws InvalidBufferOffsetException { return little.get(index(offset, 1)); }
    @ExportMessage short readBufferShort(ByteOrder order, long offset) throws InvalidBufferOffsetException { return view(order).getShort(index(offset, 2)); }
    @ExportMessage int readBufferInt(ByteOrder order, long offset) throws InvalidBufferOffsetException { return view(order).getInt(index(offset, 4)); }
    @ExportMessage long readBufferLong(ByteOrder order, long offset) throws InvalidBufferOffsetException { return view(order).getLong(index(offset, 8)); }
    @ExportMessage float readBufferFloat(ByteOrder order, long offset) throws InvalidBufferOffsetException { return view(order).getFloat(index(offset, 4)); }
    @ExportMessage double readBufferDouble(ByteOrder order, long offset) throws InvalidBufferOffsetException { return view(order).getDouble(index(offset, 8)); }
    @ExportMessage void writeBufferByte(long offset, byte value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); little.put(index(offset, 1), value); }
    @ExportMessage void writeBufferShort(ByteOrder order, long offset, short value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); view(order).putShort(index(offset, 2), value); }
    @ExportMessage void writeBufferInt(ByteOrder order, long offset, int value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); view(order).putInt(index(offset, 4), value); }
    @ExportMessage void writeBufferLong(ByteOrder order, long offset, long value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); view(order).putLong(index(offset, 8), value); }
    @ExportMessage void writeBufferFloat(ByteOrder order, long offset, float value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); view(order).putFloat(index(offset, 4), value); }
    @ExportMessage void writeBufferDouble(ByteOrder order, long offset, double value) throws InvalidBufferOffsetException, UnsupportedMessageException { requireWritable(); view(order).putDouble(index(offset, 8), value); }
}
