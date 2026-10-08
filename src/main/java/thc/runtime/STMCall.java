// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import java.util.ArrayDeque;
import thc.Language;

/** Shared transaction scopes. Internal cuts retain the original log and remaining work;
 * external delivery still abandons the attempt and restarts its original action. */
public final class STMCall extends Node {
    private final STMOp operation;
    private final boolean async;
    private final TupleShape shape;
    @Child private Force force;
    @Child private PreparedDispatch actionCall;
    @Child private PreparedDispatch otherCall;
    @Child private TupleDispatch synchronousAction;
    @Child private TupleDispatch synchronousOther;

    public STMCall(STMOp operation, TupleDestination destination, Metrics metrics) { this(operation, destination, metrics, false); }
    public STMCall(STMOp operation, TupleDestination destination, Metrics metrics, boolean async) {
        this.operation = operation; this.async = async; shape = destination.getShape();
        force = new Force(metrics, async);
        actionCall = new PreparedDispatch(1, false, metrics, new boolean[0], null);
        otherCall = new PreparedDispatch(operation == STMOp.CATCH ? 2 : 1, false, metrics, new boolean[0], null);
        synchronousAction = new TupleDispatch(destination, metrics, 1, false);
        synchronousOther = new TupleDispatch(destination, metrics, operation == STMOp.CATCH ? 2 : 1, false);
    }
    boolean captures() { return async || AstControl.captures(this); }
    public Object execute(VirtualFrame frame, Object action, Object alternative, Object nested) {
        var stm = Language.currentState(this).stm;
        var mask = async ? SynchronousMasking.current(this) : null;
        var annotations = async ? StackAnnotations.current(this) : null;
        try {
            return switch (operation) {
                case ATOMICALLY -> {
                    if (stm.hasTransaction()) throw new GuestException(nested, this);
                    yield atomic(frame, action, null, null, null);
                }
                case OR_ELSE, CATCH -> choice(frame, action, alternative, null, null);
                default -> throw new IllegalStateException("Not an STM callback: " + operation);
            };
        } catch (Throwable failure) {
            if (!async) throw failure;
            throw external(failure, mask, annotations);
        }
    }

    private Object invoke(VirtualFrame frame, Object action, Object[] arguments, PreparedDispatch dispatch) {
        if (!captures()) {
            (dispatch == actionCall ? synchronousAction : synchronousOther).execute(frame,
                Applications.requireClosure(force.execute(frame, action)), arguments);
            return null; // The original backend-native destination has already consumed the result.
        }
        Object ready;
        try { ready = async || AstControl.captures(this) ? AstControl.forceCallback(frame, this, force, action) : force.execute(frame, action); }
        catch (AstCapture cut) {
            throw cut.append((resumed, value) -> invokeReady(resumed, value, arguments, dispatch));
        }
        return invokeReady(frame, ready, arguments, dispatch);
    }
    private Object invokeReady(VirtualFrame frame, Object ready, Object[] arguments, PreparedDispatch dispatch) {
        Closure closure = Applications.requireClosure(ready);
        try {
            Object result = dispatch.execute(frame, closure, arguments);
            if (async || AstControl.captures(this)) result = AstControl.completeCallback(this, result, closure.target, shape, false);
            return TupleResults.ownedTupleResult(result, shape);
        } catch (AstCapture cut) {
            throw cut.append((resumed, value) -> TupleResults.ownedTupleResult(value, shape));
        }
    }

    private Object atomic(VirtualFrame frame, Object action, ManagedSTM.Transaction saved,
            ArrayDeque<AstResumeStep> steps, Object input) {
        ManagedSTM stm = Language.currentState(this).stm;
        ManagedSTM.Transaction ambient = stm.currentTransaction();
        while (true) {
            ManagedSTM.Transaction tx = saved == null ? stm.begin() : saved;
            boolean parked = false;
            stm.restore(tx);
            try {
                Object result = steps == null ? invoke(frame, action, new Object[]{Unit.INSTANCE}, actionCall)
                    : AstContinuations.resumeAstSteps(frame, steps, input);
                stm.commit(tx);
                return result;
            } catch (AstCapture cut) {
                if (cut.asyncRequest() != null) throw cut;
                parked = true;
                throw cut.enclose(remaining -> new AtomicResume(this, action, tx, remaining));
            } catch (STMConflict ignored) {
                // A genuine conflict, not an internal stack cut, retries the original action.
            } catch (STMRetry ignored) {
                stm.restore(null); stm.await(tx, this, async);
            } catch (GuestException failure) {
                if (stm.validException(tx)) throw failure;
            } finally { if (!parked) stm.retire(tx); stm.restore(ambient); }
            saved = null; steps = null; input = null;
        }
    }
    private record AtomicResume(STMCall site, Object action, ManagedSTM.Transaction transaction,
            ArrayDeque<AstResumeStep> steps) implements AstResumeStep {
        @Override public Object resume(VirtualFrame frame, Object input) {
            MaskingState mask = SynchronousMasking.current(site);
            StackAnnotationState annotations = StackAnnotations.current(site);
            try { return site.atomic(frame, action, transaction, steps, input); }
            catch (Throwable failure) { throw site.external(failure, mask, annotations); }
        }
    }

    private Object choice(VirtualFrame frame, Object action, Object alternative,
            ArrayDeque<AstResumeStep> steps, Object input) {
        ManagedSTM stm = Language.currentState(this).stm;
        ManagedSTM.Transaction parent = stm.parent();
        try {
            return steps == null ? nested(frame, parent, action, actionCall, null, null, null)
                : AstContinuations.resumeAstSteps(frame, steps, input);
        } catch (AstCapture cut) {
            if (cut.asyncRequest() != null) throw cut;
            throw cut.enclose(remaining -> (resumed, value) -> choice(resumed, action, alternative, remaining, value));
        } catch (STMRetry failure) {
            if (operation != STMOp.OR_ELSE) throw failure;
            return nested(frame, parent, alternative, otherCall, null, null, null);
        } catch (GuestException failure) {
            if (operation != STMOp.CATCH) throw failure;
            return invoke(frame, alternative, new Object[]{failure.getPayload(), Unit.INSTANCE}, otherCall);
        }
    }
    private Object nested(VirtualFrame frame, ManagedSTM.Transaction parent, Object action,
            PreparedDispatch dispatch, ManagedSTM.Transaction saved, ArrayDeque<AstResumeStep> steps, Object input) {
        ManagedSTM stm = Language.currentState(this).stm;
        ManagedSTM.Transaction child = saved == null ? stm.beginNested(parent) : saved;
        stm.restore(child);
        try {
            Object result = steps == null ? invoke(frame, action, new Object[]{Unit.INSTANCE}, dispatch)
                : AstContinuations.resumeAstSteps(frame, steps, input);
            stm.commitNested(parent, child);
            return result;
        } catch (AstCapture cut) {
            if (cut.asyncRequest() != null) { stm.abort(parent, child); throw cut; }
            throw cut.enclose(remaining -> (resumed, value) -> nested(resumed, parent, action, dispatch, child, remaining, value));
        } catch (Throwable failure) { stm.abort(parent, child); throw failure; }
        finally { stm.restore(parent); }
    }

    /** Never turn an internal spill/STM control failure into a guest exception or async request. */
    private RuntimeException external(Throwable failure, MaskingState mask, StackAnnotationState annotations) {
        AsyncRequest request = switch (failure) {
            case STMRestart restart -> restart.getRequest();
            case AsyncDelivery delivered -> delivered.getRequest();
            case AsyncBlocked blocked -> blocked.getRequest();
            case AstCapture capture -> capture.asyncRequest();
            case ThunkSuspended suspended -> suspended.getAsyncRequest();
            case CallSegmentSuspended suspended -> suspended.getAsyncRequest();
            default -> null;
        };
        if (request == null) {
            if (failure instanceof RuntimeException runtime) return runtime;
            if (failure instanceof Error error) throw error;
            throw new AssertionError(failure);
        }
        SynchronousMasking.set(this, mask);
        StackAnnotations.set(this, annotations);
        return new STMRestart(request);
    }
}
