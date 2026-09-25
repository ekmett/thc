// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import jdk.incubator.vector.ShortVector;

/** Eight dense durable lanes; Vector API values are transient arithmetic intermediates. */
public final class Int16X8 {
    public final short first;
    public final short second;
    public final short third;
    public final short fourth;
    public final short fifth;
    public final short sixth;
    public final short seventh;
    public final short eighth;

    public Int16X8(short first, short second, short third, short fourth,
                   short fifth, short sixth, short seventh, short eighth) {
        this.first = first; this.second = second; this.third = third; this.fourth = fourth;
        this.fifth = fifth; this.sixth = sixth; this.seventh = seventh; this.eighth = eighth;
    }
    public static Int16X8 broadcast(short value) {
        return new Int16X8(value, value, value, value, value, value, value, value);
    }
    private ShortVector vector() {
        return ShortVector.broadcast(ShortVector.SPECIES_128, first)
            .withLane(1, second).withLane(2, third).withLane(3, fourth)
            .withLane(4, fifth).withLane(5, sixth).withLane(6, seventh).withLane(7, eighth);
    }
    private static Int16X8 lanes(ShortVector vector) {
        return new Int16X8(vector.lane(0), vector.lane(1), vector.lane(2), vector.lane(3),
            vector.lane(4), vector.lane(5), vector.lane(6), vector.lane(7));
    }
    public static Int16X8 add(Int16X8 a, Int16X8 b) { return lanes(a.vector().add(b.vector())); }
    public static Int16X8 subtract(Int16X8 a, Int16X8 b) { return lanes(a.vector().sub(b.vector())); }
    public static Int16X8 negate(Int16X8 a) { return lanes(a.vector().neg()); }
    public static Int16X8 multiply(Int16X8 a, Int16X8 b) { return lanes(a.vector().mul(b.vector())); }
    public static Int16X8 min(Int16X8 a, Int16X8 b) { return lanes(a.vector().min(b.vector())); }
    public static Int16X8 max(Int16X8 a, Int16X8 b) { return lanes(a.vector().max(b.vector())); }
}
