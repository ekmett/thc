// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Original binding identity, never inferred from a display label. */
public record ManagedStackFunctionIdentity(String bindingId, String unitId, String moduleName, String occurrence) {
    public String getBindingId() { return bindingId; }
    public String getUnitId() { return unitId; }
    public String getModuleName() { return moduleName; }
    public String getOccurrence() { return occurrence; }
}

