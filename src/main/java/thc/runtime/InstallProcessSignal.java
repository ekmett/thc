// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class InstallProcessSignal extends Expr {
    @Children private Expr[] operands;

    InstallProcessSignal(Expr[] operands, CoreRepresentation proof) {
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Signal installation requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        long signal = operands[0].executeRequiredInt(frame);
        long action = operands[1].executeRequiredInt(frame);
        var mask = operands[2].executeRequiredAddress(frame);
        TupleResults.requireVoidCarrier(operands[3].execute(frame));
        FrameAccess.INSTANCE.writeInt(frame, slots[offset], (int) ManagedSignals.install(this, signal, action, mask));
        return null;
    }
}
