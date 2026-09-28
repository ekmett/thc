// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

/** THC discards speculative hints; it never evaluates or queues their payloads. */
public final class SparkResult extends Expr {
    private final String name;
    @Child private Expr state, payload;
    private final DataValue empty;
    public SparkResult(String name, Expr state, Expr payload, DataValue empty, CoreRepresentation proof) {
        this.name = name; this.state = state; this.payload = payload; this.empty = empty;
        setRepresentation(proof.withEvaluated(true));
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("Spark result requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object value = payload == null ? null : payload.execute(frame); // Retain a thunk, never enter it.
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        if (name.equals("spark#")) FrameAccess.write(frame, slots[offset], value);
        else {
            FrameAccess.writeLong(frame, slots[offset], 0L);
            if (name.equals("getSpark#")) FrameAccess.write(frame, slots[offset + 1], empty);
        }
        return null;
    }
}
