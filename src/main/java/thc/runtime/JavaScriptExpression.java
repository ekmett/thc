// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
public final class JavaScriptExpression extends Expr {
    private final JavaScriptImport declaration;
    @Children private Expr[] arguments;
    @Child private JavaScriptAccess access;
    public JavaScriptExpression(JavaScriptImport declaration, Expr[] arguments) {
        this.declaration = declaration; this.arguments = arguments; access = new JavaScriptAccess(declaration);
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("JavaScript IO requires a tuple destination"); }
    @ExplodeLoop @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var values = new Object[declaration.getArguments().length];
        for (int i = 0; i < values.length; i++) values[i] = switch (declaration.getArguments()[i]) {
            case LONG -> arguments[i].executeRequiredLong(frame);
            case DOUBLE -> arguments[i].executeRequiredDouble(frame);
            default -> throw RuntimeFault.fault("Invalid JavaScript argument type");
        };
        var state = arguments[arguments.length - 1].execute(frame);
        switch (declaration.getResult()) {
            case LONG -> FrameAccess.writeLong(frame, slots[offset], access.executeLong(values, state));
            case DOUBLE -> FrameAccess.writeDouble(frame, slots[offset], access.executeDouble(values, state));
            case VOID -> access.executeVoid(values, state);
            default -> throw RuntimeFault.fault("Invalid JavaScript result type");
        }
        if (declaration.getSafety() == ForeignSafety.SAFE) AstForeignCompleted.poll(this);
        return null;
    }
}
