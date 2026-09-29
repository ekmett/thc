// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.ByteOrder;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import jdk.incubator.vector.*;
import org.graalvm.nativeimage.ImageInfo;
import static thc.runtime.RuntimeFault.fault;

/** Native-order byte movement at the explicit ByteArray# boundary. */
public final class VectorMemory {
    private VectorMemory() {}

    // Frozen by the explicit resource-copy image recipe. Never selected by a
    // JVM property at ordinary execution time, or by the optimized image recipe.
    private static final boolean RESOURCE_COPIES = ImageInfo.inImageCode()
        && Boolean.getBoolean("thc.nativeImage.resourceCopies");

    public static <E> Vector<E> read(VectorSpecies<E> species, MemorySegment segment, long offset, ByteOrder order) {
        return RESOURCE_COPIES ? copyRead(species, segment, offset, order)
            : species.fromMemorySegment(segment, offset, order);
    }

    public static void write(Vector<?> vector, MemorySegment segment, long offset, ByteOrder order) {
        if (RESOURCE_COPIES) copyWrite(vector, segment, offset, order);
        else vector.intoMemorySegment(segment, offset, order);
    }

    private static ValueLayout laneLayout(Class<?> lane, ByteOrder order) {
        ValueLayout layout;
        if (lane == byte.class) layout = ValueLayout.JAVA_BYTE;
        else if (lane == short.class) layout = ValueLayout.JAVA_SHORT;
        else if (lane == int.class) layout = ValueLayout.JAVA_INT;
        else if (lane == long.class) layout = ValueLayout.JAVA_LONG;
        else if (lane == float.class) layout = ValueLayout.JAVA_FLOAT;
        else if (lane == double.class) layout = ValueLayout.JAVA_DOUBLE;
        else throw new IllegalArgumentException("Unsupported vector lane type");
        return layout.withByteAlignment(1).withOrder(order);
    }

    static <E> Vector<E> copyRead(VectorSpecies<E> species, MemorySegment segment, long offset, ByteOrder order) {
        Class<?> lane = species.elementType();
        int length = species.length();
        Object values;
        if (lane == byte.class) values = new byte[length];
        else if (lane == short.class) values = new short[length];
        else if (lane == int.class) values = new int[length];
        else if (lane == long.class) values = new long[length];
        else if (lane == float.class) values = new float[length];
        else if (lane == double.class) values = new double[length];
        else throw new IllegalArgumentException("Unsupported vector lane type");
        MemorySegment.copy(segment, laneLayout(lane, order), offset, values, 0, length);
        return species.fromArray(values, 0);
    }

    static void copyWrite(Vector<?> vector, MemorySegment segment, long offset, ByteOrder order) {
        MemorySegment.copy(vector.toArray(), 0, segment, laneLayout(vector.elementType(), order), offset, vector.length());
    }

    static <E> Vector<E> copyReinterpret(Vector<?> vector, VectorSpecies<E> species) {
        if (vector.bitSize() != species.vectorBitSize()) throw new IllegalArgumentException("Mismatched vector shape");
        // AbstractVector.defaultReinterpret uses vector segment operations even
        // on heap memory. Scalar bulk copies preserve that little-endian bit
        // contract without entering the unsupported vector segment intrinsics.
        var bytes = MemorySegment.ofArray(new byte[vector.byteSize()]);
        copyWrite(vector, bytes, 0, ByteOrder.LITTLE_ENDIAN);
        return copyRead(species, bytes, 0, ByteOrder.LITTLE_ENDIAN);
    }

    public static ByteVector asBytes(Vector<?> vector) {
        return RESOURCE_COPIES ? (ByteVector) copyReinterpret(vector, ByteVector.SPECIES_128.withShape(vector.shape()))
            : vector.reinterpretAsBytes();
    }
    public static ShortVector asShorts(Vector<?> vector) {
        return RESOURCE_COPIES ? (ShortVector) copyReinterpret(vector, ShortVector.SPECIES_128.withShape(vector.shape()))
            : vector.reinterpretAsShorts();
    }
    public static IntVector asInts(Vector<?> vector) {
        return RESOURCE_COPIES ? (IntVector) copyReinterpret(vector, IntVector.SPECIES_128.withShape(vector.shape()))
            : vector.reinterpretAsInts();
    }
    public static LongVector asLongs(Vector<?> vector) {
        return RESOURCE_COPIES ? (LongVector) copyReinterpret(vector, LongVector.SPECIES_128.withShape(vector.shape()))
            : vector.reinterpretAsLongs();
    }
    public static FloatVector asFloats(Vector<?> vector) {
        return RESOURCE_COPIES ? (FloatVector) copyReinterpret(vector, FloatVector.SPECIES_128.withShape(vector.shape()))
            : vector.reinterpretAsFloats();
    }
    public static DoubleVector asDoubles(Vector<?> vector) {
        return RESOURCE_COPIES ? (DoubleVector) copyReinterpret(vector, DoubleVector.SPECIES_128.withShape(vector.shape()))
            : vector.reinterpretAsDoubles();
    }

    private static int byteOffset(byte[] bytes, long index, boolean scalarOffset,
            int laneBytes, String name, int vectorBytes) {
        int stride = scalarOffset ? laneBytes : vectorBytes;
        if (bytes.length < vectorBytes || index < 0 || index > (long) (bytes.length - vectorBytes) / stride)
            throw fault(name + " ByteArray# range outside its backing storage");
        return (int) (index * stride);
    }

    public static ByteVector readByteVectorArray(byte[] bytes, long index, boolean scalarOffset) {
        return readByteVectorArray(bytes, index, scalarOffset, 16);
    }

    public static ByteVector readByteVectorArray(byte[] bytes, long index, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 1, "Byte128", vectorBytes);
        var bits = ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset);

        return bits;
    }

    public static void writeByteVectorArray(byte[] bytes, long index, ByteVector value, boolean scalarOffset) {
        writeByteVectorArray(bytes, index, value, scalarOffset, 16);
    }

    public static void writeByteVectorArray(byte[] bytes, long index, ByteVector value, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 1, "Byte128", vectorBytes);
        var vector = RuntimeTypes.requireByte(value, ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
        var bits = vector;

        asBytes(bits).intoArray(bytes, offset);
    }

    public static ShortVector readShortVectorArray(byte[] bytes, long index, boolean scalarOffset) {
        return readShortVectorArray(bytes, index, scalarOffset, 16);
    }

    public static ShortVector readShortVectorArray(byte[] bytes, long index, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 2, "Short128", vectorBytes);
        var bits = asShorts(ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset));
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        return bits;
    }

    public static void writeShortVectorArray(byte[] bytes, long index, ShortVector value, boolean scalarOffset) {
        writeShortVectorArray(bytes, index, value, scalarOffset, 16);
    }

    public static void writeShortVectorArray(byte[] bytes, long index, ShortVector value, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 2, "Short128", vectorBytes);
        var vector = RuntimeTypes.requireShort(value, ShortVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
        var bits = vector;
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        asBytes(bits).intoArray(bytes, offset);
    }

    public static LongVector readLongVectorArray(byte[] bytes, long index, boolean scalarOffset) {
        return readLongVectorArray(bytes, index, scalarOffset, 16);
    }

    public static LongVector readLongVectorArray(byte[] bytes, long index, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 8, "Long128", vectorBytes);
        var bits = asLongs(ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset));
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        return bits;
    }

    public static void writeLongVectorArray(byte[] bytes, long index, LongVector value, boolean scalarOffset) {
        writeLongVectorArray(bytes, index, value, scalarOffset, 16);
    }

    public static void writeLongVectorArray(byte[] bytes, long index, LongVector value, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 8, "Long128", vectorBytes);
        var vector = RuntimeTypes.requireLong(value, LongVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
        var bits = vector;
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        asBytes(bits).intoArray(bytes, offset);
    }

    public static IntVector readIntVectorArray(byte[] bytes, long index, boolean scalarOffset, String name) {
        return readIntVectorArray(bytes, index, scalarOffset, name, 16);
    }

    public static IntVector readIntVectorArray(byte[] bytes, long index, boolean scalarOffset, String name, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 4, name, vectorBytes);
        var bits = asInts(ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset));
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        return bits;
    }

    public static void writeIntVectorArray(byte[] bytes, long index, IntVector value, boolean scalarOffset, String name) {
        writeIntVectorArray(bytes, index, value, scalarOffset, name, 16);
    }

    public static void writeIntVectorArray(byte[] bytes, long index, IntVector value, boolean scalarOffset, String name, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 4, name, vectorBytes);
        var vector = RuntimeTypes.requireInt(value, IntVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
        var bits = vector;
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        asBytes(bits).intoArray(bytes, offset);
    }

    public static FloatVector readFloatVectorArray(byte[] bytes, long index, boolean scalarOffset) {
        return readFloatVectorArray(bytes, index, scalarOffset, 16);
    }

    public static FloatVector readFloatVectorArray(byte[] bytes, long index, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 4, "FloatX4", vectorBytes);
        var bits = asInts(ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset));
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        return asFloats(bits);
    }

    public static void writeFloatVectorArray(byte[] bytes, long index, FloatVector value, boolean scalarOffset) {
        writeFloatVectorArray(bytes, index, value, scalarOffset, 16);
    }

    public static void writeFloatVectorArray(byte[] bytes, long index, FloatVector value, boolean scalarOffset, int vectorBytes) {
        writeIntVectorArray(bytes, index,
            asInts(RuntimeTypes.requireFloat(value, FloatVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)))),
            scalarOffset, "FloatX4", vectorBytes);
    }

    public static DoubleVector readDoubleVectorArray(byte[] bytes, long index, boolean scalarOffset) {
        return readDoubleVectorArray(bytes, index, scalarOffset, 16);
    }

    public static DoubleVector readDoubleVectorArray(byte[] bytes, long index, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 8, "DoubleX2", vectorBytes);
        var bits = asLongs(ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset));
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        return asDoubles(bits);
    }

    public static void writeDoubleVectorArray(byte[] bytes, long index, DoubleVector value, boolean scalarOffset) {
        writeDoubleVectorArray(bytes, index, value, scalarOffset, 16);
    }

    public static void writeDoubleVectorArray(byte[] bytes, long index, DoubleVector value, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 8, "DoubleX2", vectorBytes);
        var vector = RuntimeTypes.requireDouble(value, DoubleVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
        var bits = asLongs(vector);
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        asBytes(bits).intoArray(bytes, offset);
    }
}
