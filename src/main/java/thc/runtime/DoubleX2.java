package thc.runtime;

import jdk.incubator.vector.DoubleVector;

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
    public static DoubleX2 add(DoubleX2 a, DoubleX2 b) { return new DoubleX2(a.vector.add(b.vector)); }
    public static DoubleX2 subtract(DoubleX2 a, DoubleX2 b) { return new DoubleX2(a.vector.sub(b.vector)); }
    public static DoubleX2 multiply(DoubleX2 a, DoubleX2 b) { return new DoubleX2(a.vector.mul(b.vector)); }
}
