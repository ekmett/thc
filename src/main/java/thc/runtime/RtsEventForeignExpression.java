// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class RtsEventForeignExpression extends Expr {
    private final RtsEventForeignOp op;
    @Children private Expr[] operands;

    RtsEventForeignExpression(RtsEventForeignOp op, Expr[] operands, CoreRepresentation proof) {
        this.op = op;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    private enum ResumeCompleted implements AstResumeStep {
        INSTANCE;
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (!RuntimeTypes.isUnit(input)) throw fault("Invalid completed RTS event continuation");
            return null;
        }
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("RTS event call requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        long count = op == RtsEventForeignOp.CAPABILITIES
            ? Integer.toUnsignedLong(operands[0].executeRequiredInt(frame)) : 0L;
        TupleResultsKt.requireVoidCarrier(operands[operands.length - 1].execute(frame));
        long value = op.invoke(this, count);
        if (op == RtsEventForeignOp.PROCESSORS) FrameAccess.INSTANCE.writeInt(frame, slots[offset], (int) value);
        else if (op.getResult() != null) FrameAccess.INSTANCE.writeLong(frame, slots[offset], value);
        if ("safe".equals(op.getSafety()) && AstControl.INSTANCE.enabled(this)) {
            boolean compiled = CompilerDirectives.inCompiledCode();
            var request = GuestThreads.pollCurrent(this, false);
            if (request != null) {
                request.compiledCapture = compiled;
                throw new AstCapture(request, SynchronousMasking.INSTANCE.current(this)).append(ResumeCompleted.INSTANCE);
            }
        }
        return null;
    }
}
