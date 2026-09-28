// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** The State component is erased; the payload retains its exact address/long carrier. */
final class ManagedFileExpression extends Expr {
    private final ManagedFileOp operation;
    @Children private Expr[] operands;

    ManagedFileExpression(ManagedFileOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Managed file call requires a State/result tuple destination");
    }

    private ManagedFiles files(VirtualFrame frame) {
        TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
        return Language.currentState(this).getFiles();
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (operation == ManagedFileOp.ERROR_MESSAGE) {
            FrameAccess.INSTANCE.write(frame, slots[offset], files(frame).errorMessage());
            AstForeignCompleted.poll(this);
            return null;
        }
        // Direct enum comparisons keep incompatible frame paths separate during partial evaluation.
        long result;
        if (operation == ManagedFileOp.OPEN) {
            var path = operands[0].executeRequiredAddress(frame);
            long mode = operands[1].executeRequiredLong(frame);
            result = files(frame).open(path, mode, ForeignSafety.SAFE);
        } else if (operation == ManagedFileOp.READ || operation == ManagedFileOp.WRITE) {
            long fd = operands[0].executeRequiredLong(frame);
            var address = operands[1].executeRequiredAddress(frame);
            long count = operands[2].executeRequiredLong(frame);
            var files = files(frame);
            result = operation == ManagedFileOp.READ ? files.read(fd, address, count, ForeignSafety.SAFE)
                : files.write(fd, address, count, ForeignSafety.SAFE);
        } else if (operation == ManagedFileOp.CLOSE) {
            long fd = operands[0].executeRequiredLong(frame);
            result = files(frame).close(fd, ForeignSafety.SAFE);
        } else if (operation == ManagedFileOp.ERROR_KIND) {
            result = files(frame).errorKind();
        } else if (operation == ManagedFileOp.SEEK) {
            long fd = operands[0].executeRequiredLong(frame);
            long displacement = operands[1].executeRequiredLong(frame);
            long mode = operands[2].executeRequiredLong(frame);
            result = files(frame).seek(fd, displacement, mode, ForeignSafety.SAFE);
        } else if (operation == ManagedFileOp.SIZE) {
            long fd = operands[0].executeRequiredLong(frame);
            result = files(frame).size(fd, ForeignSafety.SAFE);
        } else if (operation == ManagedFileOp.SET_SIZE) {
            long fd = operands[0].executeRequiredLong(frame);
            long length = operands[1].executeRequiredLong(frame);
            result = files(frame).setSize(fd, length, ForeignSafety.SAFE);
        } else if (operation == ManagedFileOp.IS_TERMINAL) {
            long fd = operands[0].executeRequiredLong(frame);
            result = files(frame).isTerminal(fd, ForeignSafety.SAFE);
        } else if (operation == ManagedFileOp.DEVICE_TYPE) {
            long fd = operands[0].executeRequiredLong(frame);
            result = files(frame).deviceType(fd, ForeignSafety.SAFE);
        } else {
            throw new IllegalStateException("Address result handled above");
        }
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], result);
        AstForeignCompleted.poll(this);
        return null;
    }
}
