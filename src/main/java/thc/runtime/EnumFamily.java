// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/** Selection reuses the same class-owned nullary values as ordinary construction. */
public final class EnumFamily {
    @CompilationFinal(dimensions = 1) private final DataValue[] values;

    public EnumFamily(DataValue[] values) { this.values = values; }

    public DataValue select(long tag) {
        return values[index(tag, values.length)];
    }
    static int index(long tag, int size) {
        if (tag < 0 || tag >= size) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("tagToEnum#: tag out of range: " + tag + " (family size " + size + ")");
        }
        return (int) tag;
    }
}
