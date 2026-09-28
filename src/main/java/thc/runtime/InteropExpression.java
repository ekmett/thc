// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/** Raw object/library values are unlifted; ordinary lowering owns any compatibility-handle demand. */
final class InteropExpression extends Expr {
    private static final AstResumeStep COMPLETED_STATE = (frame, input) -> Unit.INSTANCE;
    private final PolyglotOp operation;
    @Children private Expr[] arguments;
    @Child private InteropLibraryAcquisition acquisition;
    @Child private InteropAccess access;

    InteropExpression(PolyglotOp operation, Expr[] arguments) {
        this.operation = operation; this.arguments = arguments;
        if (operation == PolyglotOp.GET_LIBRARY) acquisition = new InteropLibraryAcquisition();
        else access = new InteropAccess();
    }
    @ExplodeLoop private Object[] arguments(VirtualFrame frame) {
        Object[] values = new Object[arguments.length];
        for (int i = 0; i < values.length; i++) values[i] = arguments[i].execute(frame);
        return values;
    }
    @Override public Object execute(VirtualFrame frame) {
        if (operation == PolyglotOp.GET_LIBRARY) return acquisition.execute(arguments[0].execute(frame));
        if (!operation.scalarResult()) throw RuntimeFault.fault("Interop message requires a tuple destination");
        Object answer = access.execute(operation, arguments(frame));
        try { AstForeignCompleted.poll(this); }
        catch (AstCapture capture) { throw capture.append(COMPLETED_STATE); }
        return answer;
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object answer = access.execute(operation, arguments(frame));
        switch (operation.getResult()) {
            case "Int8Rep" -> FrameAccess.writeInt(frame, slots[offset], (Integer) answer);
            case "IntRep", "Int64Rep" -> FrameAccess.writeLong(frame, slots[offset], (Long) answer);
            default -> FrameAccess.write(frame, slots[offset], answer);
        }
        AstForeignCompleted.poll(this);
        return null;
    }
}
