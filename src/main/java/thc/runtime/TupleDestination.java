// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
public abstract class TupleDestination {
    // These protocol variants exist before the first guest call. Resolving them
    // here prevents a cold continuation from invalidating a unique-subtype guess.
    // Keep the hierarchy open for other runtime destinations and test carriers.
    private static final Class<?>[] CARRIER_TYPES = {
        AstTupleDestination.class, BytecodeTupleSlots.class, ContinuationTupleDestination.class
    };
    private final TupleShape shape;
    protected TupleDestination(TupleShape shape) { this.shape = shape; }
    public final TupleShape getShape() { return shape; }
    public abstract void consume(VirtualFrame frame, Node node, Object result);
    /** Authenticate the final callee's descriptor before reading its exact storage layout. */
    public final void consume(VirtualFrame frame, Node node, Object result, TupleShape producer) {
        if (producer == null || !shape.matches(producer))
            throw RuntimeFault.fault("Tuple producer changed the declared result shape");
        consumeFrom(frame, node, result, producer);
    }
    protected void consumeFrom(VirtualFrame frame, Node node, Object result, TupleShape producer) {
        if (producer.getLayout() != shape.getLayout())
            throw RuntimeFault.fault("Tuple destination requires its producer descriptor");
        consume(frame, node, result);
    }
    public Object delimitedResult(VirtualFrame frame, Node node) { return null; }
}
