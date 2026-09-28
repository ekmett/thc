// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class SetThreadAllocationCounter extends Expr {
    @Child private Expr value, target, state;
    public SetThreadAllocationCounter(Expr value, Expr target, Expr state, CoreRepresentation proof) {
        this.value = value; this.target = target; this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        long counter = value.executeRequiredLong(frame);
        GuestThreads threads = GuestThreads.current(this);
        GuestThreadId identity;
        if (target == null) identity = threads.currentIdentity();
        else if (target.execute(frame) instanceof GuestThreadId id) identity = id;
        else throw RuntimeFault.fault("Allocation counter requires ThreadId#");
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        threads.setAllocationCounter(counter, identity);
        return kotlin.Unit.INSTANCE;
    }
}
