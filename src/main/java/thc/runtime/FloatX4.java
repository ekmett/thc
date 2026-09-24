package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;

/** Local FloatX4# carrier. Arithmetic retains a fixed-width vector, not boxed lanes. */
public final class FloatX4 {
    private final FloatVector vector;

    private FloatX4(FloatVector vector) { this.vector = vector; }

    public static FloatX4 pack(float first, float second, float third, float fourth) {
        return new FloatX4(FloatVector.broadcast(FloatVector.SPECIES_128, first)
            .withLane(1, second).withLane(2, third).withLane(3, fourth));
    }
    public static FloatX4 broadcast(float value) {
        return new FloatX4(FloatVector.broadcast(FloatVector.SPECIES_128, value));
    }
    public float lane(int index) { return vector.lane(index); }
    /** Both index units access a complete vector; reject full-width indices before scaling. */
    private static int byteOffset(byte[] bytes, long index, boolean scalarOffset) {
        long size = bytes.length;
        long stride = scalarOffset ? 4 : 16;
        if (size < 16 || index < 0 || index > (size - 16) / stride) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("FloatX4 ByteArray# range outside its backing storage");
        }
        return (int) (index * stride);
    }
    /** Native-order bit movement; no scalar floating extraction or arithmetic. */
    public static FloatX4 readArray(byte[] bytes, long index, boolean scalarOffset) {
        int offset = byteOffset(bytes, index, scalarOffset);
        IntVector bits = ByteVector.fromArray(ByteVector.SPECIES_128, bytes, offset).reinterpretAsInts();
        // Vector reinterpretation always groups bytes in little-endian order.
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        return new FloatX4(bits.reinterpretAsFloats());
    }
    public static void writeArray(byte[] bytes, long index, FloatX4 value, boolean scalarOffset) {
        int offset = byteOffset(bytes, index, scalarOffset);
        IntVector bits = value.vector.reinterpretAsInts();
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
        bits.reinterpretAsBytes().intoArray(bytes, offset);
    }
    public static FloatX4 add(FloatX4 a, FloatX4 b) { return new FloatX4(a.vector.add(b.vector)); }
    public static FloatX4 subtract(FloatX4 a, FloatX4 b) { return new FloatX4(a.vector.sub(b.vector)); }
    public static FloatX4 multiply(FloatX4 a, FloatX4 b) { return new FloatX4(a.vector.mul(b.vector)); }
}
