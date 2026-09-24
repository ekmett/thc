// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.exception.AbstractTruffleException
import com.oracle.truffle.api.nodes.Node

/** Only the target's unwind carries this exception; the abandoned thunk keeps its continuation. */
internal class AsyncDelivery(val request: AsyncRequest, node: Node) :
    AbstractTruffleException("Asynchronous guest exception", null, 0, node)

/** A blocking operation was cancelled before commitment and can be retried after the saved cut. */
internal class AsyncBlocked(val request: AsyncRequest, node: Node) :
    AbstractTruffleException("Asynchronous interruption before blocking operation committed", null, 0, node)

/** A trampoline has discarded every caller suffix and reached this exact tail target. */
internal class TailYield(val continuation: ContinuationResult, val target: RootCallTarget) {
    init {
        check((continuation.continuationRootNode.sourceRootNode as? GuestRoot)?.isSelf(target) == true) {
            "Tail continuation does not belong to the final tail target"
        }
    }
}

internal object AsyncContinuations {
    @JvmStatic fun isYieldMarker(value: Any?): Boolean = value === Unit ||
        value is ThunkSuspended || value is CallSegmentSuspended || value is AsyncRequest

    // Copy the delivery identity before publishing a shared continuation. Another
    // evaluator may already have consumed that continuation when its owner unwinds.
    @JvmStatic fun request(continuation: ContinuationResult): AsyncRequest? = when (val cut = continuation.result) {
        is AsyncRequest -> cut
        is ThunkSuspended -> cut.asyncRequest
        is CallSegmentSuspended -> cut.asyncRequest
        else -> null
    }

    @JvmStatic @TruffleBoundary fun deliverIfCaught(
        continuation: ContinuationResult, caught: Boolean, node: Node
    ) {
        if (!caught) return
        val request = request(continuation) ?: return
        check(request.target === Thread.currentThread() && request.state == AsyncRequestState.CLAIMED) {
            "Async delivery left its target thread or was already consumed"
        }
        throw AsyncDelivery(request, node)
    }

    /** A public host call has no Haskell catch frame. Settle only a claimed
     * request from this Java target thread; leave its parked thunk untouched. */
    @JvmStatic @TruffleBoundary fun uncaught(request: AsyncRequest, node: Node): Nothing {
        check(request.target === Thread.currentThread() && request.state == AsyncRequestState.CLAIMED) {
            "Uncaught async request left its target or was already settled"
        }
        request.acknowledge()
        throw GuestException(request.payload, node)
    }

    @JvmStatic fun publicResult(result: Any?, node: Node): Any? {
        val continuation = when (result) {
            is ContinuationResult -> result
            is TailYield -> result.continuation
            is ThunkSuspended -> publicSuspension(result, node)
            is CallSegmentSuspended -> publicSuspension(result, node)
            else -> return result
        }
        val pending = request(continuation) ?: fault("Guest continuation escaped without an async request")
        uncaught(pending, node)
    }

    @JvmStatic fun publicSuspension(suspended: ThunkSuspended, node: Node): Nothing {
        val pending = suspended.asyncRequest ?: (suspended.thunk.value as? ContinuationResult)?.let(::request)
            ?: fault("Guest thunk suspension escaped without an async request")
        uncaught(pending, node)
    }

    @JvmStatic fun publicSuspension(suspended: CallSegmentSuspended, node: Node): Nothing {
        val pending = suspended.asyncRequest ?: (suspended.segment.value as? ContinuationResult)?.let(::request)
            ?: fault("Guest call suspension escaped without an async request")
        uncaught(pending, node)
    }
}
