// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public final class ModifiedMutVar {
    private final Object old;
    private final Thunk result;
    public ModifiedMutVar(Object old, Thunk result) { this.old = old; this.result = result; }
    public Object getOld() { return old; }
    public Thunk getResult() { return result; }
}
