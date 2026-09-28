// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class MakeStableName extends Expr {
    @Child private Expr value, state;
    public MakeStableName(Expr value, Expr state) { this.value = value; this.state = state; }
    @Override public Object execute(VirtualFrame frame) { throw new RuntimeFault("StableName# tuple requires a destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object referent = value.execute(frame);
        TupleResults.requireVoidCarrier(state.execute(frame));
        FrameAccess.INSTANCE.write(frame, slots[offset], StableNames.current(this).make(referent));
        return null;
    }
}
