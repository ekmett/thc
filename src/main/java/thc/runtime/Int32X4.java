package thc.runtime;

import jdk.incubator.vector.IntVector;

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
    public static Int32X4 add(Int32X4 a, Int32X4 b) { return lanes(a.vector().add(b.vector())); }
    public static Int32X4 subtract(Int32X4 a, Int32X4 b) { return lanes(a.vector().sub(b.vector())); }
    public static Int32X4 multiply(Int32X4 a, Int32X4 b) { return lanes(a.vector().mul(b.vector())); }
    public static Int32X4 negate(Int32X4 a) { return lanes(a.vector().neg()); }
}
