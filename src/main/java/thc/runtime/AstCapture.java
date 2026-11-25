// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.List;
import java.util.function.Function;

/** Built only while unwinding an interrupted activation; steps run leaf first. */
public final class AstCapture extends ControlFlowException {
    private final Object yielded;
    private final MaskingState logicalMask;
    private final StackAnnotationState annotations = StackAnnotations.current(null);
    /** One capture owns this queue until freeze transfers it to its one-shot activation. */
    private static final class Steps extends AbstractQueue<AstResumeStep> {
        private AstResumeStep first;
        private AstResumeStep second;
        private ArrayDeque<AstResumeStep> many;
        @Override public boolean offer(AstResumeStep step) {
            Objects.requireNonNull(step);
            if (many != null) return many.offer(step);
            if (first == null) first = step;
            else if (second == null) second = step;
            else {
                many = new ArrayDeque<>(4);
                many.add(first);
                many.add(second);
                many.add(step);
                first = second = null;
            }
            return true;
        }
        @Override public AstResumeStep poll() {
            if (many != null) return many.poll();
            AstResumeStep step = first;
            first = second;
            second = null;
            return step;
        }
        @Override public AstResumeStep peek() { return many != null ? many.peek() : first; }
        @Override public int size() { return many != null ? many.size() : second != null ? 2 : first != null ? 1 : 0; }
        List<AstResumeStep> snapshot() {
            if (many != null) return List.copyOf(many);
            if (second != null) return List.of(first, second);
            return first != null ? List.of(first) : List.of();
        }
        @Override public Iterator<AstResumeStep> iterator() {
            return many != null ? many.iterator() : snapshot().iterator();
        }
    }
    private Steps steps;
    private Steps pendingSteps() {
        if (steps == null) steps = new Steps();
        return steps;
    }
    @TruffleBoundary public AstCapture(Object yielded, MaskingState logicalMask) { this.yielded = yielded; this.logicalMask = logicalMask; }
    public Object getYielded() { return yielded; }
    public MaskingState getLogicalMask() { return logicalMask; }
    @TruffleBoundary public AstCapture append(AstResumeStep step) { pendingSteps().add(step); return this; }

    /** Keep the lexical exception/cleanup scope around the saved child work. */
    @TruffleBoundary public AstCapture enclose(Function<ArrayDeque<AstResumeStep>, AstResumeStep> wrapper) {
        var steps = pendingSteps();
        AstResumeStep scope = new ScopeRecipe(wrapper, steps.snapshot());
        steps.clear();
        steps.add(scope);
        return this;
    }
    private record ScopeRecipe(Function<ArrayDeque<AstResumeStep>, AstResumeStep> wrapper,
                               List<AstResumeStep> steps) implements AstResumeStep {
        @Override public AstResumeStep forInvocation() { return wrapper.apply(new ArrayDeque<>(steps)); }
        @Override public Object resume(com.oracle.truffle.api.frame.VirtualFrame frame, Object input) {
            throw new IllegalStateException("Captured scope recipe must be instantiated before resumption");
        }
        @Override public void discard() { AstContinuations.discardSteps(new ArrayDeque<>(steps)); }
    }
    public AsyncRequest asyncRequest() {
        return switch (yielded) {
            case AsyncRequest request -> request;
            case ThunkSuspended suspended -> suspended.getAsyncRequest();
            case CallSegmentSuspended suspended -> suspended.getAsyncRequest();
            case null, default -> null;
        };
    }
    /** A catch handling this cut owns terminal retirement of its pending steps. */
    @TruffleBoundary public void discard() { if (steps != null) AstContinuations.discardSteps(steps); }
    public AstContinuation freeze(GuestRoot root, MaterializedFrame frame) { return freeze(root, frame, false); }
    @TruffleBoundary public AstContinuation freeze(GuestRoot root, MaterializedFrame frame, boolean rootEntrySpill) {
        var saved = new AstContinuation(root, yielded instanceof AstPendingTail tail ? tail.publish() : yielded,
            logicalMask, frame, pendingSteps(), annotations, rootEntrySpill, yielded instanceof AstPendingTail);
        // The saved activation now owns the queue. Reusing or discarding this
        // capture must not mutate its steps, including during nested recapture.
        steps = null;
        return saved;
    }
    @TruffleBoundary public AstCapture appendRemaining(ArrayDeque<AstResumeStep> pending) { return appendRemaining((Queue<AstResumeStep>) pending); }
    AstCapture appendRemaining(Queue<AstResumeStep> pending) {
        if (!pending.isEmpty()) pendingSteps().addAll(pending);
        pending.clear();
        return this;
    }
    public AstPendingTail pendingTail() {
        return yielded instanceof AstPendingTail tail && steps != null && steps.size() == 1 && steps.peek() == tail ? tail : null;
    }
}
