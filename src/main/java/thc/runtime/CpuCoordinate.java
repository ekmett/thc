// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** OS coordinates, not a logical capability or Windows CPU Set identifier. */
public record CpuCoordinate(int group, int processor) {
    public int getGroup() { return group; }
    public int getProcessor() { return processor; }
}
