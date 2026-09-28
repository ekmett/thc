// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class MakeStablePointer extends Expr {
    @Child private Expr value;
    @Child private Expr state;
    MakeStablePointer(Expr value, Expr state) { this.value = value; this.state = state; }
    @Override public Object execute(VirtualFrame frame) { throw fault("StablePtr# tuple requires a destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var stored = value.execute(frame);
        TupleResults.requireVoidCarrier(state.execute(frame));
        FrameAccess.INSTANCE.write(frame, slots[offset], StablePointers.current(this).make(stored));
        return null;
    }
}
