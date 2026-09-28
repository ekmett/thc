// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Scheduling cut owned by the current guest entry, never an async delivery. */
public final class AstStackSpill {
    public static final AstStackSpill INSTANCE = new AstStackSpill();
    private AstStackSpill() {}
}
