// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class GcForeignExpression extends Expr {
    private final GcForeignOp op;
    @Children private Expr[] operands;

    GcForeignExpression(GcForeignOp op, Expr[] operands, CoreRepresentation proof) {
        this.op = op;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    private enum ResumeCompleted implements AstResumeStep {
        INSTANCE;
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (!RuntimeTypes.isUnit(input)) throw fault("Invalid completed GC/clock continuation");
            return null;
        }
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("GC/clock call requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (op == GcForeignOp.STATS) operands[0].executeRequiredAddress(frame);
        if (op == GcForeignOp.HEAP_HINT) {
            try {
                operands[0].executeLong(frame);
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) {
                throw propagate(failure);
            }
        }
        TupleResultsKt.requireVoidCarrier(operands[operands.length - 1].execute(frame));
        long value = op.invoke();
        if (op.getResult() != null) FrameAccess.INSTANCE.writeLong(frame, slots[offset], value);
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

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }
}
