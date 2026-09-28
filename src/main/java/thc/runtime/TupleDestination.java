// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
public abstract class TupleDestination {
    private final TupleShape shape;
    protected TupleDestination(TupleShape shape) { this.shape = shape; }
    public final TupleShape getShape() { return shape; }
    public abstract void consume(VirtualFrame frame, Node node, Object result);
    public Object delimitedResult(VirtualFrame frame, Node node) { return null; }
}
