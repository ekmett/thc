// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
public enum ManagedAddressOrder {
    LT, LE, GT, GE;
    public boolean accepts(int comparison) {
        return this == LT ? comparison < 0 : this == LE ? comparison <= 0 : this == GT ? comparison > 0 : comparison >= 0;
    }
}
