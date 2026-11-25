// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Objects;
import java.util.Queue;
import java.util.List;
import java.util.function.Function;

/** Built only while unwinding an interrupted activation; steps run leaf first. */
public final class AstCapture extends ControlFlowException {
    private final Object yielded;
    private final MaskingState logicalMask;
    private final StackAnnotationState annotations = StackAnnotations.current(null);
    /** Pending work is empty, a single step, or this owned queue. A terminal
     * child demand can recover its first step from the exact suspension marker. */
    static final class Steps extends ArrayDeque<AstResumeStep> {
        Steps() { super(4); }
        Steps(Collection<AstResumeStep> supplied) { super(supplied); }
    }
    private Object pending;
    private boolean childDemand;
    private boolean freshlyEnclosed;

    static AstControl.ResumeChild yieldedChild(Object yielded) {
        if (yielded instanceof ThunkSuspended suspended) return suspended.getThunk();
        if (yielded instanceof CallSegmentSuspended suspended) return suspended.getSegment();
        return null;
    }
    static boolean isChildDemand(Object yielded, Object first, AstResumeStep second) {
        return second instanceof Evaluate && first instanceof AstControl.ResumeChild && first == yieldedChild(yielded);
    }
    static Steps expandDemand(Object yielded, Object demand) {
        var owned = new Steps();
        owned.add(yieldedChild(yielded));
        owned.add((Evaluate) demand);
        return owned;
    }
    private void addStep(AstResumeStep step) {
        Objects.requireNonNull(step);
        freshlyEnclosed = false;
        if (pending == null) pending = step;
        else if (pending instanceof Steps many) many.add(step);
        else if (isChildDemand(yielded, pending, step)) {
            pending = step;
            childDemand = true;
        } else {
            var many = childDemand ? expandDemand(yielded, pending) : new Steps();
            if (!childDemand) many.add((AstResumeStep) pending);
            many.add(step);
            pending = many;
            childDemand = false;
        }
    }
    private Collection<AstResumeStep> scopeSteps() {
        if (pending instanceof Steps many) return many;
        if (childDemand) return List.of(yieldedChild(yielded), (Evaluate) pending);
        return pending == null ? List.of() : List.of((AstResumeStep) pending);
    }
    @TruffleBoundary public AstCapture(Object yielded, MaskingState logicalMask) { this.yielded = yielded; this.logicalMask = logicalMask; }
    public Object getYielded() { return yielded; }
    public MaskingState getLogicalMask() { return logicalMask; }
    @TruffleBoundary public AstCapture append(AstResumeStep step) {
        addStep(step);
        return this;
    }

    /** Keep the lexical exception/cleanup scope around the saved child work. */
    @TruffleBoundary public AstCapture enclose(Function<ArrayDeque<AstResumeStep>, AstResumeStep> wrapper) {
        pending = new ScopeRecipe(wrapper, scopeSteps());
        childDemand = false;
        freshlyEnclosed = true;
        return this;
    }
    /** A lexical owner can enter an invocation with its already private pending queue. */
    @FunctionalInterface
    interface LexicalScope extends Function<ArrayDeque<AstResumeStep>, AstResumeStep> {
        Object resumeOwned(VirtualFrame frame, ArrayDeque<AstResumeStep> steps, Object input);
        @Override default AstResumeStep apply(ArrayDeque<AstResumeStep> steps) {
            return (frame, input) -> resumeOwned(frame, steps, input);
        }
    }
    private static final class ScopeRecipe implements AstResumeStep {
        private final Function<ArrayDeque<AstResumeStep>, AstResumeStep> wrapper;
        private final Collection<AstResumeStep> steps;
        private boolean oneShot;
        ScopeRecipe(Function<ArrayDeque<AstResumeStep>, AstResumeStep> wrapper, Collection<AstResumeStep> steps) {
            this.wrapper = wrapper; this.steps = steps;
        }
        private ArrayDeque<AstResumeStep> invocationSteps() {
            return oneShot && steps instanceof ArrayDeque<AstResumeStep> owned ? owned : new ArrayDeque<>(steps);
        }
        @Override public AstResumeStep forInvocation() {
            if (oneShot && steps instanceof ArrayDeque<?> && wrapper instanceof LexicalScope) return this;
            return wrapper.apply(invocationSteps());
        }
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (oneShot && steps instanceof ArrayDeque<AstResumeStep> owned && wrapper instanceof LexicalScope scope)
                return scope.resumeOwned(frame, owned, input);
            throw new IllegalStateException("Captured scope recipe must be instantiated before resumption");
        }
        @Override public void discard() { AstContinuations.discardSteps(invocationSteps()); }
    }
    public AsyncRequest asyncRequest() {
        return switch (yielded) {
            case AsyncRequest request -> request;
            case ThunkSuspended suspended -> suspended.getAsyncRequest();
            case CallSegmentSuspended suspended -> suspended.getAsyncRequest();
            case null, default -> null;
        };
    }
    static void discardPending(Object pending) {
        if (pending instanceof Steps many) AstContinuations.discardSteps(many);
        else if (pending instanceof AstResumeStep step) step.discard();
    }
    /** A catch handling this cut owns terminal retirement of its pending steps. */
    @TruffleBoundary public void discard() {
        Object owned = childDemand ? expandDemand(yielded, pending) : pending;
        clearPending();
        discardPending(owned);
    }
    public AstContinuation freeze(GuestRoot root, MaterializedFrame frame) { return freeze(root, frame, false); }
    @TruffleBoundary public AstContinuation freeze(GuestRoot root, MaterializedFrame frame, boolean rootEntrySpill) {
        var saved = new AstContinuation(root, yielded instanceof AstPendingTail tail ? tail.publish() : yielded,
            logicalMask, frame, pending, childDemand, annotations, rootEntrySpill, yielded instanceof AstPendingTail);
        if (freshlyEnclosed) {
            // The sole, newly enclosed recipe has never been exposed. The saved
            // activation's claim admits exactly one invocation or one discard.
            ((ScopeRecipe) pending).oneShot = true;
        }
        // The saved activation now owns the queue. Reusing or discarding this
        // capture must not mutate its steps, including during nested recapture.
        clearPending();
        return saved;
    }
    private void clearPending() {
        pending = null;
        childDemand = false;
        freshlyEnclosed = false;
    }
    @TruffleBoundary public AstCapture appendRemaining(ArrayDeque<AstResumeStep> pending) { return appendRemaining((Queue<AstResumeStep>) pending); }
    AstCapture appendRemaining(Queue<AstResumeStep> pending) {
        while (!pending.isEmpty()) addStep(pending.remove());
        return this;
    }
    public AstPendingTail pendingTail() {
        return yielded instanceof AstPendingTail tail && pending == tail ? tail : null;
    }
}
