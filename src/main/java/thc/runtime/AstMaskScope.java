// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TupleResults.requireVoidCarrier;
import java.util.ArrayDeque;
final class AstMaskScope implements AstResumeStep {
    private final Node node;
    private final MaskingState prior;
    private final ArrayDeque<AstResumeStep> steps;
    AstMaskScope(Node node, MaskingState prior, ArrayDeque<AstResumeStep> steps) { this.node = node; this.prior = prior; this.steps = steps; }
    @Override public Object resume(VirtualFrame frame, Object input) {
        try { return AstContinuations.resumeAstSteps(frame, steps, input); }
        catch (AstCapture cut) { throw cut.enclose(remaining -> new AstMaskScope(node, prior, remaining)); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedMaskStep(node, prior)); }
        finally { SynchronousMasking.set(node, prior); }
    }
}
