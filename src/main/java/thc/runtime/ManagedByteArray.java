// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import jdk.incubator.vector.*;
import static com.oracle.truffle.api.CompilerDirectives.transferToInterpreter;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Raw host arrays keep their fast path; guest allocations retain one owner across shrink. */
public final class ManagedByteArray {
    private ManagedByteArray() {}
    private static final VarHandle INTS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final VarHandle INT16S = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.nativeOrder());
    private static final VarHandle INT32S = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
    private static final VarHandle FLOATS = MethodHandles.byteArrayViewVarHandle(float[].class, ByteOrder.nativeOrder());
    private static final VarHandle DOUBLES = MethodHandles.byteArrayViewVarHandle(double[].class, ByteOrder.nativeOrder());
    private static int elementOffset(byte[] bytes, long index, int width, String representation) {
        if (index < 0 || index >= bytes.length / width) {
            transferToInterpreter();
            throw fault("ByteArray# " + representation + " index outside its backing storage");
        }
        return (int) index * width;
    }
    private static int byteOffset(byte[] bytes, long offset, int width, String representation) {
        if (offset < 0 || offset > (long) bytes.length - width) {
            transferToInterpreter();
            throw fault("ByteArray# " + representation + " byte offset outside its backing storage");
        }
        return (int) offset;
    }
    public static long readInt(byte[] bytes, long index) { return (long) INTS.get(bytes, elementOffset(bytes, index, 8, "Int")); }
    public static void writeInt(byte[] bytes, long index, long value) { INTS.set(bytes, elementOffset(bytes, index, 8, "Int"), value); }
    public static int readInt16(byte[] bytes, long index) { return (short) INT16S.get(bytes, elementOffset(bytes, index, 2, "16-bit")); }
    public static void writeInt16(byte[] bytes, long index, int value) { INT16S.set(bytes, elementOffset(bytes, index, 2, "16-bit"), (short) value); }
    public static int readInt16ByteOffset(byte[] bytes, long index) { return (short) INT16S.get(bytes, byteOffset(bytes, index, 2, "16-bit")); }
    public static void writeInt16ByteOffset(byte[] bytes, long index, int value) { INT16S.set(bytes, byteOffset(bytes, index, 2, "16-bit"), (short) value); }
    public static int readWord16(byte[] bytes, long index) { return Short.toUnsignedInt((short) INT16S.get(bytes, elementOffset(bytes, index, 2, "16-bit"))); }
    public static int readWord16ByteOffset(byte[] bytes, long index) { return Short.toUnsignedInt((short) INT16S.get(bytes, byteOffset(bytes, index, 2, "16-bit"))); }
    public static int readInt32(byte[] bytes, long index) { return (int) INT32S.get(bytes, elementOffset(bytes, index, 4, "32-bit")); }
    public static void writeInt32(byte[] bytes, long index, int value) { INT32S.set(bytes, elementOffset(bytes, index, 4, "32-bit"), value); }
    public static int readInt32ByteOffset(byte[] bytes, long index) { return (int) INT32S.get(bytes, byteOffset(bytes, index, 4, "32-bit")); }
    public static void writeInt32ByteOffset(byte[] bytes, long index, int value) { INT32S.set(bytes, byteOffset(bytes, index, 4, "32-bit"), value); }
    public static int readWord32(byte[] bytes, long index) { return (int) INT32S.get(bytes, elementOffset(bytes, index, 4, "32-bit")); }
    public static int readWord32ByteOffset(byte[] bytes, long index) { return (int) INT32S.get(bytes, byteOffset(bytes, index, 4, "32-bit")); }
    public static float readFloat(byte[] bytes, long index) { return (float) FLOATS.get(bytes, elementOffset(bytes, index, 4, "Float")); }
    public static void writeFloat(byte[] bytes, long index, float value) { FLOATS.set(bytes, elementOffset(bytes, index, 4, "Float"), value); }
    public static float readFloatByteOffset(byte[] bytes, long index) { return (float) FLOATS.get(bytes, byteOffset(bytes, index, 4, "Float")); }
    public static void writeFloatByteOffset(byte[] bytes, long index, float value) { FLOATS.set(bytes, byteOffset(bytes, index, 4, "Float"), value); }
    public static double readDouble(byte[] bytes, long index) { return (double) DOUBLES.get(bytes, elementOffset(bytes, index, 8, "Double")); }
    public static void writeDouble(byte[] bytes, long index, double value) { DOUBLES.set(bytes, elementOffset(bytes, index, 8, "Double"), value); }
    public static double readDoubleByteOffset(byte[] bytes, long index) { return (double) DOUBLES.get(bytes, byteOffset(bytes, index, 8, "Double")); }
    public static void writeDoubleByteOffset(byte[] bytes, long index, double value) { DOUBLES.set(bytes, byteOffset(bytes, index, 8, "Double"), value); }

    public static long size(byte[] bytes) { return bytes.length; }
    private static int index(byte[] bytes, long offset) {
        if (offset < 0 || offset >= bytes.length) throw fault("ByteArray# index outside its backing storage");
        return (int) offset;
    }
    public static int read(byte[] bytes, long offset) { return Byte.toUnsignedInt(bytes[index(bytes, offset)]); }
    public static int readSigned(byte[] bytes, long offset) { return bytes[index(bytes, offset)]; }
    public static void write(byte[] bytes, long offset, int value) { bytes[index(bytes, offset)] = (byte) value; }
    private static boolean contained(long size, long offset, long count) {
        return offset >= 0 && offset <= size && count >= 0 && count <= size - offset;
    }
    public static void copy(byte[] source, long sourceOffset, byte[] destination, long destinationOffset, long count) {
        if (source == destination) throw fault("copyByteArray# requires distinct source and destination arrays");
        if (!contained(source.length, sourceOffset, count) || !contained(destination.length, destinationOffset, count))
            throw fault("ByteArray# copy range outside its backing storage");
        System.arraycopy(source, (int) sourceOffset, destination, (int) destinationOffset, (int) count);
    }
    public static void copyMutable(byte[] source, long sourceOffset, byte[] destination, long destinationOffset, long count, boolean nonOverlapping) {
        if (!contained(source.length, sourceOffset, count) || !contained(destination.length, destinationOffset, count))
            throw fault("ByteArray# copy range outside its backing storage");
        if (nonOverlapping && source == destination && count > 0 &&
                sourceOffset < destinationOffset + count && destinationOffset < sourceOffset + count)
            throw fault("copyMutableByteArrayNonOverlapping# requires disjoint regions");
        System.arraycopy(source, (int) sourceOffset, destination, (int) destinationOffset, (int) count);
    }
    public static void fill(byte[] bytes, long offset, long count, long value) {
        if (!contained(bytes.length, offset, count)) throw fault("ByteArray# fill range outside its backing storage");
        Arrays.fill(bytes, (int) offset, (int) (offset + count), (byte) value);
    }
    public static long compare(byte[] first, long firstOffset, byte[] second, long secondOffset, long count) {
        if (!contained(first.length, firstOffset, count) || !contained(second.length, secondOffset, count))
            throw fault("ByteArray# comparison range outside its backing storage");
        return Arrays.compareUnsigned(first, (int) firstOffset, (int) (firstOffset + count),
                second, (int) secondOffset, (int) (secondOffset + count));
    }
    public static byte[] resize(byte[] bytes, long size) {
        if (size < 0 || size > Integer.MAX_VALUE) throw fault("ByteArray# size outside the managed allocation domain");
        return size == bytes.length ? bytes : Arrays.copyOf(bytes, (int) size);
    }
    public static byte[] freeze(byte[] bytes) { return bytes; }
    public static Object freezeGuest(Object value) {
        if (value instanceof ManagedAllocation || value instanceof byte[]) return value;
        throw fault("Expected a managed ByteArray#");
    }
    public static Object resizeGuest(Object value, long size) {
        if (value instanceof ManagedAllocation owner) return owner.resized(size);
        if (value instanceof byte[] bytes) return resize(bytes, size);
        throw fault("Expected a managed ByteArray#");
    }
    public static Object resizeGuest(Object value, long size, boolean nativeBacking) {
        if (value instanceof ManagedAllocation owner) return owner.resized(size, nativeBacking);
        return resizeGuest(value, size);
    }
    public static void shrinkGuest(Object value, long size) {
        if (!(value instanceof ManagedAllocation owner)) throw fault("shrinkMutableByteArray# requires an owned MutableByteArray#");
        owner.shrink(size);
    }
    public static long sizeGuest(Object value) {
        if (value instanceof ManagedAllocation owner) return owner.getSize();
        if (value instanceof byte[] bytes) return size(bytes);
        throw fault("Expected a managed ByteArray#");
    }
    public static void writeGuest(Object value, long offset, int byteValue) {
        if (value instanceof ManagedAllocation owner) owner.writeByteInt(offset, byteValue);
        else write(require(value), offset, byteValue);
    }
    public static int readGuest(Object value, long offset, boolean unsigned) {
        int result = value instanceof ManagedAllocation owner ? owner.readByteInt(offset) : read(require(value), offset);
        return unsigned ? result : (byte) result;
    }
    private static long readIntElementGuest(Object value, long index) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.elementSegment(index, 8, false);
                return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, index * 8);
            }
        }
        byte[] bytes = require(value);
        elementOffset(bytes, index, 8, "scalar");
        MemorySegment segment = MemorySegment.ofArray(bytes);
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, index * 8);
    }
    private static void writeIntElementGuest(Object value, long index, long number) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.elementSegment(index, 8, true);
                segment.set(ValueLayout.JAVA_LONG_UNALIGNED, index * 8, number);
            }
        } else {
            byte[] bytes = require(value);
            elementOffset(bytes, index, 8, "scalar");
            MemorySegment.ofArray(bytes).set(ValueLayout.JAVA_LONG_UNALIGNED, index * 8, number);
        }
    }
    private static long readIntByteOffsetGuest(Object value, long index) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.byteRangeSegment(index, 8, false);
                return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, index);
            }
        }
        byte[] bytes = require(value);
        byteOffset(bytes, index, 8, "scalar");
        MemorySegment segment = MemorySegment.ofArray(bytes);
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, index);
    }
    private static void writeIntByteOffsetGuest(Object value, long index, long number) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.byteRangeSegment(index, 8, true);
                segment.set(ValueLayout.JAVA_LONG_UNALIGNED, index, number);
            }
        } else {
            byte[] bytes = require(value);
            byteOffset(bytes, index, 8, "scalar");
            MemorySegment.ofArray(bytes).set(ValueLayout.JAVA_LONG_UNALIGNED, index, number);
        }
    }
    public static double readDoubleGuest(Object value, long index) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.elementSegment(index, 8, false);
                return segment.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, index * 8);
            }
        }
        byte[] bytes = require(value);
        elementOffset(bytes, index, 8, "scalar");
        MemorySegment segment = MemorySegment.ofArray(bytes);
        return segment.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, index * 8);
    }
    public static void writeDoubleGuest(Object value, long index, double number) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.elementSegment(index, 8, true);
                segment.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, index * 8, number);
            }
        } else {
            byte[] bytes = require(value);
            elementOffset(bytes, index, 8, "scalar");
            MemorySegment.ofArray(bytes).set(ValueLayout.JAVA_DOUBLE_UNALIGNED, index * 8, number);
        }
    }
    public static double readDoubleByteOffsetGuest(Object value, long index) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.byteRangeSegment(index, 8, false);
                return segment.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, index);
            }
        }
        byte[] bytes = require(value);
        byteOffset(bytes, index, 8, "scalar");
        MemorySegment segment = MemorySegment.ofArray(bytes);
        return segment.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, index);
    }
    public static void writeDoubleByteOffsetGuest(Object value, long index, double number) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.byteRangeSegment(index, 8, true);
                segment.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, index, number);
            }
        } else {
            byte[] bytes = require(value);
            byteOffset(bytes, index, 8, "scalar");
            MemorySegment.ofArray(bytes).set(ValueLayout.JAVA_DOUBLE_UNALIGNED, index, number);
        }
    }
    public static float readFloatGuest(Object value, long index) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.elementSegment(index, 4, false);
                return segment.get(ValueLayout.JAVA_FLOAT_UNALIGNED, index * 4);
            }
        }
        byte[] bytes = require(value);
        elementOffset(bytes, index, 4, "scalar");
        MemorySegment segment = MemorySegment.ofArray(bytes);
        return segment.get(ValueLayout.JAVA_FLOAT_UNALIGNED, index * 4);
    }
    public static void writeFloatGuest(Object value, long index, float number) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.elementSegment(index, 4, true);
                segment.set(ValueLayout.JAVA_FLOAT_UNALIGNED, index * 4, number);
            }
        } else {
            byte[] bytes = require(value);
            elementOffset(bytes, index, 4, "scalar");
            MemorySegment.ofArray(bytes).set(ValueLayout.JAVA_FLOAT_UNALIGNED, index * 4, number);
        }
    }
    public static float readFloatByteOffsetGuest(Object value, long index) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.byteRangeSegment(index, 4, false);
                return segment.get(ValueLayout.JAVA_FLOAT_UNALIGNED, index);
            }
        }
        byte[] bytes = require(value);
        byteOffset(bytes, index, 4, "scalar");
        MemorySegment segment = MemorySegment.ofArray(bytes);
        return segment.get(ValueLayout.JAVA_FLOAT_UNALIGNED, index);
    }
    public static void writeFloatByteOffsetGuest(Object value, long index, float number) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.byteRangeSegment(index, 4, true);
                segment.set(ValueLayout.JAVA_FLOAT_UNALIGNED, index, number);
            }
        } else {
            byte[] bytes = require(value);
            byteOffset(bytes, index, 4, "scalar");
            MemorySegment.ofArray(bytes).set(ValueLayout.JAVA_FLOAT_UNALIGNED, index, number);
        }
    }
    public static int readInt16Guest(Object value, long index, boolean unsigned) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.elementSegment(index, 2, false);
                return unsigned ? Short.toUnsignedInt(segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, index * 2)) : segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, index * 2);
            }
        }
        byte[] bytes = require(value);
        elementOffset(bytes, index, 2, "scalar");
        MemorySegment segment = MemorySegment.ofArray(bytes);
        return unsigned ? Short.toUnsignedInt(segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, index * 2)) : segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, index * 2);
    }
    public static void writeInt16Guest(Object value, long index, int number) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.elementSegment(index, 2, true);
                segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, index * 2, (short) number);
            }
        } else {
            byte[] bytes = require(value);
            elementOffset(bytes, index, 2, "scalar");
            MemorySegment.ofArray(bytes).set(ValueLayout.JAVA_SHORT_UNALIGNED, index * 2, (short) number);
        }
    }
    public static int readInt16ByteOffsetGuest(Object value, long index, boolean unsigned) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.byteRangeSegment(index, 2, false);
                return unsigned ? Short.toUnsignedInt(segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, index)) : segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, index);
            }
        }
        byte[] bytes = require(value);
        byteOffset(bytes, index, 2, "scalar");
        MemorySegment segment = MemorySegment.ofArray(bytes);
        return unsigned ? Short.toUnsignedInt(segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, index)) : segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, index);
    }
    public static void writeInt16ByteOffsetGuest(Object value, long index, int number) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.byteRangeSegment(index, 2, true);
                segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, index, (short) number);
            }
        } else {
            byte[] bytes = require(value);
            byteOffset(bytes, index, 2, "scalar");
            MemorySegment.ofArray(bytes).set(ValueLayout.JAVA_SHORT_UNALIGNED, index, (short) number);
        }
    }
    public static int readInt32Guest(Object value, long index, boolean unsigned) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.elementSegment(index, 4, false);
                return segment.get(ValueLayout.JAVA_INT_UNALIGNED, index * 4);
            }
        }
        byte[] bytes = require(value);
        elementOffset(bytes, index, 4, "scalar");
        MemorySegment segment = MemorySegment.ofArray(bytes);
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED, index * 4);
    }
    public static void writeInt32Guest(Object value, long index, int number) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.elementSegment(index, 4, true);
                segment.set(ValueLayout.JAVA_INT_UNALIGNED, index * 4, number);
            }
        } else {
            byte[] bytes = require(value);
            elementOffset(bytes, index, 4, "scalar");
            MemorySegment.ofArray(bytes).set(ValueLayout.JAVA_INT_UNALIGNED, index * 4, number);
        }
    }
    public static int readInt32ByteOffsetGuest(Object value, long index, boolean unsigned) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.byteRangeSegment(index, 4, false);
                return segment.get(ValueLayout.JAVA_INT_UNALIGNED, index);
            }
        }
        byte[] bytes = require(value);
        byteOffset(bytes, index, 4, "scalar");
        MemorySegment segment = MemorySegment.ofArray(bytes);
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED, index);
    }
    public static void writeInt32ByteOffsetGuest(Object value, long index, int number) {
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.byteRangeSegment(index, 4, true);
                segment.set(ValueLayout.JAVA_INT_UNALIGNED, index, number);
            }
        } else {
            byte[] bytes = require(value);
            byteOffset(bytes, index, 4, "scalar");
            MemorySegment.ofArray(bytes).set(ValueLayout.JAVA_INT_UNALIGNED, index, number);
        }
    }
    public static long readIntGuest(Object value, long index) { return readIntGuest(value, index, false); }
    public static long readIntGuest(Object value, long index, boolean byteOffset) {
        return byteOffset ? readIntByteOffsetGuest(value, index) : readIntElementGuest(value, index);
    }
    public static void writeIntGuest(Object value, long index, long number) { writeIntGuest(value, index, number, false); }
    public static void writeIntGuest(Object value, long index, long number, boolean byteOffset) {
        if (byteOffset) writeIntByteOffsetGuest(value, index, number); else writeIntElementGuest(value, index, number);
    }
    public static void fillGuest(Object value, long offset, long count, long byteValue) {
        if (value instanceof ManagedAllocation owner) owner.fill(offset, count, byteValue);
        else fill(require(value), offset, count, byteValue);
    }
    public static void copyGuest(Object source, long sourceOffset, Object destination, long destinationOffset, long count, boolean mutable) {
        copyGuest(source, sourceOffset, destination, destinationOffset, count, mutable, false);
    }
    public static void copyGuest(Object source, long sourceOffset, Object destination, long destinationOffset, long count, boolean mutable, boolean nonOverlapping) {
        if (source instanceof byte[] from && destination instanceof byte[] to) {
            if (mutable) copyMutable(from, sourceOffset, to, destinationOffset, count, nonOverlapping);
            else copy(from, sourceOffset, to, destinationOffset, count);
            return;
        }
        copyPinned(source, sourceOffset, destination, destinationOffset, count, mutable, nonOverlapping);
    }
    @TruffleBoundary
    private static void copyPinned(Object source, long sourceOffset, Object destination, long destinationOffset, long count, boolean mutable, boolean nonOverlapping) {
        long fromSize = sizeGuest(source), toSize = sizeGuest(destination);
        if (!contained(fromSize, sourceOffset, count) || !contained(toSize, destinationOffset, count))
            throw fault("ByteArray# copy range outside its backing storage");
        if (!mutable && source == destination) throw fault("copyByteArray# requires distinct source and destination arrays");
        if (nonOverlapping && source == destination && count > 0 &&
                sourceOffset < destinationOffset + count && destinationOffset < sourceOffset + count)
            throw fault("copyMutableByteArrayNonOverlapping# requires disjoint regions");
        if (source instanceof ManagedAllocation from && destination instanceof ManagedAllocation to)
            to.copyFrom(from, sourceOffset, destinationOffset, count);
        else if (source instanceof ManagedAllocation from)
            from.copyBytesTo(sourceOffset, MemorySegment.ofArray(require(destination)), destinationOffset, count);
        else if (destination instanceof ManagedAllocation to)
            to.copyBytesIn(require(source), (int) sourceOffset, destinationOffset, count);
        else throw fault("Expected a managed ByteArray#");
    }
    public static long compareGuest(Object first, long firstOffset, Object second, long secondOffset, long count) {
        if (first instanceof byte[] a && second instanceof byte[] b) return compare(a, firstOffset, b, secondOffset, count);
        if (first instanceof ManagedAllocation a && second instanceof ManagedAllocation b) return a.compareBytes(b, firstOffset, secondOffset, count);
        if (first instanceof ManagedAllocation a && second instanceof byte[] b) return a.compareBytes(b, firstOffset, secondOffset, count);
        if (first instanceof byte[] a && second instanceof ManagedAllocation b) return -b.compareBytes(a, secondOffset, firstOffset, count);
        throw fault("Expected a managed ByteArray#");
    }
    public static byte[] allocate(long size) {
        if (size < 0 || size > Integer.MAX_VALUE) throw fault("ByteArray# size outside the managed allocation domain");
        return new byte[(int) size];
    }
    public static ManagedAllocation allocateGuest(long size) { return ManagedAllocation.mutable(size, (int) ValueLayout.ADDRESS.byteSize()); }
    public static ManagedAllocation allocateGuest(long size, boolean nativeBacking) {
        return nativeBacking ? ManagedAllocation.nativeMutable(size, (int) ValueLayout.ADDRESS.byteSize()) : allocateGuest(size);
    }
    private static MemorySegment vectorSegment(byte[] bytes, long index, int stride, int vectorBytes) {
        if (bytes.length < vectorBytes || index < 0 || index > (long) (bytes.length - vectorBytes) / stride)
            throw fault("Vector ByteArray# range outside its backing storage");
        return MemorySegment.ofArray(bytes);
    }
    public static ByteVector readByteVectorGuest(Object value, long index, boolean scalarOffset) { return readByteVectorGuest(value, index, scalarOffset, 16); }
    public static ByteVector readByteVectorGuest(Object value, long index, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 1 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 1, false, vectorBytes);
                return ByteVector.fromMemorySegment(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
            }
        }
        MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
        return ByteVector.fromMemorySegment(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
    }
    public static void writeByteVectorGuest(Object value, long index, ByteVector vector, boolean scalarOffset) { writeByteVectorGuest(value, index, vector, scalarOffset, 16); }
    public static void writeByteVectorGuest(Object value, long index, ByteVector vector, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 1 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 1, true, vectorBytes);
                RuntimeTypes.requireByte(vector, ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
            }
        } else {
            MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
            RuntimeTypes.requireByte(vector, ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
        }
    }
    public static ShortVector readShortVectorGuest(Object value, long index, boolean scalarOffset) { return readShortVectorGuest(value, index, scalarOffset, 16); }
    public static ShortVector readShortVectorGuest(Object value, long index, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 2 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 2, false, vectorBytes);
                return ShortVector.fromMemorySegment(ShortVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
            }
        }
        MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
        return ShortVector.fromMemorySegment(ShortVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
    }
    public static void writeShortVectorGuest(Object value, long index, ShortVector vector, boolean scalarOffset) { writeShortVectorGuest(value, index, vector, scalarOffset, 16); }
    public static void writeShortVectorGuest(Object value, long index, ShortVector vector, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 2 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 2, true, vectorBytes);
                RuntimeTypes.requireShort(vector, ShortVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
            }
        } else {
            MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
            RuntimeTypes.requireShort(vector, ShortVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
        }
    }
    public static LongVector readLongVectorGuest(Object value, long index, boolean scalarOffset) { return readLongVectorGuest(value, index, scalarOffset, 16); }
    public static LongVector readLongVectorGuest(Object value, long index, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 8 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 8, false, vectorBytes);
                return LongVector.fromMemorySegment(LongVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
            }
        }
        MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
        return LongVector.fromMemorySegment(LongVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
    }
    public static void writeLongVectorGuest(Object value, long index, LongVector vector, boolean scalarOffset) { writeLongVectorGuest(value, index, vector, scalarOffset, 16); }
    public static void writeLongVectorGuest(Object value, long index, LongVector vector, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 8 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 8, true, vectorBytes);
                RuntimeTypes.requireLong(vector, LongVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
            }
        } else {
            MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
            RuntimeTypes.requireLong(vector, LongVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
        }
    }
    public static IntVector readInt32VectorGuest(Object value, long index, boolean scalarOffset) { return readInt32VectorGuest(value, index, scalarOffset, 16); }
    public static IntVector readInt32VectorGuest(Object value, long index, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 4 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 4, false, vectorBytes);
                return IntVector.fromMemorySegment(IntVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
            }
        }
        MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
        return IntVector.fromMemorySegment(IntVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
    }
    public static void writeInt32VectorGuest(Object value, long index, IntVector vector, boolean scalarOffset) { writeInt32VectorGuest(value, index, vector, scalarOffset, 16); }
    public static void writeInt32VectorGuest(Object value, long index, IntVector vector, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 4 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 4, true, vectorBytes);
                RuntimeTypes.requireInt(vector, IntVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
            }
        } else {
            MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
            RuntimeTypes.requireInt(vector, IntVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
        }
    }
    public static IntVector readWord32VectorGuest(Object value, long index, boolean scalarOffset) { return readWord32VectorGuest(value, index, scalarOffset, 16); }
    public static IntVector readWord32VectorGuest(Object value, long index, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 4 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 4, false, vectorBytes);
                return IntVector.fromMemorySegment(IntVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
            }
        }
        MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
        return IntVector.fromMemorySegment(IntVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
    }
    public static void writeWord32VectorGuest(Object value, long index, IntVector vector, boolean scalarOffset) { writeWord32VectorGuest(value, index, vector, scalarOffset, 16); }
    public static void writeWord32VectorGuest(Object value, long index, IntVector vector, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 4 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 4, true, vectorBytes);
                RuntimeTypes.requireInt(vector, IntVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
            }
        } else {
            MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
            RuntimeTypes.requireInt(vector, IntVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
        }
    }
    public static FloatVector readFloatVectorGuest(Object value, long index, boolean scalarOffset) { return readFloatVectorGuest(value, index, scalarOffset, 16); }
    public static FloatVector readFloatVectorGuest(Object value, long index, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 4 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 4, false, vectorBytes);
                return FloatVector.fromMemorySegment(FloatVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
            }
        }
        MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
        return FloatVector.fromMemorySegment(FloatVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
    }
    public static void writeFloatVectorGuest(Object value, long index, FloatVector vector, boolean scalarOffset) { writeFloatVectorGuest(value, index, vector, scalarOffset, 16); }
    public static void writeFloatVectorGuest(Object value, long index, FloatVector vector, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 4 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 4, true, vectorBytes);
                RuntimeTypes.requireFloat(vector, FloatVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
            }
        } else {
            MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
            RuntimeTypes.requireFloat(vector, FloatVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
        }
    }
    public static DoubleVector readDoubleVectorGuest(Object value, long index, boolean scalarOffset) { return readDoubleVectorGuest(value, index, scalarOffset, 16); }
    public static DoubleVector readDoubleVectorGuest(Object value, long index, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 8 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 8, false, vectorBytes);
                return DoubleVector.fromMemorySegment(DoubleVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
            }
        }
        MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
        return DoubleVector.fromMemorySegment(DoubleVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), segment, offset, ByteOrder.nativeOrder());
    }
    public static void writeDoubleVectorGuest(Object value, long index, DoubleVector vector, boolean scalarOffset) { writeDoubleVectorGuest(value, index, vector, scalarOffset, 16); }
    public static void writeDoubleVectorGuest(Object value, long index, DoubleVector vector, boolean scalarOffset, int vectorBytes) {
        int stride = scalarOffset ? 8 : vectorBytes;
        long offset = index * stride;
        if (value instanceof ManagedAllocation owner) {
            synchronized (owner) {
                MemorySegment segment = owner.vectorSegment(index, scalarOffset, 8, true, vectorBytes);
                RuntimeTypes.requireDouble(vector, DoubleVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
            }
        } else {
            MemorySegment segment = vectorSegment(require(value), index, stride, vectorBytes);
            RuntimeTypes.requireDouble(vector, DoubleVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).intoMemorySegment(segment, offset, ByteOrder.nativeOrder());
        }
    }
    public static byte[] require(Object value) {
        if (value instanceof byte[] bytes) return bytes;
        if (value instanceof ManagedAllocation owner) return owner.wholeBytesForPrimitive();
        throw fault("Expected a managed ByteArray#");
    }
    public static void requireState(Object value) { TupleResults.requireVoidCarrier(value); }
}
