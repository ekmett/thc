// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.Node;
public final class DelimitedTupleStep implements DelimitedPendingApplication {
    private final TupleDestination destination;
    private final Node node;
    private final TupleShape producer;
    public DelimitedTupleStep(TupleDestination destination, Node node) { this(destination, node, destination.getShape()); }
    public DelimitedTupleStep(TupleDestination destination, Node node, TupleShape producer) {
        this.destination = destination; this.node = node; this.producer = producer;
    }
    @Override public TupleDestination getDestination() { return destination; }
    @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
        destination.consume(frame, node, input.get(), producer); return null;
    }
}
