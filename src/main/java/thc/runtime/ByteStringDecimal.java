// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;

/** Writes into caller-owned storage, without a terminator or ownership transfer. */
final class ByteStringDecimal {
    private ByteStringDecimal() {}

    public static ManagedAddress signed(long value, ManagedAddress destination) {
        var allocation = destination.nativeAllocation();
        var borrow = allocation == null ? null : allocation.borrow();
        Throwable failure = null;
        try {
            // Stay nonpositive so Long.MIN_VALUE never needs an overflowing negation.
            long remaining = value > 0 ? -value : value;
            long probe = remaining;
            long width = value < 0 ? 2 : 1;
            while (probe <= -10) { width++; probe /= 10; }
            destination.requireByteRegion(width, true);
            if (value < 0) destination.writeWord8(0, 45);
            long position = width;
            do {
                destination.writeWord8(--position, 48 - remaining % 10);
                remaining /= 10;
            } while (remaining != 0);
            return destination.plus(width);
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            if (borrow != null) borrow.closeAfter(failure);
        }
    }

    public static void padded18(long value, ManagedAddress destination) {
        if (value < 0 || value >= 1_000_000_000_000_000_000L) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("ByteString padded18 requires 0 <= value < 10^18");
        }
        var allocation = destination.nativeAllocation();
        var borrow = allocation == null ? null : allocation.borrow();
        Throwable failure = null;
        try {
            destination.requireByteRegion(18, true);
            long remaining = value;
            long position = 18;
            while (position > 0) {
                destination.writeWord8(--position, 48 + remaining % 10);
                remaining /= 10;
            }
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            if (borrow != null) borrow.closeAfter(failure);
        }
    }
}
