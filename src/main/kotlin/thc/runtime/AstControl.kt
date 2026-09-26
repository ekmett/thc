// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node

/** Cold capture at an AST edge whose callee has already saved its own body. */
internal object AstControl {
    fun enabled(node: Node): Boolean = (node.rootNode as? FunctionRoot)?.enableAsync == true

    private class ResumeChild(private val child: Any) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            val resumed = input as? ChildResume ?: fault("AST child continuation requires ChildResume")
            resumed.failure?.let { throw it }
            val valid = when (child) {
                is Thunk -> child.state == 2 && child.value === resumed.value
                is CallSegment -> child.state == 2 && child.value === resumed.value
                else -> false
            }
            if (!valid) fault("AST child continuation lost its completed update")
            return resumed.value
        }
    }

    private class RetryForce(private val node: Node, private val force: Force,
                             private val value: Any?) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input !== Unit) fault("AST blackhole continuation requires Unit")
            return AstControl.force(frame, node, force, value)
        }
    }

    fun force(frame: VirtualFrame, node: Node, force: Force, value: Any?): Any? {
        if (!enabled(node)) return force.execute(frame, value)
        return try { force.execute(frame, value) }
        catch (suspended: ThunkSuspended) {
            throw AstCapture(suspended, SynchronousMasking.current(node)).append(ResumeChild(suspended.thunk))
        } catch (suspended: CallSegmentSuspended) {
            throw AstCapture(suspended, SynchronousMasking.current(node)).append(ResumeChild(suspended.segment))
        } catch (blocked: AsyncBlocked) {
            throw AstCapture(blocked.request, SynchronousMasking.current(node)).append(RetryForce(node, force, value))
        }
    }

    fun complete(node: Node, result: Any?, expectedTarget: RootCallTarget? = null,
                 tupleShape: TupleShape? = null): Any? {
        if (!enabled(node)) return result
        val tailTarget = when (result) {
            is TailYield -> result.target
            is AstTailYield -> result.target
            else -> null
        }
        val saved = when (result) {
            is TailYield -> savedGuestContinuation(result.continuation)
            is AstTailYield -> result.continuation
            else -> savedGuestContinuation(result)
        } ?: return result
        val root = saved.sourceRoot as? GuestRoot ?: fault("AST call returned a non-guest continuation")
        if (tailTarget != null && !root.isSelf(tailTarget) ||
            tailTarget == null && expectedTarget != null && !root.isSelf(expectedTarget))
            fault("AST call returned an unrelated continuation")
        if (tupleShape != null && !root.hasTupleResult(tupleShape))
            fault("AST call continuation changed its tuple result shape")
        if (!AsyncContinuations.isYieldMarker(saved.yielded))
            fault("AST call returned an unsupported continuation cut")
        val callerMask = SynchronousMasking.current(node)
        val parkedMask = (saved.yielded as? CallSegmentSuspended)?.parkedActiveMask
        val segment = CallSegment(saved.identity, parkedMask ?: callerMask, callerMask, tupleShape ?: root.tupleResult)
        val suspended = CallSegmentSuspended(segment, asyncRequest = saved.asyncRequest())
        throw AstCapture(suspended, callerMask).append(ResumeChild(segment))
    }
}
