// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public record CapiCall(String unit, String symbol, boolean zeroArgument, boolean timeClock, boolean resolution) {
    public CapiCall(String unit, String symbol, boolean zeroArgument) {
        this(unit, symbol, zeroArgument, false, false);
    }
}
