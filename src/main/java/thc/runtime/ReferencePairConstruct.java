// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;

/** Two scalar reference fields need only the first value across a cut in the second. */
final class ReferencePairConstruct extends Expr {
    private final DataLayout layout;
    @Child private Expr first;
    @Child private Expr second;

    ReferencePairConstruct(DataLayout layout, Expr first, Expr second) {
        this.layout = layout;
        this.first = first;
        this.second = second;
        setRepresentation(new CoreRepresentation(CoreKind.DATA, true, false, null, null, null, null, null, null));
    }

    private static Object read(Expr operand, VirtualFrame frame) {
        var proof = operand.getRepresentation();
        if (proof.getEvaluated()) {
            if (proof.getKind() == CoreKind.DATA) return operand.executeRequiredDataValue(frame);
            if (proof.getKind() == CoreKind.CLOSURE) return operand.executeRequiredClosure(frame);
            if (proof.getKind() == CoreKind.ADDRESS) return operand.executeRequiredAddress(frame);
        }
        return operand.execute(frame);
    }

    @Override public DataValue execute(VirtualFrame frame) {
        Object head;
        try { head = read(first, frame); }
        catch (AstCapture cut) { throw afterFirst(cut); }
        catch (DelimitedCut cut) {
            CompilerDirectives.transferToInterpreter();
            throw cut.append(frame, (saved, input, ambient, outer) -> second(saved, input.get()));
        }
        return second(frame, head);
    }

    private DataValue second(VirtualFrame frame, Object head) {
        Object tail;
        try { tail = read(second, frame); }
        catch (AstCapture cut) { throw afterSecond(cut, head); }
        catch (DelimitedCut cut) {
            CompilerDirectives.transferToInterpreter();
            throw cut.append(frame, (saved, input, ambient, outer) -> construct(head, input.get()));
        }
        return construct(head, tail);
    }

    @TruffleBoundary private AstCapture afterFirst(AstCapture cut) {
        return cut.append(this::second);
    }

    @TruffleBoundary private AstCapture afterSecond(AstCapture cut, Object head) {
        return cut.append((saved, input) -> construct(head, input));
    }

    private DataValue construct(Object head, Object tail) {
        DataValue result = layout.allocate();
        layout.initialize(result, 0, head);
        layout.initialize(result, 1, tail);
        return result;
    }

    @Override public DataValue executeDataValue(VirtualFrame frame) { return execute(frame); }
}
