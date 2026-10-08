// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import java.util.List;
import java.util.ArrayDeque;

public final class AstContinuations {
    private AstContinuations() {}
    /** Pending work belongs to this invocation; consumed prefixes are no longer roots. */
    public static Object resumeAstSteps(VirtualFrame frame, ArrayDeque<AstResumeStep> steps, Object input) {
        Object answer = input;
        try {
            while (!steps.isEmpty()) {
                // Do not retain the recipe around its instantiated scope: it still
                // owns the immutable prefix needed by other delimited invocations.
                AstResumeStep step = steps.removeFirst().forInvocation();
                try { answer = step.resume(frame, answer); }
                catch (AstCapture cut) { throw cut.appendRemaining(steps); }
                catch (DelimitedCut cut) {
                    // The interrupted operation has already recorded its own remainder.
                    if (!steps.isEmpty()) cut.append(frame, new Suffix(List.copyOf(steps)));
                    throw cut;
                }
            }
            return answer;
        } finally { steps.clear(); }
    }
    private record Suffix(List<AstResumeStep> steps) implements DelimitedStep {
        @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
            return resumeAstSteps(frame, new ArrayDeque<>(steps), input.get());
        }
    }
}
