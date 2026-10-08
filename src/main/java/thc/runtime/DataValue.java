// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import jam.vm.Lifted;

/** Guest heap data with authenticated generated final-field storage, never an AST node. */
public class DataValue extends ValidatedStorage implements Lifted {
    public DataValue(DataLayout layout, Object allocationKey) { super(layout.checkAllocationKey(allocationKey)); }
    /** Constructors have no computation to replace. */
    @Override public final Lifted resolve() { return null; }
    /** Return an existing logical reference field without forcing or boxing it. */
    @Override public final Lifted project(int field) { return getLayout().project(this, field); }
    // StaticShape requires a concrete superclass method.
    @TruffleBoundary public DataLayout getLayout() { return ClassOwnedLayouts.resolve(getClass()); }
    @TruffleBoundary @Override public String toString() { return getLayout().describe(this); }
}
