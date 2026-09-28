// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
public final class ContinuationTupleDestination extends TupleDestination {
    private final BytecodeTupleSlots destination;
    public ContinuationTupleDestination(BytecodeTupleSlots destination) { super(destination.getShape()); this.destination = destination; }
    @Override public Object delimitedResult(VirtualFrame frame, Node node) {
        var root = node.getRootNode();
        if (root == null) throw new NullPointerException("null cannot be cast to non-null type thc.runtime.BytecodeRoot");
        return TupleResults.ownedTupleResult(destination.finish(frame, ((BytecodeRoot) root).getBytecodeNode()), getShape());
    }
    @Override public void consume(VirtualFrame frame, Node node, Object result) {
        DelimitedControl.captureBytecode(result, getShape());
        if (result instanceof TailYield tail) throw new TupleCallYield(tail.getContinuation(), true, tail.getTarget());
        if (result instanceof AstTailYield tail) throw new TupleCallYield(tail.getContinuation(), true, tail.getTarget());
        if (result instanceof ContinuationResult continuation) throw new TupleCallYield(continuation);
        if (result instanceof SavedGuestContinuation continuation) throw new TupleCallYield(continuation);
        destination.consume(frame, node, result);
    }
}
