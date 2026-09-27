// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

final class ByteStringSort {
    private ByteStringSort() {}

    /** Equivalent to fps_sort's unsigned-byte qsort, preserving the caller's allocation.
     * Keep the variable-size scan outside partial evaluation, like other native leaves. */
    @TruffleBoundary
    public static void sort(ManagedAddress address, long count) {
        var allocation = address.nativeAllocation$org_intelligence_thc();
        try (var borrow = allocation == null ? null : allocation.borrow()) {
            if (address != ManagedAddress.Companion.nullAddress() || count != 0)
                address.requireByteRegion$org_intelligence_thc(count, true);
            if (count <= 1) return;
            long[] frequencies = new long[256];
            long position = 0;
            while (position < count) frequencies[(int) address.readWord8(position++)]++;
            position = 0;
            for (int value = 0; value < frequencies.length; value++) {
                long remaining = frequencies[value];
                while (remaining-- > 0) address.writeWord8(position++, value);
            }
        }
    }
}
