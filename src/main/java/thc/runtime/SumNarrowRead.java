// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Only a selected sum arm may reinterpret the canonical native WordSlot.
 * No conversion is installed globally on Long-to-Int reads. */
final class SumNarrowRead extends Expr {
    private final int slot;
    private final NarrowInteger integer;

    SumNarrowRead(int slot, NarrowInteger integer, CoreRepresentation proof) {
        this.slot = slot;
        this.integer = integer;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
                proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
                proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) { return executeInt(frame); }
    @Override public int executeInt(VirtualFrame frame) { return integer.narrow((int) frame.getLong(slot)); }
}
