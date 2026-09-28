// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class CpuAffinityQuery extends Expr {
    private final boolean applied;
    @Child private Expr state;

    CpuAffinityQuery(boolean applied, Expr state) {
        this.applied = applied;
        this.state = state;
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("CPU-affinity query requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        TupleResults.requireVoidCarrier(state.execute(frame));
        var threads = GuestThreads.current(this);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset], applied ? (threads.currentIdentity().getAffinityApplied() ? 1 : 0) :
            threads.getCpuAffinity().getMode().ordinal());
        return null;
    }
}
