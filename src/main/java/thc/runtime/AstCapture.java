// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Built only while unwinding an interrupted activation; steps run leaf first. */
public final class AstCapture extends ControlFlowException {
    private final Object yielded;
    private final MaskingState logicalMask;
    private final StackAnnotationState annotations = StackAnnotations.current(null);
    private final ArrayList<AstResumeStep> steps = new ArrayList<>();
    @TruffleBoundary public AstCapture(Object yielded, MaskingState logicalMask) { this.yielded = yielded; this.logicalMask = logicalMask; }
    public Object getYielded() { return yielded; }
    public MaskingState getLogicalMask() { return logicalMask; }
    @TruffleBoundary public AstCapture append(AstResumeStep step) { steps.add(step); return this; }

    /** Keep the lexical exception/cleanup scope around the saved child work. */
    @TruffleBoundary public AstCapture enclose(Function<List<AstResumeStep>, AstResumeStep> wrapper) {
        AstResumeStep scope = wrapper.apply(List.copyOf(steps));
        steps.clear();
        steps.add(scope);
        return this;
    }
    public AsyncRequest asyncRequest() {
        return switch (yielded) {
            case AsyncRequest request -> request;
            case ThunkSuspended suspended -> suspended.getAsyncRequest();
            case CallSegmentSuspended suspended -> suspended.getAsyncRequest();
            case null, default -> null;
        };
    }
    public AstContinuation freeze(GuestRoot root, MaterializedFrame frame) { return freeze(root, frame, false); }
    @TruffleBoundary public AstContinuation freeze(GuestRoot root, MaterializedFrame frame, boolean rootEntrySpill) {
        return new AstContinuation(root, yielded instanceof AstPendingTail tail ? tail.publish() : yielded,
            logicalMask, frame, List.copyOf(steps), annotations, rootEntrySpill, yielded instanceof AstPendingTail);
    }
    @TruffleBoundary public AstCapture appendRemaining(List<AstResumeStep> old, int first) {
        for (int i = first; i < old.size(); i++) steps.add(old.get(i));
        return this;
    }
    public AstPendingTail pendingTail() {
        return yielded instanceof AstPendingTail tail && steps.size() == 1 && steps.getFirst() == tail ? tail : null;
    }
}
