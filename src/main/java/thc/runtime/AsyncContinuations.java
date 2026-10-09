// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class AsyncContinuations {
    public static final AsyncContinuations INSTANCE = new AsyncContinuations();
    private AsyncContinuations() {}
    public static boolean isYieldMarker(Object value) {
        return value == thc.runtime.Unit.INSTANCE || value == AstStackSpill.INSTANCE ||
            value instanceof PendingWait || value instanceof ThunkSuspended || value instanceof CallSegmentSuspended || value instanceof AsyncRequest;
    }
    public static AsyncRequest request(ContinuationResult continuation) {
        return switch (continuation.getResult()) {
            case AsyncRequest request -> request;
            case ThunkSuspended suspended -> suspended.getAsyncRequest();
            case CallSegmentSuspended suspended -> suspended.getAsyncRequest();
            case null, default -> null;
        };
    }
    @TruffleBoundary public static void deliverIfCaught(SavedGuestContinuation continuation, boolean caught, Node node) {
        if (!caught) return;
        AsyncRequest request = continuation.asyncRequest();
        if (request == null) return;
        if (request.getTarget() != Thread.currentThread() || request.getState() != AsyncRequestState.CLAIMED)
            throw new IllegalStateException("Async delivery left its target thread or was already consumed");
        throw new AsyncDelivery(request, node);
    }
    @TruffleBoundary public static RuntimeException uncaught(AsyncRequest request, Node node) {
        if (request.getTarget() != Thread.currentThread() || request.getState() != AsyncRequestState.CLAIMED)
            throw new IllegalStateException("Uncaught async request left its target or was already settled");
        boolean foreignCallback = request.inForeignCallback();
        request.acknowledge();
        GuestException guest = new GuestException(request.getPayload(), node);
        if (foreignCallback) throw new ForeignCallbackAsyncFailure(request.getPayload(), guest, node);
        throw guest;
    }
    public static Object publicResult(Object result, Node node) {
        SavedGuestContinuation ast = result instanceof SavedGuestContinuation saved ? saved :
            result instanceof AstTailYield tail ? tail.getContinuation() : null;
        if (ast != null) {
            AsyncRequest pending = ast.asyncRequest();
            if (pending == null) throw fault("Guest AST continuation escaped without an async request");
            return uncaught(pending, node);
        }
        ContinuationResult continuation;
        if (result instanceof ContinuationResult saved) continuation = saved;
        else if (result instanceof TailYield tail) continuation = tail.getContinuation();
        else if (result instanceof ThunkSuspended suspended) return publicSuspension(suspended, node);
        else if (result instanceof CallSegmentSuspended suspended) return publicSuspension(suspended, node);
        else return result;
        AsyncRequest pending = request(continuation);
        if (pending == null) throw fault("Guest continuation escaped without an async request");
        return uncaught(pending, node);
    }
    public static RuntimeException publicSuspension(ThunkSuspended suspended, Node node) {
        AsyncRequest pending = suspended.getAsyncRequest();
        if (pending == null) throw fault("Guest thunk suspension escaped without an async request");
        return uncaught(pending, node);
    }
    public static RuntimeException publicSuspension(CallSegmentSuspended suspended, Node node) {
        AsyncRequest pending = suspended.getAsyncRequest();
        if (pending == null) throw fault("Guest call suspension escaped without an async request");
        return uncaught(pending, node);
    }
}
