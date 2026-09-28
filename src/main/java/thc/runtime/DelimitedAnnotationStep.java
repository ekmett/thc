// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.Node;
import java.util.IdentityHashMap;

/** Captured annotateStack# return, rebased once per multi-shot invocation. */
public final class DelimitedAnnotationStep implements DelimitedStep {
    private final Node node;
    private final StackAnnotationState prior;
    public DelimitedAnnotationStep(Node node, StackAnnotationState prior) { this.node = node; this.prior = prior; }
    public StackAnnotationState getPrior() { return prior; }
    public DelimitedAnnotationStep rebase(StackAnnotationState outside, StackAnnotationState ambient,
                                         IdentityHashMap<StackAnnotationState, StackAnnotationState> copies) {
        return new DelimitedAnnotationStep(node, prior.rebase(outside, ambient, copies));
    }
    public void unwind() { StackAnnotations.set(node, prior); }
    @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
        unwind(); return input.get();
    }
}
