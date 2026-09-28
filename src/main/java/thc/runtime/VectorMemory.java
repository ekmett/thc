// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.ByteOrder;
import jdk.incubator.vector.*;
import static thc.runtime.RuntimeFault.fault;

/** Native-order byte movement at the explicit ByteArray# boundary. */
public final class VectorMemory {
    private VectorMemory() {}

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

        bits.reinterpretAsBytes().intoArray(bytes, offset);
    }

    public static ShortVector readShortVectorArray(byte[] bytes, long index, boolean scalarOffset) {
        return readShortVectorArray(bytes, index, scalarOffset, 16);
    }

    public static ShortVector readShortVectorArray(byte[] bytes, long index, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 2, "Short128", vectorBytes);
        var bits = ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset).reinterpretAsShorts();
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
        bits.reinterpretAsBytes().intoArray(bytes, offset);
    }

    public static LongVector readLongVectorArray(byte[] bytes, long index, boolean scalarOffset) {
        return readLongVectorArray(bytes, index, scalarOffset, 16);
    }

    public static LongVector readLongVectorArray(byte[] bytes, long index, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 8, "Long128", vectorBytes);
        var bits = ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset).reinterpretAsLongs();
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
        bits.reinterpretAsBytes().intoArray(bytes, offset);
    }

    public static IntVector readIntVectorArray(byte[] bytes, long index, boolean scalarOffset, String name) {
        return readIntVectorArray(bytes, index, scalarOffset, name, 16);
    }

    public static IntVector readIntVectorArray(byte[] bytes, long index, boolean scalarOffset, String name, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 4, name, vectorBytes);
        var bits = ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset).reinterpretAsInts();
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
        bits.reinterpretAsBytes().intoArray(bytes, offset);
    }

    public static FloatVector readFloatVectorArray(byte[] bytes, long index, boolean scalarOffset) {
        return readFloatVectorArray(bytes, index, scalarOffset, 16);
    }

    public static FloatVector readFloatVectorArray(byte[] bytes, long index, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 4, "FloatX4", vectorBytes);
        var bits = ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset).reinterpretAsInts();
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        return bits.reinterpretAsFloats();
    }

    public static void writeFloatVectorArray(byte[] bytes, long index, FloatVector value, boolean scalarOffset) {
        writeFloatVectorArray(bytes, index, value, scalarOffset, 16);
    }

    public static void writeFloatVectorArray(byte[] bytes, long index, FloatVector value, boolean scalarOffset, int vectorBytes) {
        writeIntVectorArray(bytes, index,
            RuntimeTypes.requireFloat(value, FloatVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).reinterpretAsInts(),
            scalarOffset, "FloatX4", vectorBytes);
    }

    public static DoubleVector readDoubleVectorArray(byte[] bytes, long index, boolean scalarOffset) {
        return readDoubleVectorArray(bytes, index, scalarOffset, 16);
    }

    public static DoubleVector readDoubleVectorArray(byte[] bytes, long index, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 8, "DoubleX2", vectorBytes);
        var bits = ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset).reinterpretAsLongs();
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        return bits.reinterpretAsDoubles();
    }

    public static void writeDoubleVectorArray(byte[] bytes, long index, DoubleVector value, boolean scalarOffset) {
        writeDoubleVectorArray(bytes, index, value, scalarOffset, 16);
    }

    public static void writeDoubleVectorArray(byte[] bytes, long index, DoubleVector value, boolean scalarOffset, int vectorBytes) {
        int offset = byteOffset(bytes, index, scalarOffset, 8, "DoubleX2", vectorBytes);
        var vector = RuntimeTypes.requireDouble(value, DoubleVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
        var bits = vector.reinterpretAsLongs();
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        bits.reinterpretAsBytes().intoArray(bytes, offset);
    }
}
