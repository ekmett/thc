// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

/** The unique erased guest state/void carrier, shared by every execution path. */
public final class Unit {
    public static final Unit INSTANCE = new Unit();

    private Unit() {}

    @Override
    public String toString() {
        // Preserve the existing observable diagnostic spelling across the port.
        return "kotlin.Unit";
    }
}
