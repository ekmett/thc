// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/** Expected class-owned layouts identify tags without touching any payload field. */
public final class DataTagFamily {
    @CompilationFinal(dimensions = 1) private final DataLayout[] layouts;

    public DataTagFamily(DataLayout[] layouts) { this.layouts = layouts; }

    @ExplodeLoop
    public long tag(DataValue value) {
        for (int index = 0; index < layouts.length; index++)
            if (layouts[index].matches(value)) return index;
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("dataToTag: constructor does not belong to the proven family");
    }
}
