// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Ordinary AST calls retain typed tuple dispatch and its consumer. */
public final class AnnotatedTuple extends Expr {
    @Child private Expr annotation, state, body;
    public AnnotatedTuple(Expr annotation, Expr state, Expr body) {
        this.annotation = annotation; this.state = state; this.body = body; setRepresentation(body.getRepresentation());
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("annotateStack# requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object value = annotation.execute(frame);
        TupleResults.requireVoidCarrier(state.execute(frame));
        StackAnnotationState prior = StackAnnotations.enter(this, value);
        try { return body.executeTuple(frame, slots, offset); }
        catch (DelimitedCut cut) {
            if (!DelimitedControl.enabled(this)) throw cut;
            throw cut.append(frame, new DelimitedAnnotationStep(this, prior));
        } finally { StackAnnotations.set(this, prior); }
    }
}
