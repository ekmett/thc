// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import static thc.runtime.RuntimeServiceStatus.fault;

/** One owner for heap/native bytes and every address alias, including managed pointer cells. */
public final class ManagedAllocation {
    private final byte[] bytes;
    private final MemorySegment segment;
    private final boolean writable, staticImage;
    private final int pointerBytes;
    private Map<Integer, ManagedAddress> pointers;
    private boolean exposedToNative, exposedAsRawBytes;
    private volatile boolean pointerCapable;
    private volatile int logicalSize;
    private static final Object COPY_TIE_LOCK = new Object();
    private static final VarHandle BYTE_ACCESS = ValueLayout.JAVA_BYTE.varHandle();
    static { MemorySegment.ofArray(new byte[0]); }

    private ManagedAllocation(byte[] bytes, MemorySegment segment, boolean writable, int pointerBytes, boolean staticImage) {
        if (pointerBytes != 4 && pointerBytes != 8) throw fault("Unsupported target pointer width");
        this.bytes = bytes; this.segment = segment; this.writable = writable;
        this.pointerBytes = pointerBytes; this.staticImage = staticImage;
        logicalSize = (int) segment.byteSize();
    }
    public boolean isPinned() { return segment.isNative(); }
    public long getSize() { return logicalSize; }
    public int getAddressWidth() { return pointerBytes; }
    public boolean isWritable() { return writable; }
    public boolean isStaticImage() { return staticImage; }
    public boolean ownsStorage(byte[] candidate) { return bytes == candidate; }
    public Object storageKey() { return bytes == null ? this : bytes; }
    public MemorySegment nativeSegment() { return isPinned() ? segment : null; }

    public synchronized MemorySegment exposeSegment() {
        if (pointerCapable) throw fault("Pointer-bearing pinned array cannot be passed to native bitcode");
        exposedToNative = true;
        return writable ? segment : segment.asReadOnly();
    }
    public synchronized byte[] rawBytesIfPointerFree() {
        if (pointerCapable) throw fault("Pointer-bearing pinned array cannot be accessed as raw bytes");
        if (bytes == null) throw fault("Native pinned storage has no JVM byte-array alias");
        if (!writable) return bytes.clone();
        exposedAsRawBytes = true; return bytes;
    }
    public synchronized byte[] exposeToNative() {
        if (pointerCapable) throw fault("Pointer-bearing pinned array cannot be passed to native bitcode");
        if (bytes == null) throw fault("Native pinned storage has no JVM byte-array alias");
        if (!writable) return bytes.clone();
        exposedToNative = true; return bytes;
    }
    public synchronized byte[] wholeBytesForPrimitive() {
        if (bytes == null) throw fault("Native pinned storage has no JVM byte-array alias");
        if (pointerCapable || logicalSize != bytes.length)
            throw fault("Raw byte-array primitive cannot access a pointer-bearing or shrunk allocation");
        if (!writable) return bytes.clone();
        exposedAsRawBytes = true; return bytes;
    }
    private Map<Integer, ManagedAddress> cells() {
        if (pointers == null) pointers = new LinkedHashMap<>();
        return pointers;
    }
    private int range(long offset, long count) {
        if (offset < 0 || count < 0 || offset > getSize() || count > getSize() - offset)
            throw fault("Managed allocation range outside its backing storage");
        return (int) offset;
    }
    private void mutable() {
        if (!writable) throw fault("Cannot write through an immutable managed allocation");
    }
    private boolean intersectsPointer(int offset, int count) {
        return count > 0 && pointers != null && intersectsPointerCells(offset, count);
    }
    @TruffleBoundary private boolean intersectsPointerCells(int offset, int count) {
        if (pointers != null) for (int start : pointers.keySet())
            if (start < (long) offset + count && (long) start + pointerBytes > offset) return true;
        return false;
    }
    private void requireWholePointerOverlaps(int offset, int count) {
        if (pointers != null) for (int start : pointers.keySet()) {
            long end = (long) start + pointerBytes, writeEnd = (long) offset + count;
            if (start < writeEnd && end > offset && (start < offset || end > writeEnd))
                throw fault("Partial overwrite of a managed pointer cell");
        }
    }
    private void invalidate(int offset, int count) {
        if (count != 0 && pointers != null) invalidatePointerCells(offset, count);
    }
    @TruffleBoundary private void invalidatePointerCells(int offset, int count) {
        requireWholePointerOverlaps(offset, count);
        if (pointers != null) {
            var iterator = pointers.keySet().iterator();
            while (iterator.hasNext()) {
                int start = iterator.next();
                if (start < (long) offset + count && (long) start + pointerBytes > offset) iterator.remove();
            }
            if (pointers.isEmpty()) pointers = null;
        }
    }
    public long readByte(long offset) { return readByteInt(offset); }
    public void writeByte(long offset, long value) { writeByteInt(offset, (int) value); }
    public synchronized int readByteInt(long offset) {
        int start = range(offset, 1);
        if (pointerCapable && intersectsPointer(start, 1)) throw fault("Cannot expose managed pointer bits as a byte");
        return Byte.toUnsignedInt((byte) BYTE_ACCESS.get(segment, (long) start));
    }
    public synchronized void writeByteInt(long offset, int value) {
        mutable(); int start = range(offset, 1);
        if (pointerCapable) invalidate(start, 1);
        BYTE_ACCESS.set(segment, (long) start, (byte) value);
    }
    public synchronized void writeNativeIntByteOffset(long offset, int width, int value, boolean little) {
        mutable();
        if (width != 2 && width != 4) throw fault("Unsupported managed scalar width");
        int start = range(offset, width);
        if (pointerCapable) invalidate(start, width);
        for (int index = 0; index < width; index++) {
            int shift = (little ? index : width - 1 - index) * 8;
            BYTE_ACCESS.set(segment, (long) start + index, (byte) (value >>> shift));
        }
    }
    public synchronized void writeNativeScalarByteOffset(long offset, int width, long value, boolean little) {
        mutable();
        if (width != 2 && width != 4 && width != 8) throw fault("Unsupported managed scalar width");
        int start = range(offset, width);
        if (pointerCapable) invalidate(start, width);
        for (int index = 0; index < width; index++) {
            int shift = (little ? index : width - 1 - index) * 8;
            BYTE_ACCESS.set(segment, (long) start + index, (byte) (value >>> shift));
        }
    }
    @TruffleBoundary public synchronized void writeAddressByteOffset(long offset, ManagedAddress value) {
        requireAddressCell(offset); int start = range(offset, pointerBytes);
        invalidate(start, pointerBytes); pointerCapable = true;
        segment.asSlice(start, pointerBytes).fill((byte) 0); cells().put(start, value);
    }
    public synchronized void requireAddressCell(long offset) {
        mutable();
        if (exposedToNative || exposedAsRawBytes) throw fault("Cannot store a managed pointer in a raw-exposed array");
        range(offset, pointerBytes);
    }
    public synchronized void requireByteRegion(long offset, long count, boolean writable) {
        int start = range(offset, count);
        if (writable) mutable();
        if (intersectsPointer(start, (int) count)) throw fault("Native byte transport overlaps a managed pointer cell");
    }
    @TruffleBoundary public synchronized ManagedAddress readAddressByteOffset(long offset) {
        int start = range(offset, pointerBytes);
        var value = pointers == null ? null : pointers.get(start);
        if (value == null) throw fault("No managed pointer cell at this address");
        return value;
    }
    /** Package-only synchronous accesses: the caller holds this monitor through the final load/store. */
    MemorySegment elementSegment(long index, int width, boolean writable) {
        if (width <= 0 || index < 0 || index > Long.MAX_VALUE / width)
            throw fault("Managed allocation element outside its backing storage");
        int start = range(index * width, width);
        if (writable) { mutable(); if (pointerCapable) invalidate(start, width); }
        else if (intersectsPointer(start, width)) throw fault("Scalar read overlaps a managed pointer cell");
        return segment;
    }
    MemorySegment byteRangeSegment(long offset, int width, boolean writable) {
        if (width != 2 && width != 4 && width != 8) throw fault("Unsupported scalar byte-range width");
        int start = range(offset, width);
        if (writable) { mutable(); if (pointerCapable) invalidate(start, width); }
        else if (intersectsPointer(start, width)) throw fault("Scalar read overlaps a managed pointer cell");
        return segment;
    }
    MemorySegment vectorSegment(long index, boolean scalarOffset, int scalarWidth, boolean writable, int vectorBytes) {
        int stride = scalarOffset ? scalarWidth : vectorBytes;
        if (index < 0 || index > Long.MAX_VALUE / stride) throw fault("Vector index outside managed allocation");
        int start = range(index * stride, vectorBytes);
        if (writable) { mutable(); if (pointerCapable) invalidate(start, vectorBytes); }
        else if (intersectsPointer(start, vectorBytes)) throw fault("Vector read overlaps a managed pointer cell");
        return segment;
    }
    /** Public for the remaining Kotlin inline address caller; never use outside its owner lock. */
    public MemorySegment atomicSegment(long offset, int width, boolean writable) {
        if (!Thread.holdsLock(this)) throw new IllegalMonitorStateException("Managed atomic access requires its owner lock");
        requireByteRegion(offset, width, writable);
        if (offset % width != 0) throw fault("Misaligned atomic Addr#");
        return segment;
    }

    /** Owner-before-backing locking preserves atomicity through raw array aliases. */
    public synchronized int atomicNarrowInt(long index, int operand, int replacement, AtomicIntArrayOp operation) {
        mutable(); int width = operation.getWidth();
        if (width < 1 || width > 4 || operation.getOperands() != 2) throw fault("Expected narrow array CAS");
        if (index < 0 || index > Long.MAX_VALUE / width) throw fault("Managed allocation element outside its backing storage");
        int start = range(index * width, width);
        if (intersectsPointer(start, width)) throw fault("Atomic Int access overlaps a managed pointer cell");
        if (isPinned()) {
            var addressOperation = width == 1 ? AtomicAddressOp.CAS8 : width == 2 ? AtomicAddressOp.CAS16 : AtomicAddressOp.CAS32;
            int old = addressOperation.numericInt(ManagedAddress.Companion.fromGuestByteArray(this).plus(start), operand, replacement);
            return width == 1 ? (byte) old : width == 2 ? (short) old : old;
        }
        synchronized (bytes) {
            int old = width == 1 ? bytes[start] : width == 2 ? ManagedByteArray.readInt16(bytes, index) : ManagedByteArray.readInt32(bytes, index);
            int expected = width == 1 ? (byte) operand : width == 2 ? (short) operand : operand;
            if (old == expected) {
                if (width == 1) bytes[start] = (byte) replacement;
                else if (width == 2) ManagedByteArray.writeInt16(bytes, index, replacement);
                else ManagedByteArray.writeInt32(bytes, index, replacement);
            }
            return old;
        }
    }
    public synchronized long atomicInt(long index, long operand, long replacement, AtomicIntArrayOp operation) {
        mutable(); int width = operation.getWidth();
        if (index < 0 || index > Long.MAX_VALUE / width) throw fault("Managed allocation element outside its backing storage");
        int start = range(index * width, width);
        if (intersectsPointer(start, width)) throw fault("Atomic Int access overlaps a managed pointer cell");
        if (isPinned()) {
            var addressOperation = switch (operation) {
                case READ -> AtomicAddressOp.READ; case WRITE -> AtomicAddressOp.WRITE;
                case ADD -> AtomicAddressOp.ADD; case SUB -> AtomicAddressOp.SUB;
                case AND -> AtomicAddressOp.AND; case NAND -> AtomicAddressOp.NAND;
                case OR -> AtomicAddressOp.OR; case XOR -> AtomicAddressOp.XOR;
                default -> switch (width) { case 1 -> AtomicAddressOp.CAS8; case 2 -> AtomicAddressOp.CAS16; case 4 -> AtomicAddressOp.CAS32; default -> AtomicAddressOp.CAS64; };
            };
            long old = addressOperation.numeric(ManagedAddress.Companion.fromGuestByteArray(this).plus(start), operand, replacement);
            return switch (width) { case 1 -> (byte) old; case 2 -> (short) old; case 4 -> (int) old; default -> old; };
        }
        synchronized (bytes) {
            long old = switch (width) { case 1 -> bytes[start]; case 2 -> ManagedByteArray.readInt16(bytes, index);
                case 4 -> ManagedByteArray.readInt32(bytes, index); default -> ManagedByteArray.readInt(bytes, index); };
            long next;
            switch (operation) {
                case READ: return old;
                case WRITE: next = operand; break;
                case ADD: next = old + operand; break;
                case SUB: next = old - operand; break;
                case AND: next = old & operand; break;
                case NAND: next = ~(old & operand); break;
                case OR: next = old | operand; break;
                case XOR: next = old ^ operand; break;
                default:
                    long expected = switch (width) { case 1 -> (byte) operand; case 2 -> (short) operand; case 4 -> (int) operand; default -> operand; };
                    if (old != expected) return old;
                    next = replacement;
            }
            switch (width) {
                case 1: bytes[start] = (byte) next; break;
                case 2: ManagedByteArray.writeInt16(bytes, index, (int) next); break;
                case 4: ManagedByteArray.writeInt32(bytes, index, (int) next); break;
                default: ManagedByteArray.writeInt(bytes, index, next);
            }
            return old;
        }
    }
    @TruffleBoundary public synchronized byte[] copyBytesOut(long offset, long count) {
        int start = range(offset, count);
        if (intersectsPointer(start, (int) count)) throw fault("Raw copy overlaps a managed pointer cell");
        return segment.asSlice(start, count).toArray(ValueLayout.JAVA_BYTE);
    }
    public synchronized void copyBytesIn(byte[] source, int sourceOffset, long destinationOffset, long count) {
        copyBytesIn(MemorySegment.ofArray(source), sourceOffset, destinationOffset, count);
    }
    public synchronized void copyBytesIn(MemorySegment source, long sourceOffset, long destinationOffset, long count) {
        mutable();
        if (sourceOffset < 0 || count < 0 || sourceOffset > source.byteSize() || count > source.byteSize() - sourceOffset)
            throw fault("Raw copy source outside its backing storage");
        int start = range(destinationOffset, count);
        if (pointerCapable) invalidate(start, (int) count);
        MemorySegment.copy(source, sourceOffset, segment, start, count);
    }
    public synchronized void copyBytesTo(long sourceOffset, MemorySegment destination, long destinationOffset, long count) {
        requireByteRegion(sourceOffset, count, false);
        if (destinationOffset < 0 || destinationOffset > destination.byteSize() || count > destination.byteSize() - destinationOffset || destination.isReadOnly())
            throw fault("Raw copy destination outside its writable backing storage");
        MemorySegment.copy(segment, sourceOffset, destination, destinationOffset, count);
    }
    @TruffleBoundary public long compareBytes(ManagedAllocation other, long offset, long otherOffset, long count) {
        return withOrderedLocks(other, () -> {
            requireByteRegion(offset, count, false); other.requireByteRegion(otherOffset, count, false);
            return compareSegments(segment, offset, other.segment, otherOffset, count);
        });
    }
    @TruffleBoundary public synchronized long compareBytes(byte[] other, long offset, long otherOffset, long count) {
        requireByteRegion(offset, count, false);
        if (otherOffset < 0 || otherOffset > other.length || count > other.length - otherOffset)
            throw fault("ByteArray# comparison range outside its backing storage");
        return compareSegments(segment, offset, MemorySegment.ofArray(other), otherOffset, count);
    }
    public synchronized void fill(long offset, long count, long value) {
        mutable(); int start = range(offset, count);
        if (pointerCapable) invalidate(start, (int) count);
        segment.asSlice(start, count).fill((byte) value);
    }
    public void copyFrom(ManagedAllocation source, long sourceOffset, long destinationOffset, long count) {
        int sourceId = System.identityHashCode(source), thisId = System.identityHashCode(this);
        if (source == this) { synchronized (this) { copyLocked(source, sourceOffset, destinationOffset, count); } }
        else if (sourceId < thisId) { synchronized (source) { synchronized (this) { copyLocked(source, sourceOffset, destinationOffset, count); } } }
        else if (sourceId > thisId) { synchronized (this) { synchronized (source) { copyLocked(source, sourceOffset, destinationOffset, count); } } }
        else { synchronized (COPY_TIE_LOCK) { synchronized (source) { synchronized (this) { copyLocked(source, sourceOffset, destinationOffset, count); } } } }
    }
    private void copyLocked(ManagedAllocation source, long sourceOffset, long destinationOffset, long count) {
        mutable();
        if (pointerBytes != source.pointerBytes) throw fault("Cannot copy between different target pointer widths");
        int from = source.range(sourceOffset, count), to = range(destinationOffset, count);
        if (source.pointers == null && pointers == null) MemorySegment.copy(source.segment, from, segment, to, count);
        else copyPointerCellsFrom(source, from, to, count);
    }
    @TruffleBoundary private void copyPointerCellsFrom(ManagedAllocation source, int from, int to, long count) {
        int width = (int) count;
        source.requireWholePointerOverlaps(from, width); requireWholePointerOverlaps(to, width);
        var copied = new LinkedHashMap<Integer, ManagedAddress>();
        if (source.pointers != null) for (var cell : source.pointers.entrySet()) {
            int start = cell.getKey();
            if (start >= from && (long) start + pointerBytes <= (long) from + width) copied.put(to + start - from, cell.getValue());
        }
        if (!copied.isEmpty() && (exposedToNative || exposedAsRawBytes)) throw fault("Cannot copy managed pointers into a raw-exposed array");
        if (!copied.isEmpty()) pointerCapable = true;
        MemorySegment.copy(source.segment, from, segment, to, count);
        invalidate(to, width);
        if (!copied.isEmpty()) cells().putAll(copied);
    }
    /** Cold foreign calls use the same collision lock and owner ordering as copy. */
    public <T> T withOrderedLocks(ManagedAllocation other, Supplier<T> action) {
        int otherId = System.identityHashCode(other), thisId = System.identityHashCode(this);
        if (other == this) { synchronized (this) { return action.get(); } }
        if (otherId < thisId) { synchronized (other) { synchronized (this) { return action.get(); } } }
        if (otherId > thisId) { synchronized (this) { synchronized (other) { return action.get(); } } }
        synchronized (COPY_TIE_LOCK) { synchronized (other) { synchronized (this) { return action.get(); } } }
    }
    public synchronized ManagedAllocation resized(long newSize) {
        mutable();
        if (newSize < 0 || newSize > Integer.MAX_VALUE) throw fault("Managed allocation size outside JVM domain");
        if (newSize == getSize()) return this;
        if (newSize < getSize()) { shrink(newSize); return this; }
        return pointerCapable ? resizeWithPointerCells(newSize) : resizedStorage(newSize);
    }
    @TruffleBoundary public synchronized void shrink(long newSize) {
        mutable();
        if (newSize < 0 || newSize > getSize()) throw fault("MutableByteArray# shrink length outside current size");
        if (newSize == getSize()) return;
        if (pointers != null) {
            for (int start : pointers.keySet()) if (start < newSize && (long) start + pointerBytes > newSize)
                throw fault("Cannot truncate a managed pointer cell");
            var iterator = pointers.keySet().iterator();
            while (iterator.hasNext()) if (iterator.next() >= newSize) iterator.remove();
            if (pointers.isEmpty()) pointers = null;
        }
        logicalSize = (int) newSize;
    }
    @TruffleBoundary private ManagedAllocation resizeWithPointerCells(long newSize) {
        if (newSize < getSize() && pointers != null) for (int start : pointers.keySet())
            if (start < newSize && (long) start + pointerBytes > newSize) throw fault("Cannot truncate a managed pointer cell");
        var replacement = resizedStorage(newSize);
        if (pointers != null) for (var cell : pointers.entrySet()) if ((long) cell.getKey() + pointerBytes <= newSize) {
            replacement.cells().put(cell.getKey(), cell.getValue()); replacement.pointerCapable = true;
        }
        return replacement;
    }
    private ManagedAllocation resizedStorage(long newSize) {
        var replacement = mutable(newSize, pointerBytes);
        MemorySegment.copy(segment, 0, replacement.segment, 0, Math.min(getSize(), newSize));
        return replacement;
    }
    private static long compareSegments(MemorySegment first, long firstOffset, MemorySegment second, long secondOffset, long count) {
        long mismatch = MemorySegment.mismatch(first, firstOffset, firstOffset + count, second, secondOffset, secondOffset + count);
        if (mismatch < 0) return 0;
        int left = Byte.toUnsignedInt(first.get(ValueLayout.JAVA_BYTE, firstOffset + mismatch));
        int right = Byte.toUnsignedInt(second.get(ValueLayout.JAVA_BYTE, secondOffset + mismatch));
        return left - right;
    }
    public static ManagedAllocation mutable(long size, int pointerBytes) { return mutable(size, pointerBytes, false, 8); }
    public static ManagedAllocation mutable(long size, int pointerBytes, boolean pinned) { return mutable(size, pointerBytes, pinned, 8); }
    public static ManagedAllocation mutable(long size, int pointerBytes, boolean pinned, long alignment) {
        if (size < 0 || size > Integer.MAX_VALUE) throw fault("Managed allocation size outside JVM domain");
        if (pinned) {
            if (alignment <= 0 || (alignment & (alignment - 1)) != 0) throw fault("Pinned ByteArray# alignment must be a positive power of two");
            var storage = Arena.ofAuto().allocate(size + 1, alignment).asSlice(0, size);
            return new ManagedAllocation(null, storage, true, pointerBytes, false);
        }
        var bytes = new byte[(int) size];
        return new ManagedAllocation(bytes, MemorySegment.ofArray(bytes), true, pointerBytes, false);
    }
    public static ManagedAllocation immutable(byte[] bytes, int pointerBytes) { return immutable(bytes, pointerBytes, false); }
    public static ManagedAllocation immutable(byte[] bytes, int pointerBytes, boolean staticImage) {
        var copy = bytes.clone();
        return new ManagedAllocation(copy, MemorySegment.ofArray(copy), false, pointerBytes, staticImage);
    }
}
