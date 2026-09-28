// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public final class MVarReadResult {
    private final boolean present;
    private final Object value;
    public MVarReadResult(boolean present, Object value) { this.present = present; this.value = value; }
    public boolean getPresent() { return present; }
    public Object getValue() { return value; }
}
