// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.nodes.Node
import thc.Language

/** A scheduling cut owned by the current guest entry, never an async delivery. */
internal object AstStackSpill

/** Re-enter a saved caller's scopes while its exact child remains parked. */
internal class AstChildSuspension(val child: Any, val request: AsyncRequest)

/** Each public/forked/callback entry owns its driver, including reentrant entries. */
internal class AstStackScope {
    var depth = 0
    var driving = false
    var spills = 0L
    companion object { const val MAX_DEPTH = 64 }
}

internal fun astStackScope(node: Node): AstStackScope =
    Language.currentState(node).threadPollState.get().astStack

internal fun SavedGuestContinuation.stackSpill(): Boolean {
    val marker = yielded
    return when {
        marker === AstStackSpill -> true
        marker is ThunkSuspended -> marker.stackSpill
        marker is CallSegmentSuspended -> marker.stackSpill
        else -> false
    }
}

/** The driver's retained segment may acquire a new async cut deep in its chain.
 * Keep that delivery identity without replacing any of its unfinished callers. */
internal class AstStackContinuation(
    override val sourceRoot: Any,
    override val yielded: CallSegmentSuspended
) : SavedGuestContinuation {
    override val identity: Any get() = this
    private val claimed = java.util.concurrent.atomic.AtomicBoolean()

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    override fun continueWith(input: Any?): Any? {
        if (!claimed.compareAndSet(false, true)) fault("AST stack continuation was already resumed")
        if (input is AstChildSuspension) {
            if (input.child !== yielded.segment) fault("AST stack continuation received an unrelated child cut")
            return AstStackContinuation(sourceRoot,
                CallSegmentSuspended(yielded.segment, asyncRequest = input.request, stackSpill = false))
        }
        val resumed = input as? ChildResume ?: fault("AST stack continuation requires ChildResume")
        resumed.failure?.let { throw it }
        val segment = yielded.segment
        if (segment.state != 2 || segment.value !== resumed.value)
            fault("AST stack continuation lost its completed segment")
        return resumed.value
    }
}
