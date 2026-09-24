package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;

/** Local DoubleX2# carrier. Arithmetic retains a fixed-width vector, not boxed lanes. */
public final class DoubleX2 {
    private final DoubleVector vector;

    private DoubleX2(DoubleVector vector) { this.vector = vector; }

    public static DoubleX2 pack(double first, double second) {
        return new DoubleX2(DoubleVector.broadcast(DoubleVector.SPECIES_128, first)
            .withLane(1, second));
    }
    public static DoubleX2 broadcast(double value) {
        return new DoubleX2(DoubleVector.broadcast(DoubleVector.SPECIES_128, value));
    }
    public double lane(int index) { return vector.lane(index); }
    /** Both index units access a complete vector; reject full-width indices before scaling. */
    private static int byteOffset(byte[] bytes, long index, boolean scalarOffset) {
        long size = bytes.length;
        long stride = scalarOffset ? 8 : 16;
        if (size < 16 || index < 0 || index > (size - 16) / stride) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("DoubleX2 ByteArray# range outside its backing storage");
        }
        return (int) (index * stride);
    }
    /** Native-order bit movement; no scalar floating extraction or arithmetic. */
    public static DoubleX2 readArray(byte[] bytes, long index, boolean scalarOffset) {
        int offset = byteOffset(bytes, index, scalarOffset);
        LongVector bits = ByteVector.fromArray(ByteVector.SPECIES_128, bytes, offset).reinterpretAsLongs();
        // Vector reinterpretation always groups bytes in little-endian order.
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        return new DoubleX2(bits.reinterpretAsDoubles());
    }
    public static void writeArray(byte[] bytes, long index, DoubleX2 value, boolean scalarOffset) {
        int offset = byteOffset(bytes, index, scalarOffset);
        LongVector bits = value.vector.reinterpretAsLongs();
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        bits.reinterpretAsBytes().intoArray(bytes, offset);
    }
    public static DoubleX2 add(DoubleX2 a, DoubleX2 b) { return new DoubleX2(a.vector.add(b.vector)); }
    public static DoubleX2 subtract(DoubleX2 a, DoubleX2 b) { return new DoubleX2(a.vector.sub(b.vector)); }
    public static DoubleX2 multiply(DoubleX2 a, DoubleX2 b) { return new DoubleX2(a.vector.mul(b.vector)); }
    public static DoubleX2 negate(DoubleX2 a) { return new DoubleX2(a.vector.neg()); }
    public static DoubleX2 divide(DoubleX2 a, DoubleX2 b) { return new DoubleX2(a.vector.div(b.vector)); }
}
