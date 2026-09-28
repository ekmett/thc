// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.RuntimeServiceStatus.fault;

/** The unavailable DWARF backend reports failure, exactly as GHC's USE_LIBDW=0.
 * It neither captures a native stack nor writes a Location. Managed IPE is a
 * separate, available facility; a missing DWARF session is never fake success.
 */
final class OriginalLibdwExpression extends Expr {
    private final LibdwForeignOp operation;
    @Children private Expr[] operands;

    OriginalLibdwExpression(LibdwForeignOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Original libdw call requires a tuple destination");
    }

    @ExplodeLoop
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        // Native stubs ignore pointer contents, but evaluating the arguments and
        // checking their carriers remains required, including the State token.
        Expr[] firstOperands = operands;
        if (firstOperands == null) CompilerDirectives.transferToInterpreter();
        for (int index = 0; index < firstOperands.length - 1; index++) operands[index].executeRequiredAddress(frame);
        TupleResultsKt.requireVoidCarrier(operands[operands.length - 1].execute(frame));
        if (operation == LibdwForeignOp.LOOKUP) FrameAccess.INSTANCE.writeInt(frame, slots[offset], 1);
        else if (operation != LibdwForeignOp.CLEAR)
            FrameAccess.INSTANCE.writeObject(frame, slots[offset], ManagedAddress.nullAddress());
        return null;
    }
}
