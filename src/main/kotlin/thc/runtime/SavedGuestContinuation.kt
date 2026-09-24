// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.bytecode.ContinuationResult

/** A cold, one-shot view of a parked guest activation. Its identity is the
 * exact object published in a thunk or call segment's state-5 value slot. */
internal interface SavedGuestContinuation {
    val identity: Any
    val yielded: Any?
    val sourceRoot: Any
    fun continueWith(input: Any?): Any?
}

/** Keep existing bytecode state-5 values raw for exact private request checks. */
private class BytecodeSavedContinuation(private val saved: ContinuationResult) : SavedGuestContinuation {
    override val identity: Any get() = saved
    override val yielded: Any? get() = saved.result
    override val sourceRoot: Any get() = saved.continuationRootNode.sourceRootNode
    override fun continueWith(input: Any?): Any? = saved.continueWith(input)
}

internal fun savedGuestContinuation(value: Any?): SavedGuestContinuation? = when (value) {
    is SavedGuestContinuation -> value
    is ContinuationResult -> BytecodeSavedContinuation(value)
    else -> null
}

internal fun SavedGuestContinuation.asyncRequest(): AsyncRequest? = when (val marker = yielded) {
    is AsyncRequest -> marker
    is ThunkSuspended -> marker.asyncRequest
    is CallSegmentSuspended -> marker.asyncRequest
    else -> null
}
