package thc.runtime;

import jdk.incubator.vector.FloatVector;

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
    public static FloatX4 add(FloatX4 a, FloatX4 b) { return new FloatX4(a.vector.add(b.vector)); }
    public static FloatX4 subtract(FloatX4 a, FloatX4 b) { return new FloatX4(a.vector.sub(b.vector)); }
    public static FloatX4 multiply(FloatX4 a, FloatX4 b) { return new FloatX4(a.vector.mul(b.vector)); }
}
