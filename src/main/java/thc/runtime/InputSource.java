// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;

public abstract class InputSource {
    private final ArgumentLayout layout;
    @CompilerDirectives.CompilationFinal(dimensions = 1) protected final CoreRepresentation[] physicalProofs;
    protected InputSource(ArgumentLayout layout) { this.layout = layout; physicalProofs = layout == null ? null : layout.getPhysicalProofs(); }
    public final ArgumentLayout getLayout() { return layout; }
    public final CoreRepresentation[] getPhysicalProofs() { return physicalProofs; }
    public final CoreRepresentation[] getPhysicalProofs$org_intelligence_thc() { return physicalProofs; }
    public abstract int readInt(VirtualFrame frame, Node node, Object[] values, int index);
    public abstract long readLong(VirtualFrame frame, Node node, Object[] values, int index);
    public abstract float readFloat(VirtualFrame frame, Node node, Object[] values, int index);
    public abstract double readDouble(VirtualFrame frame, Node node, Object[] values, int index);
    public abstract Object reference(VirtualFrame frame, Node node, Object[] values, int index);
    public abstract void setReference(VirtualFrame frame, Node node, Object[] values, int index, Object value);
    public final void copy(VirtualFrame frame, Node node, Object[] values, int sourceOffset, HandoffStorage destination, int targetOffset, int count) {
        copy(frame, node, values, sourceOffset, destination, targetOffset, count, destination.getLayout());
    }
    @ExplodeLoop public final void copy(VirtualFrame frame, Node node, Object[] values, int sourceOffset,
            HandoffStorage destination, int targetOffset, int count, HandoffLayout shape) {
        for (int i = 0; i < count; i++) {
            int source = sourceOffset + i, target = targetOffset + i;
            if (shape.isInt(target)) shape.setInt(destination, target, readInt(frame, node, values, source));
            else if (shape.isLong(target)) shape.setLong(destination, target, readLong(frame, node, values, source));
            else if (shape.isFloat(target)) shape.setFloat(destination, target, readFloat(frame, node, values, source));
            else if (shape.isDouble(target)) shape.setDouble(destination, target, readDouble(frame, node, values, source));
            else shape.setObject(destination, target, reference(frame, node, values, source));
        }
    }
}
