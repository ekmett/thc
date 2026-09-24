// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.nodes.Node

/** Context-owned requests for the existing private, exact catch# continuation cut. */
internal class CapturedAsyncRequests {
    private val active = linkedSetOf<CapturedAsyncRequest>()
    private val byParent = java.util.IdentityHashMap<Any, CapturedAsyncRequest>()
    private var closed = false

    @Synchronized fun submit(parent: Any, child: CallSegment, payload: Any?): CapturedAsyncRequest {
        check(!closed) { "Guest context has closed" }
        val saved = when (parent) {
            is Thunk -> synchronized(parent.monitor) { if (parent.state == 5) parent.value as? ContinuationResult else null }
            is CallSegment -> synchronized(parent.monitor) { if (parent.state == 5) parent.value as? ContinuationResult else null }
            else -> null
        }
        check(saved?.continuationRootNode?.sourceRootNode is BytecodeRoot &&
            (saved.result as? CallSegmentSuspended)?.segment === child &&
            child.caughtIOAction && child.tupleShape != null) {
            "Async request requires the exact parked catch# action"
        }
        check(!byParent.containsKey(parent)) { "This captured boundary already has a pending request" }
        return CapturedAsyncRequest(this, parent, child, payload).also {
            active.add(it)
            byParent[parent] = it
        }
    }

    @Synchronized internal fun finished(request: CapturedAsyncRequest) {
        active.remove(request)
        byParent.remove(request.parent, request)
    }

    fun close() {
        val pending = synchronized(this) {
            closed = true
            active.toList().also { active.clear(); byParent.clear() }
        }
        pending.forEach(CapturedAsyncRequest::contextClosed)
    }
}

internal enum class CapturedRequestState { PENDING, COMMITTED, ACKNOWLEDGED, CANCELLED, FAILED }

/** The parent and child are logical continuation identities, never carrier Thread identities. */
internal class CapturedAsyncRequest internal constructor(
    private val requests: CapturedAsyncRequests,
    val parent: Any,
    val child: CallSegment,
    val payload: Any?
) {
    private val monitor = java.lang.Object()
    @Volatile var state = CapturedRequestState.PENDING
        private set

    /** Called while holding the exact parent's claim monitor, before consuming its continuation. */
    internal fun commit(parent: Any, child: CallSegment): Boolean = synchronized(monitor) {
        if (this.parent !== parent || this.child !== child || state != CapturedRequestState.PENDING)
            false
        else {
            state = CapturedRequestState.COMMITTED
            monitor.notifyAll()
            true
        }
    }

    /** The original catch# has accepted this async-origin payload, not merely noticed a safepoint. */
    @TruffleBoundary fun acknowledge() {
        synchronized(monitor) {
            check(state == CapturedRequestState.COMMITTED) { "Async delivery was not committed" }
            state = CapturedRequestState.ACKNOWLEDGED
            monitor.notifyAll()
        }
        requests.finished(this)
    }

    fun cancel(): Boolean {
        val cancelled = synchronized(monitor) {
            if (state != CapturedRequestState.PENDING) false
            else {
                state = CapturedRequestState.CANCELLED
                monitor.notifyAll()
                true
            }
        }
        if (cancelled) requests.finished(this)
        return cancelled
    }

    internal fun fail() {
        val failed = synchronized(monitor) {
            if (state == CapturedRequestState.PENDING || state == CapturedRequestState.COMMITTED) {
                state = CapturedRequestState.FAILED
                monitor.notifyAll()
                true
            } else false
        }
        if (failed) requests.finished(this)
    }

    internal fun contextClosed() = fail()

    /** This waits for actual delivery; interruption can revoke only an uncommitted request. */
    @TruffleBoundary fun await(node: Node): CapturedRequestState {
        try {
            return TruffleSafepoint.setBlockedThreadInterruptibleFunction(node, waitForTerminal, this)
        } catch (failure: Throwable) {
            cancel()
            throw failure
        }
    }

    private fun waitForTerminal(): CapturedRequestState = synchronized(monitor) {
        while (state == CapturedRequestState.PENDING || state == CapturedRequestState.COMMITTED)
            monitor.wait()
        state
    }

    companion object {
        private val waitForTerminal = TruffleSafepoint.InterruptibleFunction<CapturedAsyncRequest, CapturedRequestState> {
            it.waitForTerminal()
        }
    }
}
