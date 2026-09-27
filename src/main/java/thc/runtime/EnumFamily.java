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
        if (tag < 0 || tag >= values.length) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("tagToEnum#: tag out of range: " + tag + " (family size " + values.length + ")");
        }
        return values[(int) tag];
    }
}
