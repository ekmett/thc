// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import jdk.incubator.vector.*;

/** Integer SIMD has no division instruction on the pinned target.
 * These residual loops preserve unsigned lane arithmetic without exploding
 * divisions and reconstructions beyond the guest compiler's code-size limit. */
public final class VectorIntegerDivision {
    private VectorIntegerDivision() {}
    @TruffleBoundary
    public static ByteVector quotByte(ByteVector left, ByteVector right, boolean unsigned) {
        var result = left;
        for (int lane = 0; lane < left.length(); lane++) {
            long a = left.lane(lane);
            long b = right.lane(lane);
            long value = unsigned ? (a & 0xffL) / (b & 0xffL) : a / b;
            result = result.withLane(lane, (byte) value);
        }
        return result;
    }

    @TruffleBoundary
    public static ByteVector remByte(ByteVector left, ByteVector right, boolean unsigned) {
        var result = left;
        for (int lane = 0; lane < left.length(); lane++) {
            long a = left.lane(lane);
            long b = right.lane(lane);
            long value = unsigned ? (a & 0xffL) % (b & 0xffL) : a % b;
            result = result.withLane(lane, (byte) value);
        }
        return result;
    }

    @TruffleBoundary
    public static ShortVector quotShort(ShortVector left, ShortVector right, boolean unsigned) {
        var result = left;
        for (int lane = 0; lane < left.length(); lane++) {
            long a = left.lane(lane);
            long b = right.lane(lane);
            long value = unsigned ? (a & 0xffffL) / (b & 0xffffL) : a / b;
            result = result.withLane(lane, (short) value);
        }
        return result;
    }

    @TruffleBoundary
    public static ShortVector remShort(ShortVector left, ShortVector right, boolean unsigned) {
        var result = left;
        for (int lane = 0; lane < left.length(); lane++) {
            long a = left.lane(lane);
            long b = right.lane(lane);
            long value = unsigned ? (a & 0xffffL) % (b & 0xffffL) : a % b;
            result = result.withLane(lane, (short) value);
        }
        return result;
    }

    @TruffleBoundary
    public static IntVector quotInt(IntVector left, IntVector right, boolean unsigned) {
        var result = left;
        for (int lane = 0; lane < left.length(); lane++) {
            long a = left.lane(lane);
            long b = right.lane(lane);
            long value = unsigned ? (a & 0xffff_ffffL) / (b & 0xffff_ffffL) : a / b;
            result = result.withLane(lane, (int) value);
        }
        return result;
    }

    @TruffleBoundary
    public static IntVector remInt(IntVector left, IntVector right, boolean unsigned) {
        var result = left;
        for (int lane = 0; lane < left.length(); lane++) {
            long a = left.lane(lane);
            long b = right.lane(lane);
            long value = unsigned ? (a & 0xffff_ffffL) % (b & 0xffff_ffffL) : a % b;
            result = result.withLane(lane, (int) value);
        }
        return result;
    }

    @TruffleBoundary
    public static LongVector quotLong(LongVector left, LongVector right, boolean unsigned) {
        var result = left;
        for (int lane = 0; lane < left.length(); lane++) {
            long a = left.lane(lane);
            long b = right.lane(lane);
            long value = unsigned ? Long.divideUnsigned(a, b) : a / b;
            result = result.withLane(lane, value);
        }
        return result;
    }

    @TruffleBoundary
    public static LongVector remLong(LongVector left, LongVector right, boolean unsigned) {
        var result = left;
        for (int lane = 0; lane < left.length(); lane++) {
            long a = left.lane(lane);
            long b = right.lane(lane);
            long value = unsigned ? Long.remainderUnsigned(a, b) : a % b;
            result = result.withLane(lane, value);
        }
        return result;
    }
}
