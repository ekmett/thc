// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Recursive indirection: captured by identity and initialized once. */
public final class RecCell {
    private volatile boolean initialized;
    private volatile Object value;
    public boolean getInitialized() { return initialized; }
    public void setInitialized(boolean initialized) { this.initialized = initialized; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
}
