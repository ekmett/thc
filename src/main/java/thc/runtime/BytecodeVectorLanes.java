// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.bytecode.LocalAccessor;

/** Parse-time lane destinations keep wide instructions below JVM method-size limits. */
public final class BytecodeVectorLanes {
    @CompilationFinal(dimensions = 1) private final LocalAccessor[] slots;

    public BytecodeVectorLanes(LocalAccessor[] slots) { this.slots = slots; }
    public LocalAccessor[] getSlots() { return slots; }
}
