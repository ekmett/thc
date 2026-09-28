// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

/** The C calls return the logical singleton State tuple, with no physical fields. */
final class Md5ForeignExpression extends Expr {
    private final Md5ForeignOp operation;
    @Children private Expr[] operands;

    Md5ForeignExpression(Md5ForeignOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("MD5 foreign call requires a State tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var first = operands[0].executeRequiredAddress(frame);
        switch (operation) {
            case INIT -> {
                ManagedByteArray.requireState(operands[1].execute(frame));
                ManagedMd5.INSTANCE.init(first);
            }
            case UPDATE -> {
                var input = operands[1].executeRequiredAddress(frame);
                long length = operands[2].executeRequiredInt(frame);
                ManagedByteArray.requireState(operands[3].execute(frame));
                ManagedMd5.INSTANCE.update(first, input, length);
            }
            case FINAL -> {
                var context = operands[1].executeRequiredAddress(frame);
                ManagedByteArray.requireState(operands[2].execute(frame));
                ManagedMd5.INSTANCE.finish(first, context);
            }
        }
        return null;
    }
}
