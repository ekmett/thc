// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** Shared callback boundary with a backend-native tuple destination. */
public final class STMCall extends Node {
    private final STMOp operation;
    private final boolean async;
    @Child private Force force;
    @Child private TupleDispatch actionCall;
    @Child private TupleDispatch otherCall;

    private static final class ActionSuspended extends AbstractTruffleException implements InternalGuestControl {
        final AsyncRequest request;
        ActionSuspended(AsyncRequest request) { super("Interrupted STM callback", null, 0, null); this.request = request; }
    }
    private static AsyncRequest request(Object marker) {
        AsyncRequest request = switch (marker) {
            case AsyncRequest value -> value;
            case ThunkSuspended value -> value.getAsyncRequest();
            case CallSegmentSuspended value -> value.getAsyncRequest();
            case null, default -> null;
        };
        if (request == null) throw fault("STM cannot save a non-asynchronous transaction continuation");
        return request;
    }
    private static final class AsyncDestination extends TupleDestination {
        private final TupleDestination destination;
        AsyncDestination(TupleDestination destination) { super(destination.getShape()); this.destination = destination; }
        @Override public Object delimitedResult(VirtualFrame frame, Node node) { return destination.delimitedResult(frame, node); }
        @Override public void consume(VirtualFrame frame, Node node, Object result) {
            var saved = SavedGuestContinuationKt.savedGuestContinuation(result instanceof TailYield tail ? tail.getContinuation() : result);
            if (saved != null) throw new ActionSuspended(request(saved.getYielded()));
            destination.consume(frame, node, result);
        }
    }
    public STMCall(STMOp operation, TupleDestination destination, Metrics metrics) { this(operation, destination, metrics, false); }
    public STMCall(STMOp operation, TupleDestination destination, Metrics metrics, boolean async) {
        this.operation = operation; this.async = async;
        force = new Force(metrics, async);
        actionCall = new TupleDispatch(async ? new AsyncDestination(destination) : destination, metrics, 1, false);
        otherCall = new TupleDispatch(async ? new AsyncDestination(destination) : destination, metrics, operation == STMOp.CATCH ? 2 : 1, false);
    }
    public void execute(VirtualFrame frame, Object action, Object alternative, Object nested) {
        var stm = Language.currentState(this).stm;
        var mask = async ? SynchronousMasking.current(this) : null;
        var annotations = async ? StackAnnotations.current(this) : null;
        try {
            switch (operation) {
                case ATOMICALLY -> {
                    if (stm.hasTransaction()) throw new GuestException(nested, this);
                    while (true) {
                        var tx = stm.begin();
                        try {
                            actionCall.execute(frame, ApplicationKt.requireClosure(force.execute(frame, action)), new Object[]{kotlin.Unit.INSTANCE});
                            stm.commit(tx);
                            break;
                        } catch (STMConflict ignored) {
                            // Replay only transactional effects; no lock spans the action.
                        } catch (STMRetry ignored) {
                            stm.restore(null); stm.await(tx, this, async);
                        } catch (GuestException failure) {
                            if (stm.validException(tx)) throw failure;
                        } finally { stm.restore(null); }
                    }
                }
                case OR_ELSE -> {
                    var parent = stm.parent();
                    try { nested(stm, parent, frame, action, actionCall); }
                    catch (STMRetry ignored) { nested(stm, parent, frame, alternative, otherCall); }
                }
                case CATCH -> {
                    var parent = stm.parent();
                    try { nested(stm, parent, frame, action, actionCall); }
                    catch (GuestException failure) {
                        otherCall.execute(frame, ApplicationKt.requireClosure(force.execute(frame, alternative)),
                            new Object[]{failure.getPayload(), kotlin.Unit.INSTANCE});
                    }
                }
                default -> throw new IllegalStateException("Not an STM callback: " + operation);
            }
        } catch (Throwable failure) {
            if (!async) throw failure;
            // The scope's finally already detached the log. Never retain the yielded
            // child: Force would demand it before reaching an outer restart.
            AsyncRequest request = switch (failure) {
                case STMRestart restart -> restart.getRequest();
                case ActionSuspended suspended -> suspended.request;
                case AsyncBlocked blocked -> blocked.getRequest();
                case AstCapture capture -> request(capture.getYielded());
                case ThunkSuspended suspended -> request(suspended);
                case CallSegmentSuspended suspended -> request(suspended);
                default -> throw failure;
            };
            SynchronousMasking.set(this, java.util.Objects.requireNonNull(mask));
            StackAnnotations.set(this, java.util.Objects.requireNonNull(annotations));
            throw new STMRestart(request);
        }
    }
    private void nested(ManagedSTM stm, ManagedSTM.Transaction parent, VirtualFrame frame, Object action, TupleDispatch call) {
        var child = stm.beginNested(parent);
        try {
            call.execute(frame, ApplicationKt.requireClosure(force.execute(frame, action)), new Object[]{kotlin.Unit.INSTANCE});
            stm.commitNested(parent, child);
        } catch (Throwable failure) { stm.abort(parent, child); throw failure; }
        finally { stm.restore(parent); }
    }
}
