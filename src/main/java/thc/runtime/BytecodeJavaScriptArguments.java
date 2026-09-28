// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
/** Bytecode operands stay in typed locals until the interop protocol needs them. */
public final class BytecodeJavaScriptArguments {
    private final JavaScriptImport declaration;
    @CompilationFinal(dimensions = 1) private final LocalAccessor[] slots;
    private final LocalAccessor state;
    public BytecodeJavaScriptArguments(JavaScriptImport declaration, LocalAccessor[] slots, LocalAccessor state) {
        this.declaration = declaration; this.slots = slots; this.state = state;
    }
    public JavaScriptImport getDeclaration() { return declaration; }
    @ExplodeLoop public Object[] read(BytecodeNode bytecode, VirtualFrame frame) {
        var values = new Object[slots.length];
        try {
            for (int i = 0; i < slots.length; i++) values[i] = switch (declaration.getArguments()[i]) {
                case LONG -> slots[i].getLong(bytecode, frame);
                case DOUBLE -> slots[i].getDouble(bytecode, frame);
                default -> throw RuntimeFault.fault("Invalid JavaScript argument type");
            };
        } catch (UnexpectedResultException error) { throw propagate(error); }
        return values;
    }
    public Object state(BytecodeNode bytecode, VirtualFrame frame) {
        return state.getObject(bytecode, frame);
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
