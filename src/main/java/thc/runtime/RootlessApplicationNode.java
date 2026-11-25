// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.Node;
import java.util.concurrent.atomic.AtomicBoolean;
import static thc.runtime.RuntimeFault.fault;

/** Dedicated exact application transport, separate from ordinary thunk target dispatch. */
final class RootlessApplicationNode extends Node {
    static final Object FALLBACK = new Object();
    @Child private DispatchCallTarget calls = DispatchCallTargetNodeGen.create();

    private void requireOwner(ExactThunkApplication request) { request.requireOwner(this); }
    private Closure eligibleHead(ExactThunkApplication request) {
        // Observation does not prepare the head. An unprepared or general head
        // retains the original owning body and its exact entry-poll order.
        Object head = request.plan.function().peek();
        if (!(head instanceof Closure fn) || fn.arity != 1 || fn.supplied.length != 0 || fn.environment != null ||
                !(fn.target.getRootNode() instanceof FunctionRoot root) || !root.getCapturesContinuations$org_intelligence_thc() ||
                root.getTypedInput() != null || root.getTupleResult() != null ||
                root.getInputLayout() != null || root.getEntryArgumentOffset() != 1 || root.getInputProofs() == null ||
                root.getInputProofs().size() != 1 || root.getInputProofs().getFirst().getKind() != CoreKind.DATA)
            return null;
        return fn;
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    Object execute(Force force, ExactThunkApplication request, Metrics metrics) {
        requireOwner(request);
        Closure fn = eligibleHead(request);
        if (fn == null) return FALLBACK;
        FunctionRoot owner = (FunctionRoot) request.plan.owner().getRootNode();
        if (owner.getEnableAsync$org_intelligence_thc()) {
            AsyncRequest pending = GuestThreads.pollCurrent(owner, false);
            if (pending != null) {
                pending.compiledCapture = com.oracle.truffle.api.CompilerDirectives.inCompiledCode();
                return new Continuation(this, force, request, metrics, pending, false);
            }
        }
        return call(force, request, metrics, fn);
    }
    private Object call(Force force, ExactThunkApplication request, Metrics metrics, Closure fn) {
        // Ordinary ingress initializes a dense target's destination to -1, so
        // this call neither supplies nor borrows a scalar result loan.
        Object answer;
        RootCallTarget destination = fn.target;
        FunctionRoot original = (FunctionRoot) destination.getRootNode();
        if (!original.noGraphFailure.isValid()) {
            RootCallTarget published = original.publishedRecoveryTarget();
            if (published != null) destination = published;
        }
        try { answer = calls.execute(this, destination, new Object[]{0L, request.argument}, metrics); }
        catch (TailCall tail) { answer = force.exactApplicationTail(tail, metrics); }
        return finishCall(force, request, metrics, fn.target, answer);
    }
    private Object finishCall(Force force, ExactThunkApplication request, Metrics metrics,
                              RootCallTarget expected, Object answer) {
        RootCallTarget witness = answer instanceof TailYield tail ? tail.getTarget() :
            answer instanceof AstTailYield tail ? tail.getTarget() : null;
        SavedGuestContinuation saved = SavedGuestContinuations.savedGuestContinuation(answer instanceof TailYield tail ? tail.getContinuation() :
            answer instanceof AstTailYield tail ? tail.getContinuation() : answer);
        if (saved == null) return demand(force, request, metrics, answer);
        if (!(saved.getSourceRoot() instanceof GuestRoot root) ||
                !root.isSelf(witness != null ? witness : expected) || !AsyncContinuations.isYieldMarker(saved.getYielded()))
            throw fault("Application thunk returned an unrelated continuation");
        if (saved.getYielded() instanceof DelimitedCut) throw fault("control0# cannot capture across a thunk update");
        MaskingState mask = SynchronousMasking.current(this);
        MaskingState parked = saved.getYielded() instanceof CallSegmentSuspended cut ? cut.getParkedActiveMask() : null;
        CallSegment child = new CallSegment(saved.getIdentity(), parked != null ? parked : mask, mask);
        return new Continuation(this, force, request, metrics,
            new CallSegmentSuspended(child, null, saved.asyncRequest(), saved.stackSpill()), false);
    }
    private Object demand(Force force, ExactThunkApplication request, Metrics metrics, Object answer) {
        try { return force.exactApplicationValue(answer, metrics); }
        catch (ThunkSuspended cut) { return new Continuation(this, force, request, metrics, cut, true); }
    }

    /** A cold one-shot saved suffix with the original adapter's exact body identity. */
    static final class Continuation implements SavedGuestContinuation {
        private final RootlessApplicationNode operation;
        private final Force force;
        private ExactThunkApplication request;
        private final Metrics metrics;
        private Object yielded;
        private final boolean demanded;
        private final MaskingState mask;
        private final StackAnnotationState annotations;
        private final ManagedSTM.Transaction transaction;
        private final GuestRoot owner;
        private final AtomicBoolean claimed = new AtomicBoolean();
        Continuation(RootlessApplicationNode operation, Force force, ExactThunkApplication request,
                     Metrics metrics, Object yielded, boolean demanded) {
            this.operation = operation; this.force = force; this.request = request; this.metrics = metrics;
            this.yielded = yielded; this.demanded = demanded;
            owner = (GuestRoot) request.plan.owner().getRootNode();
            mask = SynchronousMasking.current(operation); annotations = StackAnnotations.current(operation);
            transaction = request.plan.context().stm.currentTransaction();
        }
        void requireOwner() {
            ExactThunkApplication current = request;
            if (current == null) throw fault("Application thunk continuation was already consumed");
            operation.requireOwner(current);
        }
        @Override public Object getIdentity() { return this; }
        @Override public Object getSourceRoot() { return owner; }
        @Override public Object getYielded() { return yielded; }
        @Override @TruffleBoundary public Object continueWith(Object input) {
            ExactThunkApplication current = request;
            if (current == null) throw fault("Application thunk continuation was already consumed");
            operation.requireOwner(current);
            if (!claimed.compareAndSet(false, true)) throw fault("Application thunk continuation was already consumed");
            Object marker = yielded; yielded = null; request = null;
            Object child = marker instanceof ThunkSuspended cut ? cut.getThunk() :
                marker instanceof CallSegmentSuspended cut ? cut.getSegment() : null;
            MaskingState ambient = SynchronousMasking.current(operation);
            StackAnnotationState outerAnnotations = StackAnnotations.current(operation);
            ManagedSTM stm = current.plan.context().stm;
            ManagedSTM.Transaction outerTransaction = stm.currentTransaction();
            try {
                SynchronousMasking.set(operation, mask); StackAnnotations.set(operation, annotations);
                if (transaction == null || transaction.active()) stm.restore(transaction);
                if (marker instanceof AsyncRequest) {
                    if (input != Unit.INSTANCE) throw fault("Invalid AST root poll resume value");
                    Closure head = operation.eligibleHead(current);
                    if (head == null) throw fault("Prepared application thunk head changed after its entry poll");
                    return operation.call(force, current, metrics, head);
                }
                if (input instanceof AstChildSuspension cut) {
                    if (cut.getChild() != child) throw fault("Application thunk received an unrelated child cut");
                    Object next = child instanceof Thunk thunk ? new ThunkSuspended(thunk, cut.getRequest()) :
                        new CallSegmentSuspended((CallSegment) child, null, cut.getRequest(), false);
                    return new Continuation(operation, force, current, metrics, next, demanded);
                }
                if (input instanceof TailCall tail)
                    return operation.demand(force, current, metrics, force.exactApplicationTail(tail, metrics));
                if (!(input instanceof ChildResume result)) throw fault("Application thunk requires a completed child");
                if (result.getFailure() != null) throw result.takeFailure();
                Object value = result.getValue();
                boolean complete = child instanceof Thunk thunk ? thunk.getState() == 2 && thunk.getValue() == value :
                    ((CallSegment) child).getState() == 2 && ((CallSegment) child).getValue() == value;
                if (!complete) throw fault("Application thunk lost its completed child update");
                value = result.takeValue();
                return demanded ? value : operation.demand(force, current, metrics, value);
            } finally {
                stm.restore(outerTransaction); SynchronousMasking.set(operation, ambient);
                StackAnnotations.set(operation, outerAnnotations);
            }
        }
        @Override public void discard() { if (claimed.compareAndSet(false, true)) { yielded = null; request = null; } }
    }
}
