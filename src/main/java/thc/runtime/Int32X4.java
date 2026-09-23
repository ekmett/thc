package thc.runtime;

import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;

/** Dense durable lanes; transient Vector API objects never enter guest storage. */
public final class Int32X4 {
    public final int first;
    public final int second;
    public final int third;
    public final int fourth;
    public Int32X4(int first, int second, int third, int fourth) {
        this.first = first; this.second = second; this.third = third; this.fourth = fourth;
    }
    private IntVector vector() {
        return IntVector.broadcast(IntVector.SPECIES_128, first).withLane(1, second).withLane(2, third).withLane(3, fourth);
    }
    private static Int32X4 lanes(IntVector vector) {
        return new Int32X4(vector.lane(0), vector.lane(1), vector.lane(2), vector.lane(3));
    }
    /** Both index units access a complete vector; reject full-width indices before scaling. */
    private static int byteOffset(byte[] bytes, long index, boolean scalarOffset) {
        long size = bytes.length;
        long stride = scalarOffset ? 4 : 16;
        if (size < 16 || index < 0 || index > (size - 16) / stride)
            throw new RuntimeFault("Int32X4 ByteArray# range outside its backing storage");
        return (int) (index * stride);
    }
    /** Native-order lanes, indexed in Int32 elements or complete Int32X4 vectors. */
    public static Int32X4 readArray(byte[] bytes, long index, boolean scalarOffset) {
        int offset = byteOffset(bytes, index, scalarOffset);
        IntVector value = ByteVector.fromArray(ByteVector.SPECIES_128, bytes, offset).reinterpretAsInts();
        // Vector reinterpretation always groups bytes in little-endian order.
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) value = value.lanewise(VectorOperators.REVERSE_BYTES);
        return lanes(value);
    }
    public static void writeArray(byte[] bytes, long index, Int32X4 value, boolean scalarOffset) {
        int offset = byteOffset(bytes, index, scalarOffset);
        IntVector vector = value.vector();
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) vector = vector.lanewise(VectorOperators.REVERSE_BYTES);
        vector.reinterpretAsBytes().intoArray(bytes, offset);
    }
    public static Int32X4 add(Int32X4 a, Int32X4 b) { return lanes(a.vector().add(b.vector())); }
    public static Int32X4 subtract(Int32X4 a, Int32X4 b) { return lanes(a.vector().sub(b.vector())); }
    public static Int32X4 multiply(Int32X4 a, Int32X4 b) { return lanes(a.vector().mul(b.vector())); }
    public static Int32X4 negate(Int32X4 a) { return lanes(a.vector().neg()); }
}
