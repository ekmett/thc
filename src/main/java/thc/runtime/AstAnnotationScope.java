// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import java.util.ArrayDeque;

/** Restore the annotation scope even when an earlier resumed step fails or captures. */
final class AstAnnotationScope implements AstResumeStep {
    private final Node node;
    private final StackAnnotationState prior;
    private final ArrayDeque<AstResumeStep> steps;
    AstAnnotationScope(Node node, StackAnnotationState prior, ArrayDeque<AstResumeStep> steps) {
        this.node = node; this.prior = prior; this.steps = steps;
    }
    @Override public Object resume(VirtualFrame frame, Object input) {
        try { return AstContinuations.resumeAstSteps(frame, steps, input); }
        catch (AstCapture cut) { throw cut.enclose(next -> new AstAnnotationScope(node, prior, next)); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedAnnotationStep(node, prior)); }
        finally { StackAnnotations.set(node, prior); }
    }
}
