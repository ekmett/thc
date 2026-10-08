// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Lazy spark transport and context-owned queue queries. */
public final class SparkResult extends Expr {
    private final String name;
    @Child private Expr state, payload;
    private final DataValue empty;
    private final int programSlot, constructorIndex;
    public SparkResult(String name, Expr state, Expr payload, DataValue empty, CoreRepresentation proof) {
        this.name = name; this.state = state; this.payload = payload; this.empty = empty;
        programSlot = constructorIndex = -1;
        setRepresentation(proof.withEvaluated(true));
    }
    SparkResult(String name, Expr state, Expr payload, CoreRepresentation proof, int programSlot, int constructorIndex) {
        this.name = name; this.state = state; this.payload = payload; this.empty = null;
        this.programSlot = programSlot; this.constructorIndex = constructorIndex; setRepresentation(proof.withEvaluated(true));
    }
    @Override public Object execute(VirtualFrame frame) {
        if (!name.equals("par#")) throw RuntimeFault.fault("Spark result requires a tuple destination");
        var pool = SparkPool.current(this);
        if (pool.isEnabled()) pool.hint(this, payload.execute(frame));
        return 1L;
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object value = payload == null ? null : payload.execute(frame); // Retain a thunk, never enter it.
        TupleResults.requireVoidCarrier(state.execute(frame));
        var pool = SparkPool.current(this);
        if (name.equals("spark#")) {
            pool.hint(this, value);
            FrameAccess.write(frame, slots[offset], value);
        } else if (name.equals("numSparks#")) FrameAccess.writeLong(frame, slots[offset], pool.count());
        else {
            var thunk = pool.poll();
            FrameAccess.writeLong(frame, slots[offset], thunk == null ? 0L : 1L);
            FrameAccess.write(frame, slots[offset + 1], thunk == null ? programSlot < 0 ? empty : Program.instance(frame, programSlot).constructorLayout(constructorIndex).allocate() : thunk);
        }
        return null;
    }
}
