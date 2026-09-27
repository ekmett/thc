// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node

/** Callback authority of a synchronous managed foreign activation. This is not
 * a promise of an interruptible native transport or permission to leave Truffle. */
internal enum class ForeignSafety {
    UNSAFE, SAFE;

    companion object {
        fun synchronous(declared: String): ForeignSafety = when (declared) {
            "unsafe" -> UNSAFE
            "safe" -> SAFE
            else -> fault("Unsupported synchronous foreign safety: $declared")
        }
    }
}

/** The typed result is already in the caller's destination before this cut. */
internal object AstForeignCompleted : AstResumeStep {
    override fun resume(frame: VirtualFrame, input: Any?): Any? {
        if (input !== Unit) fault("Invalid completed foreign-call continuation")
        return null
    }

    fun poll(node: Node) {
        if (!AstControl.enabled(node)) return
        val compiled = CompilerDirectives.inCompiledCode()
        GuestThreads.pollCurrent(node, false)?.let { request ->
            request.compiledCapture = compiled
            throw AstCapture(request, SynchronousMasking.current(node)).append(this)
        }
    }
}
