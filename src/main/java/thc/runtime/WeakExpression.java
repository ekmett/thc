// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

final class WeakExpression extends Expr {
    private final WeakOp operation;
    @Children private Expr[] operands;
    @Child private Expr runner;
    WeakExpression(WeakOp operation, Expr[] operands, Expr runner) {
        this.operation = operation; this.operands = operands; this.runner = runner;
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("Weak# tuple requires a destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var first = operands[0].execute(frame);
        var registry = ManagedWeaks.current(this);
        if (operation == WeakOp.MAKE || operation == WeakOp.MAKE_PLAIN) {
            var value = operands[1].execute(frame);
            Object action = null;
            if (operation == WeakOp.MAKE) {
                action = operands[2].execute(frame);
                if (action == null) throw fault("mkWeak# requires a finalizer carrier");
            }
            TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
            FrameAccess.writeObject(frame, slots[offset], registry.make(first, value, action, runner == null ? null : runner.execute(frame)));
        } else if (operation == WeakOp.ADD_C_FINALIZER) {
            if (!(first instanceof ManagedAddress function)) throw fault("Expected a C function Addr#");
            var address = operands[1].executeRequiredAddress(frame);
            long flag;
            try { flag = operands[2].executeLong(frame); }
            catch (UnexpectedResultException failure) { throw propagate(failure); }
            var environment = operands[3].executeRequiredAddress(frame);
            var weak = operands[4].execute(frame);
            TupleResults.requireVoidCarrier(operands[5].execute(frame));
            var provider = Language.currentState(this).cbits();
            FrameAccess.writeLong(frame, slots[offset], registry.addCFinalizer(function, address, flag, environment, weak, provider));
        } else {
            TupleResults.requireVoidCarrier(operands[1].execute(frame));
            var result = operation == WeakOp.FINALIZE ? registry.finalize(first) : registry.dereference(first);
            FrameAccess.writeLong(frame, slots[offset], result.getFlag());
            FrameAccess.writeObject(frame, slots[offset + 1], result.getValue());
        }
        return null;
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
