// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import thc.runtime.CoreFreeVariables.coreFreeVariables

import thc.runtime.CoreCallDemands.CALL_DEMANDS_PROPERTY

import thc.runtime.Scalar64Primitives.scalar64PrimitiveOperation
import thc.runtime.Scalar64Primitives.word64Literal

import thc.runtime.BitPrimitives.scalarBitPrimitiveShift
import thc.runtime.FloatingPrimitives.floatingPrimitive

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.dsl.TypeSystemReference
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.nodes.*
import com.oracle.truffle.api.profiles.BranchProfile
import com.oracle.truffle.api.profiles.CountingConditionProfile
import com.oracle.truffle.api.source.SourceSection

/* Indexed frames, selective captures, rooted application and self-tail frame
 * restoration follow Cadenza. See NOTICE.md and LICENSE.txt. Haskell thunks
 * supply the additional lazy update/blackhole protocol. Async-capable AST
 * roots spill bounded non-tail activation chains to saved continuations. */
internal fun fault(message: String): Nothing {
    CompilerDirectives.transferToInterpreterAndInvalidate()
    throw RuntimeFault(message)
}
/** Machine-word narrowing retains the Word# Long carrier. */
internal fun narrowWordPrimitiveMask(name: String): Long = when (name) {
    "narrow8Word#" -> 0xffL
    "narrow16Word#" -> 0xffffL
    "narrow32Word#" -> 0xffff_ffffL
    else -> 0L
}
internal fun narrowWordLiteral(kind: String, value: String): Int {
    val maximum = when (kind) {
        "word8" -> 0xffL; "word16" -> 0xffffL; "word32" -> 0xffff_ffffL
        else -> throw RuntimeFault("Invalid narrow word literal kind: $kind")
    }
    val number = value.toLongOrNull()
    if (number == null || number !in 0L..maximum || number.toString() != value)
        throw RuntimeFault("Invalid $kind literal: $value")
    return number.toInt()
}
/** Narrow literals have canonical source spellings and an Int computation carrier. */
internal fun int8Literal(value: String): Int {
    val number = value.toLongOrNull()
    if (number == null || number !in Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong() || number.toString() != value)
        throw RuntimeFault("Invalid int8 literal: $value")
    return number.toInt()
}
internal fun int16Literal(value: String): Int {
    val number = value.toLongOrNull()
    if (number == null || number !in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong() || number.toString() != value)
        throw RuntimeFault("Invalid int16 literal: $value")
    return number.toInt()
}
internal fun int32Literal(value: String): Int {
    val number = value.toLongOrNull()
    if (number == null || number !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || number.toString() != value)
        throw RuntimeFault("Invalid int32 literal: $value")
    return number.toInt()
}
/** Int64 literals are canonical decimal signed 64-bit carriers, including both endpoints. */
internal fun int64Literal(value: String): Long {
    val number = value.toLongOrNull()
    if (number == null || number.toString() != value) throw RuntimeFault("Invalid int64 literal: $value")
    return number
}
private data class MemoizedGuestFailure(val payload: Any?, val location: Node, val someException: Boolean)
private class Literal(private val value: Any?) : Expr() {
    init { representation = CoreRepresentation(when (value) {
        is Int, is Long -> CoreKind.LONG; is Float -> CoreKind.FLOAT; is Double -> CoreKind.DOUBLE
        is ManagedAddress -> CoreKind.ADDRESS; Unit -> CoreKind.VOID; else -> CoreKind.OBJECT
    }, evaluated = true) }
    override fun execute(frame: VirtualFrame) = value
    override fun executeInt(frame: VirtualFrame) = RuntimeTypesGen.expectInteger(value)
    override fun executeLong(frame: VirtualFrame) = RuntimeTypesGen.expectLong(value)
    override fun executeFloat(frame: VirtualFrame) = RuntimeTypesGen.expectFloat(value)
    override fun executeDouble(frame: VirtualFrame) = RuntimeTypesGen.expectDouble(value)
}
/** Replace only the successfully forced link; aliases may already have updated this cell. */
internal fun updateForcedCell(cell: RecCell, original: Thunk, result: Any?) {
    synchronized(cell) {
        if (cell.initialized && cell.value === original) cell.value = result
    }
}

internal class Force @JvmOverloads constructor(private val metrics: Metrics, private val asyncMode: Boolean = false) : Node() {
    private object Retry
    private class Parked(val boundary: Any, val continuation: SavedGuestContinuation)
    @Child private var calls = ThunkTargetCache(metrics)
    @Child private var trampoline = TailCallLoop(metrics)
    private val tailCallProfile = BranchProfile.create()
    private val resumeProfile = BranchProfile.create()
    private val suspensionProfile = BranchProfile.create()
    @CompilationFinal @Volatile private var seenThunk = false
    @Suppress("UNUSED_PARAMETER")
    fun execute(frame: VirtualFrame, original: Any?): Any? {
        if (!seenThunk) {
            if (original !is Thunk) return original
            CompilerDirectives.transferToInterpreterAndInvalidate()
            seenThunk = true
        }
        if (original !is Thunk) return original
        // Every successful update below verifies WHNF before publishing state 2.
        // Re-entering the result through a generic forcing loop loses that fact
        // and merges the suspension with its answer in the compiled graph.
        while (true) {
            when (original.state) {
                2 -> { if (metrics.enabled) metrics.incrementThunkHits(); return original.value }
                3 -> rethrowFailure(original)
                4 -> fault("Interrupted thunk has no resumable continuation")
            }
            val observed = if (original.state == 5) {
                resumeProfile.enter()
                SavedGuestContinuationKt.savedGuestContinuation(original.value)
            } else null
            val child = suspendedChild(observed)
            // The continuation owns its captured callee frame. None of the
            // update/resume helpers needs this caller's frame; materializing
            // it here poisons frame-access speculation on ordinary loop exits.
            if (child != null) return resumeChain(original)
            val result = executeOne(original, observed, Unit)
            if (result !== Retry) return result
        }
    }

    /** Private proof seam: deliver at this captured caller, leaving its exact shared child parked. */
    @CompilerDirectives.TruffleBoundary
    internal fun deliverAtCapturedHandler(original: Thunk, child: Thunk, payload: Any?): Any? {
        var claimed = false
        try {
            val continuation = synchronized(original.monitor) {
                val saved = original.value as? ContinuationResult
                if (original.state != 5 || (saved?.result as? ThunkSuspended)?.thunk !== child ||
                    child.state != 5)
                    fault("Async handler cut requires the exact parked shared child")
                original.value = null
                original.owner = Thread.currentThread()
                original.state = 1
                claimed = true
                checkNotNull(saved)
            }
            return evaluateOwned(original, SavedGuestContinuationKt.savedGuestContinuation(continuation), AsyncThunkUnwind(payload))
        } catch (failure: Throwable) {
            if (claimed) suspendOwned(original)
            throw failure
        }
    }

    /** Private request cut; the token names a logical continuation rather than a host carrier. */
    internal fun deliverAtCapturedIOHandler(request: CapturedAsyncRequest,
                                            afterClaim: (() -> Unit)? = null): Any? {
        try {
            val answer = deliverAtCapturedIOHandler(request.parent, request.child, request.payload, afterClaim, request)
            if (request.state != CapturedRequestState.ACKNOWLEDGED)
                throw IllegalStateException("Captured handler returned without acknowledging delivery")
            return answer
        } catch (failure: Throwable) {
            // An observer may have completed or reparked the parent after submit.
            // The exact-cut check then fails before ownership is claimed; do not
            // leave the sender pending or disturb the observer's result.
            request.fail()
            throw failure
        }
    }

    /** Private test cut at a captured original catch# frame; its action remains shared. */
    @CompilerDirectives.TruffleBoundary
    internal fun deliverAtCapturedIOHandler(original: Any, child: CallSegment, payload: Any?,
                                            afterClaim: (() -> Unit)? = null,
                                            request: CapturedAsyncRequest? = null): Any? {
        fun exact(saved: ContinuationResult?): Boolean =
            saved != null && saved.continuationRootNode.sourceRootNode is BytecodeRoot &&
            (saved.result as? CallSegmentSuspended)?.segment === child &&
                child.caughtIOAction && child.tupleShape != null
        return when (original) {
            is Thunk -> {
                var claimed = false
                try {
                    val continuation = synchronized(original.monitor) {
                        val saved = original.value as? ContinuationResult
                        if (original.state != 5 || !exact(saved))
                            fault("Async IO handler cut requires the exact parked action")
                        if (request != null && !request.commit(original, child))
                            fault("Async IO handler cut requires a pending request for this continuation")
                        original.value = null
                        original.owner = Thread.currentThread()
                        original.state = 1
                        claimed = true
                        checkNotNull(saved)
                    }
                    // The captured parent commits this cut. An independent observer may
                    // complete the shared child before its handler continuation runs.
                    afterClaim?.invoke()
                    evaluateOwned(original, SavedGuestContinuationKt.savedGuestContinuation(continuation), PrivateIOUnwind(child, payload, request))
                } catch (failure: Throwable) {
                    if (claimed) suspendOwned(original)
                    if (claimed) request?.fail()
                    throw failure
                }
            }
            is CallSegment -> {
                var claimed = false
                try {
                    var mask = MaskingState.UNMASKED
                    val continuation = synchronized(original.monitor) {
                        val saved = original.value as? ContinuationResult
                        if (original.state != 5 || !exact(saved))
                            fault("Async IO handler cut requires the exact parked action")
                        if (request != null && !request.commit(original, child))
                            fault("Async IO handler cut requires a pending request for this continuation")
                        mask = original.logicalMask
                        original.value = null
                        original.owner = Thread.currentThread()
                        original.state = 1
                        claimed = true
                        checkNotNull(saved)
                    }
                    afterClaim?.invoke()
                    evaluateCallSegment(original, checkNotNull(SavedGuestContinuationKt.savedGuestContinuation(continuation)), mask,
                        PrivateIOUnwind(child, payload, request))
                } catch (failure: Throwable) {
                    if (claimed) suspendCallOwned(original)
                    if (claimed) request?.fail()
                    throw failure
                }
            }
            else -> fault("Async IO handler cut requires a captured thunk or call segment")
        }
    }

    private fun executeOne(original: Thunk,
                           observed: SavedGuestContinuation?, resumeValue: Any?): Any? {
        while (true) {
            when (original.state) {
                2 -> { if (metrics.enabled) metrics.incrementThunkHits(); return original.value }
                3 -> rethrowFailure(original)
                4 -> fault("Interrupted thunk has no resumable continuation")
            }
            // A volatile state read alone cannot claim an unevaluated thunk:
            // another thread can enter during the single-threaded transition.
            var continuation: SavedGuestContinuation? = null
            var claimedHere = false
            try {
                val claim = synchronized(original.monitor) {
                    when (original.state) {
                        0 -> { original.owner = Thread.currentThread(); original.state = 1; claimedHere = true; 0 }
                        5 -> {
                            resumeProfile.enter()
                            if ((observed != null && original.value !== observed.identity) ||
                                (observed == null &&
                                    (suspendedChild(SavedGuestContinuationKt.savedGuestContinuation(original.value)) != null))) 3 else {
                                continuation = SavedGuestContinuationKt.savedGuestContinuation(original.value)
                                    ?: fault("Suspended thunk has no guest continuation")
                                original.value = null // One owner consumes the one-shot continuation.
                                original.owner = Thread.currentThread()
                                original.state = 1
                                claimedHere = true
                                0
                            }
                        }
                        1 -> if (original.owner === Thread.currentThread()) 2 else 1
                        else -> 3
                    }
                }
                when (claim) {
                    0 -> return evaluateOwned(original, continuation, resumeValue)
                    1 -> awaitOwner(original)
                    2 -> { if (metrics.enabled) metrics.incrementBlackholes(); fault("Blackhole: cyclic thunk entered while evaluating") }
                    3 -> return Retry
                }
            } catch (failure: Throwable) {
                // A safepoint can transfer control after the ownership store but
                // before evaluateOwned's handler begins. Never strand state 1.
                if (claimedHere) suspendOwned(original)
                throw failure
            }
        }
    }

    @CompilerDirectives.TruffleBoundary
    private fun resumeChain(original: Any, drainSpills: Boolean = false,
                            delimitedInvocation: Boolean = false): Any? {
        val parked = java.util.ArrayDeque<Parked>()
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        var leaf: Any = original
        while (true) {
            var leafContinuation: SavedGuestContinuation? = null
            while (true) {
                if (seen.put(leaf, true) != null) fault("Suspended thunk dependency cycle")
                leafContinuation = continuationOf(leaf)
                if (delimitedInvocation && leafContinuation?.yielded is DelimitedCut)
                    throw UnsupportedCore("control0# cannot recapture a parked one-shot invocation chain")
                val child = suspendedChild(leafContinuation) ?: break
                parked.addLast(Parked(leaf, leafContinuation!!))
                if (drainSpills) {
                    val scope = AstStackKt.astStackScope(this)
                    scope.maxParkedSpillParents = maxOf(scope.maxParkedSpillParents, parked.size)
                }
                leaf = child
            }
            var current: Any = leaf
            var expected = leafContinuation
            var input: Any? = Unit
            while (true) {
                var spilled = false
                var outcome: Any? = try {
                    val answer = when (current) {
                        is Thunk -> executeOne(current, expected, input)
                        is CallSegment -> executeCallSegment(current, expected, input)
                        else -> fault("Invalid suspended continuation boundary")
                    }
                    if (answer === Retry) null else ChildResume(answer, null)
                } catch (suspension: ThunkSuspended) {
                    if (drainSpills && (suspension.stackSpill || delimitedInvocation) && suspension.asyncRequest == null) {
                        spilled = true
                        null
                    } else if (suspension.thunk === current && suspension.asyncRequest != null &&
                        astCaller(parked.peekLast())) {
                        AstChildSuspension(current, suspension.asyncRequest)
                    } else {
                        // Resignal the requested update boundary, not a deeper child.
                        resignalParked(original, suspension.asyncRequest, suspension.stackSpill)
                        throw suspension
                    }
                } catch (suspension: CallSegmentSuspended) {
                    if (drainSpills && (suspension.stackSpill || delimitedInvocation) && suspension.asyncRequest == null) {
                        spilled = true
                        null
                    } else if (suspension.segment === current && suspension.asyncRequest != null &&
                        astCaller(parked.peekLast())) {
                        AstChildSuspension(current, suspension.asyncRequest)
                    } else {
                        // The requested thunk is still parked even if a deeper
                        // dependency yielded again. A new caller must capture the
                        // requested update boundary, not skip its continuation.
                        resignalParked(original, suspension.asyncRequest, suspension.stackSpill)
                        throw suspension
                    }
                } catch (cut: DelimitedCut) {
                    if (delimitedInvocation)
                        throw UnsupportedCore("control0# cannot recapture a parked one-shot invocation chain")
                    throw cut
                } catch (failure: GuestException) { ChildResume(null, failure) }
                catch (tail: TailCall) {
                    if (!AstTailAnchor.accepts(AstStackKt.astStackScope(this).tailAnchor, tail)) throw tail
                    // Unwind each retained AST scope before entering the live anchor.
                    // This is not a guest exception, and never acknowledges delivery.
                    tail
                }
                val pending = (input as? AstChildSuspension)?.request
                    ?.takeIf { it.state == AsyncRequestState.CLAIMED }
                if (pending != null && (outcome is ChildResume || outcome == null && !spilled)) {
                    // Another evaluator may have completed or reparked this caller.
                    // Forward the delivery through its still-saved lexical callers;
                    // neither the result nor an identity race consumes the token.
                    outcome = if (astCaller(parked.peekLast())) AstChildSuspension(current, pending)
                        else resignalPending(original, pending)
                }
                if (outcome == null) {
                    if (spilled) {
                        // The driver still owns this deque. Descend only the new cut,
                        // retaining older callers instead of rescanning the whole chain.
                        leaf = current
                        seen.remove(current)
                    } else {
                        // Another evaluator advanced a link; rescan from the root.
                        leaf = original
                        parked.clear()
                        seen.clear()
                    }
                    break
                }
                seen.remove(current)
                if (parked.isEmpty()) {
                    if (outcome is TailCall) throw outcome
                    val completed = outcome as? ChildResume ?: fault("AST child cut has no saved caller")
                    completed.failure?.let { throw it }
                    return completed.value
                }
                val parent = parked.removeLast()
                current = parent.boundary
                expected = parent.continuation
                input = outcome
            }
        }
    }

    private fun astCaller(parked: Parked?): Boolean =
        parked?.continuation is AstContinuation || parked?.continuation is AstStackContinuation

    private fun resignalPending(original: Any, request: AsyncRequest): Nothing {
        resignalParked(original, request, false)
        // A completed shared update can be read after delivery without replay.
        throw AsyncBlocked(request, this)
    }

    private fun resignalParked(original: Any, request: AsyncRequest?, stackSpill: Boolean) {
        when (original) {
            is Thunk -> if (original.state == 5) throw ThunkSuspended(original, request, stackSpill)
            is CallSegment -> if (original.state == 5)
                throw CallSegmentSuspended(original, null, request, stackSpill)
        }
    }

    /** Drain after unwinding to the entry driver or a cold invocation-owned boundary. */
    @CompilerDirectives.TruffleBoundary
    internal fun drainStack(initial: SavedGuestContinuation,
                            resultShape: TupleShape? = (initial.sourceRoot as? GuestRoot)?.tupleResult,
                            delimitedInvocation: Boolean = false, tailSpill: Boolean = false): Any? {
        val mask = SynchronousMasking.current(this)
        val root = initial.sourceRoot as? GuestRoot ?: fault("AST stack cut has no guest root")
        val parkedMask = (initial.yielded as? CallSegmentSuspended)?.parkedActiveMask ?: mask
        val segment = CallSegment(initial.identity, parkedMask, mask, resultShape, false, tailSpill)
        while (true) {
            try { return resumeChain(segment, drainSpills = true, delimitedInvocation = delimitedInvocation) }
            catch (cut: CallSegmentSuspended) {
                if (cut.segment !== segment) throw cut
                if (!cut.stackSpill || cut.asyncRequest != null)
                    return AstStackContinuation(root, cut)
            }
        }
    }

    /** The caller already owns the saved invocation, not a new evaluation of its prefix. */
    @CompilerDirectives.TruffleBoundary
    internal fun drainDelimitedBoundary(boundary: Any): Any? =
        resumeChain(boundary, drainSpills = true, delimitedInvocation = true)

    private fun continuationOf(boundary: Any): SavedGuestContinuation? = when (boundary) {
        is Thunk -> if (boundary.state == 5) SavedGuestContinuationKt.savedGuestContinuation(boundary.value) else null
        is CallSegment -> if (boundary.state == 5) SavedGuestContinuationKt.savedGuestContinuation(boundary.value) else null
        else -> fault("Invalid suspended continuation boundary")
    }

    private fun suspendedChild(continuation: SavedGuestContinuation?): Any? = when (val signal = continuation?.yielded) {
        is ThunkSuspended -> signal.thunk
        is CallSegmentSuspended -> signal.segment
        else -> null
    }

    private fun executeCallSegment(segment: CallSegment, observed: SavedGuestContinuation?, resumeValue: Any?): Any? {
        while (true) {
            when (segment.state) {
                2 -> return segment.value
                3 -> rethrowCallFailure(segment)
                4 -> fault("Interrupted call segment has no resumable continuation")
            }
            var continuation: SavedGuestContinuation? = null
            var resumeMask = MaskingState.UNMASKED
            var claimedHere = false
            try {
                val claim = synchronized(segment.monitor) {
                    when (segment.state) {
                        5 -> if ((observed != null && segment.value !== observed.identity) ||
                            (observed == null && suspendedChild(SavedGuestContinuationKt.savedGuestContinuation(segment.value)) != null)) 3 else {
                            continuation = SavedGuestContinuationKt.savedGuestContinuation(segment.value)
                                ?: fault("Suspended call segment has no guest continuation")
                            resumeMask = segment.logicalMask
                            segment.value = null // Consume the one-shot continuation under ownership.
                            segment.owner = Thread.currentThread()
                            segment.state = 1
                            claimedHere = true
                            0
                        }
                        1 -> if (segment.owner === Thread.currentThread()) 2 else 1
                        else -> 3
                    }
                }
                when (claim) {
                    0 -> return evaluateCallSegment(segment, continuation!!, resumeMask, resumeValue)
                    1 -> awaitCallOwner(segment)
                    2 -> fault("Blackhole: cyclic call segment entered while evaluating")
                    3 -> return Retry
                }
            } catch (failure: Throwable) {
                if (claimedHere) suspendCallOwned(segment)
                throw failure
            }
        }
    }

    private fun evaluateCallSegment(segment: CallSegment, continuation: SavedGuestContinuation,
                                    resumeMask: MaskingState, resumeValue: Any?): Any? {
        val carrierAmbient = SynchronousMasking.current(this)
        val carrierAnnotations = StackAnnotations.current(this)
        try {
            SynchronousMasking.set(this, resumeMask)
            if (segment.tailSpill && continuation is AstContinuation && continuation.tailSpill) {
                AstStackKt.astStackScope(this).tailAnchor?.let { continuation.rebaseTailBloom(it.liveBloom()) }
            }
            val returned = try { continuation.continueWith(resumeValue) }
                catch (tail: TailCall) {
                    if (segment.tailSpill && AstTailAnchor.accepts(AstStackKt.astStackScope(this).tailAnchor, tail)) throw tail
                    tailCallProfile.enter(); trampoline.execute(tail)
                }
            val result = when (returned) {
                is TailYield -> returned.continuation
                is AstTailYield -> returned.continuation
                else -> returned
            }
            val saved = SavedGuestContinuationKt.savedGuestContinuation(result)
            if (saved != null) {
                if (returned !is TailYield && returned !is AstTailYield && !sameContinuationBody(saved.sourceRoot, continuation.sourceRoot))
                    throw IllegalStateException("Nested guest yield has no captured caller segment")
                val parkedMask = (saved.yielded as? CallSegmentSuspended)?.parkedActiveMask
                if (parkedMask != null && SynchronousMasking.current(this) != segment.callerMask)
                    throw IllegalStateException("Parked call segment did not restore its caller mask")
                val request = saved.asyncRequest()
                publishCallContinuation(segment, saved, parkedMask ?: SynchronousMasking.current(this))
                throw CallSegmentSuspended(segment, null, request, saved.stackSpill())
            }
            // A completed tuple may still be a producer-thread slab loan.
            // Release that loan even if the callee returned under a wrong mask.
            val answer = segment.tupleShape?.let { ownedTupleResult(result, it) } ?: result
            if (SynchronousMasking.current(this) != segment.callerMask)
                throw IllegalStateException("Completed call segment did not restore its caller mask")
            synchronized(segment.monitor) {
                segment.value = answer // A scalar call may return an unforced thunk; never enter it here.
                segment.owner = null
                segment.state = 2
                (segment.monitor as java.lang.Object).notifyAll()
            }
            return answer
        } catch (e: CallSegmentSuspended) {
            if (e.segment !== segment) suspendCallOwned(segment)
            throw e
        } catch (e: ThunkSuspended) {
            suspendCallOwned(segment)
            throw e
        } catch (e: AsyncThunkUnwind) {
            suspendCallOwned(segment)
            throw e
        } catch (e: GuestException) {
            if (SynchronousMasking.current(this) != segment.callerMask) {
                suspendCallOwned(segment)
                throw IllegalStateException("Failed call segment did not restore its caller mask", e)
            }
            publishCallFailure(segment, MemoizedGuestFailure(e.payload, e.location ?: this, e.someException))
            throw e
        } catch (e: RuntimeFault) {
            publishCallFailure(segment, e)
            throw e
        } catch (e: Throwable) {
            suspendCallOwned(segment)
            throw e
        } finally {
            SynchronousMasking.set(this, carrierAmbient)
            StackAnnotations.set(this, carrierAnnotations)
        }
    }

    private fun publishCallContinuation(segment: CallSegment, continuation: SavedGuestContinuation,
                                        logicalMask: MaskingState) {
        val safepoint = TruffleSafepoint.getCurrent()
        val previous = safepoint.setAllowSideEffects(false)
        try {
            synchronized(segment.monitor) {
                segment.value = continuation.identity
                segment.logicalMask = logicalMask
                segment.owner = null
                segment.state = 5
                (segment.monitor as java.lang.Object).notifyAll()
            }
        } finally { safepoint.setAllowSideEffects(previous) }
    }

    private fun publishCallFailure(segment: CallSegment, failure: Any) = synchronized(segment.monitor) {
        segment.value = failure
        segment.owner = null
        segment.state = 3
        (segment.monitor as java.lang.Object).notifyAll()
    }

    private fun suspendCallOwned(segment: CallSegment) = synchronized(segment.monitor) {
        if (segment.state != 1 || segment.owner !== Thread.currentThread()) return@synchronized
        segment.owner = null
        segment.state = 4
        (segment.monitor as java.lang.Object).notifyAll()
    }

    private fun rethrowCallFailure(segment: CallSegment): Nothing {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        when (val failure = segment.value) {
            is MemoizedGuestFailure -> throw GuestException(failure.payload, failure.location, failure.someException)
            is Throwable -> throw failure
            else -> fault("Invalid failed call segment")
        }
    }

    @CompilerDirectives.TruffleBoundary
    private fun awaitCallOwner(segment: CallSegment) {
        TruffleSafepoint.setBlockedThreadInterruptible(this, TruffleSafepoint.Interruptible<CallSegment> { waiting ->
            synchronized(waiting.monitor) {
                if (waiting.state == 1 && waiting.owner !== Thread.currentThread()) {
                    if (asyncMode || AstControl.enabled(this)) GuestThreads.pollCurrent(this, true)?.let { throw AsyncBlocked(it, this) }
                    GuestThreads.blocking(GuestThreadStatus.BLACK_HOLE).use { (waiting.monitor as java.lang.Object).wait() }
                }
            }
        }, segment)
    }

    private fun evaluateOwned(thunk: Thunk, continuation: SavedGuestContinuation?, resumeValue: Any?): Any? {
        try {
            if (metrics.enabled && continuation == null) {
                val target = thunk.target ?: fault("Unevaluated thunk has no body")
                metrics.incrementThunkEvaluations(); metrics.recordThunk(target)
            }
            val returned = if (continuation == null) {
                try { calls.call(thunk.target ?: fault("Unevaluated thunk has no body"), thunk.environment) }
                catch (tail: TailCall) { tailCallProfile.enter(); trampoline.execute(tail) }
            } else {
                // A captured logical computation may move to another host
                // thread. The saved activation reinstalls its logical mask;
                // the carrier's ambient mask must survive the entire resumed
                // chain, including a tail-call trampoline.
                val ambient = SynchronousMasking.current(this)
                val ambientAnnotations = StackAnnotations.current(this)
                try {
                    try { continuation.continueWith(resumeValue) }
                    catch (tail: TailCall) { tailCallProfile.enter(); trampoline.execute(tail) }
                } finally {
                    SynchronousMasking.set(this, ambient)
                    StackAnnotations.set(this, ambientAnnotations)
                }
            }
            val result = when (returned) {
                is TailYield -> returned.continuation
                is AstTailYield -> returned.continuation
                else -> returned
            }
            if (result is SavedGuestContinuation || result is ContinuationResult) {
                // Construct the cold bytecode view only after the suspension profile.
                // Otherwise parsing its implementation can specialize the shared
                // interface before another continuation kind links in this graph.
                suspensionProfile.enter()
                val continuationResult = SavedGuestContinuationKt.savedGuestContinuation(result)
                if (continuationResult == null) CompilerDirectives.transferToInterpreter()
                val saved = continuationResult!!
                if (saved.yielded is DelimitedCut)
                    fault("control0# cannot capture across a thunk update")
                val expectedRoot = continuation?.sourceRoot
                    ?: thunk.target?.rootNode
                if (returned !is TailYield && returned !is AstTailYield && !sameContinuationBody(saved.sourceRoot, expectedRoot))
                    throw IllegalStateException("Nested guest yield has no captured caller segment")
                val request = saved.asyncRequest()
                publishContinuation(thunk, saved)
                throw ThunkSuspended(thunk, request, saved.stackSpill())
            }
            if (result is Thunk) fault("Thunk target violated WHNF convention")
            synchronized(thunk.monitor) {
                thunk.value = result
                thunk.target = null
                thunk.environment = null
                thunk.owner = null
                thunk.state = 2
                (thunk.monitor as java.lang.Object).notifyAll()
            }
            return result
        } catch (e: DelimitedCut) {
            val failure = RuntimeFault("control0# cannot capture across a thunk update")
            publishFailure(thunk, failure)
            throw failure
        } catch (e: ThunkSuspended) {
            // Without a captured caller segment, the outer update frame cannot
            // resume after this child. Release its owner and fail closed.
            if (e.thunk !== thunk) suspendOwned(thunk)
            throw e
        } catch (e: AsyncThunkUnwind) {
            suspendOwned(thunk)
            throw e
        } catch (e: GuestException) {
            publishFailure(thunk, MemoizedGuestFailure(e.payload, e.location ?: this, e.someException))
            throw e
        } catch (e: RuntimeFault) {
            publishFailure(thunk, e)
            throw e
        } catch (e: Throwable) {
            // Any escaping host failure may follow an observable effect. Without
            // a captured continuation, resetting to state 0 would replay it.
            suspendOwned(thunk)
            throw e
        }
    }

    private fun sameContinuationBody(actual: Any, expected: Any?): Boolean =
        actual === expected || actual is GuestRoot && expected is GuestRoot && actual.isSelf(expected.callTarget)

    private fun publishContinuation(thunk: Thunk, continuation: SavedGuestContinuation) {
        // A side-effecting thread-local action must not unwind between storing
        // the captured frame and publishing the released owner. This is the
        // exceptional yield path, not a cost on ordinary thunk evaluation.
        val safepoint = TruffleSafepoint.getCurrent()
        val previous = safepoint.setAllowSideEffects(false)
        try {
            synchronized(thunk.monitor) {
                thunk.value = continuation.identity
                thunk.target = null
                thunk.environment = null
                thunk.owner = null
                thunk.state = 5
                (thunk.monitor as java.lang.Object).notifyAll()
            }
        } finally {
            safepoint.setAllowSideEffects(previous)
        }
    }

    // Failure publication is cold; inlining its monitor and unwind edges at
    // every force site makes large bytecode roots exceed the PE graph budget.
    // Keep ordinary thunk evaluation and successful publication compiled.
    @CompilerDirectives.TruffleBoundary
    private fun publishFailure(thunk: Thunk, failure: Any) = synchronized(thunk.monitor) {
        thunk.value = failure
        thunk.target = null
        thunk.environment = null
        thunk.owner = null
        thunk.state = 3
        (thunk.monitor as java.lang.Object).notifyAll()
    }

    // As with memoized failure, ownership release is an exceptional exit.
    @CompilerDirectives.TruffleBoundary
    private fun suspendOwned(thunk: Thunk) = synchronized(thunk.monitor) {
        if (thunk.state != 1 || thunk.owner !== Thread.currentThread()) return@synchronized
        // An arbitrary Java/bytecode stack is not a resumable Haskell AP_STACK.
        // Retain the body for a future continuation implementation; never replay
        // effects by silently returning this thunk to state 0.
        thunk.owner = null
        thunk.state = 4
        (thunk.monitor as java.lang.Object).notifyAll()
    }

    private fun rethrowFailure(thunk: Thunk): Nothing {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        when (val failure = thunk.value) {
            is MemoizedGuestFailure -> throw GuestException(failure.payload, failure.location, failure.someException)
            is Throwable -> throw failure
            else -> fault("Invalid failed thunk")
        }
    }

    @CompilerDirectives.TruffleBoundary
    private fun awaitOwner(thunk: Thunk) {
        TruffleSafepoint.setBlockedThreadInterruptible(this, TruffleSafepoint.Interruptible<Thunk> { waiting ->
            synchronized(waiting.monitor) {
                if (waiting.state == 1 && waiting.owner !== Thread.currentThread()) {
                    if (asyncMode || AstControl.enabled(this)) GuestThreads.pollCurrent(this, true)?.let { throw AsyncBlocked(it, this) }
                    GuestThreads.blocking(GuestThreadStatus.BLACK_HOLE).use { (waiting.monitor as java.lang.Object).wait() }
                }
            }
        }, thunk)
    }
}
/** Typed execution widens per result kind; each fallback consumes the already evaluated value. */
internal class Evaluate(@field:Child private var value: Expr, metrics: Metrics) : Expr() {
    init { representation = value.representation.copy(evaluated = true); coreSourceLocation = value.coreSourceLocation }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int) = value.executeTuple(frame, slots, offset)
    @Child private var force = Force(metrics)
    private fun forceResult(frame: VirtualFrame, original: Any?): Any? {
        val result = AstControl.force(frame, this, force, original)
        // WHNF reads need no write. A failed force never reaches this point.
        if (original is Thunk && value is LocalRead) (value as LocalRead).writeForced(frame, original, result)
        return result
    }
    override fun execute(frame: VirtualFrame): Any? = if (AstControl.captures(this)) executeAsync(frame) else when {
        value.representation.isInt -> value.executeRequiredInt(frame)
        value.representation.isLong -> value.executeRequiredLong(frame)
        value.representation.isFloat -> value.executeRequiredFloat(frame)
        value.representation.isDouble -> value.executeRequiredDouble(frame)
        value.representation.evaluated -> value.execute(frame)
        else -> forceResult(frame, value.execute(frame))
    }
    private fun executeAsync(frame: VirtualFrame): Any? {
        // Preserve proof-directed primitive access even when child edges can
        // suspend, keeping primitive frame accesses visible at loop headers.
        val original = try {
            when {
                value.representation.isInt -> value.executeRequiredInt(frame)
                value.representation.isLong -> value.executeRequiredLong(frame)
                value.representation.isFloat -> value.executeRequiredFloat(frame)
                value.representation.isDouble -> value.executeRequiredDouble(frame)
                else -> value.execute(frame)
            }
        }
        catch (cut: AstCapture) {
            if (value.representation.evaluated) throw cut
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? = forceResult(frame, input)
            })
        }
        return if (value.representation.evaluated) original else forceResult(frame, original)
    }
    @CompilationFinal private var genericLong = false
    override fun executeInt(frame: VirtualFrame): Int =
        if (AstControl.captures(this)) RuntimeTypesGen.expectInteger(executeAsync(frame))
        else if (value.representation.evaluated || value.representation.isInt) value.executeInt(frame)
        else RuntimeTypesGen.expectInteger(forceResult(frame, value.execute(frame)))
    override fun executeFloat(frame: VirtualFrame): Float =
        if (AstControl.captures(this)) RuntimeTypesGen.expectFloat(executeAsync(frame))
        else if (value.representation.evaluated || value.representation.isFloat) value.executeFloat(frame)
        else RuntimeTypesGen.expectFloat(forceResult(frame, value.execute(frame)))
    override fun executeDouble(frame: VirtualFrame): Double =
        if (AstControl.captures(this)) RuntimeTypesGen.expectDouble(executeAsync(frame))
        else if (value.representation.evaluated || value.representation.isDouble) value.executeDouble(frame)
        else RuntimeTypesGen.expectDouble(forceResult(frame, value.execute(frame)))
    override fun executeLong(frame: VirtualFrame): Long {
        if (AstControl.captures(this)) return RuntimeTypesGen.expectLong(executeAsync(frame))
        if (value.representation.evaluated || value.representation.isLong) return value.executeLong(frame)
        if (genericLong) return RuntimeTypesGen.expectLong(forceResult(frame, value.execute(frame)))
        return try { value.executeLong(frame) }
        catch (unexpected: UnexpectedResultException) {
            // The exception already invalidated compiled code. Widen once, and
            // force its saved result without evaluating the child a second time.
            genericLong = true
            RuntimeTypesGen.expectLong(forceResult(frame, unexpected.result))
        }
    }
    @CompilationFinal private var genericClosure = false
    override fun executeClosure(frame: VirtualFrame): Closure {
        if (AstControl.captures(this)) return RuntimeTypesGen.expectClosure(executeAsync(frame))
        if (value.representation.evaluated || value.representation.isLong) return value.executeClosure(frame)
        if (value.representation.kind == CoreKind.CLOSURE || genericClosure) return RuntimeTypesGen.expectClosure(forceResult(frame, value.execute(frame)))
        return try { value.executeClosure(frame) }
        catch (unexpected: UnexpectedResultException) {
            // The exception already invalidated compiled code. Widen once, and
            // force its saved result without evaluating the child a second time.
            genericClosure = true
            RuntimeTypesGen.expectClosure(forceResult(frame, unexpected.result))
        }
    }
    @CompilationFinal private var genericDataValue = false
    override fun executeDataValue(frame: VirtualFrame): DataValue {
        if (AstControl.captures(this)) return RuntimeTypesGen.expectDataValue(executeAsync(frame))
        if (value.representation.evaluated || value.representation.isLong) return value.executeDataValue(frame)
        if (value.representation.kind == CoreKind.DATA || genericDataValue) return RuntimeTypesGen.expectDataValue(forceResult(frame, value.execute(frame)))
        return try { value.executeDataValue(frame) }
        catch (unexpected: UnexpectedResultException) {
            // The exception already invalidated compiled code. Widen once, and
            // force its saved result without evaluating the child a second time.
            genericDataValue = true
            RuntimeTypesGen.expectDataValue(forceResult(frame, unexpected.result))
        }
    }
    @CompilationFinal private var genericAddress = false
    override fun executeAddress(frame: VirtualFrame): ManagedAddress {
        if (AstControl.captures(this)) return RuntimeTypesGen.expectManagedAddress(executeAsync(frame))
        if (value.representation.evaluated || value.representation.isLong) return value.executeAddress(frame)
        if (value.representation.kind == CoreKind.ADDRESS || genericAddress) return RuntimeTypesGen.expectManagedAddress(forceResult(frame, value.execute(frame)))
        return try { value.executeAddress(frame) }
        catch (unexpected: UnexpectedResultException) {
            // The exception already invalidated compiled code. Widen once, and
            // force its saved result without evaluating the child a second time.
            genericAddress = true
            RuntimeTypesGen.expectManagedAddress(forceResult(frame, unexpected.result))
        }
    }
}
private class Application(function: Expr,
                          @field:Children private var arguments: Array<Expr>, tail: Boolean, metrics: Metrics) : Expr() {
    init { representation = CoreRepresentation(CoreKind.UNKNOWN, evaluated = true) }
    @Child private var function = Evaluate(function, metrics)
    private val inputLayout = ArgumentLayout.fromProofs(arguments.map { it.representation })
    @Child private var dispatch = Dispatch.create(arguments.size, tail, metrics,
        arguments.map { it.representation.evaluated }.toBooleanArray(), inputLayout)
    @ExplodeLoop override fun execute(frame: VirtualFrame): Any? {
        val fn = try { function.executeRequiredClosure(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? =
                    applyArguments(frame, ApplicationKt.requireClosure(input), arrayOfNulls(ArgumentLayout.width(inputLayout, arguments.size)), 0)
            })
        }
        val values = arrayOfNulls<Any>(ArgumentLayout.width(inputLayout, arguments.size))
        return applyArguments(frame, fn, values, 0)
    }
    @ExplodeLoop private fun applyArguments(frame: VirtualFrame, fn: Closure, values: Array<Any?>, start: Int): Any? {
        for (i in start until arguments.size) {
            try {
                if (inputLayout?.isEmpty(i) == true) arguments[i].executeTuple(frame, ArgumentLayout.EMPTY_TUPLE_SLOTS, 0)
                else values[ArgumentLayout.offset(inputLayout, i)] = arguments[i].execute(frame)
            } catch (cut: AstCapture) {
                throw cut.append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                        if (inputLayout?.isEmpty(i) != true) values[ArgumentLayout.offset(inputLayout, i)] = input
                        return applyArguments(frame, fn, values, i + 1)
                    }
                })
            }
        }
        return dispatch.execute(frame, fn, values)
    }
}
/** Each cloned root owns its widening state; recursive RHSs stay raw until publication. */
internal class LocalBinding(private val slot: Int, @field:Child private var value: Expr,
                           preferLong: Boolean,
                           @field:CompilationFinal(dimensions = 1) private val vectorSlots: IntArray? = null) : Node() {
    private val exactLong = value.representation.isLong
    private val referenceKind = if (value.representation.evaluated) value.representation.kind else CoreKind.UNKNOWN
    @CompilationFinal private var generic = !preferLong ||
        (value.representation.present && !exactLong)
    fun evaluate(frame: VirtualFrame): Any? {
        if (referenceKind == CoreKind.DATA) return value.executeRequiredDataValue(frame)
        if (referenceKind == CoreKind.CLOSURE) return value.executeRequiredClosure(frame)
        if (referenceKind == CoreKind.ADDRESS) return value.executeRequiredAddress(frame)
        return value.execute(frame)
    }
    fun write(frame: VirtualFrame) {
        try { writeValue(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? {
                    if (vectorSlots == null) FrameAccess.write(frame, slot, input)
                    return Unit
                }
            })
        }
    }
    private fun writeValue(frame: VirtualFrame) {
        if (vectorSlots != null) { value.executeTuple(frame, vectorSlots, 0); return }
        if (value.representation.isInt) { FrameAccess.writeInt(frame, slot, value.executeRequiredInt(frame)); return }
        if (exactLong) { FrameAccess.writeLong(frame, slot, value.executeRequiredLong(frame)); return }
        if (value.representation.isFloat) { FrameAccess.writeFloat(frame, slot, value.executeRequiredFloat(frame)); return }
        if (value.representation.isDouble) { FrameAccess.writeDouble(frame, slot, value.executeRequiredDouble(frame)); return }
        if (referenceKind == CoreKind.DATA) { FrameAccess.write(frame, slot, value.executeRequiredDataValue(frame)); return }
        if (referenceKind == CoreKind.CLOSURE) { FrameAccess.write(frame, slot, value.executeRequiredClosure(frame)); return }
        if (referenceKind == CoreKind.ADDRESS) { FrameAccess.write(frame, slot, value.executeRequiredAddress(frame)); return }
        if (generic) { FrameAccess.write(frame, slot, value.execute(frame)); return }
        try { FrameAccess.writeLong(frame, slot, value.executeLong(frame)) }
        catch (unexpected: UnexpectedResultException) {
            generic = true
            FrameAccess.write(frame, slot, unexpected.result)
        }
    }
}
private class Let(@field:CompilationFinal(dimensions = 1) private val slots: IntArray,
                  rhs: Array<Expr>, primitiveEligible: BooleanArray,
                  @field:Child private var body: Expr, private val recursive: Boolean,
                  private val vectorSlots: Array<IntArray?> = arrayOfNulls(rhs.size)) : Expr() {
    init { representation = body.representation }
    @Children private var bindings = Array(rhs.size) { index ->
        LocalBinding(slots[index], rhs[index], !recursive && primitiveEligible[index], vectorSlots[index])
    }
    override fun execute(frame: VirtualFrame): Any? {
        initialize(frame)
        return body.execute(frame)
    }
    override fun executeInt(frame: VirtualFrame): Int {
        initialize(frame)
        return body.executeInt(frame)
    }
    override fun executeLong(frame: VirtualFrame): Long {
        initialize(frame)
        return body.executeLong(frame)
    }
    override fun executeFloat(frame: VirtualFrame): Float { initialize(frame); return body.executeFloat(frame) }
    override fun executeDouble(frame: VirtualFrame): Double { initialize(frame); return body.executeDouble(frame) }
    override fun executeClosure(frame: VirtualFrame): Closure {
        initialize(frame)
        return body.executeClosure(frame)
    }
    override fun executeDataValue(frame: VirtualFrame): DataValue {
        initialize(frame)
        return body.executeDataValue(frame)
    }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress {
        initialize(frame)
        return body.executeAddress(frame)
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        initialize(frame, destination = slots, offset = offset); return body.executeTuple(frame, slots, offset)
    }
    private fun initialize(frame: VirtualFrame, destination: IntArray? = null, offset: Int = 0) {
        try { initializeFrom(frame, 0) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? =
                    if (destination == null) body.execute(frame) else body.executeTuple(frame, destination, offset)
            })
        }
    }
    @ExplodeLoop private fun initializeFrom(frame: VirtualFrame, start: Int) {
        if (recursive) {
            // A closure captures these cells, never a mutable activation frame.
            if (start == 0) for (slot in slots) FrameAccess.write(frame, slot, RecCell())
            for (i in start until slots.size) {
                val cell = FrameAccess.read(frame, slots[i]) as? RecCell ?: fault("Invalid recursive cell")
                cell.value = try { bindings[i].evaluate(frame) }
                catch (cut: AstCapture) {
                    throw cut.append(object : AstResumeStep {
                        override fun resume(frame: VirtualFrame, input: Any?): Any {
                            cell.value = input
                            cell.initialized = true
                            initializeFrom(frame, i + 1)
                            return Unit
                        }
                    })
                }
                cell.initialized = true
            }
            // Existing recursive captures retain the cells; the let body and
            // closures built after publication read the values directly.
            // Publish only after every RHS has captured the whole group.
            for (slot in slots) {
                val cell = FrameAccess.read(frame, slot) as? RecCell ?: fault("Invalid recursive cell")
                FrameAccess.write(frame, slot, cell.value)
            }
        } else for (i in start until bindings.size) {
            try { bindings[i].write(frame) }
            catch (cut: AstCapture) {
                throw cut.append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any {
                        initializeFrom(frame, i + 1)
                        return Unit
                    }
                })
            }
        }
    }
}
private const val DEFAULT_ALTERNATIVE = 0
private const val DATA_ALTERNATIVE = 1
private const val LITERAL_ALTERNATIVE = 2

private class Alternative(val kind: Int, val value: Any?,
                          @field:CompilationFinal(dimensions = 1) val fields: IntArray,
                          @field:Child var body: Expr,
                          @field:CompilationFinal(dimensions = 2) val vectorFields: Array<IntArray?> = emptyArray()) : Node() {
    private val matchProfile = CountingConditionProfile.create()
    fun matchesData(value: DataValue): Boolean = matchProfile.profile((this.value as DataLayout).matches(value))
    fun matchesLong(value: Long): Boolean = matchProfile.profile(value == this.value as Long)
    fun matches(frame: VirtualFrame, slot: Int): Boolean = matchProfile.profile(when (kind) {
        DATA_ALTERNATIVE -> if (frame.isObject(slot)) {
            val scrutinee = frame.getObject(slot)
            (value as DataLayout).matches(scrutinee)
        } else false
        LITERAL_ALTERNATIVE -> matchesLiteral(frame, slot)
        else -> false
    })
    private fun matchesLiteral(frame: VirtualFrame, slot: Int): Boolean {
        val literal = value
        if (literal is Int) {
            if (frame.isInt(slot)) return frame.getInt(slot) == literal
            val scrutinee = FrameAccess.read(frame, slot)
            return scrutinee is Int && scrutinee == literal
        }
        if (literal is Long) {
            val number = literal.toLong()
            if (frame.isLong(slot)) return frame.getLong(slot) == number
            val scrutinee = FrameAccess.read(frame, slot)
            return scrutinee is Long && scrutinee.toLong() == number
        }
        // Lowering rejects floating/BigNat alternatives. The remaining literal
        // carrier is ManagedAddress, whose Object.equals is identity, not the
        // separate address-comparison primop. Never invoke a scrutinee's equals.
        return FrameAccess.read(frame, slot) === literal
    }
}
private open class Case(scrutinee: Expr, protected val binderSlot: Int,
                   @field:Children protected var alternatives: Array<Alternative>, metrics: Metrics,
                   binderProof: CoreRepresentation? = null, private val delimited: Boolean = false) : Expr() {
    init {
        val proofs = alternatives.map { it.body.representation }
        val kinds = proofs.map { it.kind }.toSet()
        val kind = when {
            kinds.size == 1 -> kinds.single()
            kinds.isNotEmpty() && kinds.all { it in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) } -> CoreKind.OBJECT
            else -> CoreKind.UNKNOWN
        }
        val aggregate = proofs.firstOrNull()?.takeIf { first -> first.isAggregate &&
            proofs.all { it.isAggregate && TupleShape.compatible(first, it) } }
        val narrow = proofs.firstOrNull()?.takeIf { first -> first.isInt && proofs.all { it.primReps == first.primReps } }
        representation = aggregate?.copy(evaluated = proofs.all { it.evaluated }) ?: CoreVectors.caseResult(proofs)
            ?: narrow?.copy(evaluated = proofs.all { it.evaluated })
            ?: CoreRepresentation(kind, proofs.all { it.evaluated }, proofs.isNotEmpty() && proofs.all { it.present })
    }
    // Preserve primitive scrutinees through their frame write and literal comparisons.
    @Child private var scrutinee = LocalBinding(binderSlot, Evaluate(scrutinee, metrics).apply {
        if (binderProof != null) representation = representation.refine(binderProof.copy(evaluated = false))
    }, true)
    protected fun prepare(frame: VirtualFrame, destination: IntArray? = null, offset: Int = 0) {
        try { scrutinee.write(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? {
                    val branch = select(frame).body
                    return if (destination == null) branch.execute(frame)
                        else branch.executeTuple(frame, destination, offset)
                }
            })
        }
        catch (cut: DelimitedCut) {
            if (!delimited) throw cut
            throw cut.append(frame, object : DelimitedStep {
                override fun resume(frame: MaterializedFrame, input: DelimitedResume,
                                    ambient: MaskingState, outerMask: DelimitedStep?): Any? {
                    // The suspended scrutinee has completed. Do not evaluate it
                    // again: only bind its value and execute the saved branch.
                    FrameAccess.write(frame, binderSlot, input.get())
                    val branch = select(frame).body
                    return if (destination == null) branch.execute(frame)
                        else branch.executeTuple(frame, destination, offset)
                }
            })
        }
    }
    private fun select(frame: VirtualFrame): Alternative {
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) { restoreFields(frame, alt); return alt }
        }
        return fallback ?: fault("Non-exhaustive Core case")
    }
    protected open fun matches(frame: VirtualFrame, alternative: Alternative): Boolean = alternative.matches(frame, binderSlot)

    @ExplodeLoop override fun execute(frame: VirtualFrame): Any? {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.execute(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.execute(frame)
    }

    @ExplodeLoop override fun executeInt(frame: VirtualFrame): Int {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeInt(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeInt(frame)
    }
    @ExplodeLoop override fun executeLong(frame: VirtualFrame): Long {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeLong(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeLong(frame)
    }


    @ExplodeLoop override fun executeFloat(frame: VirtualFrame): Float {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeFloat(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeFloat(frame)
    }

    @ExplodeLoop override fun executeDouble(frame: VirtualFrame): Double {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeDouble(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeDouble(frame)
    }

    @ExplodeLoop override fun executeClosure(frame: VirtualFrame): Closure {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeClosure(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeClosure(frame)
    }

    @ExplodeLoop override fun executeDataValue(frame: VirtualFrame): DataValue {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeDataValue(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeDataValue(frame)
    }

    @ExplodeLoop override fun executeAddress(frame: VirtualFrame): ManagedAddress {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeAddress(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeAddress(frame)
    }

    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        prepare(frame, slots, offset)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeTuple(frame, slots, offset)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeTuple(frame, slots, offset)
    }
    @ExplodeLoop private fun restoreFields(frame: VirtualFrame, alt: Alternative) {
        if (alt.kind == DATA_ALTERNATIVE) {
            val data = frame.getObject(binderSlot) as? DataValue ?: fault("Invalid constructor case")
            val layout = alt.value as? DataLayout ?: fault("Invalid constructor alternative")
            for (i in alt.fields.indices) {
                val lanes = if (i < alt.vectorFields.size) alt.vectorFields[i] else null
                if (lanes == null) layout.restore(data, i, frame, alt.fields[i])
                else layout.restoreVector(data, i, frame, lanes, 0)
            }
        }
    }
}
/** Category selection happens during lowering, never in the guest loop. */
private class DataCase(scrutinee: Expr, binder: Int, alternatives: Array<Alternative>, metrics: Metrics,
                       proof: CoreRepresentation, delimited: Boolean) : Case(scrutinee, binder, alternatives, metrics, proof, delimited) {
    override fun matches(frame: VirtualFrame, alternative: Alternative): Boolean =
        alternative.matchesData(frame.getObject(binderSlot) as DataValue)
}
private class LongCase(scrutinee: Expr, binder: Int, alternatives: Array<Alternative>, metrics: Metrics,
                       proof: CoreRepresentation, delimited: Boolean) : Case(scrutinee, binder, alternatives, metrics, proof, delimited) {
    override fun matches(frame: VirtualFrame, alternative: Alternative): Boolean =
        alternative.matchesLong(frame.getLong(binderSlot))
}
private class DefaultCase(scrutinee: Expr, binder: Int, alternatives: Array<Alternative>, metrics: Metrics,
                          proof: CoreRepresentation, delimited: Boolean) : Case(scrutinee, binder, alternatives, metrics, proof, delimited) {
    override fun execute(frame: VirtualFrame): Any? { prepare(frame); return alternatives.last().body.execute(frame) }
    override fun executeInt(frame: VirtualFrame): Int { prepare(frame); return alternatives.last().body.executeInt(frame) }
    override fun executeLong(frame: VirtualFrame): Long { prepare(frame); return alternatives.last().body.executeLong(frame) }
    override fun executeFloat(frame: VirtualFrame): Float { prepare(frame); return alternatives.last().body.executeFloat(frame) }
    override fun executeDouble(frame: VirtualFrame): Double { prepare(frame); return alternatives.last().body.executeDouble(frame) }
    override fun executeClosure(frame: VirtualFrame): Closure { prepare(frame); return alternatives.last().body.executeClosure(frame) }
    override fun executeDataValue(frame: VirtualFrame): DataValue { prepare(frame); return alternatives.last().body.executeDataValue(frame) }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress { prepare(frame); return alternatives.last().body.executeAddress(frame) }
}
private class FunctionBody(expression: Expr, metrics: Metrics, result: CoreRepresentation,
    private val tuple: TupleShape?, @field:CompilationFinal(dimensions = 1) private val tupleSlots: IntArray) : Node() {
    @Child private var value = Evaluate(expression, metrics)
    private val resultKind = if (result.kind == CoreKind.UNKNOWN) expression.representation.kind else result.kind
    private val exactInt = (if (result.kind == CoreKind.UNKNOWN) expression.representation else result).isInt
    private val exactLong = resultKind == CoreKind.LONG && !exactInt
    @CompilationFinal private var genericResult = result.present && !exactLong

    /** Keep a primitive body until the mandatory Object-returning root/call boundary. */
    fun execute(frame: VirtualFrame): Any? {
        val shape = tuple
        if (shape != null) {
            try { value.executeTuple(frame, tupleSlots, 0) }
            catch (cut: DelimitedCut) {
                if (!DelimitedControl.enabled(this)) throw cut
                throw cut.append(frame, object : DelimitedStep {
                    override fun resume(frame: MaterializedFrame, input: DelimitedResume,
                                        ambient: MaskingState, outerMask: DelimitedStep?): Any? {
                        input.get()
                        return ownedTupleResult(shape.finish(frame, tupleSlots), shape)
                    }
                })
            }
            catch (cut: AstCapture) {
                throw cut.append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                        if (input != null) fault("Invalid AST tuple resume value")
                        return shape.finish(frame, tupleSlots)
                    }
                })
            }
            // Both continuation protocols can retain a carrier in an owned frame.
            return shape.finish(frame, tupleSlots,
                AstControl.captures(this) || DelimitedControl.enabled(this))
        }
        // Kotlin's enum when uses a mutable synthetic int[] mapping. Graal
        // cannot fold that lookup, even when this node's resultKind is constant.
        if (exactInt) return value.executeRequiredInt(frame)
        if (resultKind == CoreKind.LONG) return value.executeRequiredLong(frame)
        if (resultKind == CoreKind.FLOAT) return value.executeRequiredFloat(frame)
        if (resultKind == CoreKind.DOUBLE) return value.executeRequiredDouble(frame)
        if (resultKind == CoreKind.DATA) return value.executeRequiredDataValue(frame)
        if (resultKind == CoreKind.CLOSURE) return value.executeRequiredClosure(frame)
        if (resultKind == CoreKind.ADDRESS) return value.executeRequiredAddress(frame)
        if (genericResult) return value.execute(frame)
        return try { value.executeLong(frame) }
        catch (unexpected: UnexpectedResultException) {
            genericResult = true
            return unexpected.result
        }
    }
    fun executeLong(frame: VirtualFrame): Long = value.executeRequiredLong(frame)
    fun executeInt(frame: VirtualFrame): Int = value.executeRequiredInt(frame)
}
private class SelfRepeater(@field:Child private var body: FunctionBody, private val metrics: Metrics) : Node(), RepeatingNode {
    fun once(frame: VirtualFrame): Any? {
        val root = rootNode as? FunctionRoot ?: fault("Invalid self-loop function root")
        root.forceEntry(frame)
        root.pollBeforeBody(this)
        val entry = root.handoff
        return if (entry != null && entry.resultInt && entry.destination(frame) >= 0) entry.finishInt(frame, body.executeInt(frame))
        else if (entry != null && entry.resultLong && entry.destination(frame) >= 0) entry.finishLong(frame, body.executeLong(frame))
        else body.execute(frame)
    }
    override fun executeRepeating(frame: VirtualFrame): Boolean = error("value loop")
    override fun executeRepeatingWithValue(frame: VirtualFrame): Any? = try { once(frame) }
    catch (_: AstSelfCall) {
        if (metrics.enabled) metrics.incrementSelfTailReentries()
        RepeatingNode.CONTINUE_LOOP_STATUS
    }
    catch (tail: HandoffTailCall) {
        val root = rootNode as? FunctionRoot ?: fault("Invalid self-loop function root")
        if (!root.isSelf(tail.target)) throw tail
        if (metrics.enabled) metrics.incrementSelfTailReentries()
        root.restoreHandoff(frame, tail.arguments, false)
        RepeatingNode.CONTINUE_LOOP_STATUS
    }
    catch (tail: TailCall) {
        val root = rootNode as? FunctionRoot ?: fault("Invalid self-loop function root")
        if (!root.isSelf(tail.target)) throw tail
        if (metrics.enabled) metrics.incrementSelfTailReentries()
        root.restoreTail(frame, tail)
        RepeatingNode.CONTINUE_LOOP_STATUS
    }
}
/** A side-exit body is a call boundary, not a new owner of an inherited tail cycle. */
internal enum class FunctionRootRole { FUNCTION, PASS_THROUGH }

internal class FunctionRoot(language: TruffleLanguage<*>?, descriptor: FrameDescriptor, private val label: String,
                            private val captureLayout: CaptureLayout?,
                            @field:CompilationFinal(dimensions = 1) private val environmentSlots: IntArray,
                            @field:CompilationFinal(dimensions = 1) private val argumentSlots: IntArray,
                            @field:CompilationFinal(dimensions = 1) private val argumentIndices: IntArray,
                            body: Expr, private val metrics: Metrics,
                            @field:CompilationFinal(dimensions = 1) private val argumentProofs: Array<CoreRepresentation> = emptyArray(),
                            resultProof: CoreRepresentation = body.representation,
                            private val coreSourceLocation: CoreSourceLocation? = body.coreSourceLocation,
                            entryStrict: BooleanArray = booleanArrayOf(),
                            internal val handoff: HandoffEntry? = null,
                            tuple: TupleShape? = null,
                            tupleSlots: IntArray = intArrayOf(),
                            inputLayout: ArgumentLayout? = null,
                            internal val enableAsync: Boolean = false,
                            @field:CompilationFinal(dimensions = 2) private val environmentVectorSlots: Array<IntArray?> = emptyArray(),
                            internal val enableDelimited: Boolean = false,
                            internal val role: FunctionRootRole = FunctionRootRole.FUNCTION,
                            internal val stackCapture: Boolean = false) : GuestRoot(language, descriptor) {
    internal val capturesContinuations = enableAsync || stackCapture
    init {
        configureEntry(entryStrict, captureLayout != null)
        configureInput(inputLayout)
        configureTupleResult(tuple)
        configureScalarResult(body.representation.refine(resultProof))
        fun initialize(slot: Int, kind: FrameSlotKind) {
            if (descriptor.getSlotKind(slot) == FrameSlotKind.Illegal) descriptor.setSlotKind(slot, kind)
        }
        // These carriers are already required by buildFrame. Establish their
        // slot kinds before publishing the root, not on its first compiled call.
        // An asynchronously forced argument may still contain a thunk here.
        for (i in argumentSlots.indices) {
            if (capturesContinuations && argumentIndices[i] + entryArgumentOffset in strictArgumentPositions) continue
            val proof = argumentProofs.getOrNull(i) ?: continue
            val kind = when {
                proof.referenceCarrier() != null -> FrameSlotKind.Object
                proof.isInt -> FrameSlotKind.Int
                proof.isLong -> FrameSlotKind.Long
                proof.isFloat -> FrameSlotKind.Float
                proof.isDouble -> FrameSlotKind.Double
                else -> continue
            }
            initialize(argumentSlots[i], kind)
        }
        // Dense entry first snapshots its owned packet, independently of the
        // formal slots. Its immutable physical layout fixes these carriers too.
        handoff?.let { entry ->
            for (i in entry.snapshotSlots.indices) initialize(entry.snapshotSlots[i], when {
                entry.arguments.isInt(i) -> FrameSlotKind.Int
                entry.arguments.isLong(i) -> FrameSlotKind.Long
                else -> FrameSlotKind.Object
            })
            initialize(entry.destinationSlot, FrameSlotKind.Long)
        }
    }
    @field:CompilationFinal(dimensions = 1)
    private val argumentReferences = argumentProofs.map { it.referenceCarrier() }.toTypedArray()
    @field:CompilationFinal(dimensions = 1)
    private val strictArguments = BooleanArray(argumentSlots.size) {
        capturesContinuations && argumentIndices[it] + entryArgumentOffset in strictArgumentPositions
    }
    @field:CompilationFinal(dimensions = 1)
    private val strictSlots = argumentSlots.indices.filter { strictArguments[it] }.toIntArray()
    @Child private var entryForce = Force(metrics, enableAsync)
    @field:CompilationFinal private var hasSelfTail = false
    private val tailCallProfile = BranchProfile.create()
    @Child private var loop: LoopNode = Truffle.getRuntime().createLoopNode(SelfRepeater(FunctionBody(body, metrics, resultProof, tuple, tupleSlots), metrics))
    @Child private var delimitedHandoff: HandoffCaller? = null
    override fun requiresUnprofiledReturn(): Boolean = capturesContinuations || enableDelimited
    override fun requiresMaterializableFrame(): Boolean = capturesContinuations || enableDelimited

    override fun bloom(frame: VirtualFrame): Long = frame.getLong(FrameLayout.BLOOM_FILTER)
    /** A pass-through side root has no catcher of its own. */
    fun entryBloom(inherited: Long): Long =
        if (stackCapture && role == FunctionRootRole.PASS_THROUGH) inherited else inherited or mask

    /** Identity return: no return loan, tuple copy, lazy force or external delivery. */
    fun isTailSpillIdentityRoot(): Boolean = stackCapture && !enableAsync && !enableDelimited &&
        handoff == null && typedInput == null && tupleResult == null && AstTailResult.supports(scalarResultProof)

    /** Java anchor bridge; the driver runs the saved suffix, not the original body. */
    fun drainTailChild(saved: SavedGuestContinuation): Any? = entryForce.drainStack(saved, tailSpill = true)

    fun restartTailAnchor(frame: VirtualFrame, transfer: TailCall): Any? {
        check(role == FunctionRootRole.FUNCTION && isSelf(transfer.target))
        restoreTail(frame, transfer)
        if (metrics.enabled) metrics.incrementSelfTailReentries()
        return executeBody(frame)
    }
    @ExplodeLoop fun buildFrame(arguments: Array<Any?>, frame: VirtualFrame) {
        val offset = if (captureLayout == null) 1 else 2
        for (i in argumentSlots.indices) {
            val value = arguments[argumentIndices[i] + offset]
            val reference = if (i < argumentReferences.size) argumentReferences[i] else null
            if (strictArguments[i]) FrameAccess.write(frame, argumentSlots[i], value)
            else if (reference != null)
                FrameAccess.write(frame, argumentSlots[i], AstSelfCallsKt.requireReferenceCarrier(value, reference))
            else if (i < argumentProofs.size && argumentProofs[i].isInt)
                FrameAccess.writeInt(frame, argumentSlots[i], value as? Int ?: fault("Expected primitive Int argument"))
            else if (i < argumentProofs.size && argumentProofs[i].isLong)
                FrameAccess.writeLong(frame, argumentSlots[i], value as? Long ?: fault("Expected primitive Long argument"))
            else if (i < argumentProofs.size && argumentProofs[i].isFloat)
                FrameAccess.writeFloat(frame, argumentSlots[i], value as? Float ?: fault("Expected primitive Float argument"))
            else if (i < argumentProofs.size && argumentProofs[i].isDouble)
                FrameAccess.writeDouble(frame, argumentSlots[i], value as? Double ?: fault("Expected primitive Double argument"))
            else FrameAccess.write(frame, argumentSlots[i], value)
        }
        if (captureLayout != null) {
            val environment = arguments[1] as? CapturedFrame ?: fault("Invalid captured frame")
            restoreCaptured(frame, environment)
        }
    }
    @ExplodeLoop private fun restoreCaptured(frame: VirtualFrame, environment: CapturedFrame) {
        val layout = captureLayout ?: fault("Missing capture layout")
        for (i in environmentSlots.indices) {
            val lanes = environmentVectorSlots.getOrNull(i)
            if (lanes == null) layout.restore(environment, i, frame, environmentSlots[i])
            else layout.restoreVector(environment, i, frame, lanes, 0)
        }
    }
    fun handoffDestination(frame: VirtualFrame): Int = handoff?.destination(frame) ?: -1

    @ExplodeLoop fun forceEntry(frame: VirtualFrame) {
        for (index in strictSlots.indices) {
            val argument = strictSlots[index]
            val slot = argumentSlots[argument]
            val answer = try { AstControl.force(frame, this, entryForce, FrameAccess.read(frame, slot)) }
            catch (cut: AstCapture) {
                throw cut.append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                        writeStrict(frame, argument, input)
                        return executeCapturableBody(frame)
                    }
                })
            }
            writeStrict(frame, argument, answer)
        }
    }

    private fun writeStrict(frame: VirtualFrame, argument: Int, value: Any?) {
        val reference = argumentReferences.getOrNull(argument)
        FrameAccess.write(frame, argumentSlots[argument],
            if (reference == null) value else AstSelfCallsKt.requireReferenceCarrier(value, reference))
    }

    private class ResumeBody(private val root: FunctionRoot) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input !== Unit) fault("Invalid AST root poll resume value")
            return root.executeBody(frame)
        }
    }

    fun pollBeforeBody(node: Node) {
        if (!enableAsync) return
        val enteredCompiled = CompilerDirectives.inCompiledCode()
        val request = GuestThreads.pollCurrent(node, false) ?: return
        request.compiledCapture = enteredCompiled
        throw AstCapture(request, SynchronousMasking.current(node)).append(ResumeBody(this))
    }

    fun restoreTail(frame: VirtualFrame, transfer: TailCall) {
        val input = transfer.input
        if (input != null) restoreTypedInput(frame, input, false)
        else {
            if (typedInput != null) fault("Typed input target received a scalar packet")
            buildFrame(transfer.args, frame)
        }
    }
    @ExplodeLoop internal fun transferTypedSelf(frame: VirtualFrame, function: Closure, source: AstInputSource, node: Node) {
        val entry = typedInput ?: fault("Target does not support typed tuple inputs")
        entry.validateSelfSource(source, frame, node)
        for (i in argumentSlots.indices) {
            val from = argumentIndices[i]
            val to = argumentSlots[i]
            if (entry.packet.isInt(entry.header + from)) FrameAccess.writeInt(frame, to, source.readInt(frame, node, null, from))
            else if (entry.packet.isLong(entry.header + from)) FrameAccess.writeLong(frame, to, source.readLong(frame, node, null, from))
            else if (entry.packet.isFloat(entry.header + from)) FrameAccess.writeFloat(frame, to, source.readFloat(frame, node, null, from))
            else if (entry.packet.isDouble(entry.header + from)) FrameAccess.writeDouble(frame, to, source.readDouble(frame, node, null, from))
            else {
                val value = source.reference(frame, node, null, from)
                val expected = argumentReferences.getOrNull(i)
                TypedInputsKt.writeInputReference(frame, to, if (expected == null) value else AstSelfCallsKt.requireReferenceCarrier(value, expected))
            }
        }
        if (captureLayout != null) restoreCaptured(frame, function.environment ?: fault("Invalid captured frame"))
        // Retain the activation's bloom and result destination, as for a caught self tail packet.
    }
    @ExplodeLoop private fun restoreTypedInput(frame: VirtualFrame, input: HandoffStorage, initial: Boolean) {
        val entry = typedInput ?: fault("Target does not support typed tuple inputs")
        try {
            if (initial) entry.validate(input) else entry.validateTail(input)
            if (initial) frame.setLong(FrameLayout.BLOOM_FILTER, entryBloom(entry.packet.getLong(input, 0)))
            for (i in argumentSlots.indices) {
                val from = argumentIndices[i] + entry.header
                val to = argumentSlots[i]
                if (entry.packet.isInt(from)) FrameAccess.writeInt(frame, to, entry.packet.getInt(input, from))
                else if (entry.packet.isLong(from)) FrameAccess.writeLong(frame, to, entry.packet.getLong(input, from))
                else if (entry.packet.isFloat(from)) FrameAccess.writeFloat(frame, to, entry.packet.getFloat(input, from))
                else if (entry.packet.isDouble(from)) FrameAccess.writeDouble(frame, to, entry.packet.getDouble(input, from))
                else {
                    val value = entry.packet.getObject(input, from)
                    val expected = if (i < argumentReferences.size) argumentReferences[i] else null
                    TypedInputsKt.writeInputReference(frame, to, if (expected == null || strictArguments[i]) value else AstSelfCallsKt.requireReferenceCarrier(value, expected))
                }
            }
            if (captureLayout != null) {
                val environment = entry.packet.getObject(input, 1) as? CapturedFrame ?: fault("Invalid captured frame")
                restoreCaptured(frame, environment)
            }
        } finally { entry.releaseChecked(input) }
    }

    @ExplodeLoop internal fun restoreHandoff(frame: VirtualFrame, input: HandoffStorage, initial: Boolean) {
        val entry = handoff ?: fault("Target does not support the handoff ABI")
        check(input.layout === entry.arguments && input.live)
        if (!initial) check(entry.destination(frame) >= 0)
        try {
            if (initial) {
                entry.snapshot(frame, input)
                frame.setLong(entry.destinationSlot, 0L)
                frame.setLong(FrameLayout.BLOOM_FILTER, entryBloom(entry.arguments.getLong(input, 0)))
            }
            val offset = if (captureLayout == null) 1 else 2
            for (i in argumentSlots.indices) {
                val position = argumentIndices[i] + offset
                if (entry.arguments.isInt(position)) FrameAccess.writeInt(frame, argumentSlots[i], entry.arguments.getInt(input, position))
                else if (entry.arguments.isLong(position)) FrameAccess.writeLong(frame, argumentSlots[i], entry.arguments.getLong(input, position))
                else {
                    val value = entry.arguments.getObject(input, position)
                    val reference = argumentReferences.getOrNull(i)
                    FrameAccess.write(frame, argumentSlots[i], if (reference != null) AstSelfCallsKt.requireReferenceCarrier(value, reference) else value)
                }
            }
            if (captureLayout != null) {
                val environment = entry.arguments.getObject(input, 1) as? CapturedFrame ?: fault("Invalid captured frame")
                restoreCaptured(frame, environment)
            }
        } finally { entry.state().arguments.release(input, entry.arguments) }
    }

    override fun execute(frame: VirtualFrame): Any? {
        if (!capturesContinuations) return executeInitial(frame, false)
        val stack = AstStackKt.astStackScope(this)
        val driver = !stack.driving
        if (driver) stack.driving = true
        return try {
            stack.depth++
            val result = try { executeInitial(frame, stack.depth >= AstStackScope.MAX_DEPTH) }
                finally { stack.depth-- }
            // Drain only after all nested guest activations have returned.
            val saved = when (result) {
                is AstTailYield -> result.continuation
                else -> SavedGuestContinuationKt.savedGuestContinuation(result)
            }
            if (driver && saved?.stackSpill() == true && saved.asyncRequest() == null)
                entryForce.drainStack(saved)
            else result
        } finally { if (driver) stack.driving = false }
    }

    private fun executeInitial(frame: VirtualFrame, spill: Boolean): Any? {
        if (metrics.enabled && CompilerDirectives.inCompiledCode()) metrics.incrementCompiledEntries()
        val entry = handoff
        val typed = typedInput
        if (typed != null) {
            restoreTypedInput(frame, typed.take(frame.arguments), true)
        } else if (entry != null && frame.arguments.isEmpty()) {
            val state = entry.state()
            val input = state.pending ?: fault("Missing typed argument loan")
            state.pending = null
            restoreHandoff(frame, input, true)
        } else {
            entry?.initializeOrdinary(frame)
            frame.setLong(FrameLayout.BLOOM_FILTER, entryBloom(frame.arguments[0] as? Long ?: fault("Invalid bloom argument")))
            buildFrame(frame.arguments, frame)
        }
        if (spill) {
            return captureStack(frame.materialize())
        }
        // Roots without either capture capability retain their virtual result path.
        if (!capturesContinuations) return executeCapturableBody(frame)
        return try { executeCapturableBody(frame) }
        catch (cut: AstCapture) {
            // Continuations own a real frame only on the interrupted slow path.
            finishCapture(cut, frame.materialize())
        }
    }

    @CompilerDirectives.TruffleBoundary
    fun finishCapture(cut: AstCapture, frame: MaterializedFrame): Any? {
        if (cut.yielded is AstPendingTail && isTailSpillIdentityRoot()) {
            val pending = cut.pendingTail()
            if (pending != null && role == FunctionRootRole.PASS_THROUGH) {
                AstStackKt.astStackScope(this).compactedFrames++
                return AstTailYield(pending.child, pending.target)
            }
            if (role == FunctionRootRole.FUNCTION) {
                val child = pending?.child ?: cut.freeze(this, frame)
                return AstTailAnchor(this, frame, child, SynchronousMasking.current(this), StackAnnotations.current(this))
            }
        }
        return cut.freeze(this, frame)
    }

    /** A root-entry cut has no executed body or caller suffix to unwind here. */
    @CompilerDirectives.TruffleBoundary
    private fun captureStack(frame: MaterializedFrame): AstContinuation {
        if (thc.Language.currentState(this).stm.hasTransaction())
            throw UnsupportedCore("AST stack spilling across an active STM transaction is unsupported")
        AstStackKt.astStackScope(this).spills++
        return AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this))
            .append(ResumeBody(this)).freeze(this, frame, true)
    }

    private fun executeCapturableBody(frame: VirtualFrame): Any? {
        // This is fixed for the entire linked program, including its callees.
        // Do not expand cold frame-capture handlers in ordinary scalar graphs.
        if (!enableDelimited) return executeBody(frame)
        return try { executeBody(frame) }
        catch (cut: DelimitedCut) { throw cut.append(frame, DelimitedRootStep(this)) }
    }

    internal fun resumeDelimited(frame: VirtualFrame, transfer: ControlFlowException, site: DelimitedActionSite): Any? {
        if (role == FunctionRootRole.PASS_THROUGH) throw transfer
        if (transfer is TailCall && !isSelf(transfer.target)) return site.tail(transfer)
        if (transfer is HandoffTailCall && !isSelf(transfer.target)) {
            val entry = handoff ?: fault("Missing saved handoff entry")
            val caller = delimitedHandoff ?: installDelimitedHandoff(entry)
            return caller.trampoline(entry.state(), transfer)
        }
        when (transfer) {
            is TailCall -> restoreTail(frame, transfer)
            is HandoffTailCall -> restoreHandoff(frame, transfer.arguments, false)
            AstSelfCall.INSTANCE -> Unit // The self application already performed parallel local moves.
            else -> throw transfer
        }
        if (metrics.enabled) metrics.incrementSelfTailReentries()
        // The captured caller's return register is no longer live. A resumed
        // suffix returns an owned value to its saved caller, not a stale loan.
        handoff?.initializeOrdinary(frame)
        return try {
            executeBody(frame)
        } catch (cut: DelimitedCut) { throw cut.append(frame, DelimitedRootStep(this)) }
        catch (tail: TailCall) { site.tail(tail) }
        catch (tail: HandoffTailCall) {
            val entry = handoff ?: fault("Missing saved handoff entry")
            (delimitedHandoff ?: installDelimitedHandoff(entry)).trampoline(entry.state(), tail)
        }
    }

    @CompilerDirectives.TruffleBoundary private fun installDelimitedHandoff(entry: HandoffEntry): HandoffCaller =
        delimitedHandoff ?: insert(HandoffCaller(callTarget, entry, metrics)).also { delimitedHandoff = it }

    private fun executeBody(frame: VirtualFrame): Any? {
        if (role == FunctionRootRole.PASS_THROUGH) {
            val repeating = loop.repeatingNode as? SelfRepeater ?: fault("Invalid function body node")
            return repeating.once(frame)
        }
        // Non-looping roots retain entry argument facts. Once self recursion
        // is observed, PE selects only the loop body instead of duplicating it.
        if (hasSelfTail) return loop.execute(frame)
        val repeating = loop.repeatingNode as? SelfRepeater ?: fault("Invalid function self-loop node")
        return try { repeating.once(frame) }
        catch (_: AstSelfCall) {
            tailCallProfile.enter()
            if (metrics.enabled) metrics.incrementSelfTailReentries()
            CompilerDirectives.transferToInterpreterAndInvalidate()
            hasSelfTail = true
            loop.execute(frame)
        }
        catch (tail: HandoffTailCall) {
            tailCallProfile.enter()
            if (!isSelf(tail.target)) throw tail
            if (metrics.enabled) metrics.incrementSelfTailReentries()
            CompilerDirectives.transferToInterpreterAndInvalidate()
            hasSelfTail = true
            restoreHandoff(frame, tail.arguments, false)
            loop.execute(frame)
        }
        catch (tail: TailCall) {
            tailCallProfile.enter()
            if (!isSelf(tail.target)) throw tail
            if (metrics.enabled) metrics.incrementSelfTailReentries()
            CompilerDirectives.transferToInterpreterAndInvalidate()
            hasSelfTail = true
            // Keep this frame's bloom ancestry and restore the new captures.
            restoreTail(frame, tail)
            loop.execute(frame)
        }
    }
    override fun getSourceSection(): SourceSection? = coreSourceLocation?.section
    fun getCoreSourceNotes(): List<CoreSourceNote> = coreSourceLocation?.notes.orEmpty()
    override fun getName() = label
    override fun toString() = label
    override fun isCloningAllowed() = true
}
internal class EntryRoot(language: TruffleLanguage<*>?, private val arity: Int, metrics: Metrics) : RootNode(language, FrameLayout().build()) {
    @Child private var dispatch = Dispatch.create(arity, false, metrics)
    @Child private var force = Force(metrics)
    override fun execute(frame: VirtualFrame): Any? {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L)
        val value = force.execute(frame, frame.arguments[0])
        if (arity == 0) return value
        val fn = value as? Closure ?: fault("Application of a non-function")
        val args = frame.arguments[1] as? Array<Any?> ?: fault("Invalid host arguments")
        return force.execute(frame, dispatch.execute(frame, fn, args))
    }
    override fun getName() = "THC host entry/$arity"
}
private data class Local(val slot: Int, val primitive: Boolean, val proof: CoreRepresentation, val cell: Boolean,
                         val entry: BooleanArray? = null, val tupleSlots: IntArray? = null,
                         val arityCertificate: CoreApplicationCertificates.Arity? = null)
private class Scope(val layout: FrameLayout, val locals: MutableMap<String, Local> = linkedMapOf(),
                    val joins: MutableMap<String, LocalJoinTarget> = linkedMapOf(),
                    var self: AstSelfLayout? = null) {
    fun child() = Scope(layout.scope(), LinkedHashMap(locals), LinkedHashMap(joins), self)
    fun bind(id: String, primitive: Boolean, proof: CoreRepresentation = CoreRepresentation.UNKNOWN, cell: Boolean = false,
             entry: BooleanArray? = null, arityCertificate: CoreApplicationCertificates.Arity? = null,
             kind: FrameSlotKind = FrameSlotKind.Illegal): Local =
        Local(layout.bind(id, kind), if (proof.present) proof.isLong else primitive, proof, cell, entry,
            arityCertificate = arityCertificate).also { locals[id] = it; joins.remove(id) }
    fun bindTuple(id: String, proof: CoreRepresentation, slots: IntArray): Local =
        Local(-1, false, proof, false, tupleSlots = slots).also { locals[id] = it; joins.remove(id) }
    fun bindVoid(id: String, proof: CoreRepresentation): Local =
        Local(-1, false, proof.copy(evaluated = true), false).also { locals[id] = it; joins.remove(id) }
    fun bindSlot(id: String, local: Local) { locals[id] = local; joins.remove(id) }
    fun refine(id: String, proof: CoreRepresentation) { locals[id]?.let { locals[id] = it.copy(proof = proof, primitive = if (proof.present) proof.isLong else it.primitive) } }
    fun publish(id: String, proof: CoreRepresentation) {
        refine(id, proof)
        locals[id]?.let { locals[id] = it.copy(cell = false) }
    }
    fun bindJoin(id: String, target: LocalJoinTarget) { joins[id] = target; locals.remove(id) }
}
private data class FunctionSpec(val target: RootCallTarget, val captureLayout: CaptureLayout?, val captures: IntArray)

/**
 * Constructs and links the AST backend's executable roots from exported GHC Core.
 *
 * This program holder is not a Truffle node or a guest closure. Lowering assigns
 * lexical bindings to indexed invocation-frame slots, as Cadenza does; resulting
 * nodes operate on the runtime's shared value and capture representations.
 *
 * Public requests may select captured asynchronous execution. The default AST
 * mode remains synchronous for subsystems with explicit continuation barriers.
 */
class Program(private val language: TruffleLanguage<*>?, moduleData: Map<String, Any?>,
              internal val enableAsync: Boolean = false,
              private val outlineCaseArms: Boolean = false) : ExecutableProgram {
    override fun getAsynchronousExceptions() = enableAsync
    private val capturesContinuations = enableAsync || outlineCaseArms
    init { thc.CoreForeignArtifacts.requireExecutableInput(moduleData) }
    private val demand = moduleData["demandBindings"] as? CoreDemandBindings
    private val foreignExceptionBridge = ForeignExceptionBridge.bind(moduleData, ::entryValue, ::dataLayout)
    private val rubbishLiterals = RubbishLiterals(language)
    private val foreignLinks = moduleData["foreignLinks"] as? List<thc.ForeignBitcode> ?: emptyList()
    private val packageScalarLinks = moduleData["packageScalarLinks"] as? List<thc.PackageScalarLink> ?: emptyList()
    private val stackTargetLayout = moduleData["targetLayout"]
    private val callDemandsEnabled = java.lang.Boolean.getBoolean(CALL_DEMANDS_PROPERTY)
    private val metrics = demand?.metrics ?: Metrics(moduleData["instrument"] != false)
    private val loadingStatistics = moduleData["coreLoadingStatistics"] as? (() -> Map<String, Any>)
    private val containsDelimited = (moduleData["bindings"] as? List<*>)?.any { binding ->
        val body = (binding as? Map<*, *>)?.get("expr")
        if (body is thc.CoreBindingBody) body.header.containsDelimitedControl else DelimitedControl.contains(binding)
    } ?: DelimitedControl.contains(moduleData["bindings"])
    private val delimited = containsDelimited || demand != null && moduleData["captureDelimited"] == true
    private val sources = CoreSources(moduleData)
    private var currentSource: CoreSourceLocation? = null
    private var operandBuilder: OperandBuilder? = null
    private inner class OperandBuilder(private val layout: FrameLayout) {
        private val bindings = ArrayList<LocalBinding>()
        private val temporaries = ArrayList<Int>()
        fun operand(value: Expr): Expr {
            val proof = value.representation
            if (proof.isTypedTransport) {
                val shape = TupleShape(proof, language as thc.Language)
                val slots = IntArray(shape.width) { layout.bind("<async operand field $it>") }
                temporaries.addAll(slots.toList())
                bindings += LocalBinding(-1, value, false, slots)
                return if (proof.isVector) VectorLocalRead(shape, slots) else TupleLocalRead(shape, slots)
            }
            // This fresh scratch slot has exactly the writer below, including
            // its resumed value. Establish its known carrier before compilation;
            // unknown values keep ordinary first-write profiling and widening.
            val kind = when {
                proof.isInt -> FrameSlotKind.Int
                proof.isLong -> FrameSlotKind.Long
                proof.isFloat -> FrameSlotKind.Float
                proof.isDouble -> FrameSlotKind.Double
                proof.isEvaluatedReference -> FrameSlotKind.Object
                else -> FrameSlotKind.Illegal
            }
            val slot = layout.bind("<async operand ${bindings.size}>", kind)
            temporaries += slot
            bindings += LocalBinding(slot, value, proof.isLong)
            return LocalRead(slot, false).proven(proof)
        }
        fun finish(body: Expr): Expr = if (bindings.isEmpty()) body else AstOperands(bindings.toTypedArray(), temporaries.toIntArray(), body)
    }
    private var attachedRootCount = 0
    private var constructedRootCount = 0
    private var initializedBindingCount = 0
    private val preparationLock = Any()
    private fun <T> withSource(location: CoreSourceLocation?, action: () -> T): T {
        val previous = currentSource
        currentSource = location
        return try { action() } finally { currentSource = previous }
    }
    private fun rootSource(body: Expr): CoreSourceLocation? = (body.coreSourceLocation ?: currentSource).also {
        if (it != null && sources.enabled) attachedRootCount++
    }
    private val diagnosticUnsupported = moduleData["diagnosticUnsupported"] == true
    private val deferredUnsupported = linkedSetOf<String>()
    private val bindings = moduleData["bindings"] as? List<Map<String, Any?>> ?: throw RuntimeFault("Missing bindings")
    private val constructors = (moduleData["constructors"] as? List<Map<String, Any?>> ?: emptyList()).associateBy { it["id"] as String }
        .let { demand?.constructors(it) ?: it }
    private val dataLayouts = demand?.layouts ?: mutableMapOf<String, DataLayout>()
    private val globals = bindings.associate { it["id"] as String to GlobalBinding(it["name"] as String) }
        .let { demand?.globals(it) ?: it }
    private val globalProofs = bindings.associate { binding ->
        val expression = binding["expr"] as List<Any?>
        val delayed = representation(binding) && expression[0] !in listOf("lam", "lit", "con", "void")
        (binding["id"] as String) to if (diagnosticUnsupported) CoreRepresentation.UNKNOWN
            else CoreRepresentations.binder(binding).copy(evaluated = demand == null && !delayed && expression[0] in listOf("lam", "lit", "con", "void"))
    }
    private val indices = bindings.withIndex().associate { it.value["id"] as String to it.index }
    private val names = bindings.withIndex().groupBy({ it.value["name"] as String }, { it.index })
    private val hostEntries = mutableMapOf<Int, RootCallTarget>()
    private val globalEntries = bindings.associate { it["id"] as String to CoreEntries.binding(it) }
    private val globalArityCertificates = bindings.associate { it["id"] as String to CoreApplicationCertificates.binding(it) }
    private val validateInputs = if (diagnosticUnsupported) null else CoreInputCalls.validator(bindings, constructors, demand)
    init {
        val eager = ArrayList<Map<String, Any?>>()
        for (binding in bindings) {
            // Strict global initialization retains its original scheduling.
            val source = binding["expr"] as? thc.CoreBindingBody
            if ((source == null && demand == null) || !representation(binding) ||
                demand == null && source?.header?.tag in listOf("lit", "con", "void")) {
                eager += binding
                continue
            }
            globals.getValue(binding["id"] as String).defer(preparationLock) {
                validateBindings(listOf(binding))
                val scope = Scope(FrameLayout())
                val initializer = initializer(binding, scope)
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), scope.layout.build())
                initializer.execute(frame).also { value ->
                    CoreFunctionIdentity.install(moduleData, binding, value, globalArityCertificates)
                    initializedBindingCount++
                }
            }
        }
        validateBindings(eager)
        val scope = Scope(FrameLayout())
        val initializers = eager.map { initializer(it, scope) }
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), scope.layout.build())
        eager.forEachIndexed { index, binding ->
            val value = initializers[index].execute(frame)
            CoreFunctionIdentity.install(moduleData, binding, value, globalArityCertificates)
            globals.getValue(binding["id"] as String).initialize(value)
            initializedBindingCount++
        }
    }

    private fun validateBindings(requested: List<Map<String, Any?>>) {
        if (requested.isEmpty()) return
        if (capturesContinuations) AstAsyncAdmission.validate(requested)
        ArrayOp.validateApplications(requested)
        CoreStackForeign.validateHeads(requested)
        CoreStackInfoForeign.validateHeads(requested)
        CoreOriginalStdio.validateHeads(requested)
        CoreProcessForeign.validateHeads(requested)
        CoreStablePointers.validateHeads(requested)
        CoreRtsShutdown.validateHeads(requested)
        CoreMainThreadForeign.validateHeads(requested)
        CoreBoundThreadForeign.validateHeads(requested)
        CoreStringRtsForeign.validateHeads(requested)
        CoreEnvironmentForeign.validateHeads(requested)
        CoreRtsDiagnosticForeign.validateHeads(requested)
        CoreRtsArgumentsForeign.validateHeads(requested)
        CoreManagedFiles.validateHeads(requested)
        CoreMd5Foreign.validateHeads(requested)
        CoreGmpForeign.validateHeads(requested)
        CoreLibdwForeign.validateHeads(requested)
        CoreNativeAllocationForeign.validateHeads(requested)
        CoreMemoryCopyForeign.MEMMOVE.validateHeads(requested)
        CoreMemoryCopyForeign.MEMCPY.validateHeads(requested)
        CoreSignalForeign.validateHeads(requested)
        if (!diagnosticUnsupported) {
            CoreRepresentations.validateAggregates(requested, constructors)
            checkNotNull(validateInputs).accept(requested)
        }
    }

    private fun initializer(binding: Map<String, Any?>, scope: Scope): Expr {
        CoreRepresentations.requireNoSum(CoreRepresentations.binder(binding), "global binding")
        return withSource(sources.binding(binding)) {
            val expr = binding["expr"] as List<Any?>
            CoreRepresentations.requireNoSum(CoreRepresentations.expression(expr), "global binding")
            if (demand != null && !representation(binding) && expr[0] !in listOf("lit", "void"))
                throw UnsupportedCore("Demand loading does not yet support effectful strict global initialization")
            if (representation(binding) && (expr[0] !in listOf("lam", "lit", "con", "void") || demand != null && expr[0] != "lam")) delay(expr, scope, binding["name"] as String)
            else argument(expr, scope, representation(binding), binding["name"] as String)
        }
    }
    private fun bindingIndex(name: String): Int = indices[name] ?: names[name]?.singleOrNull()
        ?: names.entries.singleOrNull { it.key.substringAfterLast('.') == name }?.value?.singleOrNull()
        ?: throw RuntimeFault("Unknown or ambiguous entry $name")
    @Synchronized override fun hostEntryTarget(arity: Int): RootCallTarget =
        hostEntries.getOrPut(arity) { EntryRoot(language, arity, metrics).callTarget }
    override fun entryValue(name: String): Any? = if (name !in indices && demand?.contains(name) == true)
        globals.getValue(name).read() else globals.getValue(bindings[bindingIndex(name)]["id"] as String).read()
    override fun constructorLayout(id: String): DataLayout = dataLayout(id)
    override fun entryTarget(name: String): RootCallTarget {
        var value = entryValue(name)
        while (value is Thunk && value.state == 2) value = value.value
        return when (value) { is Closure -> value.target; is Thunk -> value.target ?: hostEntryTarget(0); else -> hostEntryTarget(0) }
    }
    override fun diagnostics(): Map<String, Any> = linkedMapOf(
        "backend" to "ast", "asyncExceptions" to enableAsync, "sourceNotesEnabled" to sources.enabled, "sourceSpanCount" to sources.spanCount,
        "sourceRootCount" to attachedRootCount, "loweredRootCount" to constructedRootCount,
        "hostEntryRootCount" to hostEntries.size,
        "initializedBindingCount" to initializedBindingCount,
        "instrumented" to metrics.enabled, "thunkEvaluationsByLabel" to metrics.thunkCountsSnapshot(),
        "compiledEntries" to metrics.compiledEntries, "leadingCaseReturns" to metrics.leadingCaseReturns, "thunkEvaluations" to metrics.thunkEvaluations,
        "thunkHits" to metrics.thunkHits, "blackholes" to metrics.blackholes, "directCacheMisses" to metrics.directCacheMisses,
        "indirectCalls" to metrics.indirectCalls, "tailBounces" to metrics.tailBounces, "papAllocations" to metrics.papAllocations,
        "localJoinTransfers" to metrics.localJoinTransfers,
        "selfTailReentries" to metrics.selfTailReentries, "trampolineIterations" to metrics.trampolineIterations,
        "unsupportedPolicy" to (if (diagnosticUnsupported) "diagnostic-traps"
            else if (bindings.any { it["expr"] is thc.CoreBindingBody }) "reject-at-binding-admission" else "reject-at-load"),
        "deferredUnsupported" to deferredUnsupported.toList(), "unsupportedTraps" to metrics.unsupportedTraps,
        "frames" to "indexed primitive slots; selective StaticShape captures",
        "stackPolicy" to (if (capturesContinuations) "tail-safe; bounded AST activation chains; active STM spilling unsupported"
            else "tail-safe; non-tail calls and nested thunk forcing use host stack"), "threadPolicy" to
            if (enableAsync) "context-owned Java threads; captured asynchronous delivery"
            else "context-owned Java threads; external asynchronous delivery disabled") + (loadingStatistics?.invoke() ?: emptyMap())
    private fun representation(binding: Map<String, Any?>): Boolean = binding["lifted"] as? Boolean
        ?: throw UnsupportedCore("Unknown levity for ${binding["id"]}")
    private fun function(label: String, args: List<Map<String, Any?>>, expression: List<Any?>, outer: Scope,
                         resultProof: CoreRepresentation = CoreRepresentations.expression(expression),
                         entryStrict: BooleanArray = BooleanArray(args.size),
                         role: FunctionRootRole = FunctionRootRole.FUNCTION,
                         bodyTail: Boolean = true): FunctionSpec {
        if (entryStrict.size != args.size) throw RuntimeFault("Function entry contract arity mismatch")
        val scope = Scope(FrameLayout())
        val free = coreFreeVariables(expression)
        val argumentIds = args.map { it["id"] as String }.toSet()
        args.forEach { CoreRepresentations.requireInput(CoreRepresentations.binder(it)) }
        val inputLayout = ArgumentLayout.fromProofs(args.map(CoreRepresentations::binder))
        val freeLocals = (free - argumentIds).filter { it in outer.locals }
        freeLocals.filter { outer.locals.getValue(it).let { local -> local.slot < 0 && local.proof.kind == CoreKind.VOID } }
            .forEach { scope.bindVoid(it, outer.locals.getValue(it).proof) }
        val captured = freeLocals.filter { outer.locals.getValue(it).let { local -> local.slot >= 0 || local.proof.kind != CoreKind.VOID } }
        val captureFields = arrayListOf<Local>()
        val captureDestinations = arrayListOf<Int>()
        val vectorDestinations = arrayListOf<IntArray?>()
        for (id in captured) {
            val local = outer.locals.getValue(id)
            if (local.proof.isTypedTransport) {
                CoreRepresentations.requireInput(local.proof)
                val fields = ArgumentLayout.leaves(local.proof)
                val sources = local.tupleSlots
                if (local.cell || sources?.size != fields.size)
                    throw UnsupportedCore("Aggregate capture requires exact typed locals")
                // Logical aggregates remain aliases of owned typed properties.
                // Void leaves have no fields; vector leaves keep their raw species.
                val destinations = IntArray(fields.size) { index ->
                    val field = fields[index]
                    val destination = scope.layout.bind("$id captured field $index", outlinedSlotKind(field))
                    captureFields += Local(sources[index], field.isLong, field, false,
                        tupleSlots = if (field.isVector) intArrayOf(sources[index]) else null)
                    vectorDestinations += if (field.isVector) intArrayOf(destination) else null
                    captureDestinations += destination
                    destination
                }
                scope.bindTuple(id, local.proof, destinations)
            } else {
                CoreRepresentations.requireScalar(local.proof, "capture")
                captureFields += local
                vectorDestinations += null
                captureDestinations += scope.bind(id, local.primitive, local.proof, local.cell, local.entry,
                    local.arityCertificate, outlinedSlotKind(local.proof, local.cell)).slot
            }
        }
        val captureSources = captureFields.map { if (it.proof.isVector) it.tupleSlots!![0] else it.slot }.toIntArray()
        val environmentSlots = captureDestinations.toIntArray()
        val environmentVectorSlots = vectorDestinations.toTypedArray()
        val argumentSlots = arrayListOf<Int>(); val argumentIndices = arrayListOf<Int>()
        val argumentProofs = arrayListOf<CoreRepresentation>()
        for ((index, arg) in args.withIndex()) {
            val lifted = representation(arg)
            val proof = CoreRepresentations.binder(arg).let { if (lifted) it.copy(evaluated = entryStrict[index]) else it }
            if (proof.isTypedTransport) {
                if (lifted) throw RuntimeFault("Typed formal cannot be lifted")
                val fields = ArgumentLayout.leaves(proof)
                val slots = IntArray(fields.size) { leaf -> scope.layout.bind("${arg["id"]} typed input $leaf") }
                scope.bindTuple(arg["id"] as String, proof, slots)
                fields.forEachIndexed { leaf, field ->
                    argumentIndices += ArgumentLayout.offset(inputLayout, index) + leaf
                    argumentProofs += field
                    argumentSlots += slots[leaf]
                }
            } else if (arg["id"] in free || capturesContinuations && entryStrict[index]) {
                argumentIndices += ArgumentLayout.offset(inputLayout, index); argumentProofs += proof
                argumentSlots += scope.bind(arg["id"] as String, !lifted && arg["coercion"] != true, proof).slot
            }
        }
        val captures = if (captureFields.isEmpty()) null else CaptureLayout.withVectors(requireNotNull(language),
            captureFields.map { it.proof.takeIf { proof -> proof.isVector } }.toTypedArray(),
            captureFields.map { !it.proof.isVector && it.primitive }.toBooleanArray(),
            captureFields.map { !it.cell && it.proof.isLong && it.proof.evaluated }.toBooleanArray(),
            captureFields.map { if (it.cell) null else it.proof.referenceCarrier() }.toTypedArray(),
            captureFields.map { !it.cell && it.proof.isFloat && it.proof.evaluated }.toBooleanArray(),
            captureFields.map { !it.cell && it.proof.isDouble && it.proof.evaluated }.toBooleanArray(),
            captureFields.map { if (!it.cell && it.proof.evaluated) it.proof.narrowInteger else null }.toTypedArray())
        val allArgumentSlots = IntArray(args.size) { -1 }
        val allArgumentProofs = Array(args.size) { CoreRepresentation.UNKNOWN }
        args.forEachIndexed { index, arg ->
            scope.locals[arg["id"]]?.takeIf { !it.proof.isTypedTransport }?.let { local ->
                allArgumentSlots[index] = local.slot
                allArgumentProofs[index] = local.proof
            }
        }
        if (role == FunctionRootRole.FUNCTION) scope.self = AstSelfLayout(captures, environmentSlots,
            allArgumentSlots, allArgumentProofs, entryStrict.copyOf(), inputLayout, environmentVectorSlots)
        val body = compile(expression, scope, bodyTail)
        // Async AST has no caller capture around a typed handoff loan yet.
        // Keep admitted roots on the ordinary scalar call ABI.
        // Mandatory typed ingress (including narrow scalars) owns its own packet.
        // It must not also advertise the empty-argument scalar handoff protocol.
        val handoff = if (capturesContinuations || inputLayout?.requiresTyped == true) null else HandoffEntry.create(language, scope.layout,
            args.map(CoreRepresentations::binder), resultProof, captures != null)
        if ((body.representation.isSum || resultProof.isSum) && (!body.representation.isSum || !resultProof.isSum))
            throw RuntimeFault("Sum function requires exact body and declared result proofs")
        val effectiveResult = body.representation.refine(resultProof)
        val tuple = if (effectiveResult.isTypedTransport) TupleShape(effectiveResult, language as thc.Language) else null
        val tupleSlots = IntArray(tuple?.width ?: 0) { scope.layout.bind("<typed return $it>") }
        val root = FunctionRoot(language, scope.layout.build(), label, captures, environmentSlots,
            argumentSlots.toIntArray(), argumentIndices.toIntArray(), body, metrics, argumentProofs.toTypedArray(), resultProof,
            rootSource(body), entryStrict, handoff, tuple, tupleSlots, inputLayout, enableAsync, environmentVectorSlots, delimited, role,
            stackCapture = outlineCaseArms)
        constructedRootCount++
        root.configureForeignExceptionBridge(foreignExceptionBridge)
        if (language is thc.Language) root.configureTypedInput(TypedInputLayout.create(language, inputLayout, captures != null))
        if (role == FunctionRootRole.FUNCTION && !capturesContinuations && body is Case && inputLayout == null) root.configureLeadingCaseReturn(LeadingCaseReturn.discover(args, expression,
            resultProof, root.entryArgumentOffset, free.intersect(argumentIds), captures != null,
            ::dataLayout, sources, body.coreSourceLocation))
        return FunctionSpec(root.callTarget, captures, captureSources)
    }
    private fun caseArm(expression: List<Any?>, scope: Scope, tail: Boolean): Expr {
        // Atomic values and lambdas already have small bodies/independent roots.
        // An outer lexical join owns this activation's slots: retain it here
        // until there is an explicit cross-root join transfer protocol.
        if (!outlineCaseArms || expression[0] !in listOf("app", "case", "let") ||
            coreFreeVariables(expression).any { it in scope.joins }) return compile(expression, scope, tail)
        val operands = operandBuilder
        operandBuilder = null
        val fn = try { function("case arm", emptyList(), expression, scope,
            role = FunctionRootRole.PASS_THROUGH, bodyTail = tail) }
        finally { operandBuilder = operands }
        return AstCaseArm(fn.target, fn.captureLayout, fn.captures, tail).located(currentSource)
    }
    /** Selected side roots must not compile a first-store deopt for carriers
     * already fixed by their case binder or immutable capture layout. */
    private fun outlinedSlotKind(proof: CoreRepresentation, cell: Boolean = false): FrameSlotKind = when {
        !outlineCaseArms -> FrameSlotKind.Illegal
        cell || proof.isVector -> FrameSlotKind.Object
        !proof.evaluated -> FrameSlotKind.Illegal
        proof.isInt -> FrameSlotKind.Int
        proof.isLong -> FrameSlotKind.Long
        proof.isFloat -> FrameSlotKind.Float
        proof.isDouble -> FrameSlotKind.Double
        proof.isEvaluatedReference -> FrameSlotKind.Object
        else -> FrameSlotKind.Illegal
    }
    private fun delay(expr: List<Any?>, scope: Scope, label: String): Expr {
        val fn = function(label, emptyList(), expr, scope)
        (fn.target.rootNode as GuestRoot).tupleResult?.let { CoreRepresentations.requireScalar(it.proof, "thunk") }
        return Delay(fn.target, fn.captureLayout, fn.captures).proven(CoreRepresentations.expression(expr).copy(evaluated = false))
            .located(sources.expression(expr, currentSource))
    }
    private fun argument(expr: List<Any?>, scope: Scope, lifted: Boolean, label: String = "argument thunk", allowEmpty: Boolean = false, declaredLifted: Boolean = lifted): Expr {
        val operands = operandBuilder
        operandBuilder = null
        val result = try { argumentUnsequenced(expr, scope, lifted, label, allowEmpty, declaredLifted) }
            finally { operandBuilder = operands }
        return operands?.operand(result) ?: result
    }
    private fun argumentUnsequenced(expr: List<Any?>, scope: Scope, lifted: Boolean, label: String, allowEmpty: Boolean, declaredLifted: Boolean): Expr {
        val proof = CoreRepresentations.expression(expr)
        fun check(value: CoreRepresentation) {
            if (allowEmpty || value.isVector) CoreRepresentations.requireInput(value)
            else CoreRepresentations.requireScalar(value, "argument")
            if (value.isTypedTransport && declaredLifted) throw RuntimeFault("Typed argument cannot be lifted")
        }
        check(proof)
        val lexical = if (expr[0] == "var") scope.locals[expr[1]]?.proof else null
        lexical?.let(::check)
        if (proof.isTypedTransport || lexical?.isTypedTransport == true) {
            if (declaredLifted) throw RuntimeFault("Typed argument cannot be lifted")
            return compile(expr, scope, false).also {
                if (!it.representation.isTypedTransport) throw RuntimeFault("Missing exact typed argument proof")
            }
        }
        if (!lifted) return Evaluate(compile(expr, scope, false).also { check(it.representation) }, metrics)
        // GHC's context-aware exprOkForSpecEval certificate also covers total
        // primitive operands in constructors, without strictifying recursive
        // dictionary knots. Allocate these values directly instead of creating
        // an update thunk and captures. A false certificate overrides HNF.
        // Older exports fall back to exprIsHNF; missing proofs stay lazy.
        val head = (expr.getOrNull(1) as? List<*>)?.takeIf { expr[0] == "app" && it.firstOrNull() == "var" }
        val headId = head?.getOrNull(1) as? String
        val arityCertificate = headId?.let { id ->
            if (id in scope.locals) scope.locals.getValue(id).arityCertificate else globalArityCertificates[id]
        }
        val unopenedHead = headId != null && headId !in scope.locals && headId !in globalArityCertificates && demand?.contains(headId) == true
        if (!unopenedHead && CoreApplicationCertificates.eagerApplication(expr, arityCertificate))
            return compile(expr, scope, false).also { check(it.representation) }
        return when (expr[0]) { "var", "lit", "lam", "con", "prim", "void" -> compile(expr, scope, false); else -> delay(expr, scope, label) }.also { check(it.representation) }
    }
    private fun literal(kind: String, encoded: Any?, proof: CoreRepresentation? = null): Any {
        if (encoded is CoreFloatingLiteral) return encoded.decode(kind)
        val value = encoded as? String ?: throw UnsupportedCore("Malformed Core literal payload")
        return when (kind) {
        "rubbish" -> rubbishLiterals.decode(requireNotNull(proof))
        "int8" -> int8Literal(value)
        "int16" -> int16Literal(value)
        "int32" -> int32Literal(value)
        "int64" -> int64Literal(value)
        "word64" -> word64Literal(value)
        "int", "char" -> value.toLong()
        "word" -> value.toULong().toLong()
        "float" -> value.toFloat()
        "double" -> value.toDouble()
        "word8", "word16", "word32" -> narrowWordLiteral(kind, value)
        "string-bytes" -> ManagedAddress.fromHex(value)
        "null-addr" -> if (value == "0") ManagedAddress.nullAddress() else throw UnsupportedCore("Malformed null Addr# literal")
        "function-addr" -> CFinalizerLabels.fromCore(value, proof)
        "data-addr" -> CoreDataLabels.fromCore(value, proof, stackTargetLayout as? TargetLayout)
        "bignat" -> BigNatLiterals.decode(value)
        else -> throw UnsupportedCore("Unsupported literal kind $kind")
        }
    }
    private fun compile(expr: List<Any?>, scope: Scope, tail: Boolean): Expr {
        CoreStateApplications.inline(expr)?.let { return compile(it, scope, tail) }
        val outer = operandBuilder
        val head = (expr.getOrNull(1) as? List<*>)?.firstOrNull()
        val operands = if (capturesContinuations && expr.firstOrNull() == "app" && head in setOf("prim", "con"))
            OperandBuilder(scope.layout) else null
        operandBuilder = operands
        val result = try {
            withSource(sources.expression(expr, currentSource)) {
                val node = compileLocated(expr, scope, tail).located(currentSource)
                (operands?.finish(node) ?: node).located(currentSource)
            }
        } finally { operandBuilder = outer }
        return outer?.operand(result) ?: result
    }
    private fun compileLocated(expr: List<Any?>, scope: Scope, tail: Boolean): Expr = try {
        val lowered = compileSupported(expr, scope, tail)
        val metadata = if (diagnosticUnsupported && lowered is GlobalRead) CoreRepresentation.UNKNOWN
            else CoreRepresentations.expression(expr)
        // GHC HNF can become a thunk in our lazy storage. Only retain evaluatedness
        // established by the lowered producer or its lexical storage contract.
        lowered.proven(lowered.representation.refine(metadata.copy(evaluated = false)))
    } catch (gap: UnsupportedCore) {
        if (!diagnosticUnsupported) throw gap
        val message = gap.message ?: "Unsupported Core"
        deferredUnsupported += message
        // An unavailable value stays lazy until demanded. This applies uniformly
        // to unknown globals/operations, without special-casing library names or
        // removing branches. The default mode still rejects the same Core.
        val body = UnsupportedExpression(message, metrics).located(currentSource)
        val target = FunctionRoot(language, FrameLayout().build(), "unsupported: $message", null,
            intArrayOf(), intArrayOf(), intArrayOf(), body, metrics, coreSourceLocation = rootSource(body),
            stackCapture = outlineCaseArms).callTarget
        constructedRootCount++
        DiagnosticUnavailable(target, message, metrics)
    }
    private fun compileSupported(expr: List<Any?>, scope: Scope, tail: Boolean): Expr = when (expr[0]) {
        "var" -> {
            val id = expr[1] as String
            val occurrence = CoreRepresentations.expression(expr)
            val proof = scope.locals[id]?.proof ?: globalProofs[id] ?: demand?.occurrence(id, occurrence)
            CoreVectors.requireVariableProof(proof, occurrence)
            scope.joins[id]?.let { joinJump(it, emptyList(), emptyList<Boolean>(), scope) }
                ?: scope.locals[id]?.let {
                    if (it.tupleSlots != null) {
                        val shape = TupleShape(it.proof, language as thc.Language)
                        if (it.proof.isVector) VectorLocalRead(shape, it.tupleSlots)
                        else TupleLocalRead(shape, it.tupleSlots)
                    }
                    else if (it.slot < 0 && it.proof.kind == CoreKind.VOID) Literal(Unit).proven(it.proof)
                    else LocalRead(it.slot, it.cell).proven(it.proof)
                }
                ?: globals[id]?.let { GlobalRead(it).proven(proof ?: globalProofs.getValue(id)) }
                ?: throw UnsupportedCore("Unresolved external binding $id")
        }
        "lit" -> Literal(literal(expr[1] as String, expr[2], CoreRepresentations.expression(expr))).let {
            if (expr[1] in listOf("int8", "word8", "int16", "word16", "int32", "word32")) it.proven(CoreRepresentations.narrowLiteralProof(expr))
            else if (expr[1] == "bignat") it.proven(BigNatLiterals.proof(expr))
            else if (expr[1] == "rubbish") it.proven(RubbishLiterals.proof(expr)) else it
        }
        "void" -> Literal(Unit)
        "lam" -> {
            val args = expr[1] as List<Map<String, Any?>>
            val fn = function("lambda ${args.joinToString { it["name"].toString() }}", args, expr[2] as List<Any?>, scope,
                CoreRepresentations.lambdaResult(expr), CoreEntries.lambda(expr))
            MakeClosure(fn.target, args.size, fn.captureLayout, fn.captures)
        }
        "app" -> {
            val fn = expr[1] as List<Any?>; val args = expr[2] as List<List<Any?>>
            val flags = expr.getOrNull(3) as? List<*> ?: throw RuntimeFault("Application lacks representation flags")
            if (flags.size != args.size) throw RuntimeFault("Application representation flag count mismatch")
            val callStrict = CoreCallDemands.lowerApplication(expr, callDemandsEnabled)
            val tupleProof = CoreRepresentations.expression(expr)
            val tupleOperation = if (fn[0] == "prim") TupleArithmeticOp.named(fn[1] as String) else null
            val floatDecode = if (fn[0] == "prim") FloatDecodeOp.named(fn[1] as String) else null
            // Join heads are lexically owned too: foreign metadata cannot turn
            // a local jump into an unresolved external declaration.
            val defined = fn[0] == "var" &&
                (fn[1] in scope.locals || fn[1] in scope.joins ||
                    if (demand != null && CoreRepresentations.metadata(expr)?.containsKey("foreignCall") == true)
                        demand.isDefined(fn[1] as String) else fn[1] in globals)
            val cpuAffinity = CoreCpuAffinity.validate(expr, defined || fn.getOrNull(1) in scope.joins)
            val runtimeService = CoreRuntimeServices.validate(expr, defined || fn.getOrNull(1) in scope.joins)
            val packageScalar = if (cpuAffinity == null && runtimeService == null) CorePackageScalarForeign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags,
                CoreRepresentations.metadata(expr)?.get("rep"), packageScalarLinks) else null
            // A verified package owner takes precedence over similarly named RTS symbols.
            val foreignMetadata = if (packageScalar == null) CoreRepresentations.metadata(expr) else null
            val stackClone = CoreStackForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags)
            val stackInfo = CoreStackInfoForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val originalStdio = CoreOriginalStdio.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val originalProcess = CoreProcessForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val capi = CoreCapiForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags,
                CoreRepresentations.metadata(expr)?.get("rep"), foreignLinks)
            val stableFree = CoreStablePointers.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val sharedCAF = CoreSharedCAFStores.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val shutdown = CoreRtsShutdown.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val mainThreadForeign = CoreMainThreadForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val boundThreadForeign = CoreBoundThreadForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val gcForeign = CoreGcForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val rtsEventForeign = CoreRtsEventForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val allocationCounterForeign = CoreBoundThreadForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"), true)
            val stringRts = CoreStringRtsForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val floatingForeign = CoreFloatForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val textForeign = CoreTextForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val environment = CoreEnvironmentForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val rtsDiagnostic = CoreRtsDiagnosticForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val rtsArguments = CoreRtsArgumentsForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val managedFile = CoreManagedFiles.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val javascript = if (packageScalar == null && !stackClone && stackInfo == null && originalStdio == null && managedFile == null) CoreJavaScript.validate(expr, defined) else null
            val md5 = if (javascript == null) CoreMd5Foreign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep")) else null
            val gmp = CoreGmpForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val processSignal = CoreSignalForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val nativeAllocation = CoreNativeAllocationForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val memmove = CoreMemoryCopyForeign.MEMMOVE.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val memcpy = CoreMemoryCopyForeign.MEMCPY.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val byteStringSort = CoreByteStringSort.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val byteStringDecimal = CoreByteStringDecimal.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val byteStringUtf8 = CoreByteStringUtf8Foreign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val memset = CoreMemsetForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val memorySearch = CoreMemorySearchForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val libdw = CoreLibdwForeign.validate(foreignMetadata,
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val polyglot = if (originalProcess == null && rtsEventForeign == null && gcForeign == null && textForeign == null && !byteStringSort && byteStringDecimal == null && byteStringUtf8 == null && memorySearch == null && floatingForeign == null && cpuAffinity == null && runtimeService == null && !allocationCounterForeign && environment == null && packageScalar == null && !stackClone && stackInfo == null && originalStdio == null && capi == null &&
                !stableFree && shutdown == null && !mainThreadForeign && !boundThreadForeign && stringRts == null && rtsDiagnostic == null && rtsArguments == null && sharedCAF == null && managedFile == null && javascript == null && md5 == null && gmp == null && libdw == null && nativeAllocation == null && !memmove && !memcpy && !memset && processSignal == null)
                CorePolyglot.validate(expr, defined) else null
            if ((packageScalar != null || javascript != null || polyglot != null || runtimeService == RuntimeServiceCall.EXCEPTION_TEXT) &&
                foreignExceptionBridge == null) fault("Foreign execution requires a linked genuine THC.Exception runtime bundle")
            if (runtimeService != null) {
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { lowered ->
                        CoreRuntimeServices.validateOperand(runtimeService, index, lowered.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                when (runtimeService) {
                    RuntimeServiceCall.QUERY -> RuntimeQueryExpression(1, operands[0], operands[1], operands[2], operands[3])
                    RuntimeServiceCall.CONTROL -> RuntimeControlExpression(operands[0], operands[1], operands[2])
                    RuntimeServiceCall.TRACE -> RuntimeTraceExpression(operands[0], operands[1], operands[2], operands[3], operands[4])
                    RuntimeServiceCall.EXCEPTION_TEXT -> ExceptionTextExpression(operands[0], operands[1], operands[2], operands[3])
                }.proven(tupleProof.copy(evaluated = true))
            } else if (cpuAffinity != null) {
                val state = compile(args.single(), scope, false)
                CoreBoundThreadForeign.validateOperand(state.representation,
                    if (args.single()[0] == "var") scope.locals[args.single()[1]]?.proof ?: globalProofs[args.single()[1]] else null)
                CpuAffinityQuery(cpuAffinity, state).proven(tupleProof.copy(evaluated = true))
            } else if (stackClone) {
CoreStackForeign.validateHead(fn, defined)
                val state = args.single()
                CoreStackForeign.validateBinding(if (state[0] == "var")
                    scope.locals[state[1]]?.proof ?: globalProofs[state[1]] else null)
                val operand = compile(state, scope, false)
                CoreStackForeign.validateState(operand.representation)
                CloneStackExpression(operand, tupleProof)
            } else if (stackInfo != null) {
                val layout = CoreStackInfoForeign.requireLayout(stackTargetLayout)
                CoreStackInfoForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreStackInfoForeign.validateOperand(stackInfo, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                OriginalStackInfoExpression(stackInfo, layout, operands.toTypedArray(), tupleProof)
            } else if (originalProcess != null) {
                CoreProcessForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreProcessForeign.validateOperand(originalProcess, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                ProcessForeignExpression(originalProcess, operands.toTypedArray(), tupleProof)
            } else if (originalStdio != null) {
                CoreOriginalStdio.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        if (originalStdio.processIdentity || originalStdio == OriginalStdioOp.SET_ERRNO || originalStdio.eventDescriptor || originalStdio.waitStatus || originalStdio.pathRemoval || originalStdio.flagConstant || originalStdio.fcntl || originalStdio == OriginalStdioOp.SIGPROCMASK || originalStdio.readiness || originalStdio.seekConstant || originalStdio.stat || originalStdio.termios || originalStdio.sigset || originalStdio.savedTermios || originalStdio.readImage || originalStdio.pathStat || originalStdio.pathMode || originalStdio == OriginalStdioOp.ACCESS || originalStdio == OriginalStdioOp.UNLINKAT || originalStdio == OriginalStdioOp.FSTATAT || originalStdio.pathLink || originalStdio.currentDirectory || originalStdio.directoryStream || originalStdio == OriginalStdioOp.TCSETATTR || originalStdio.opening ||
                            originalStdio.iconv || originalStdio.strerror || originalStdio.duplication || originalStdio.locking)
                            CoreOriginalStdio.validateScalarOperand(originalStdio, index,
                            operand.representation, if (argument[0] == "var")
                                scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                OriginalStdioExpression(originalStdio, operands.toTypedArray(), tupleProof)
            } else if (capi != null) {
                CoreCapiForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreCapiForeign.validateOperand(capi, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                CapiExpression(capi, operands.toTypedArray(), tupleProof)
            } else if (packageScalar != null) {
                CoreCapiForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CorePackageScalarForeign.validateOperand(packageScalar, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                PackageScalarExpression(packageScalar, operands.toTypedArray(), tupleProof)
            } else if (stableFree) {
                CoreStablePointers.validateHead(fn, defined)
                FreeStablePointer(compile(args[0], scope, false), compile(args[1], scope, false))
                    .proven(tupleProof.copy(evaluated = true))
            } else if (sharedCAF != null) {
                CoreSharedCAFStores.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreSharedCAFStores.validateOperand(index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                SharedCAFStoreExpression(sharedCAF, operands[0], operands[1])
                    .proven(tupleProof.copy(evaluated = true))
            } else if (rtsArguments != null) {
                CoreRtsArgumentsForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreRtsArgumentsForeign.validateOperand(rtsArguments, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                RtsArgumentsExpression(rtsArguments, operands.toTypedArray(), tupleProof)
            } else if (rtsDiagnostic != null) {
                CoreRtsDiagnosticForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreRtsDiagnosticForeign.validateOperand(rtsDiagnostic, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                RtsDiagnosticExpression(rtsDiagnostic, operands.toTypedArray(), tupleProof)
            } else if (rtsEventForeign != null) {
                CoreRtsEventForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreRtsEventForeign.validateOperand(rtsEventForeign, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                RtsEventForeignExpression(rtsEventForeign, operands.toTypedArray(), tupleProof)
            } else if (gcForeign != null) {
                CoreGcForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreGcForeign.validateOperand(gcForeign, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                GcForeignExpression(gcForeign, operands.toTypedArray(), tupleProof)
            } else if (boundThreadForeign || allocationCounterForeign) {
                CoreBoundThreadForeign.validateHead(fn, defined)
                val argument = args.single()
                val state = compile(argument, scope, false)
                CoreBoundThreadForeign.validateOperand(state.representation, if (argument[0] == "var")
                    scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                BoundThreadSupport(state, allocationCounterForeign).proven(tupleProof.copy(evaluated = true))
            } else if (environment != null) {
                CoreEnvironmentForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreEnvironmentForeign.validateOperand(environment, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                EnvironmentExpression(environment, operands.toTypedArray(), tupleProof)
            } else if (textForeign != null) {
                CoreTextForeign.validateHead(fn, defined || fn.getOrNull(1) in scope.joins)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreTextForeign.validateOperand(textForeign, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                TextForeignExpression(textForeign, operands.toTypedArray(), tupleProof)
            } else if (floatingForeign != null) {
                CoreFloatForeign.validateHead(fn, defined || fn.getOrNull(1) in scope.joins)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreFloatForeign.validateOperand(floatingForeign, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                FloatForeignExpression(floatingForeign, operands[0], operands[1], tupleProof)
            } else if (stringRts != null) {
                CoreStringRtsForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreStringRtsForeign.validateOperand(stringRts, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                StringRtsExpression(stringRts, operands.toTypedArray(), tupleProof)
            } else if (shutdown != null) {
                CoreRtsShutdown.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreRtsShutdown.validateOperand(shutdown, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                ShutdownRuntime(shutdown, operands[0], operands[1], operands[2])
                    .proven(tupleProof.copy(evaluated = true))
            } else if (mainThreadForeign) {
                CoreMainThreadForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreMainThreadForeign.validateOperand(index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                RegisterMainThread(operands[0], operands[1]).proven(tupleProof.copy(evaluated = true))
            } else if (managedFile != null) {
                CoreManagedFiles.validateHead(fn, defined)
                ManagedFileExpression(managedFile, args.map { compile(it, scope, false) }.toTypedArray(), tupleProof)
            } else if (processSignal != null) {
                CoreSignalForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreSignalForeign.validateOperand(processSignal, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                InstallProcessSignal(operands.toTypedArray(), tupleProof)
            } else if (nativeAllocation != null) {
                CoreNativeAllocationForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreNativeAllocationForeign.validateOperand(nativeAllocation, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                NativeAllocationExpression(nativeAllocation, operands.toTypedArray(), tupleProof)
            } else if (byteStringSort) {
                CoreByteStringSort.validateHead(fn, defined || fn.getOrNull(1) in scope.joins)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreByteStringSort.validateOperand(index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                ByteStringSortExpression(operands.toTypedArray(), tupleProof)
            } else if (byteStringDecimal != null) {
                CoreByteStringDecimal.validateHead(fn, defined || fn.getOrNull(1) in scope.joins)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreByteStringDecimal.validateOperand(byteStringDecimal, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                ByteStringDecimalExpression(byteStringDecimal, operands.toTypedArray(), tupleProof)
            } else if (byteStringUtf8 != null) {
                CoreByteStringUtf8Foreign.validateHead(fn, defined || fn.getOrNull(1) in scope.joins)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreByteStringUtf8Foreign.validateOperand(index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                ByteStringUtf8Expression(byteStringUtf8, operands.toTypedArray(), tupleProof)
            } else if (memorySearch != null) {
                CoreMemorySearchForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreMemorySearchForeign.validateOperand(memorySearch, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                MemorySearchExpression(memorySearch, operands.toTypedArray(), tupleProof)
            } else if (memset) {
                CoreMemsetForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreMemsetForeign.validateOperand(index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                MemsetExpression(operands.toTypedArray(), tupleProof)
            } else if (memmove) {
                CoreMemoryCopyForeign.MEMMOVE.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreMemoryCopyForeign.MEMMOVE.validateOperand(index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null, false)
                    }
                }
                MemmoveExpression(operands.toTypedArray(), tupleProof)
            } else if (memcpy) {
                CoreMemoryCopyForeign.MEMCPY.validateHead(fn, defined)
                val byteArrays = CoreMemoryCopyForeign.MEMCPY.byteArrays(foreignMetadata)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreMemoryCopyForeign.MEMCPY.validateOperand(index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null, byteArrays)
                    }
                }
                MemcpyExpression(operands.toTypedArray(), tupleProof, byteArrays)
            } else if (libdw != null) {
                CoreLibdwForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreLibdwForeign.validateOperand(libdw, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                OriginalLibdwExpression(libdw, operands.toTypedArray(), tupleProof)
            } else if (gmp != null) {
                CoreGmpForeign.validateHead(fn, defined)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreGmpForeign.validateOperand(gmp, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                GmpForeignExpression(gmp, operands.toTypedArray(), tupleProof)
            } else if (md5 != null) {
                CoreMd5Foreign.validateHead(fn, defined)
                Md5ForeignExpression(md5, args.map { compile(it, scope, false) }.toTypedArray(), tupleProof)
            } else if (javascript != null) {
                JavaScriptExpression(javascript, args.map { argument(it, scope, false) }.toTypedArray())
                    .proven(tupleProof.copy(evaluated = true))
            } else if (polyglot != null) {
                PolyglotExpression(polyglot, args.mapIndexed { index, value ->
                    argument(value, scope, flags[index] as Boolean)
                }.toTypedArray()).proven(tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && fn[1] in setOf("newBCO#", "mkApUpd0#")) {
                val name = fn[1] as String
                if (capturesContinuations)
                    throw UnsupportedCore("GHC BCO frames do not yet preserve AST captures")
                GhcBCO.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                GhcBCOExpression(name, args.mapIndexed { index, value ->
                    argument(value, scope, flags[index] as Boolean)
                }.toTypedArray(), language as thc.Language, metrics, tupleProof)
            } else if (fn[0] == "prim" && fn[1] in setOf("newPromptTag#", "prompt#", "control0#")) {
                val name = fn[1] as String
                DelimitedControl.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                DelimitedPrimitive(name, TupleShape(tupleProof, language as thc.Language),
                    args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }.toTypedArray(),
                    language as thc.Language, metrics)
            } else if (fn[0] == "prim" && fn[1] == "tagToEnum#") {
                if (args.size != 1) throw RuntimeFault("tagToEnum#: Exactly one operand required")
                val operand = compile(args[0], scope, false)
                val ids = CoreEnums.validate(expr, operand.representation, constructors)
                TagToEnum(EnumFamily(ids.map { dataLayout(it).allocate() }.toTypedArray()), operand)
            } else if (fn[0] == "prim" && fn[1] in CoreDataTags.operations) {
                if (args.size != 1) throw RuntimeFault("dataToTag: Exactly one operand required")
                val operand = argument(args[0], scope, false)
                val ids = CoreDataTags.validate(expr, operand.representation, constructors)
                DataToTag(DataTagFamily(ids.map(::dataLayout).toTypedArray()), operand)
            } else if (fn[0] == "prim" && fn[1] in CoreVectors.operations) {
                val name = fn[1] as String
                CoreVectors.validate(name, args.map(CoreVectors::argumentProof), tupleProof)
                CoreVectors.validateFlags(flags)
                val operands = args.map { compile(it, scope, false) }.toTypedArray()
                val shuffle = if (name.startsWith("shuffle"))
                    CoreVectors.shuffleIndices(args[2], tupleProof.vector!!.lanes) else null
                when (name) {
                    in GeneratedVectors.operations -> GeneratedVectors.expression(name, operands, shuffle) { count ->
                        IntArray(count) { scope.layout.bind("<vector lane $it>") }
                    }
                    "packInt64X2#" -> VectorPack(operands[0], IntArray(2) { scope.layout.bind("<vector lane $it>") })
                    "unpackInt64X2#" -> VectorUnpack(operands[0])
                    "packInt32X4#" -> Vector32Pack(operands[0], IntArray(4) { scope.layout.bind("<vector lane $it>") })
                    "unpackInt32X4#" -> Vector32Unpack(operands[0])
                    "packDoubleX2#" -> VectorDoublePack(operands[0], IntArray(2) { scope.layout.bind("<double vector lane $it>") })
                    "unpackDoubleX2#" -> VectorDoubleUnpack(operands[0])
                    in CoreVectors.operationsDouble -> VectorDoubleOperation(name, operands)
                    "packFloatX4#" -> VectorFloatPack(operands[0], IntArray(4) { scope.layout.bind("<float vector lane $it>") })
                    "unpackFloatX4#" -> VectorFloatUnpack(operands[0])
                    in CoreVectors.operationsFloat -> VectorFloatOperation(name, operands)
                    in CoreVectors.fusedFloat8 -> VectorFloat8Fused(name, operands)
                    in CoreVectors.fusedFloat16 -> VectorFloat16Fused(name, operands)
                    in CoreVectors.fusedDouble4 -> VectorDouble4Fused(name, operands)
                    in CoreVectors.fusedDouble8 -> VectorDouble8Fused(name, operands)
                    in CoreVectors.operations32 -> Vector32Operation(name, operands)
                    "packInt16X8#" -> Vector16Pack(operands[0], IntArray(8) { scope.layout.bind("<int16 vector lane $it>") })
                    "unpackInt16X8#" -> Vector16Unpack(operands[0])
                    in CoreVectors.operations16 -> Vector16Operation(name, operands)
                    "packInt8X16#" -> Vector8Pack(operands[0], IntArray(16) { scope.layout.bind("<int8 vector lane $it>") })
                    "unpackInt8X16#" -> Vector8Unpack(operands[0])
                    in CoreVectors.operations8 -> Vector8Operation(name, operands)
                    "packWord8X16#" -> VectorWord8Pack(operands[0], IntArray(16) { scope.layout.bind("<word8 vector lane $it>") })
                    "unpackWord8X16#" -> VectorWord8Unpack(operands[0])
                    in CoreVectors.operationsWord8 -> VectorWord8Operation(name, operands)
                    "packWord16X8#" -> VectorWord16Pack(operands[0], IntArray(8) { scope.layout.bind("<word16 vector lane $it>") })
                    "unpackWord16X8#" -> VectorWord16Unpack(operands[0])
                    in CoreVectors.operationsWord16 -> VectorWord16Operation(name, operands)
                    "packWord32X4#" -> VectorWord32Pack(operands[0], IntArray(4) { scope.layout.bind("<word32 vector lane $it>") })
                    "unpackWord32X4#" -> VectorWord32Unpack(operands[0])
                    in CoreVectors.operationsWord32 -> VectorWord32Operation(name, operands)
                    else -> VectorOperation(name, operands)
                }
            } else if (fn[0] == "prim" && CoreArithmeticExceptions.payload(fn[1] as String) != null) {
                val name = fn[1] as String
                CoreArithmeticExceptions.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                val operand = argument(args.single(), scope, false, allowEmpty = true)
                CoreArithmeticExceptions.validate(name, listOf(operand.representation), flags, tupleProof)
                val id = CoreArithmeticExceptions.payload(name)!!
                val payload = globals[id] ?: throw UnsupportedCore("Unresolved implicit exception binding $id")
                RaiseArithmeticException(operand, RaiseException(GlobalRead(payload), true))
            } else if (fn[0] == "prim" && fn[1] in setOf("raiseIO#", "catch#", "getMaskingState#",
                    "unmaskAsyncExceptions#", "maskAsyncExceptions#", "maskUninterruptible#")) {
                val name = fn[1] as String
                CoreSynchronousExceptions.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                if (name == "raiseIO#") RaiseIOException(operands[0], operands[1], tupleProof, CoreExceptionPayload.validate(expr))
                else if (delimited && name != "getMaskingState#") DelimitedIOBoundary(name,
                    TupleShape(tupleProof, language as thc.Language), operands.toTypedArray(), language as thc.Language, metrics)
                else if (name == "catch#") CatchException(TupleShape(tupleProof, language as thc.Language),
                    operands[0], operands[1], operands[2], metrics)
                else if (name == "getMaskingState#") GetMaskingState(operands[0], tupleProof)
                else MaskAction(TupleShape(tupleProof, language as thc.Language), when (name) {
                    "maskAsyncExceptions#" -> MaskingState.MASKED_INTERRUPTIBLE
                    "maskUninterruptible#" -> MaskingState.MASKED_UNINTERRUPTIBLE
                    else -> MaskingState.UNMASKED
                }, operands[0], operands[1], metrics)
            } else if (fn[0] == "prim" && fn[1] == "noDuplicate#") {
                CoreNoDuplicate.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                NoDuplicate(argument(args[0], scope, false), tupleProof)
            } else if (fn[0] == "prim" && CoreThreadScheduling.named(fn[1] as String)) {
                val name = fn[1] as String
                CoreThreadScheduling.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                CoreThreadScheduling.validate(name, operands.map { it.representation }, flags, tupleProof)
                when (name) {
                    "par#" -> Literal(1L).also { it.representation = tupleProof.copy(evaluated = true) }
                    "delay#" -> DelayThread(operands[0], operands[1], enableAsync, tupleProof)
                    "setThreadAllocationCounter#" -> SetThreadAllocationCounter(operands[0], null, operands[1], tupleProof)
                    "setOtherThreadAllocationCounter#" -> SetThreadAllocationCounter(operands[0], operands[1], operands[2], tupleProof)
                    else -> SparkResult(name, operands.last(), if (name == "spark#") operands[0] else null,
                        if (name == "getSpark#") dataLayouts.getOrPut(CoreThreadScheduling.FALSE) {
                            DataLayout(language ?: fault("Spark constructor requires a guest language"),
                                CoreThreadScheduling.FALSE, "False", emptyArray())
                        }.allocate() else null, tupleProof)
                }
            } else if (fn[0] == "prim" && CoreThreadObservation.named(fn[1] as String)) {
                val name = fn[1] as String
                CoreThreadObservation.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                val state = argument(args[0], scope, false)
                CoreThreadObservation.validate(name, listOf(state.representation), flags, tupleProof)
                ThreadObservation(name == "listThreads#", state, tupleProof)
            } else if (fn[0] == "prim" && fn[1] == "yield#") {
                CoreYield.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                YieldThread(argument(args[0], scope, false), enableAsync, tupleProof)
            } else if (fn[0] == "prim" && CoreFileWait.named(fn[1] as String)) {
                val name = fn[1] as String
                CoreFileWait.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.map { argument(it, scope, false) }
                CoreFileWait.validate(name, operands.map { it.representation }, flags, tupleProof)
                val payload = globals[CoreFileWait.badFd]
                    ?: throw UnsupportedCore("$name requires original blockedOnBadFD payload")
                WaitFileDescriptor(operands[0], operands[1], payload, name == "waitWrite#", enableAsync, tupleProof)
            } else if (fn[0] == "prim" && fn[1] in listOf("fork#", "forkOn#", "myThreadId#", "threadStatus#", "killThread#", "labelThread#", "threadLabel#")) {
                val name = fn[1] as String
                CoreGuestThreads.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                CoreGuestThreads.validate(name, operands.map { it.representation }, flags, tupleProof)
                when (name) {
                    "fork#" -> ForkThread(operands[0], operands[1], tupleProof)
                    "forkOn#" -> ForkThread(operands[1], operands[2], tupleProof, operands[0])
                    "myThreadId#" -> MyThreadId(operands[0], tupleProof)
                    "threadStatus#" -> ThreadStatus(operands[0], operands[1], tupleProof)
                    "threadLabel#" -> ThreadLabel(operands[0], operands[1], tupleProof)
                    "killThread#" -> KillThread(operands[0], operands[1], operands[2], enableAsync, tupleProof)
                    else -> LabelThread(operands[0], operands[1], operands[2], tupleProof)
                }
            } else if (fn[0] == "prim" && fn[1] == "annotateStack#") {
                StackAnnotations.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                if (capturesContinuations) AnnotatedAction(TupleShape(tupleProof, language as thc.Language),
                    argument(args[0], scope, true), argument(args[1], scope, true),
                    argument(args[2], scope, false), metrics, enableAsync)
                else AnnotatedTuple(argument(args[0], scope, true), argument(args[2], scope, false),
                    TupleApplication(language as thc.Language, TupleShape(tupleProof, language),
                        argument(args[1], scope, true), arrayOf(Literal(Unit).proven(CoreRepresentations.expression(args[2]))), false, metrics))
            } else if (fn[0] == "prim" && fn[1] == "clearCCS#") {
                CoreProfileAction.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                // There is no profiling CCS on this target. Invoke the action,
                // preserving its lazy result and ordinary continuation machinery.
                TupleApplication(language as thc.Language, TupleShape(tupleProof, language),
                    argument(args[0], scope, true), arrayOf(InspectionState(compile(args[1], scope, false))),
                    tail, metrics).proven(tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && ClosureInspectOp.named(fn[1] as String) != null) {
                val operation = ClosureInspectOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                ClosureInspectExpression(operation,
                    args.mapIndexed { index, value -> argument(value, scope, index == 0) }.toTypedArray(), tupleProof)
            } else if (fn[0] == "prim" && fn[1] == "getCurrentCCS#") {
                CoreCurrentCCS.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                GetCurrentCCS(argument(args[0], scope, true), argument(args[1], scope, false), tupleProof)
            } else if (fn[0] == "prim" && STMOp.named(fn[1] as String) != null) {
                val operation = STMOp.named(fn[1] as String)!!
                if (containsDelimited && operation != STMOp.NEW && operation != STMOp.READ_IO)
                    throw UnsupportedCore("STM transaction frames do not support explicit delimited capture")
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                operation.validate(operands.map { it.representation }, flags, tupleProof)
                val nested = if (operation == STMOp.ATOMICALLY) GlobalRead(globals[STMOp.NESTED]
                    ?: throw UnsupportedCore("atomically# requires original nestedAtomically payload")) else null
                STMExpression(operation, tupleProof, operands.toTypedArray(),
                    if (operation.callback) TupleShape(tupleProof, language as thc.Language) else null, metrics, nested, enableAsync)
            } else if (fn[0] == "prim" && MVarOp.named(fn[1] as String) != null) {
                val operation = MVarOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                operation.validateBindings(args.map(CoreRepresentations::expression), args.map {
                    if (it[0] == "var") scope.locals[it[1]]?.proof ?: globalProofs[it[1]] else null
                })
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                operation.validate(operands.map { it.representation }, flags, tupleProof)
                mVarExpression(operation, tupleProof, operands.toTypedArray(), enableAsync)
            } else if (fn[0] == "prim" && CompactImageOp.named(fn[1] as String) != null) {
                val operation = CompactImageOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                CompactImageExpression(operation, args.mapIndexed { index, value ->
                    argument(value, scope, flags[index] as Boolean)
                }.toTypedArray()).proven(tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && CompactOp.named(fn[1] as String) != null) {
                val operation = CompactOp.named(fn[1] as String)!!
                if (capturesContinuations && operation.adds)
                    throw UnsupportedCore("Compact graph traversal does not yet support resumable asynchronous forcing")
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val failures = if (operation.adds) CompactOp.failures.map { globals[it]
                    ?: throw UnsupportedCore("Compact addition requires original exception payload: $it") }.toTypedArray()
                    else emptyArray()
                CompactExpression(operation, args.mapIndexed { index, value ->
                    argument(value, scope, flags[index] as Boolean)
                }.toTypedArray(), metrics, failures).proven(tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && MutVarOp.named(fn[1] as String) != null) {
                val operation = MutVarOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                mutVarExpression(operation, tupleProof,
                    args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }.toTypedArray(),
                    language, metrics, enableAsync)
            } else if (fn[0] == "prim" && WeakOp.named(fn[1] as String) != null) {
                val operation = WeakOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                operation.validateBindings(args.map(CoreRepresentations::expression), args.map {
                    if (it[0] == "var") scope.locals[it[1]]?.proof ?: globalProofs[it[1]] else null
                })
                if (operation == WeakOp.MAKE)
                    operation.validateAction(CoreRepresentations.knownFunctionSignature(args[2], bindings))
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                operation.validate(operands.map { it.representation }, flags, tupleProof)
                WeakExpression(operation, operands.toTypedArray()).proven(tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && StableNameOp.named(fn[1] as String) != null) {
                val operation = StableNameOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                when (operation) {
                    StableNameOp.MAKE -> MakeStableName(operands[0], operands[1])
                    StableNameOp.HASH -> HashStableName(operands[0])
                }.proven(tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && StablePointerOp.named(fn[1] as String) != null) {
                val operation = StablePointerOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                (when (operation) {
                    StablePointerOp.MAKE -> MakeStablePointer(operands[0], operands[1])
                    StablePointerOp.DEREFERENCE -> DereferenceStablePointer(operands[0], operands[1])
                    StablePointerOp.EQUAL -> EqualStablePointers(operands[0], operands[1])
                }).proven(tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && ArrayOp.named(fn[1] as String) != null) {
                val operation = ArrayOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                arrayExpression(operation, tupleProof,
                    args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }.toTypedArray())
            } else if (fn[0] == "prim" && SmallArrayOp.named(fn[1] as String) != null) {
                val operation = SmallArrayOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                smallArrayExpression(operation, tupleProof,
                    args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }.toTypedArray())
            } else if (fn[0] == "prim" && VectorMemoryOp.named(fn[1] as String) != null) {
                val operation = VectorMemoryOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                if (operation.isAddress) VectorAddressExpression(operation, args.map { compile(it, scope, false) }.toTypedArray())
                else VectorByteArrayExpression(operation, args.map { compile(it, scope, false) }.toTypedArray())
            } else if (fn[0] == "prim" && fn[1] in prefetchArities) {
                if (args.size != prefetchArities[fn[1]]) fault("Wrong prefetch arity")
                PrefetchExpression(argument(args[0], scope, flags[0] as Boolean),
                    if (args.size == 3) compile(args[1], scope, false) else null,
                    compile(args.last(), scope, false), tupleProof)
            } else if (fn[0] == "prim" && TraceOp.named(fn[1] as String) != null) {
                val operation = TraceOp.named(fn[1] as String)!!
                if (args.size != operation.arity) fault("Wrong trace arity")
                TraceExpression(operation, compile(args[0], scope, false),
                    if (operation == TraceOp.BINARY) compile(args[1], scope, false) else null,
                    compile(args.last(), scope, false), tupleProof)
            } else if (fn[0] == "prim" && fn[1] == "touch#") {
                CoreTouch.validateRaw(args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags,
                    CoreRepresentations.metadata(expr)?.get("rep"))
                val kept = argument(args[0], scope, flags[0] as Boolean)
                val state = compile(args[1], scope, false)
                CoreTouch.validate(listOf(kept.representation, state.representation), flags, tupleProof)
                TouchExpression(kept, state, tupleProof)
            } else if (fn[0] == "prim" && fn[1] == "keepAlive#") {
                CoreKeepAlive.validate(args.map(CoreRepresentations::expression), flags, tupleProof,
                    args.getOrNull(2)?.let { CoreRepresentations.knownFunctionSignature(it, bindings) })
                val kept = argument(args[0], scope, flags[0] as Boolean)
                val state = compile(args[1], scope, false)
                val function = compile(args[2], scope, false)
                val stateArgument = arrayOf<Expr>(Literal(Unit))
                val action = if (tupleProof.isAggregate) TupleApplication(language as thc.Language,
                    TupleShape(tupleProof, language), function, stateArgument, false, metrics)
                else Application(function, stateArgument, false, metrics)
                KeepAliveExpression(kept, state, action, tupleProof)
            } else if (fn[0] == "prim" && AtomicAddressOp.named(fn[1] as String) != null) {
                val operation = AtomicAddressOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                AtomicAddressExpression(operation, tupleProof, args.map { compile(it, scope, false) }.toTypedArray())
            } else if (fn[0] == "prim" && FloatingAddressOp.named(fn[1] as String) != null) {
                val operation = FloatingAddressOp.named(fn[1] as String)!!
                val byteOffset = (fn[1] as String).contains("Word8") && (fn[1] as String).contains("As")
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                FloatingAddressExpression(operation, tupleProof, args.map { compile(it, scope, false) }.toTypedArray(), byteOffset)
            } else if (fn[0] == "prim" && AddressArrayCopyOp.named(fn[1] as String) != null) {
                val operation = AddressArrayCopyOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.map { compile(it, scope, false) }
                if (operation.toArray) AddressToByteArrayExpression(tupleProof,
                    operands[0], operands[1], operands[2], operands[3], operands[4])
                else ByteArrayToAddressExpression(tupleProof,
                    operands[0], operands[1], operands[2], operands[3], operands[4])
            } else if (fn[0] == "prim" && PinnedMemoryOp.named(fn[1] as String) != null) {
                val operation = PinnedMemoryOp.named(fn[1] as String)!!
                val byteOffset = (fn[1] as String).contains("Word8") && (fn[1] as String).contains("As")
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                if (operation == PinnedMemoryOp.CONTENTS || operation == PinnedMemoryOp.MUTABLE_CONTENTS)
                    PinnedByteArrayContents(tupleProof, compile(args[0], scope, false))
                else if (operation == PinnedMemoryOp.INDEX_ADDR_OFF || operation == PinnedMemoryOp.INDEX_ADDR_ARRAY)
                    PinnedPointerIndexExpression(operation, tupleProof, byteOffset,
                        compile(args[0], scope, false), compile(args[1], scope, false))
                else if (!operation.tuple && operation.addressRead != null)
                    PinnedScalarIndexExpression(operation.addressRead, tupleProof, byteOffset,
                        compile(args[0], scope, false), compile(args[1], scope, false))
                else if (operation == PinnedMemoryOp.WRITE_ADDR_ARRAY)
                    PinnedPointerArrayWrite(tupleProof, byteOffset, compile(args[0], scope, false),
                        compile(args[1], scope, false), compile(args[2], scope, false),
                        compile(args[3], scope, false))
                else if (operation == PinnedMemoryOp.READ_ADDR_ARRAY)
                    PinnedPointerArrayRead(tupleProof, byteOffset, compile(args[0], scope, false),
                        compile(args[1], scope, false), compile(args[2], scope, false))
                else PinnedMemoryExpression(operation, tupleProof, args.map { compile(it, scope, false) }.toTypedArray(), byteOffset)
            } else if (fn[0] == "prim" && AtomicIntArrayOp.named(fn[1] as String) != null) {
                val operation = AtomicIntArrayOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                AtomicIntArrayExpression(operation, args.map { compile(it, scope, false) }.toTypedArray()).proven(tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && ByteArrayOp.named(fn[1] as String) != null) {
                val operation = ByteArrayOp.named(fn[1] as String)!!
                val byteOffset = (fn[1] as String).contains("Word8") && (fn[1] as String).contains("As")
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                byteArrayExpression(operation, tupleProof, args.map { compile(it, scope, false) }.toTypedArray(), byteOffset)
            } else if (floatDecode != null) {
                floatDecode.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                FloatDecodeExpression(floatDecode, tupleProof, argument(args.single(), scope, false))
            } else if (tupleOperation != null) {
                tupleOperation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                if (tupleOperation == TupleArithmeticOp.QUOT_REM_WORD_2)
                    DoubleWordDivisionExpression(tupleProof, argument(args[0], scope, false),
                        argument(args[1], scope, false), argument(args[2], scope, false))
                else TupleArithmeticExpression(tupleOperation, tupleProof,
                    argument(args[0], scope, false), argument(args[1], scope, false))
            } else if (tupleProof.isSum && fn[0] == "con" && constructors[fn[1]]?.get("kind") == "unboxed-sum") {
                val tag = SumShape.constructor(tupleProof, constructors[fn[1]], fn[2])
                if (args.size != 1) throw RuntimeFault("Sum constructor must be saturated")
                val selected = tupleProof.alternatives!![tag - 1]
                val lifted = flags.single() as? Boolean ?: throw UnsupportedCore("Unknown sum payload levity")
                val payload = if (selected.isTypedTransport) compile(args.single(), scope, false)
                    else argument(args.single(), scope, lifted)
                SumShape.payload(selected, payload.representation, lifted)
                val intSlots = if (selected.isTypedTransport) TupleShape.flatten(selected).mapIndexed { index, field ->
                    if (field.isInt) scope.layout.bind("<narrow sum payload $index>") else -1
                }.toIntArray() else IntArray(0)
                SumConstruct(TupleShape(tupleProof, language as thc.Language), tag, payload, intSlots)
            } else if (tupleProof.isTuple && fn[0] == "con" && constructors[fn[1]]?.get("kind") == "unboxed-tuple") {
                val shape = TupleShape(tupleProof, language as thc.Language)
                if (shape.components.size != args.size || (fn[2] as Number).toInt() != args.size ||
                    (constructors[fn[1]]?.get("arity") as? Number)?.toInt() != args.size)
                    throw RuntimeFault("Tuple constructor arity mismatch")
                TupleConstruct(shape, args.mapIndexed { index, arg ->
                    TupleShape.requireCompatible(shape.components[index], CoreRepresentations.expression(arg), component = true)
                    if (shape.components[index].isTypedTransport && flags[index] != false)
                        throw RuntimeFault("Typed tuple field cannot be lifted")
                    if (shape.components[index].isTypedTransport) compile(arg, scope, false)
                    else argument(arg, scope, flags[index] as? Boolean ?: throw UnsupportedCore("Unknown tuple field levity"))
                }.toTypedArray())
            } else if (fn[0] == "var" && fn[1] in scope.joins) {
                joinJump(scope.joins.getValue(fn[1] as String), args, flags, scope, callStrict)
            } else {
            val constructorStrictFields = if (fn[0] == "con" && (fn[2] as Number).toInt() == args.size)
                strictConstructorFields(fn[1] as String, args.size) else null
            val constructorLayout = if (constructorStrictFields != null) dataLayout(fn[1] as String) else null
            val entryStrict = when (fn[0]) {
                "lam" -> CoreEntries.lambda(fn)
                "var" -> (fn[1] as String).let { id -> if (id in scope.locals) scope.locals.getValue(id).entry else globalEntries[id] }
                else -> null
            }?.takeIf { args.size >= it.size }
            val nodes = args.mapIndexed { i, arg ->
                val lifted = flags[i] as? Boolean ?: throw UnsupportedCore("Unknown argument levity")
                // A saturated constructor's strict operand is already a CBV
                // context. Compile it directly, without an allocate/force thunk.
                // Partial constructors deliberately take the ordinary lazy path.
                val field = constructorLayout?.logicalProof(i)
                if (field?.isAggregate == true) {
                    if (lifted) throw RuntimeFault("Aggregate constructor operand must be unlifted")
                    compile(arg, scope, false).also { TupleShape.requireCompatible(field, it.representation, component = true) }
                } else argument(arg, scope, lifted && !callStrict[i] && constructorStrictFields?.get(i) != true && entryStrict?.getOrNull(i) != true,
                    allowEmpty = fn[0] != "prim" && fn[0] != "con", declaredLifted = lifted)
            }.toTypedArray()
            when {
                fn[0] == "prim" -> {
                    // The scalar lowering has no aggregate destination or vector carrier.
                    nodes.forEach { CoreRepresentations.requireScalar(it.representation, "argument") }
                    primitive(fn[1] as String, nodes, CoreExceptionPayload.validate(expr))
                }
                constructorStrictFields != null -> {
                    val layout = dataLayout(fn[1] as String)
                    Construct(layout, nodes, constructorVectorSlots(layout, scope.layout))
                }
                else -> {
                    val function = compile(fn, scope, false)
                    val input = ArgumentLayout.fromProofs(nodes.map { it.representation })
                    if (input?.requiresTyped == true)
                        AstTypedApplication(function, nodes, scope.layout, tail, metrics,
                            if (tupleProof.isTypedTransport) TupleShape(tupleProof, language as thc.Language) else null,
                            tail && !capturesContinuations && scope.self?.let { TypedInputsKt.supportsTypedSelf(it.inputLayout, it.entryStrict, input) } == true)
                    else if (tupleProof.isTypedTransport) {
                        val shape = TupleShape(tupleProof, language as thc.Language)
                        val vectorSlots = if (tupleProof.isVector) IntArray(shape.width) { scope.layout.bind("<vector call result $it>") } else null
                        TupleApplication(language as thc.Language, shape, function, nodes, tail, metrics, vectorSlots)
                    }
                    else {
                    val self = scope.self
                    if (tail && !capturesContinuations && self != null && self.inputLayout == null && nodes.none { it.representation.isEmptyTuple } && self.arity > 0 && nodes.size <= self.arity) {
                        val temporaries = IntArray(self.arity) { scope.layout.bind("<self argument $it>") }
                        AstTailApplication(function, nodes, self, temporaries, metrics)
                    } else Application(function, nodes, tail, metrics)
                    }
                }
            }
            }
        }
        "let" -> {
            val recursive = expr[1] as Boolean; val group = expr[2] as List<Map<String, Any?>>
            val definitions = CoreJoins.definitions(group)
            if (definitions != null) compileJoins(expr, scope, tail, definitions) else {
                group.forEach {
                    val proof = CoreRepresentations.binder(it)
                    if (proof.isVector) {
                        if (recursive || representation(it)) throw UnsupportedCore("Vector let binding must be nonrecursive and unlifted")
                        CoreRepresentations.requireInput(proof)
                    } else CoreRepresentations.requireScalar(proof, "let binding")
                }
                val local = scope.child()
                val vectorSlots = arrayOfNulls<IntArray>(group.size)
                val slots = group.mapIndexed { index, binding ->
                    val proof = CoreRepresentations.binder(binding)
                    if (proof.isVector) {
                        val fields = TupleShape.flatten(proof)
                        val lanes = IntArray(fields.size) { local.layout.bind("${binding["id"]} vector let lane $it") }
                        vectorSlots[index] = lanes
                        local.bindTuple(binding["id"] as String, proof, lanes).slot
                    } else local.bind(binding["id"] as String, !representation(binding),
                        proof.copy(evaluated = false), cell = recursive, entry = CoreEntries.binding(binding),
                        arityCertificate = CoreApplicationCertificates.binding(binding)).slot
                }.toIntArray()
                val rhs = group.map { binding -> withSource(sources.binding(binding, currentSource)) {
                    val it = binding
                    val rhsExpr = it["expr"] as List<Any?>; val lifted = representation(it)
                    CoreRepresentations.requireNoSum(CoreRepresentations.expression(rhsExpr), "let binding")
                    if (recursive && !lifted) throw UnsupportedCore("Recursive unlifted binding unsupported")
                    val node = if (recursive && lifted && rhsExpr[0] !in listOf("lam", "lit", "con", "void")) delay(rhsExpr, local, it["name"].toString())
                    else argument(rhsExpr, if (recursive) local else scope, lifted, it["name"].toString())
                    node.proven(node.representation.refine(CoreRepresentations.binder(it).copy(evaluated = false)))
                } }.toTypedArray()
                // RHS closures retain their original cell-bearing Local records.
                // Only the body sees the values published after the entire group.
                group.forEachIndexed { index, binding -> local.publish(binding["id"] as String, rhs[index].representation) }
                Let(slots, rhs, group.map { !representation(it) }.toBooleanArray(),
                    compile(expr[3] as List<Any?>, local, tail), recursive, vectorSlots)
            }
        }
        "case" -> {
            CoreVectorMemory.readCase(expr, constructors)?.let { compileVectorReadCase(it, scope, tail) } ?: run {
            val scrutineeExpr = expr[1] as List<Any?>
            val scrutinee = compile(scrutineeExpr, scope, false)
            val local = scope.child()
            if (scrutineeExpr[0] == "var") {
                val id = scrutineeExpr[1] as String
                local.locals[id]?.let { local.refine(id, it.proof.copy(evaluated = true)) }
            }
            val binderProof = scrutinee.representation.refine(CoreRepresentations.caseBinder(expr).copy(evaluated = false)).copy(evaluated = true)
            if (binderProof.isSum) compileSumCase(expr, scrutinee, binderProof, local, tail)
            else if (binderProof.isTuple) compileTupleCase(expr, scrutinee, binderProof, local, tail)
            else if (binderProof.isVector) compileVectorCase(expr, scrutinee, binderProof, local, tail)
            else {
            val binder = local.bind(expr[2] as String, !binderProof.present || binderProof.isLong, binderProof,
                kind = outlinedSlotKind(binderProof)).slot
            val alternatives = (expr[3] as List<List<Any?>>).map { alt ->
                val child = local.child(); val kind = alt[0] as String
                val value = when (kind) {
                    "lit" -> (alt[1] as List<String>).let {
                        if (it[0] in setOf("bignat", "rubbish")) throw UnsupportedCore("BigNat/rubbish literal alternatives are invalid GHC Core")
                        if (it[0] in setOf("float", "double")) throw UnsupportedCore("Floating literal alternatives are invalid GHC Core")
                        literal(it[0], it[1])
                    }
                    "data" -> dataLayout(alt[1] as String)
                    else -> alt[1]
                }
                val ids = alt[2] as List<String>
                val layout = value as? DataLayout
                if (layout != null && layout.logicalArity != ids.size) throw RuntimeFault("Constructor field/binder mismatch")
                val metadata = CoreRepresentations.alternativeBinders(alt)
                val strict = if (kind == "data") strictConstructorFields(alt[1] as String, ids.size) else null
                val vectorFields = arrayOfNulls<IntArray>(layout?.arity ?: ids.size)
                val slots = ids.flatMapIndexed { index, id ->
                    val raw = metadata.getOrNull(index)?.let(CoreRepresentations::binder) ?: CoreRepresentation.UNKNOWN
                    val physical = layout?.fieldOffset(index) ?: index
                    val aggregate = layout?.logicalProof(index)?.takeIf { it.isAggregate }
                    val vector = if (aggregate == null) layout?.vectorProof(physical) else null
                    if (aggregate != null) {
                        if (!raw.present || metadata.getOrNull(index)?.get("lifted") != false)
                            throw RuntimeFault("Aggregate constructor binder requires an unlifted shape")
                        TupleShape.requireCompatible(aggregate, raw, component = true)
                        val proof = aggregate.refine(raw)
                        val lanes = IntArray(layout.logicalWidth(index)) { child.layout.bind("$id constructor aggregate $it") }
                        child.bindTuple(id, proof, lanes)
                        lanes.forEachIndexed { leaf, slot ->
                            if (layout.isVector(physical + leaf)) vectorFields[physical + leaf] = intArrayOf(slot)
                        }
                        lanes.toList()
                    } else {
                    val proof = if (vector != null) raw.refine(vector)
                        else if (layout?.isLong(physical) == true) raw.refine(CoreRepresentation(CoreKind.LONG, true))
                        else raw.copy(evaluated = strict?.get(index) == true ||
                            ((constructors[alt[1] as? String]?.get("fieldLifted") as? List<*>)?.getOrNull(index) == false))
                    if (vector != null) {
                        if (metadata.getOrNull(index)?.get("lifted") != false)
                            throw UnsupportedCore("Vector constructor binder must be unlifted")
                        val lanes = IntArray(1) { child.layout.bind("$id constructor vector") }
                        vectorFields[physical] = lanes
                        listOf(child.bindTuple(id, proof, lanes).slot)
                    } else listOf(child.bind(id, layout?.isLong(physical) == true, proof).slot)
                    }
                }.toIntArray()
                val tag = when (kind) {
                    "default" -> DEFAULT_ALTERNATIVE
                    "data" -> DATA_ALTERNATIVE
                    "lit" -> LITERAL_ALTERNATIVE
                    else -> throw RuntimeFault("Invalid Core alternative kind $kind")
                }
                Alternative(tag, value, slots, caseArm(alt[3] as List<Any?>, child, tail), vectorFields)
            }.toTypedArray()
            if (!CoreRepresentations.expression(expr).isAggregate && alternatives.any { it.body.representation.isAggregate } &&
                alternatives.any { !it.body.representation.isAggregate })
                throw RuntimeFault("Missing exact aggregate case result proof")
            CoreRepresentations.validateDeclaredCaseResult(CoreRepresentations.expression(expr), alternatives.map { it.body.representation })
            CoreRepresentations.validateAggregateCaseResult(CoreRepresentations.expression(expr), alternatives.map { it.body.representation })
            CoreRepresentations.validateFloatingCaseResult(CoreRepresentations.expression(expr),
                alternatives.map { it.body.representation })
            when (caseCategory(binderProof, alternatives.map { it.kind },
                alternatives.all { it.kind != LITERAL_ALTERNATIVE || it.value is Long })) {
                CaseCategory.DATA -> DataCase(scrutinee, binder, alternatives, metrics, binderProof, delimited)
                CaseCategory.LONG -> LongCase(scrutinee, binder, alternatives, metrics, binderProof, delimited)
                CaseCategory.DEFAULT_ONLY -> DefaultCase(scrutinee, binder, alternatives, metrics, binderProof, delimited)
                CaseCategory.GENERIC -> Case(scrutinee, binder, alternatives, metrics, delimited = delimited)
            }
            }
            }
        }
        "con" -> {
            val id = expr[1] as String; val arity = (expr[2] as Number).toInt()
            if (constructors[id]?.get("kind") == "unboxed-tuple" && arity == 0 && CoreRepresentations.expression(expr).isTuple) {
                val proof = CoreRepresentations.expression(expr)
                if (proof.components?.size != 0) throw RuntimeFault("Empty tuple constructor has nonempty logical components")
                TupleConstruct(TupleShape(proof, language as thc.Language), emptyArray())
            } else if (arity == 0) construct(id, emptyArray(), scope.layout) else {
                val layout = FrameLayout()
                val constructor = dataLayout(id)
                if (constructor.hasAggregateFields)
                    throw UnsupportedCore("Unsaturated aggregate-field constructor requires aggregate inputs")
                // CoreFields has already validated every fieldType against its
                // registered primitive representation. A typed constructor PAP
                // must retain the scalar proofs beside its vector lanes too.
                val fieldTypes = constructors.getValue(id)["fieldTypes"] as? List<*>
                val proofs = List(arity) { index ->
                    fieldTypes?.get(index)?.let(CoreRepresentations::parse) ?: CoreRepresentation.UNKNOWN
                }
                val inputLayout = ArgumentLayout.fromProofs(proofs)
                val argumentSlots = arrayListOf<Int>()
                val argumentIndices = arrayListOf<Int>()
                val argumentProofs = arrayListOf<CoreRepresentation>()
                val fields = Array<Expr>(arity) { index ->
                    val vector = constructor.vectorProof(index)
                    if (vector == null) {
                        val slot = layout.bind("field$index")
                        argumentSlots += slot
                        argumentIndices += ArgumentLayout.offset(inputLayout, index)
                        argumentProofs += proofs[index]
                        LocalRead(slot, false).proven(proofs[index])
                    } else {
                        val lanes = IntArray(1) { layout.bind("field$index vector") }
                        TupleShape.flatten(vector).forEachIndexed { lane, proof ->
                            argumentSlots += lanes[lane]
                            argumentIndices += ArgumentLayout.offset(inputLayout, index) + lane
                            argumentProofs += proof
                        }
                        VectorLocalRead(TupleShape(vector, language as thc.Language), lanes)
                    }
                }
                val body = construct(id, fields, layout)
                val root = FunctionRoot(language, layout.build(), "constructor $id", null, intArrayOf(),
                    argumentSlots.toIntArray(), argumentIndices.toIntArray(), body, metrics,
                    argumentProofs.toTypedArray(), coreSourceLocation = rootSource(body),
                    entryStrict = strictConstructorFields(id, arity), inputLayout = inputLayout,
                    stackCapture = outlineCaseArms)
                constructedRootCount++
                root.configureForeignExceptionBridge(foreignExceptionBridge)
                if (language is thc.Language) root.configureTypedInput(TypedInputLayout.create(language, inputLayout, false))
                val target = root.callTarget
                MakeClosure(target, arity, null, intArrayOf())
            }
        }
        "prim" -> throw UnsupportedCore("Unsaturated primitive ${expr[1]}")
        else -> throw UnsupportedCore("Unsupported Core node ${expr[0]}")
    }
    private fun compileSumCase(expr: List<Any?>, scrutinee: Expr, proof: CoreRepresentation, scope: Scope, tail: Boolean): Expr {
        val shape = TupleShape(proof, language as thc.Language)
        val slots = IntArray(shape.width) { scope.layout.bind("<sum case $it>") }
        scope.bindTuple(expr[2] as String, proof, slots)
        val tags = mutableSetOf<Int>()
        var fallback = -1
        val arms = (expr[3] as List<List<Any?>>).mapIndexed { index, alt ->
            val child = scope.child()
            val conversions = ArrayList<Pair<Int, Expr>>()
            val ids = alt[2] as List<String>
            if (alt[0] == "default") {
                if (fallback >= 0 || ids.isNotEmpty() || CoreRepresentations.alternativeBinders(alt).isNotEmpty())
                    throw RuntimeFault("Invalid sum DEFAULT alternative")
                fallback = index
            } else {
                if (alt[0] != "data" || ids.size != 1) throw RuntimeFault("Invalid sum alternative")
                val tag = SumShape.constructor(proof, constructors[alt[1]], ids.size)
                if (!tags.add(tag)) throw RuntimeFault("Duplicate sum alternative tag")
                val component = proof.alternatives!![tag - 1]
                val metadata = CoreRepresentations.alternativeBinders(alt)
                if (metadata.size != 1 || metadata[0]["id"] != ids[0]) throw RuntimeFault("Missing sum payload binder proof")
                val actual = CoreRepresentations.binder(metadata.single())
                val lifted = metadata.single()["lifted"] as? Boolean ?: throw RuntimeFault("Unknown sum payload binder levity")
                SumShape.payload(component, actual, lifted)
                val field = component.refine(actual).copy(evaluated = component.evaluated)
                val leaves = TupleShape.flatten(field)
                val projection = SumShape.projection(proof, tag - 1).mapIndexed { index, physical ->
                    val leaf = leaves[index]
                    if (!leaf.isInt) slots[physical] else child.layout.bind("<narrow sum arm $index>").also { destination ->
                        conversions += destination to SumNarrowRead(slots[physical], leaf.narrowInteger!!, leaf)
                    }
                }.toIntArray()
                if (component.isTypedTransport) child.bindTuple(ids[0], field, projection)
                else if (component.kind == CoreKind.VOID) child.bindVoid(ids[0], field)
                else child.bindSlot(ids[0], Local(projection[0], component.isLong, field, false))
            }
            val body = caseArm(alt[3] as List<Any?>, child, tail)
            if (conversions.isEmpty()) body else Let(conversions.map { it.first }.toIntArray(),
                conversions.map { it.second }.toTypedArray(), BooleanArray(conversions.size), body, false)
        }.toTypedArray()
        if (arms.isEmpty()) throw RuntimeFault("Empty sum case")
        val alternatives = expr[3] as List<List<Any?>>
        fun selected(tag: Int) = alternatives.indexOfFirst { it[0] == "data" && (constructors[it[1]]?.get("tag") as? Number)?.toInt() == tag }
            .let { if (it >= 0) it else fallback }
        val result = arms.first().representation.refine(CoreRepresentations.expression(expr))
        arms.forEach { result.refine(it.representation) }
        CoreRepresentations.validateFloatingCaseResult(result, arms.map { it.representation })
        return SumCase(scrutinee, slots, arms, IntArray(proof.alternatives!!.size) { selected(it + 1) },
            result.copy(evaluated = arms.all { it.representation.evaluated }))
    }
    private fun compileVectorReadCase(read: VectorReadCase, scope: Scope, tail: Boolean): Expr {
        val local = scope.child()
        local.bindVoid(read.stateBinder, CoreVectorMemory.stateProof)
        val vectorProof = read.operation.vectorProof
        val lanes = IntArray(TupleShape.flatten(vectorProof).size) { local.layout.bind("<vector read lane $it>") }
        local.bindTuple(read.vectorBinder, vectorProof, lanes)
        val operands = read.arguments.map { compile(it, scope, false) }.toTypedArray()
        val value = (if (read.operation.isAddress) VectorAddressExpression(read.operation, operands)
            else VectorByteArrayExpression(read.operation, operands)).located(currentSource)
        val body = compile(read.body, local, tail)
        // The whole tuple binder is deliberately absent from local scope.
        // Store the vector only after all operand/State checks and the load finish.
        return Let(intArrayOf(-1), arrayOf(value), booleanArrayOf(false), body, false, arrayOf(lanes))
    }
    private fun compileVectorCase(expr: List<Any?>, scrutinee: Expr, proof: CoreRepresentation,
                                  scope: Scope, tail: Boolean): Expr {
        CoreRepresentations.requireInput(proof)
        val alternatives = expr[3] as List<List<Any?>>
        val only = alternatives.singleOrNull() ?: throw RuntimeFault("Vector case requires one default alternative")
        if (only[0] != "default" || (only[2] as List<*>).isNotEmpty())
            throw RuntimeFault("Vector case requires one default alternative")
        val lanes = IntArray(TupleShape.flatten(proof).size) { scope.layout.bind("<vector case lane $it>") }
        scope.bindTuple(expr[2] as String, proof, lanes)
        return Let(intArrayOf(-1), arrayOf(scrutinee), booleanArrayOf(false),
            compile(only[3] as List<Any?>, scope, tail), false, arrayOf(lanes))
    }
    private fun compileTupleCase(expr: List<Any?>, scrutinee: Expr, proof: CoreRepresentation, local: Scope, tail: Boolean): Expr {
        val shape = TupleShape(proof, language as thc.Language)
        val slots = IntArray(shape.width) { local.layout.bind("<tuple case $it>") }
        local.bindTuple(expr[2] as String, proof, slots)
        val alternatives = expr[3] as List<List<Any?>>
        if (alternatives.isEmpty()) return TupleCase(scrutinee, slots,
            EmptyCaseResult(CoreRepresentations.expression(expr)))
        if (alternatives.size != 1) throw RuntimeFault("Tuple case requires at most one alternative")
        val alt = alternatives.single()
        val ids = alt[2] as List<String>
        if (alt[0] == "data") {
            if (constructors[alt[1]]?.get("kind") != "unboxed-tuple" || ids.size != shape.components.size ||
                (constructors[alt[1]]?.get("arity") as? Number)?.toInt() != ids.size)
                throw RuntimeFault("Tuple alternative shape mismatch")
            val metadata = CoreRepresentations.alternativeBinders(alt)
            ids.forEachIndexed { index, id ->
                val component = shape.components[index]
                val raw = metadata.getOrNull(index)?.let(CoreRepresentations::binder) ?: component
                TupleShape.requireCompatible(component, raw, component = true)
                val field = component.refine(raw)
                val width = TupleShape.flatten(component).size
                val offset = shape.offsets[index]
                if (component.isTypedTransport) local.bindTuple(id, component.copy(evaluated = true), slots.copyOfRange(offset, offset + width))
                else if (component.kind == CoreKind.VOID) local.bindVoid(id, field)
                else local.bindSlot(id, Local(slots[offset], component.isLong, field.copy(evaluated = component.isLong || component.evaluated), false))
            }
        } else if (alt[0] != "default" || ids.isNotEmpty()) throw RuntimeFault("Invalid tuple alternative")
        return TupleCase(scrutinee, slots, caseArm(alt[3] as List<Any?>, local, tail))
    }
    private fun joinJump(target: LocalJoinTarget, args: List<List<Any?>>, flags: List<*>, scope: Scope,
                         callStrict: BooleanArray = BooleanArray(args.size)): Expr {
        if (args.size != target.slots.size) throw RuntimeFault("Local join arity mismatch")
        val nodes = args.mapIndexed { index, arg ->
            val lifted = flags.getOrNull(index) as? Boolean ?: throw RuntimeFault("Missing join argument levity")
            argument(arg, scope, lifted && !callStrict[index] && !target.entryStrict[index],
                allowEmpty = target.proofs[index].isTypedTransport, declaredLifted = lifted).also {
                CoreRepresentations.requireJoinArgument(target.proofs[index], it.representation)
            }
        }.toTypedArray()
        val typedTemps = arrayOfNulls<IntArray>(nodes.size)
        val temps = IntArray(nodes.size) { index ->
            if (target.proofs[index].isTypedTransport) {
                typedTemps[index] = IntArray(ArgumentLayout.leaves(target.proofs[index]).size) {
                    scope.layout.bind("<join typed argument $index field $it>")
                }
                -1
            } else scope.layout.bind("<join argument $index>")
        }
        return LocalJoinCall(language as thc.Language, target, nodes, temps, metrics, typedTemps)
    }
    private fun compileJoins(expr: List<Any?>, outer: Scope, tail: Boolean,
                             definitions: List<CoreJoinDefinition>): Expr {
        val recursive = expr[1] == true
        val shadowed = if (recursive) definitions.map { it.id }.toSet() else emptySet()
        definitions.forEach { definition ->
            definition.parameters.forEach {
                val proof = CoreRepresentations.binder(it)
                CoreRepresentations.requireInput(proof)
                if (proof.isTypedTransport && representation(it)) throw RuntimeFault("Typed join formal must be unlifted")
            }
            val formals = definition.parameters.map { it["id"] as String }.toSet()
            (coreFreeVariables(definition.body) - formals - shadowed).forEach { id ->
                outer.locals[id]?.let { captured ->
                    if (captured.proof.isTypedTransport) {
                        // A join stays in this activation: its lexical aggregate is already
                        // held in typed frame slots, not in a closure environment.
                        CoreRepresentations.requireInput(captured.proof)
                        val slots = captured.tupleSlots ?: throw RuntimeFault("Missing typed join capture slots")
                        if (slots.size != ArgumentLayout.leaves(captured.proof).size || slots.any { it < 0 })
                            throw RuntimeFault("Typed join capture disagrees with its physical slots")
                    } else CoreRepresentations.requireScalar(captured.proof, "join capture")
                }
            }
        }
        CoreJoins.validate(expr[2] as List<Map<String, Any?>>, expr[3] as List<Any?>, recursive)
        val identity = Any()
        val local = outer.child()
        val entryContracts = definitions.map(CoreEntries::join)
        // Snapshot the outer join scope before publishing this group. Besides
        // lexical correctness, this lets nonrecursive regions dispatch once.
        val bodyScopes = definitions.map { definition ->
            val scope = local.child()
            val entryStrict = CoreEntries.join(definition)
            definition.parameters.forEachIndexed { index, parameter ->
                val lifted = representation(parameter)
                val proof = CoreRepresentations.binder(parameter).let { if (lifted) it.copy(evaluated = entryStrict[index]) else it }
                if (proof.isTypedTransport) {
                    val lanes = IntArray(ArgumentLayout.leaves(proof).size) {
                        scope.layout.bind("${parameter["id"]} join typed field $it")
                    }
                    scope.bindTuple(parameter["id"] as String, proof.copy(evaluated = true), lanes)
                } else scope.bind(parameter["id"] as String, !lifted && parameter["coercion"] != true, proof)
            }
            scope
        }
        val targets = definitions.mapIndexed { index, definition ->
            val parameters = definition.parameters.map { bodyScopes[index].locals.getValue(it["id"] as String) }
            LocalJoinTarget(identity, index + 1, parameters.map { it.slot }.toIntArray(),
                parameters.map { it.proof }.toTypedArray(), entryContracts[index], definition.result,
                parameters.map { parameter -> if (parameter.proof.isTypedTransport) parameter.tupleSlots else null }.toTypedArray())
        }
        definitions.forEachIndexed { index, definition -> local.bindJoin(definition.id, targets[index]) }
        if (recursive) bodyScopes.forEachIndexed { index, scope ->
            val parameters = definitions[index].parameters.map { it["id"] as String }.toSet()
            definitions.forEachIndexed { targetIndex, definition ->
                if (definition.id !in parameters) scope.bindJoin(definition.id, targets[targetIndex])
            }
        }
        val entry = compile(expr[3] as List<Any?>, local, tail)
        val bodies = definitions.mapIndexed { index, definition ->
            withSource(sources.binding(definition.binding, currentSource)) {
                compile(definition.body, bodyScopes[index], tail).also { node ->
                    node.representation = node.representation.refine(definition.result.copy(evaluated = false))
                }
            }
        }
        val result = CoreRepresentations.expression(expr).let { proof ->
            val inferred = entry.representation.refine(proof.copy(evaluated = false))
            bodies.forEach { TupleShape.requireCompatible(inferred, it.representation) }
            inferred.copy(evaluated = entry.representation.evaluated && bodies.all { it.representation.evaluated })
        }
        val tuple = if (result.isTypedTransport) TupleShape(result, language as thc.Language) else null
        val tupleSlots = IntArray(tuple?.width ?: 0) { local.layout.bind("<join tuple result $it>") }
        return LocalJoinRegion(identity, local.layout.bind("<join selector>"), local.layout.bind("<join result>"),
            (listOf(entry) + bodies).toTypedArray(), result, recursive, tuple, tupleSlots, delimited)
    }
    private fun dataLayout(id: String): DataLayout = dataLayouts.getOrPut(id) {
        val info = constructors[id] ?: throw RuntimeFault("Missing constructor metadata $id")
        val fields = CoreFields(info)
        DataLayout.fromFields(language ?: throw RuntimeFault("Constructor layout requires a guest language"),
            id, info["name"] as String, fields)
    }
    private fun primitive(name: String, args: Array<Expr>, someException: Boolean = false): Expr =
        NarrowScalarOp.named(name)?.let { NarrowScalarExpression(name, it, args) } ?: floatingPrimitive(name, args) ?: when (name) {
        "reallyUnsafePtrEquality#" -> {
            if (args.size != 2) throw RuntimeFault("Primitive arity mismatch: $name")
            PointerEquality(args[0], args[1])
        }
        "raise#" -> {
            if (args.size != 1) throw RuntimeFault("Primitive arity mismatch: $name")
            RaiseException(args[0], someException)
        }
        "addr2Int#", "int2Addr#" -> {
            if (args.size != 1) throw RuntimeFault("Primitive arity mismatch: $name")
            if (name == "addr2Int#") AddressToInt(args[0]) else IntToAddress(args[0])
        }
        "eqAddr#", "neAddr#" -> {
            if (args.size != 2) throw RuntimeFault("Primitive arity mismatch: $name")
            CompareManagedAddress(args[0], args[1], name == "neAddr#")
        }
        "ltAddr#", "leAddr#", "gtAddr#", "geAddr#" -> {
            if (args.size != 2) throw RuntimeFault("Primitive arity mismatch: $name")
            CompareOrderedManagedAddress(args[0], args[1], when (name) {
                "ltAddr#" -> ManagedAddressOrder.LT
                "leAddr#" -> ManagedAddressOrder.LE
                "gtAddr#" -> ManagedAddressOrder.GT
                else -> ManagedAddressOrder.GE
            })
        }
        "minusAddr#", "remAddr#" -> {
            if (args.size != 2) throw RuntimeFault("Primitive arity mismatch: $name")
            if (name == "minusAddr#") SubtractManagedAddress(args[0], args[1])
            else RemainderManagedAddress(args[0], args[1])
        }
        "plusAddr#", "indexCharOffAddr#", "indexWord8OffAddr#", "indexInt8OffAddr#" -> {
            if (args.size != 2) throw RuntimeFault("Primitive arity mismatch: $name")
            if (name == "plusAddr#") PlusManagedAddress(args[0], args[1])
            else if (name == "indexCharOffAddr#") IndexManagedScalarAddress(ManagedAddressRead.CHAR, args[0], args[1])
            else IndexManagedByte(name == "indexInt8OffAddr#", args[0], args[1])
        }
        "indexWord16OffAddr#", "indexInt16OffAddr#" -> {
            if (args.size != 2) throw RuntimeFault("Primitive arity mismatch: $name")
            IndexManagedScalarAddress(if (name == "indexInt16OffAddr#") ManagedAddressRead.INT16
                else ManagedAddressRead.WORD16, args[0], args[1])
        }
        else -> Primitive(name, args)
    }
    private fun strictConstructorFields(id: String, arity: Int): BooleanArray {
        val info = constructors[id] ?: throw RuntimeFault("Missing constructor metadata $id")
        if ((info["kind"] ?: "boxed") != "boxed") throw UnsupportedCore("Unsupported constructor representation ${info["kind"]}: $id")
        if ((info["arity"] as Number).toInt() != arity) throw RuntimeFault("Constructor arity mismatch: $id")
        val strict = info["strictFields"] as? List<*> ?: throw RuntimeFault("Missing constructor strictness metadata: $id")
        val lifted = info["fieldLifted"] as? List<*> ?: throw RuntimeFault("Missing constructor representation metadata: $id")
        if (strict.size != arity || lifted.size != arity) throw RuntimeFault("Constructor metadata length mismatch: $id")
        return BooleanArray(arity) { i ->
            val strictField = strict[i] as? Boolean ?: throw RuntimeFault("Unknown constructor field strictness: $id")
            strictField && (lifted[i] as? Boolean
                ?: throw UnsupportedCore("Unknown strict constructor field levity: $id field $i"))
        }
    }
    private fun constructorVectorSlots(layout: DataLayout, frame: FrameLayout): Array<IntArray?> =
        Array(layout.logicalArity) { index ->
            if (layout.logicalProof(index)?.isAggregate == true || layout.isVector(layout.fieldOffset(index)))
                IntArray(layout.logicalWidth(index)) { lane ->
                frame.bind("<constructor ${layout.id} field $index lane $lane>")
            } else null
        }
    private fun construct(id: String, args: Array<Expr>, frame: FrameLayout): Expr {
        val strict = strictConstructorFields(id, args.size)
        val fields = Array(args.size) { i ->
            // Constructor workers carry CBV obligations independently of argument
            // levity (CorePrep, Note [Pin evaluatedness on floats]). This body runs
            // only at saturation, including entry through a constructor closure/PAP.
            if (strict[i]) Evaluate(args[i], metrics) else args[i]
        }
        val layout = dataLayout(id)
        return Construct(layout, fields, constructorVectorSlots(layout, frame))
    }
}

/** Unavailable scalars stay lazy; demanding a tuple traps before writing any destination. */
private class DiagnosticUnavailable(private val target: RootCallTarget, message: String, metrics: Metrics) : Expr() {
    @Child private var tupleTrap = UnsupportedExpression(message, metrics)
    override fun execute(frame: VirtualFrame): Thunk = Thunk(target, null)
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Nothing = tupleTrap.execute(frame)
}

/** Explicit development mode only; execution never fabricates a guest result. */
private class UnsupportedExpression(private val message: String, private val metrics: Metrics) : Expr() {
    init { representation = CoreRepresentation(CoreKind.UNKNOWN, evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        metrics.incrementUnsupportedTraps()
        throw RuntimeFault("Diagnostic unsupported path reached: $message")
    }
}
