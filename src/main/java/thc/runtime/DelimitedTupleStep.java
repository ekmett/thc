// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
public final class DelimitedTupleStep implements DelimitedStep {
    private final TupleDestination destination;
    private final Node node;
    public DelimitedTupleStep(TupleDestination destination, Node node) { this.destination = destination; this.node = node; }
    @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
        destination.consume(frame, node, input.get()); return null;
    }
}
