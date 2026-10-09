// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Collection;

public final class AstContinuations {
    private AstContinuations() {}
    /** Pending work belongs to this invocation; consumed prefixes are no longer roots. */
    public static Object resumeAstSteps(VirtualFrame frame, ArrayDeque<AstResumeStep> steps, Object input) {
        Object answer = input;
        Throwable primary = null;
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
                    steps.clear();
                    throw cut;
                }
            }
            return answer;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try { discardSteps(steps); }
            catch (RuntimeException | Error cleanup) {
                if (primary == null) throw cleanup;
                if (primary != cleanup) primary.addSuppressed(cleanup);
            }
        }
    }
    /** Remove owning edges before cleanup, including when one cleanup fails. */
    static void discardSteps(Collection<AstResumeStep> steps) {
        if (steps.isEmpty()) return;
        var pending = List.copyOf(steps);
        steps.clear();
        Throwable failure = null;
        for (var step : pending) try { step.discard(); }
        catch (RuntimeException | Error caught) {
            if (failure == null) failure = caught;
            else if (failure != caught) failure.addSuppressed(caught);
        }
        if (failure instanceof RuntimeException caught) throw caught;
        if (failure instanceof Error caught) throw caught;
    }
    private record Suffix(List<AstResumeStep> steps) implements DelimitedStep {
        @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
            return resumeAstSteps(frame, new ArrayDeque<>(steps), input.get());
        }
    }
}
