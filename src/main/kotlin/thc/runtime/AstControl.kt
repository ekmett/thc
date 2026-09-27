// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node

/** Cold capture at an AST edge whose callee has already saved its own body. */
internal object AstControl {
    /** External delivery remains separate from internal scheduling captures. */
    fun enabled(node: Node): Boolean = (node.rootNode as? FunctionRoot)?.enableAsync == true
    fun captures(node: Node): Boolean = (node.rootNode as? FunctionRoot)?.capturesContinuations == true

    private class ResumeChild(private val child: Any, private val node: Node) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? = resumeChild(child, node, input, this)
    }

    @JvmStatic fun resumeChild(child: Any, node: Node, input: Any?, step: AstResumeStep): Any? {
        if (input is TailCall && AstTailAnchor.accepts(astStackScope(node).tailAnchor, input)) throw input
        if (input is AstChildSuspension) {
            if (input.child !== child) fault("AST caller received an unrelated child cut")
            val marker = when (child) {
                is Thunk -> ThunkSuspended(child, input.request)
                is CallSegment -> CallSegmentSuspended(child, asyncRequest = input.request, stackSpill = false)
                else -> fault("Invalid AST suspended child")
            }
            throw AstCapture(marker, SynchronousMasking.current(node)).append(step)
        }
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

    private class RetryForce(private val node: Node, private val force: Force,
                             private val value: Any?) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input !== Unit) fault("AST blackhole continuation requires Unit")
            return AstControl.force(frame, node, force, value)
        }
    }

    fun force(frame: VirtualFrame, node: Node, force: Force, value: Any?): Any? {
        if (!captures(node)) return force.execute(frame, value)
        return try { force.execute(frame, value) }
        catch (suspended: ThunkSuspended) {
            throw AstCapture(suspended, SynchronousMasking.current(node)).append(ResumeChild(suspended.thunk, node))
        } catch (suspended: CallSegmentSuspended) {
            throw AstCapture(suspended, SynchronousMasking.current(node)).append(ResumeChild(suspended.segment, node))
        } catch (blocked: AsyncBlocked) {
            throw AstCapture(blocked.request, SynchronousMasking.current(node)).append(RetryForce(node, force, value))
        }
    }

    fun complete(node: Node, result: Any?, expectedTarget: RootCallTarget? = null,
                 tupleShape: TupleShape? = null, identityTail: Boolean = false): Any? {
        if (!captures(node)) return result
        if (result !is TailYield && result !is AstTailYield &&
            result !is SavedGuestContinuation && result !is ContinuationResult) return result
        return completeSuspended(node, result, expectedTarget, tupleShape, identityTail)
    }

    // The ordinary result path must not inline continuation adapters, ownership
    // records and capture diagnostics at every call site. No guest frame crosses
    // this cold boundary; the caller appends its own suffix after the capture.
    @TruffleBoundary(transferToInterpreterOnException = false)
    private fun completeSuspended(node: Node, result: Any?, expectedTarget: RootCallTarget?,
                                  tupleShape: TupleShape?, identityTail: Boolean): Any? {
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
        val caller = node.rootNode as? FunctionRoot
        if (identityTail && tupleShape == null && saved is AstContinuation &&
            (saved.rootEntrySpill || saved.tailSpill) && saved.stackSpill() && saved.asyncRequest() == null &&
            caller?.isTailSpillIdentityRoot() == true &&
            root is FunctionRoot && root.isTailSpillIdentityRoot() && root.role == FunctionRootRole.PASS_THROUGH &&
            caller.scalarResultProof.kind == root.scalarResultProof.kind &&
            caller.scalarResultProof.isInt == root.scalarResultProof.isInt) {
            if (saved.rootEntrySpill) saved.certifyTailEntry()
            val pending = AstPendingTail(saved, tailTarget ?: expectedTarget ?: root.callTarget, node, callerMask)
            throw AstCapture(pending, callerMask).append(pending)
        }
        val parkedMask = (saved.yielded as? CallSegmentSuspended)?.parkedActiveMask
        val segment = CallSegment(saved.identity, parkedMask ?: callerMask, callerMask, tupleShape ?: root.tupleResult)
        val suspended = CallSegmentSuspended(segment, asyncRequest = saved.asyncRequest(), stackSpill = saved.stackSpill())
        throw AstCapture(suspended, callerMask).append(ResumeChild(segment, node))
    }
}
