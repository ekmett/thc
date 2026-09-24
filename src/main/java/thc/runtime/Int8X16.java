// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import jdk.incubator.vector.ByteVector;

/** Sixteen dense durable lanes; Vector API values are transient arithmetic intermediates. */
public final class Int8X16 {
    public final byte first;
    public final byte second;
    public final byte third;
    public final byte fourth;
    public final byte fifth;
    public final byte sixth;
    public final byte seventh;
    public final byte eighth;
    public final byte ninth;
    public final byte tenth;
    public final byte eleventh;
    public final byte twelfth;
    public final byte thirteenth;
    public final byte fourteenth;
    public final byte fifteenth;
    public final byte sixteenth;

    public Int8X16(byte first, byte second, byte third, byte fourth,
                   byte fifth, byte sixth, byte seventh, byte eighth,
                   byte ninth, byte tenth, byte eleventh, byte twelfth,
                   byte thirteenth, byte fourteenth, byte fifteenth, byte sixteenth) {
        this.first = first; this.second = second; this.third = third; this.fourth = fourth;
        this.fifth = fifth; this.sixth = sixth; this.seventh = seventh; this.eighth = eighth;
        this.ninth = ninth; this.tenth = tenth; this.eleventh = eleventh; this.twelfth = twelfth;
        this.thirteenth = thirteenth; this.fourteenth = fourteenth; this.fifteenth = fifteenth; this.sixteenth = sixteenth;
    }
    public static Int8X16 broadcast(byte value) {
        return new Int8X16(value, value, value, value, value, value, value, value, value, value, value, value, value, value, value, value);
    }
    private ByteVector vector() {
        return ByteVector.broadcast(ByteVector.SPECIES_128, first)
            .withLane(1, second).withLane(2, third).withLane(3, fourth).withLane(4, fifth)
            .withLane(5, sixth).withLane(6, seventh).withLane(7, eighth).withLane(8, ninth)
            .withLane(9, tenth).withLane(10, eleventh).withLane(11, twelfth).withLane(12, thirteenth)
            .withLane(13, fourteenth).withLane(14, fifteenth).withLane(15, sixteenth);
    }
    private static Int8X16 lanes(ByteVector vector) {
        return new Int8X16(vector.lane(0), vector.lane(1), vector.lane(2), vector.lane(3),
            vector.lane(4), vector.lane(5), vector.lane(6), vector.lane(7),
            vector.lane(8), vector.lane(9), vector.lane(10), vector.lane(11),
            vector.lane(12), vector.lane(13), vector.lane(14), vector.lane(15));
    }
    public static Int8X16 add(Int8X16 a, Int8X16 b) { return lanes(a.vector().add(b.vector())); }
    public static Int8X16 subtract(Int8X16 a, Int8X16 b) { return lanes(a.vector().sub(b.vector())); }
    public static Int8X16 negate(Int8X16 a) { return lanes(a.vector().neg()); }
    /** Low-eight-bit products; hardware lowering is verified separately for the pinned target. */
    public static Int8X16 multiply(Int8X16 a, Int8X16 b) { return lanes(a.vector().mul(b.vector())); }
}
