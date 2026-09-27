// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;

/** Private v1 bridge codes. Public Haskell callers receive typed availability,
 * never a fabricated zero when a host service cannot supply a measurement. */
public final class RuntimeServiceStatus {
    public static final long UNSUPPORTED = -1L;
    public static final long DISABLED = -2L;
    public static final long DENIED = -3L;
    public static final long UNAVAILABLE = -4L;

    private RuntimeServiceStatus() {}

    /** Strings cross the scalar bridge as Unicode codepoints, not UTF-16 units.
     * Metadata is small and infrequently queried; no native buffer is borrowed. */
    public static long text(String value, long offset) {
        int length = value.codePointCount(0, value.length());
        if (offset == -1L) return length;
        if (offset < 0 || offset >= length) throw fault("Runtime service text offset out of bounds");
        return value.codePointAt(value.offsetByCodePoints(0, (int) offset));
    }

    static RuntimeFault fault(String message) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return new RuntimeFault(message);
    }
}
