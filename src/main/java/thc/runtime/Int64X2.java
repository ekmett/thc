package thc.runtime;

import jdk.incubator.vector.LongVector;

/** Exact guest lane storage. Vector API payload arrays never become guest values. */
public final class Int64X2 {
    public final long first;
    public final long second;
    public Int64X2(long first, long second) { this.first = first; this.second = second; }
    private LongVector vector() { return LongVector.broadcast(LongVector.SPECIES_128, first).withLane(1, second); }
    private static Int64X2 lanes(LongVector vector) { return new Int64X2(vector.lane(0), vector.lane(1)); }
    public static Int64X2 add(Int64X2 a, Int64X2 b) { return lanes(a.vector().add(b.vector())); }
    public static Int64X2 subtract(Int64X2 a, Int64X2 b) { return lanes(a.vector().sub(b.vector())); }
    public static Int64X2 negate(Int64X2 a) { return lanes(a.vector().neg()); }
}
