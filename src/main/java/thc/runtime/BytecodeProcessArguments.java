// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/** Typed operand locals survive evaluation exactly once, before native effects. */
public final class BytecodeProcessArguments {
    private final ProcessOp operation;
    @CompilationFinal(dimensions = 1) private final LocalAccessor[] slots;
    @CompilationFinal(dimensions = 1) private final boolean[] intArguments;

    public BytecodeProcessArguments(ProcessOp operation, LocalAccessor[] slots) {
        this.operation = operation;
        this.slots = slots;
        var arguments = operation.getArguments();
        intArguments = new boolean[arguments.size()];
        for (int index = 0; index < intArguments.length; index++)
            intArguments[index] = "Int32Rep".equals(arguments.get(index));
    }

    public ProcessOp getOperation() { return operation; }

    @ExplodeLoop
    public Object[] read(BytecodeNode bytecode, VirtualFrame frame) {
        Object[] values = new Object[slots.length];
        for (int index = 0; index < values.length; index++) {
            if (intArguments[index]) {
                try {
                    values[index] = (long) slots[index].getInt(bytecode, frame);
                } catch (UnexpectedResultException failure) {
                    throw propagate(failure);
                }
            } else values[index] = slots[index].getObject(bytecode, frame);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }
}
