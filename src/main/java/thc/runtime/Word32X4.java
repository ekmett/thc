package thc.runtime;

import jdk.incubator.vector.IntVector;

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
    public static Word32X4 add(Word32X4 a, Word32X4 b) { return lanes(a.vector().add(b.vector())); }
    public static Word32X4 subtract(Word32X4 a, Word32X4 b) { return lanes(a.vector().sub(b.vector())); }
    public static Word32X4 multiply(Word32X4 a, Word32X4 b) { return lanes(a.vector().mul(b.vector())); }
}
