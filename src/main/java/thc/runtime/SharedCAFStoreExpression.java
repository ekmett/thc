// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeFault.fault;

public final class SharedCAFStoreExpression extends Expr {
    private final SharedCAFStore store;
    @Child private Expr pointer;
    @Child private Expr state;
    public SharedCAFStoreExpression(SharedCAFStore store, Expr pointer, Expr state) {
        this.store = store; this.pointer = pointer; this.state = state;
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("RTS shared-CAF tuple requires a destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var candidate = pointer.executeRequiredAddress(frame);
        TupleResults.requireVoidCarrier(state.execute(frame));
        FrameAccess.write(frame, slots[offset], StablePointers.current(this).getOrSetSharedCAF(store, candidate));
        return null;
    }
}
