// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node

/** A single deadline survives safepoint wakeups and captured guest continuations. */
internal class ThreadDelayToken(private val owner: GuestThreads, microseconds: Long) {
    private val deadline = System.nanoTime() + microseconds.coerceIn(0L, Long.MAX_VALUE / 1000L) * 1000L

    @TruffleBoundary(transferToInterpreterOnException = false)
    fun await(node: Node, async: Boolean, compiledAtCut: Boolean) {
        if (GuestThreads.current(node) !== owner) fault("Delay belongs to another guest context")
        TruffleSafepoint.setBlockedThreadInterruptibleFunction(node,
            TruffleSafepoint.InterruptibleFunction<ThreadDelayToken, Unit> { token ->
                while (true) {
                    if (async) owner.poll(node, interruptible = true)?.let {
                        it.compiledCapture = compiledAtCut
                        throw AsyncBlocked(it, node)
                    }
                    val remaining = token.deadline - System.nanoTime()
                    if (remaining <= 0L) break
                    GuestThreads.blocking(GuestThreadStatus.DELAY).use {
                        Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                    }
                }
            }, this)
    }
}

internal class DelayThread(@field:Child private var duration: Expr, @field:Child private var state: Expr,
                           private val async: Boolean, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }

    private class Resume(private val node: DelayThread, private val token: ThreadDelayToken) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any {
            if (input !== Unit) fault("Invalid delay continuation")
            node.await(token)
            return Unit
        }
    }

    private fun await(token: ThreadDelayToken) {
        try { token.await(this, async, CompilerDirectives.inCompiledCode()) }
        catch (blocked: AsyncBlocked) {
            throw AstCapture(blocked.request, SynchronousMasking.current(this)).append(Resume(this, token))
        }
    }

    override fun execute(frame: VirtualFrame): Any {
        val microseconds = duration.executeRequiredLong(frame)
        requireVoidCarrier(state.execute(frame))
        await(ThreadDelayToken(GuestThreads.current(this), microseconds))
        return Unit
    }
}
