// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public interface NativeCpuAffinity {
    int getCount();
    CpuAffinityMode getMode();
    default CpuCoordinate coordinate(int index) { return null; }
    AutoCloseable bindCurrent(int index);
    AutoCloseable resetCurrent();
}
