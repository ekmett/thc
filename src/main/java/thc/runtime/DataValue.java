// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/** Guest heap data with authenticated generated final-field storage, never an AST node. */
public class DataValue extends ValidatedStorage {
    public DataValue(DataLayout layout, Object allocationKey) { super(layout.checkAllocationKey(allocationKey)); }
    // StaticShape requires a concrete superclass method.
    @TruffleBoundary public DataLayout getLayout() { return ClassOwnedLayouts.resolve(getClass()); }
    @TruffleBoundary @Override public String toString() { return getLayout().describe(this); }
}
