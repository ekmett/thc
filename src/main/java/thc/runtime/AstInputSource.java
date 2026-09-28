// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class AstInputSource extends InputSource {
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] slots;
    public AstInputSource(ArgumentLayout layout, int[] slots) { super(layout); this.slots = slots; }
    public int[] getSlots() { return slots; }
    @Override public int readInt(VirtualFrame frame, Node node, Object[] values, int index) {
        if (frame.isInt(slots[index])) return frame.getInt(slots[index]);
        if (FrameAccess.read(frame, slots[index]) instanceof Integer value) return value;
        throw fault("Expected primitive Int input");
    }
    @Override public long readLong(VirtualFrame frame, Node node, Object[] values, int index) {
        if (frame.isLong(slots[index])) return frame.getLong(slots[index]);
        if (FrameAccess.read(frame, slots[index]) instanceof Long value) return value;
        throw fault("Expected primitive Long input");
    }
    @Override public float readFloat(VirtualFrame frame, Node node, Object[] values, int index) {
        if (frame.isFloat(slots[index])) return frame.getFloat(slots[index]);
        if (FrameAccess.read(frame, slots[index]) instanceof Float value) return value;
        throw fault("Expected primitive Float input");
    }
    @Override public double readDouble(VirtualFrame frame, Node node, Object[] values, int index) {
        if (frame.isDouble(slots[index])) return frame.getDouble(slots[index]);
        if (FrameAccess.read(frame, slots[index]) instanceof Double value) return value;
        throw fault("Expected primitive Double input");
    }
    @Override public Object reference(VirtualFrame frame, Node node, Object[] values, int index) { return FrameAccess.read(frame, slots[index]); }
    @Override public void setReference(VirtualFrame frame, Node node, Object[] values, int index, Object value) { TypedInputs.writeInputReference(frame, slots[index], value); }
    @ExplodeLoop public void clear(VirtualFrame frame) { for (int slot : slots) frame.clear(slot); }
}
