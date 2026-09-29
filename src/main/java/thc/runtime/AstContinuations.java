// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import java.util.List;

public final class AstContinuations {
    private AstContinuations() {}
    /** A consumed prefix is never retained after a second interruption. */
    public static Object resumeAstSteps(VirtualFrame frame, List<AstResumeStep> steps, Object input) {
        Object answer = input;
        for (int i = 0; i < steps.size(); i++) {
            try { answer = steps.get(i).resume(frame, answer); }
            catch (AstCapture cut) { throw cut.appendRemaining(steps, i + 1); }
            catch (DelimitedCut cut) {
                // The interrupted operation records its own pending work in
                // the cut. Never replay it or retain a consumed child edge.
                if (i + 1 < steps.size()) cut.append(frame, new Suffix(List.copyOf(steps.subList(i + 1, steps.size()))));
                throw cut;
            }
        }
        return answer;
    }
    private record Suffix(List<AstResumeStep> steps) implements DelimitedStep {
        @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
            return resumeAstSteps(frame, steps, input.get());
        }
    }
}
