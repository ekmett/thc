// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

public final class BytecodeInputSource extends InputSource {
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final LocalAccessor[] slots;
    public BytecodeInputSource(ArgumentLayout layout, LocalAccessor[] slots) { super(layout); this.slots = slots; }
    public LocalAccessor[] getSlots() { return slots; }
    private BytecodeNode bytecode(Node node) { return ((BytecodeRoot) java.util.Objects.requireNonNull(node.getRootNode(), "null cannot be cast to non-null type thc.runtime.BytecodeRoot")).getBytecodeNode(); }
    @SuppressWarnings("unchecked") private static <E extends Throwable, T> T propagate(Throwable error) throws E { throw (E) error; }
    @Override public int readInt(VirtualFrame frame, Node node, Object[] values, int index) {
        try {
            if (physicalProofs[index].isInt()) return slots[index].getInt(bytecode(node), frame);
        if (slots[index].getObject(bytecode(node), frame) instanceof Integer value) return value;
        throw fault("Expected primitive Int input");
        } catch (UnexpectedResultException error) { return propagate(error); }
    }
    @Override public long readLong(VirtualFrame frame, Node node, Object[] values, int index) {
        try {
            if (physicalProofs[index].isLong()) return slots[index].getLong(bytecode(node), frame);
        if (slots[index].getObject(bytecode(node), frame) instanceof Long value) return value;
        throw fault("Expected primitive Long input");
        } catch (UnexpectedResultException error) { return propagate(error); }
    }
    @Override public float readFloat(VirtualFrame frame, Node node, Object[] values, int index) {
        try {
            if (physicalProofs[index].isFloat()) return slots[index].getFloat(bytecode(node), frame);
        if (slots[index].getObject(bytecode(node), frame) instanceof Float value) return value;
        throw fault("Expected primitive Float input");
        } catch (UnexpectedResultException error) { return propagate(error); }
    }
    @Override public double readDouble(VirtualFrame frame, Node node, Object[] values, int index) {
        try {
            if (physicalProofs[index].isDouble()) return slots[index].getDouble(bytecode(node), frame);
        if (slots[index].getObject(bytecode(node), frame) instanceof Double value) return value;
        throw fault("Expected primitive Double input");
        } catch (UnexpectedResultException error) { return propagate(error); }
    }
    @Override public Object reference(VirtualFrame frame, Node node, Object[] values, int index) { return slots[index].getObject(bytecode(node), frame); }
    @Override public void setReference(VirtualFrame frame, Node node, Object[] values, int index, Object value) { slots[index].setObject(bytecode(node), frame, value); }
}
