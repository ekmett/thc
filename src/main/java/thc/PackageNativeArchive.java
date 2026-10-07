// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import thc.runtime.CoreForeignOverride;

/** A retained obligation is a prohibition, never a foreign execution capability. */
public final class PackageNativeArchive {
    private final String detail, unit;
    private final boolean wholeModule;
    private final List<Map<?,?>> excluded;
    public PackageNativeArchive(String detail, boolean wholeModule, String unit, List<Map<?,?>> excluded) {
        this.detail = detail; this.wholeModule = wholeModule; this.unit = unit; this.excluded = excluded;
    }
    public String getDetail() { return detail; }
    public boolean getWholeModule() { return wholeModule; }
    public List<Map<?,?>> getExcluded() { return excluded; }
    public boolean blocks(Object binding) {
        if (wholeModule) return true;
        for (var call : CoreCallInventory.mapCalls(binding)) {
            if (!(call.get("target") instanceof Map<?,?> target) || !Objects.equals(target.get("unit"), unit)) continue;
            // Archive obligations forbid native execution, not an existing
            // context-owned operation. Both lowerers still validate the exact
            // call ABI before creating that operation; this grants no native
            // capability and cannot discharge another call's obligations.
            for (var emitted : excluded) if (Objects.equals(target.get("symbol"), emitted.get("symbol")) &&
                    Objects.equals(call.get("convention"), emitted.get("convention")) &&
                    Objects.equals(call.get("safety"), emitted.get("safety")) &&
                    CoreForeignOverride.select(Map.of("foreignCall", call)) == null) return true;
        }
        return false;
    }
}
