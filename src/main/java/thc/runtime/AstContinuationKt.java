// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.List;

public final class AstContinuationKt {
    private AstContinuationKt() {}
    /** A consumed prefix is never retained after a second interruption. */
    public static Object resumeAstSteps(VirtualFrame frame, List<AstResumeStep> steps, Object input) {
        Object answer = input;
        for (int i = 0; i < steps.size(); i++) {
            try { answer = steps.get(i).resume(frame, answer); }
            catch (AstCapture cut) { throw cut.appendRemaining(steps, i + 1); }
        }
        return answer;
    }
}
