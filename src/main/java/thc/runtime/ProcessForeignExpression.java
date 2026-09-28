// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

final class ProcessForeignExpression extends Expr {
    private final ProcessOp operation;
    @Children private Expr[] operands;

    ProcessForeignExpression(ProcessOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Original process call requires its State/result tuple");
    }

    @ExplodeLoop
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object[] values = new Object[operands.length];
        for (int index = 0; index < values.length; index++) {
            String argument = operation.getArguments().get(index);
            if ("Int32Rep".equals(argument)) values[index] = (long) operands[index].executeRequiredInt(frame);
            else if ("AddrRep".equals(argument)) values[index] = operands[index].executeRequiredAddress(frame);
            else values[index] = operands[index].execute(frame);
        }
        long result = ManagedProcessForeign.current(this).invoke(operation, values, this);
        long errno = Language.currentState(this).getStdio().errno();
        FrameAccess.INSTANCE.writeInt(frame, slots[offset], (int) result);
        if (operation == ProcessOp.WAIT) AstForeignCompleted.poll(this, errno, result == -1L && errno == 4L);
        return null;
    }
}
