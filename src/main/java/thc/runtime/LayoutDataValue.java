// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Shared-carrier fallback; the class-owned base itself retains no owner field. */
public class LayoutDataValue extends DataValue {
    private final DataLayout layout;
    public LayoutDataValue(DataLayout layout, Object allocationKey) { super(layout, allocationKey); this.layout = layout; }
    @Override public final DataLayout getLayout() { return layout; }
}
