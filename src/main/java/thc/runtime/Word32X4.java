// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;

/** Four unsigned lanes stored as raw ints; unpack zero-extends to Long. */
public final class Word32X4 {
    public final int first;
    public final int second;
    public final int third;
    public final int fourth;

    public Word32X4(int first, int second, int third, int fourth) {
        this.first = first; this.second = second; this.third = third; this.fourth = fourth;
    }
    public static Word32X4 broadcast(int value) {
        return new Word32X4(value, value, value, value);
    }
    private IntVector vector() {
        return IntVector.broadcast(IntVector.SPECIES_128, first)
            .withLane(1, second).withLane(2, third).withLane(3, fourth);
    }
    private static Word32X4 lanes(IntVector vector) {
        return new Word32X4(vector.lane(0), vector.lane(1), vector.lane(2), vector.lane(3));
    }
    /** Both index units access a complete vector; reject full-width indices before scaling. */
    private static int byteOffset(byte[] bytes, long index, boolean scalarOffset) {
        long size = bytes.length;
        long stride = scalarOffset ? 4 : 16;
        if (size < 16 || index < 0 || index > (size - 16) / stride) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Word32X4 ByteArray# range outside its backing storage");
        }
        return (int) (index * stride);
    }
    /** Native-order raw lane bits, indexed in Word32 elements or complete Word32X4 vectors. */
    public static Word32X4 readArray(byte[] bytes, long index, boolean scalarOffset) {
        int offset = byteOffset(bytes, index, scalarOffset);
        IntVector value = ByteVector.fromArray(ByteVector.SPECIES_128, bytes, offset).reinterpretAsInts();
        // Vector reinterpretation always groups bytes in little-endian order.
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) value = value.lanewise(VectorOperators.REVERSE_BYTES);
        return lanes(value);
    }
    public static void writeArray(byte[] bytes, long index, Word32X4 value, boolean scalarOffset) {
        int offset = byteOffset(bytes, index, scalarOffset);
        IntVector vector = value.vector();
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) vector = vector.lanewise(VectorOperators.REVERSE_BYTES);
        vector.reinterpretAsBytes().intoArray(bytes, offset);
    }
    public static Word32X4 add(Word32X4 a, Word32X4 b) { return lanes(a.vector().add(b.vector())); }
    public static Word32X4 subtract(Word32X4 a, Word32X4 b) { return lanes(a.vector().sub(b.vector())); }
    public static Word32X4 multiply(Word32X4 a, Word32X4 b) { return lanes(a.vector().mul(b.vector())); }
    public static Word32X4 min(Word32X4 a, Word32X4 b) { return lanes(a.vector().lanewise(VectorOperators.UMIN, b.vector())); }
    public static Word32X4 max(Word32X4 a, Word32X4 b) { return lanes(a.vector().lanewise(VectorOperators.UMAX, b.vector())); }
}
