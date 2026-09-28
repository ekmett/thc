// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public final class WeakResult {
    private final long flag;
    private final Object value;
    public WeakResult(long flag, Object value) { this.flag = flag; this.value = value; }
    public long getFlag() { return flag; }
    public Object getValue() { return value; }
}
