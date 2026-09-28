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

    public BytecodeProcessArguments(ProcessOp operation, LocalAccessor[] slots) {
        this.operation = operation;
        this.slots = slots;
    }

    public ProcessOp getOperation() { return operation; }

    @ExplodeLoop
    public Object[] read(BytecodeNode bytecode, VirtualFrame frame) {
        Object[] values = new Object[slots.length];
        for (int index = 0; index < values.length; index++) {
            if ("Int32Rep".equals(operation.getArguments().get(index))) {
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
