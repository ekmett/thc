package thc.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidBufferOffsetException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Byte-addressed interop over the guest's original allocation, never a native address. */
@ExportLibrary(InteropLibrary.class)
public final class CbitsBuffer implements TruffleObject {
    private final ByteBuffer little;
    private final ByteBuffer big;
    private final boolean writable;

    public CbitsBuffer(byte[] bytes, boolean writable) {
        this.little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        this.big = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        this.writable = writable;
    }
    @ExportMessage boolean hasBufferElements() { return true; }
    @ExportMessage boolean isBufferWritable() { return writable; }
    @ExportMessage long getBufferSize() { return little.capacity(); }
    private int index(long offset, int width) throws InvalidBufferOffsetException {
        if (offset < 0 || offset > (long) little.capacity() - width)
            throw InvalidBufferOffsetException.create(offset, width);
        return (int) offset;
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
