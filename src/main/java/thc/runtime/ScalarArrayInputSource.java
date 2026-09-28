// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class ScalarArrayInputSource extends InputSource {
    public ScalarArrayInputSource(ArgumentLayout layout) { super(layout); if (layout != null && layout.getRequiresTyped()) throw new IllegalArgumentException("Failed requirement."); }
    @Override public int readInt(VirtualFrame frame, Node node, Object[] values, int index) {
        if (java.util.Objects.requireNonNull(values)[index] instanceof Integer value) return value;
        throw fault("Expected primitive Int input");
    }
    @Override public long readLong(VirtualFrame frame, Node node, Object[] values, int index) {
        if (java.util.Objects.requireNonNull(values)[index] instanceof Long value) return value;
        throw fault("Expected primitive Long input");
    }
    @Override public float readFloat(VirtualFrame frame, Node node, Object[] values, int index) {
        if (java.util.Objects.requireNonNull(values)[index] instanceof Float value) return value;
        throw fault("Expected primitive Float input");
    }
    @Override public double readDouble(VirtualFrame frame, Node node, Object[] values, int index) {
        if (java.util.Objects.requireNonNull(values)[index] instanceof Double value) return value;
        throw fault("Expected primitive Double input");
    }
    @Override public Object reference(VirtualFrame frame, Node node, Object[] values, int index) {
        return ColdCallChecks.values(values)[index];
    }
    @Override public void setReference(VirtualFrame frame, Node node, Object[] values, int index, Object value) {
        ColdCallChecks.values(values)[index] = value;
    }
}
