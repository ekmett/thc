// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class BoundThreadSupport extends Expr {
    @Child private Expr state;
    private final boolean allocationCounter;

    BoundThreadSupport(Expr state, boolean allocationCounter) {
        this.state = state;
        this.allocationCounter = allocationCounter;
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Bound-thread support query requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        TupleResults.requireVoidCarrier(state.execute(frame));
        // Bound-thread support stays false; the distinct counter query samples this guest thread.
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], allocationCounter ? GuestThreads.current(this).allocationCounter() : 0L);
        return null;
    }
}
