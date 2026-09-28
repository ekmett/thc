// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class PolyglotExpression extends Expr {
    private final PolyglotOp operation;
    @Children private Expr[] arguments;
    @Child private PolyglotAccess access = new PolyglotAccess();
    public PolyglotExpression(PolyglotOp operation, Expr[] arguments) { this.operation = operation; this.arguments = arguments; }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("Polyglot IO requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        switch (operation) {
            case EVAL -> {
                var language = arguments[0].executeRequiredAddress(frame);
                var source = arguments[1].executeRequiredAddress(frame);
                var name = arguments[2].executeRequiredAddress(frame);
                var state = arguments[3].execute(frame);
                FrameAccess.write(frame, slots[offset], access.eval(language, source, name, state));
            }
            case READ_MEMBER -> {
                var value = arguments[0].execute(frame);
                var name = arguments[1].executeRequiredAddress(frame);
                var state = arguments[2].execute(frame);
                FrameAccess.write(frame, slots[offset], access.readMember(frame, value, name, state));
            }
            case EXECUTE_INT -> {
                var value = arguments[0].execute(frame);
                long input = arguments[1].executeRequiredLong(frame);
                var state = arguments[2].execute(frame);
                FrameAccess.writeLong(frame, slots[offset], access.executeInt(frame, value, input, state));
            }
        }
        AstForeignCompleted.poll(this);
        return null;
    }
}
