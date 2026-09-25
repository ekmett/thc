// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.dsl.TypeSystemReference
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.*
import com.oracle.truffle.api.profiles.BranchProfile
import com.oracle.truffle.api.profiles.CountingConditionProfile
import com.oracle.truffle.api.source.SourceSection

/* Indexed frames, selective captures, rooted application and self-tail frame
 * restoration follow Cadenza. See NOTICE.md and LICENSE.txt. Haskell thunks
 * supply the additional lazy update/blackhole protocol. Arbitrary non-tail
 * recursion still uses the host stack. */
open class RuntimeFault(message: String) : RuntimeException(message)
/** A known implementation gap, distinct from malformed Core or runtime errors. */
internal class UnsupportedCore(message: String) : RuntimeFault(message)
internal fun fault(message: String): Nothing {
    CompilerDirectives.transferToInterpreterAndInvalidate()
    throw RuntimeFault(message)
}
/** Narrow unsigned carriers are zero-extended Longs, unlike signed Int8/16/32 carriers. */
internal fun narrowWordPrimitiveMask(name: String): Long = when (name) {
    "wordToWord8#", "word8ToWord#", "int8ToWord8#", "narrow8Word#", "plusWord8#", "subWord8#", "timesWord8#", "ltWord8#", "leWord8#",
    "quotWord8#", "remWord8#", "eqWord8#", "neWord8#", "gtWord8#", "geWord8#", "andWord8#", "orWord8#", "xorWord8#", "notWord8#", "uncheckedShiftLWord8#", "uncheckedShiftRLWord8#" -> 0xffL
    "wordToWord16#", "word16ToWord#", "int16ToWord16#", "narrow16Word#", "plusWord16#", "subWord16#", "timesWord16#", "ltWord16#", "leWord16#",
    "quotWord16#", "remWord16#", "eqWord16#", "neWord16#", "gtWord16#", "geWord16#", "andWord16#", "orWord16#", "xorWord16#", "notWord16#", "uncheckedShiftLWord16#", "uncheckedShiftRLWord16#" -> 0xffffL
    "wordToWord32#", "word32ToWord#", "int32ToWord32#", "narrow32Word#", "plusWord32#", "subWord32#", "timesWord32#", "ltWord32#", "leWord32#",
    "quotWord32#", "remWord32#", "eqWord32#", "neWord32#", "gtWord32#", "geWord32#", "andWord32#", "orWord32#", "xorWord32#", "notWord32#", "uncheckedShiftLWord32#", "uncheckedShiftRLWord32#" -> 0xffff_ffffL
    else -> 0L
}
/** Fixed-width signed arithmetic retains canonical sign-extended Long carriers. */
internal fun narrowIntPrimitiveShift(name: String): Int = when (name) {
    "word8ToInt8#", "negateInt8#", "plusInt8#", "subInt8#", "timesInt8#", "quotInt8#", "remInt8#", "eqInt8#", "neInt8#", "ltInt8#", "leInt8#", "gtInt8#", "geInt8#", "uncheckedShiftLInt8#", "uncheckedShiftRAInt8#" -> 56
    "word16ToInt16#", "negateInt16#", "plusInt16#", "subInt16#", "timesInt16#", "quotInt16#", "remInt16#", "eqInt16#", "neInt16#", "ltInt16#", "leInt16#", "gtInt16#", "geInt16#", "uncheckedShiftLInt16#", "uncheckedShiftRAInt16#" -> 48
    "word32ToInt32#", "negateInt32#", "plusInt32#", "subInt32#", "timesInt32#", "quotInt32#", "remInt32#", "eqInt32#", "neInt32#", "ltInt32#", "leInt32#", "gtInt32#", "geInt32#", "uncheckedShiftLInt32#", "uncheckedShiftRAInt32#" -> 32
    else -> 0
}
private fun signedNarrow(value: Long, shift: Int): Long = (value shl shift) shr shift
internal fun narrowWordLiteral(kind: String, value: String): Long {
    val maximum = when (kind) {
        "word8" -> 0xffL; "word16" -> 0xffffL; "word32" -> 0xffff_ffffL
        else -> throw RuntimeFault("Invalid narrow word literal kind: $kind")
    }
    val number = value.toLongOrNull()
    if (number == null || number !in 0L..maximum || number.toString() != value)
        throw RuntimeFault("Invalid $kind literal: $value")
    return number
}
/** Int8 literals are canonical decimal signed 8-bit values, widened to Long. */
internal fun int8Literal(value: String): Long {
    val number = value.toLongOrNull()
    if (number == null || number !in Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong() || number.toString() != value)
        throw RuntimeFault("Invalid int8 literal: $value")
    return number
}
/** Int16 literals are canonical decimal signed 16-bit values, widened to Long. */
internal fun int16Literal(value: String): Long {
    val number = value.toLongOrNull()
    if (number == null || number !in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong() || number.toString() != value)
        throw RuntimeFault("Invalid int16 literal: $value")
    return number
}
/** Int32 literals are canonical decimal signed 32-bit values, widened to Long. */
internal fun int32Literal(value: String): Long {
    val number = value.toLongOrNull()
    if (number == null || number !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || number.toString() != value)
        throw RuntimeFault("Invalid int32 literal: $value")
    return number
}
/** Int64 literals are canonical decimal signed 64-bit carriers, including both endpoints. */
internal fun int64Literal(value: String): Long {
    val number = value.toLongOrNull()
    if (number == null || number.toString() != value) throw RuntimeFault("Invalid int64 literal: $value")
    return number
}
/** Cadenza's recursive indirection: captured by identity, initialized once. */
internal class RecCell {
    @Volatile var initialized = false
    @Volatile var value: Any? = null
}
/** Program linkage is fixed before guest execution; CAF contents remain lazy. */
internal class GlobalBinding(val name: String) {
    @CompilationFinal private var initialized = false
    @CompilationFinal private var value: Any? = null
    fun initialize(value: Any?) { check(!initialized); this.value = value; initialized = true }
    fun read(): Any? {
        if (!initialized) fault("Uninitialized global binding")
        return value
    }
}
internal class Thunk(target: RootCallTarget, var environment: CapturedFrame?) {
    // Updated thunks retain only their answer (or memoized guest failure).
    var target: RootCallTarget? = target
    // 0 = unevaluated, 1 = owned, 2 = WHNF, 3 = ordinary failure,
    // 4 = interrupted without a resumable continuation, 5 = cooperatively
    // yielded guest root with a captured continuation. State publishes the
    // value/continuation and release of ownership to all waiting guest threads.
    @Volatile var state = 0
    var value: Any? = null
    var owner: Thread? = null
    val monitor = java.lang.Object()
}
/** A cold, one-shot call continuation. Unlike a thunk update, its answer may itself be lazy. */
internal class CallSegment @JvmOverloads constructor(
    continuation: Any,
    var logicalMask: MaskingState = MaskingState.UNMASKED,
    val callerMask: MaskingState = MaskingState.UNMASKED,
    val tupleShape: TupleShape? = null,
    /** This cold token was captured inside a real catch# action boundary. */
    val caughtIOAction: Boolean = false
) {
    init { check(savedGuestContinuation(continuation) != null) { "Call segment needs a saved continuation" } }
    @Volatile var state = 5 // owned=1, completed=2, failure=3, unsupported unwind=4, parked=5
    var value: Any? = continuation
    var owner: Thread? = null
    val monitor = java.lang.Object()
}
/** Keep immutable guest failure data, never a shared mutable Truffle stack trace. */
private data class MemoizedGuestFailure(val payload: Any?, val location: Node)
/** Async delivery must carry its origin separately from its guest payload. */
internal class AsyncThunkUnwind(val payload: Any?) : RuntimeException("Asynchronous guest unwind")
/** Cold committed cut for one exact original catch# action, never supplied by Core. */
internal class PrivateIOUnwind @JvmOverloads constructor(
    val action: CallSegment, val payload: Any?, val request: CapturedAsyncRequest? = null) :
    RuntimeException("Private captured IO-handler unwind", null, false, false)
/** Private origin tag; only checkpointed catch# may unwrap it for its handler. */
internal class CapturedAsyncDelivery @JvmOverloads constructor(
    val payload: Any?, val request: CapturedAsyncRequest? = null) :
    com.oracle.truffle.api.exception.AbstractTruffleException(
        "Private captured IO-handler delivery", null, 0, null)
/** A root-local bytecode yield hands the shared thunk to another evaluator. */
internal class ThunkSuspended @JvmOverloads constructor(val thunk: Thunk, val asyncRequest: AsyncRequest? = null) :
    com.oracle.truffle.api.exception.AbstractTruffleException(
        "Internal bytecode thunk suspension", null, 0, null)
/** A call returned its own bytecode continuation; only that exact call edge may capture it. */
internal class CapturedCallSuspension(val segment: CallSegment) :
    com.oracle.truffle.api.exception.AbstractTruffleException(
        "Internal bytecode call suspension", null, 0, null)
internal class CallSegmentSuspended @JvmOverloads constructor(
    val segment: CallSegment,
    /** Logical mask before a caller parked to its root-entry mask for Yield. */
    val parkedActiveMask: MaskingState? = null,
    val asyncRequest: AsyncRequest? = savedGuestContinuation(segment.value)?.asyncRequest()
) :
    com.oracle.truffle.api.exception.AbstractTruffleException(
        "Internal bytecode call segment suspension", null, 0, null)
/** Cold caller-segment input distinguishes a child result from its guest failure. */
internal class ChildResume(val value: Any?, val failure: GuestException?)
internal class Metrics(val enabled: Boolean) {
    private val thunkCounts = linkedMapOf<String, Long>()
    @CompilerDirectives.TruffleBoundary @Synchronized fun recordThunk(label: String) {
        thunkCounts[label] = (thunkCounts[label] ?: 0L) + 1L
    }
    @CompilerDirectives.TruffleBoundary @Synchronized fun thunkCountsSnapshot(): Map<String, Long> = thunkCounts.toMap()
    private val compiledEntriesCounter = java.util.concurrent.atomic.AtomicLong()
    val compiledEntries: Long get() = compiledEntriesCounter.get()
    fun incrementCompiledEntries() { compiledEntriesCounter.incrementAndGet() }
    private val leadingCaseReturnsCounter = java.util.concurrent.atomic.AtomicLong()
    val leadingCaseReturns: Long get() = leadingCaseReturnsCounter.get()
    fun incrementLeadingCaseReturns() { leadingCaseReturnsCounter.incrementAndGet() }
    private val thunkEvaluationsCounter = java.util.concurrent.atomic.AtomicLong()
    val thunkEvaluations: Long get() = thunkEvaluationsCounter.get()
    fun incrementThunkEvaluations() { thunkEvaluationsCounter.incrementAndGet() }
    private val thunkHitsCounter = java.util.concurrent.atomic.AtomicLong()
    val thunkHits: Long get() = thunkHitsCounter.get()
    fun incrementThunkHits() { thunkHitsCounter.incrementAndGet() }
    private val blackholesCounter = java.util.concurrent.atomic.AtomicLong()
    val blackholes: Long get() = blackholesCounter.get()
    fun incrementBlackholes() { blackholesCounter.incrementAndGet() }
    private val directCacheMissesCounter = java.util.concurrent.atomic.AtomicLong()
    val directCacheMisses: Long get() = directCacheMissesCounter.get()
    fun incrementDirectCacheMisses() { directCacheMissesCounter.incrementAndGet() }
    private val indirectCallsCounter = java.util.concurrent.atomic.AtomicLong()
    val indirectCalls: Long get() = indirectCallsCounter.get()
    fun incrementIndirectCalls() { indirectCallsCounter.incrementAndGet() }
    private val tailBouncesCounter = java.util.concurrent.atomic.AtomicLong()
    val tailBounces: Long get() = tailBouncesCounter.get()
    fun incrementTailBounces() { tailBouncesCounter.incrementAndGet() }
    private val selfTailReentriesCounter = java.util.concurrent.atomic.AtomicLong()
    val selfTailReentries: Long get() = selfTailReentriesCounter.get()
    fun incrementSelfTailReentries() { selfTailReentriesCounter.incrementAndGet() }
    private val localJoinTransfersCounter = java.util.concurrent.atomic.AtomicLong()
    val localJoinTransfers: Long get() = localJoinTransfersCounter.get()
    fun incrementLocalJoinTransfers() { localJoinTransfersCounter.incrementAndGet() }
    private val trampolineIterationsCounter = java.util.concurrent.atomic.AtomicLong()
    val trampolineIterations: Long get() = trampolineIterationsCounter.get()
    fun incrementTrampolineIterations() { trampolineIterationsCounter.incrementAndGet() }
    private val papAllocationsCounter = java.util.concurrent.atomic.AtomicLong()
    val papAllocations: Long get() = papAllocationsCounter.get()
    fun incrementPapAllocations() { papAllocationsCounter.incrementAndGet() }
    private val unsupportedTrapsCounter = java.util.concurrent.atomic.AtomicLong()
    val unsupportedTraps: Long get() = unsupportedTrapsCounter.get()
    fun incrementUnsupportedTraps() { unsupportedTrapsCounter.incrementAndGet() }
}
@TypeSystemReference(RuntimeTypes::class)
internal abstract class Expr : Node() {
    // Assigned during lowering, before adoption. Evaluatedness describes our stored value.
    @CompilationFinal var representation: CoreRepresentation = CoreRepresentation.UNKNOWN
        set(value) {
            field = value
            vectorLayout = if (value.isVector) VectorLayout(value) else null
        }
    @CompilationFinal private var vectorLayout: VectorLayout? = null
    protected val typedVectorLayout: VectorLayout? get() = vectorLayout
    @CompilationFinal var coreSourceLocation: CoreSourceLocation? = null
    fun located(location: CoreSourceLocation?): Expr { coreSourceLocation = location; return this }
    override fun getSourceSection(): SourceSection? = coreSourceLocation?.section ?: parent?.encapsulatingSourceSection
    fun proven(proof: CoreRepresentation): Expr { representation = proof; return this }
    open fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int = 0): Any? {
        val layout = vectorLayout
        if (layout != null) {
            layout.write(frame, slots, offset, execute(frame))
            return null
        }
        fault("Expression does not produce a tuple")
    }
    abstract fun execute(frame: VirtualFrame): Any?
    @Throws(UnexpectedResultException::class)
    open fun executeLong(frame: VirtualFrame): Long {
        return RuntimeTypesGen.expectLong(execute(frame))
    }

    @Throws(UnexpectedResultException::class)
    open fun executeFloat(frame: VirtualFrame): Float = RuntimeTypesGen.expectFloat(execute(frame))
    @Throws(UnexpectedResultException::class)
    open fun executeDouble(frame: VirtualFrame): Double = RuntimeTypesGen.expectDouble(execute(frame))
    fun executeRequiredFloat(frame: VirtualFrame): Float = try { executeFloat(frame) }
    catch (_: UnexpectedResultException) { fault("Expected primitive Float") }
    fun executeRequiredDouble(frame: VirtualFrame): Double = try { executeDouble(frame) }
    catch (_: UnexpectedResultException) { fault("Expected primitive Double") }

    @Throws(UnexpectedResultException::class)
    open fun executeClosure(frame: VirtualFrame): Closure = RuntimeTypesGen.expectClosure(execute(frame))

    @Throws(UnexpectedResultException::class)
    open fun executeDataValue(frame: VirtualFrame): DataValue = RuntimeTypesGen.expectDataValue(execute(frame))

    @Throws(UnexpectedResultException::class)
    open fun executeAddress(frame: VirtualFrame): ManagedAddress = RuntimeTypesGen.expectManagedAddress(execute(frame))

    /** Primitive consumers reject an unexpected value; forwarding nodes preserve it. */
    fun executeRequiredLong(frame: VirtualFrame): Long = try { executeLong(frame) }
    catch (_: UnexpectedResultException) { fault("Expected primitive Long") }

    fun executeRequiredClosure(frame: VirtualFrame): Closure = try { executeClosure(frame) }
    catch (_: UnexpectedResultException) { fault("Application of a non-function") }

    fun executeRequiredDataValue(frame: VirtualFrame): DataValue = try { executeDataValue(frame) }
    catch (_: UnexpectedResultException) { fault("Expected constructor value") }

    fun executeRequiredAddress(frame: VirtualFrame): ManagedAddress = try { executeAddress(frame) }
    catch (_: UnexpectedResultException) { fault("Expected a managed literal Addr#") }
}
private class Literal(private val value: Any?) : Expr() {
    init { representation = CoreRepresentation(when (value) {
        is Long -> CoreKind.LONG; is Float -> CoreKind.FLOAT; is Double -> CoreKind.DOUBLE
        is ManagedAddress -> CoreKind.ADDRESS; Unit -> CoreKind.VOID; else -> CoreKind.OBJECT
    }, evaluated = true) }
    override fun execute(frame: VirtualFrame) = value
    override fun executeLong(frame: VirtualFrame) = RuntimeTypesGen.expectLong(value)
    override fun executeFloat(frame: VirtualFrame) = RuntimeTypesGen.expectFloat(value)
    override fun executeDouble(frame: VirtualFrame) = RuntimeTypesGen.expectDouble(value)
}
internal class LocalRead(private val slot: Int, private val cell: Boolean = true) : Expr() {
    override fun executeFloat(frame: VirtualFrame): Float =
        if ((!cell && representation.isFloat) || frame.isFloat(slot)) frame.getFloat(slot) else super.executeFloat(frame)
    override fun executeDouble(frame: VirtualFrame): Double =
        if ((!cell && representation.isDouble) || frame.isDouble(slot)) frame.getDouble(slot) else super.executeDouble(frame)
    override fun executeLong(frame: VirtualFrame): Long =
        if ((!cell && representation.isLong) || frame.isLong(slot)) frame.getLong(slot) else super.executeLong(frame)

    override fun execute(frame: VirtualFrame): Any? {
        val value = if (!cell && representation.isEvaluatedReference) frame.getObject(slot) else FrameAccess.read(frame, slot)
        // A proved, nonrecursive unlifted reference can carry the null sentinel
        // returned by original foreign protocols (the terminal stack location).
        // It is already bound; unlike unknown locals or unpublished RecCells,
        // null here describes the value, not its initialization state.
        if (!cell && representation.isEvaluatedUnliftedObject) return value
        if (!cell || value !is RecCell) return value ?: fault("Uninitialized local binding")
        if (!value.initialized) fault("Recursive binding read before initialization")
        return value.value
    }

    override fun executeDataValue(frame: VirtualFrame): DataValue =
        if (!cell && representation.evaluated && representation.kind == CoreKind.DATA)
            RuntimeTypesGen.expectDataValue(frame.getObject(slot)) else super.executeDataValue(frame)

    override fun executeClosure(frame: VirtualFrame): Closure =
        if (!cell && representation.evaluated && representation.kind == CoreKind.CLOSURE)
            RuntimeTypesGen.expectClosure(frame.getObject(slot)) else super.executeClosure(frame)

    override fun executeAddress(frame: VirtualFrame): ManagedAddress =
        if (!cell && representation.evaluated && representation.kind == CoreKind.ADDRESS)
            RuntimeTypesGen.expectManagedAddress(frame.getObject(slot)) else super.executeAddress(frame)

    /** Recursive captures retain their cell identity until the whole group is published. */
    fun writeForced(frame: VirtualFrame, original: Thunk, result: Any?) {
        val binding = FrameAccess.read(frame, slot)
        if (binding === original) FrameAccess.write(frame, slot, result)
        else if (cell && binding is RecCell) updateForcedCell(binding, original, result)
    }
}
/** Replace only the successfully forced link; aliases may already have updated this cell. */
internal fun updateForcedCell(cell: RecCell, original: Thunk, result: Any?) {
    synchronized(cell) {
        if (cell.initialized && cell.value === original) cell.value = result
    }
}

private class GlobalRead(private val binding: GlobalBinding) : Expr() {
    override fun execute(frame: VirtualFrame) = binding.read()
}
private class MakeClosure(private val target: RootCallTarget, private val arity: Int,
                          private val captureLayout: CaptureLayout?,
                          @field:CompilationFinal(dimensions = 1) private val captures: IntArray) : Expr() {
    init { representation = CoreRepresentation(CoreKind.CLOSURE, evaluated = true) }
    // Cadenza's closed-lambda optimization: immutable code needs no allocation.
    private val constantClosure = if (captureLayout == null) Closure(environment = null, arity = arity, target = target) else null
    override fun execute(frame: VirtualFrame): Closure = constantClosure ?: Closure(
        environment = captureLayout!!.capture(frame, captures), arity = arity, target = target)
    override fun executeClosure(frame: VirtualFrame): Closure = execute(frame)
}
private class Delay(private val target: RootCallTarget, private val captureLayout: CaptureLayout?,
                    @field:CompilationFinal(dimensions = 1) private val captures: IntArray) : Expr() {
    override fun execute(frame: VirtualFrame): Thunk = Thunk(target, captureLayout?.capture(frame, captures))
}
internal class Force @JvmOverloads constructor(private val metrics: Metrics, private val asyncMode: Boolean = false) : Node() {
    private object Retry
    private class Parked(val boundary: Any, val continuation: SavedGuestContinuation)
    @Child private var calls = ThunkTargetCache(metrics)
    @Child private var trampoline = TailCallLoop(metrics)
    private val tailCallProfile = BranchProfile.create()
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
            val observed = if (original.state == 5) savedGuestContinuation(original.value) else null
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
            return evaluateOwned(original, savedGuestContinuation(continuation), AsyncThunkUnwind(payload))
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
                    evaluateOwned(original, savedGuestContinuation(continuation), PrivateIOUnwind(child, payload, request))
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
                    evaluateCallSegment(original, checkNotNull(savedGuestContinuation(continuation)), mask,
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
                        5 -> if ((observed != null && original.value !== observed.identity) ||
                            (observed == null &&
                                (suspendedChild(savedGuestContinuation(original.value)) != null))) 3 else {
                            continuation = savedGuestContinuation(original.value)
                                ?: fault("Suspended thunk has no guest continuation")
                            original.value = null // One owner consumes the one-shot continuation.
                            original.owner = Thread.currentThread()
                            original.state = 1
                            claimedHere = true
                            0
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
    private fun resumeChain(original: Thunk): Any? {
        val parked = java.util.ArrayDeque<Parked>()
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        while (true) {
            parked.clear()
            seen.clear()
            var leaf: Any = original
            var leafContinuation: SavedGuestContinuation? = null
            while (true) {
                if (seen.put(leaf, true) != null) fault("Suspended thunk dependency cycle")
                leafContinuation = continuationOf(leaf)
                val child = suspendedChild(leafContinuation) ?: break
                parked.addLast(Parked(leaf, leafContinuation!!))
                leaf = child
            }
            var current: Any = leaf
            var expected = leafContinuation
            var input: Any? = Unit
            while (true) {
                val outcome = try {
                    val answer = when (current) {
                        is Thunk -> executeOne(current, expected, input)
                        is CallSegment -> executeCallSegment(current, expected, input)
                        else -> fault("Invalid suspended continuation boundary")
                    }
                    if (answer === Retry) null else ChildResume(answer, null)
                } catch (suspension: ThunkSuspended) {
                    // Resignal the requested update boundary, not a deeper child.
                    if (original.state == 5) throw ThunkSuspended(original, suspension.asyncRequest)
                    throw suspension
                } catch (suspension: CallSegmentSuspended) {
                    // The requested thunk is still parked even if a deeper
                    // dependency yielded again. A new caller must capture the
                    // requested update boundary, not skip its continuation.
                    if (original.state == 5) throw ThunkSuspended(original, suspension.asyncRequest)
                    throw suspension
                } catch (failure: GuestException) { ChildResume(null, failure) }
                if (outcome == null) break // Another evaluator advanced a link; rescan from the root.
                if (parked.isEmpty()) {
                    outcome.failure?.let { throw it }
                    return outcome.value
                }
                val parent = parked.removeLast()
                current = parent.boundary
                expected = parent.continuation
                input = outcome
            }
        }
    }

    private fun continuationOf(boundary: Any): SavedGuestContinuation? = when (boundary) {
        is Thunk -> if (boundary.state == 5) savedGuestContinuation(boundary.value) else null
        is CallSegment -> if (boundary.state == 5) savedGuestContinuation(boundary.value) else null
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
                            (observed == null && suspendedChild(savedGuestContinuation(segment.value)) != null)) 3 else {
                            continuation = savedGuestContinuation(segment.value)
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
        try {
            SynchronousMasking.set(this, resumeMask)
            val returned = try { continuation.continueWith(resumeValue) }
                catch (tail: TailCall) { tailCallProfile.enter(); trampoline.execute(tail) }
            val result = if (returned is TailYield) returned.continuation else returned
            val saved = savedGuestContinuation(result)
            if (saved != null) {
                if (returned !is TailYield && !sameContinuationBody(saved.sourceRoot, continuation.sourceRoot))
                    throw IllegalStateException("Nested guest yield has no captured caller segment")
                val parkedMask = (saved.yielded as? CallSegmentSuspended)?.parkedActiveMask
                if (parkedMask != null && SynchronousMasking.current(this) != segment.callerMask)
                    throw IllegalStateException("Parked call segment did not restore its caller mask")
                val request = saved.asyncRequest()
                publishCallContinuation(segment, saved, parkedMask ?: SynchronousMasking.current(this))
                throw CallSegmentSuspended(segment, asyncRequest = request)
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
                segment.monitor.notifyAll()
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
            publishCallFailure(segment, MemoizedGuestFailure(e.payload, e.location ?: this))
            throw e
        } catch (e: RuntimeFault) {
            publishCallFailure(segment, e)
            throw e
        } catch (e: Throwable) {
            suspendCallOwned(segment)
            throw e
        } finally { SynchronousMasking.set(this, carrierAmbient) }
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
                segment.monitor.notifyAll()
            }
        } finally { safepoint.setAllowSideEffects(previous) }
    }

    private fun publishCallFailure(segment: CallSegment, failure: Any) = synchronized(segment.monitor) {
        segment.value = failure
        segment.owner = null
        segment.state = 3
        segment.monitor.notifyAll()
    }

    private fun suspendCallOwned(segment: CallSegment) = synchronized(segment.monitor) {
        if (segment.state != 1 || segment.owner !== Thread.currentThread()) return@synchronized
        segment.owner = null
        segment.state = 4
        segment.monitor.notifyAll()
    }

    private fun rethrowCallFailure(segment: CallSegment): Nothing {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        when (val failure = segment.value) {
            is MemoizedGuestFailure -> throw GuestException(failure.payload, failure.location)
            is Throwable -> throw failure
            else -> fault("Invalid failed call segment")
        }
    }

    @CompilerDirectives.TruffleBoundary
    private fun awaitCallOwner(segment: CallSegment) {
        TruffleSafepoint.setBlockedThreadInterruptible(this, TruffleSafepoint.Interruptible<CallSegment> { waiting ->
            synchronized(waiting.monitor) {
                if (waiting.state == 1 && waiting.owner !== Thread.currentThread()) {
                    if (asyncMode) GuestThreads.pollCurrent(this, true)?.let { throw AsyncBlocked(it, this) }
                    GuestThreads.blocking(GuestThreadStatus.BLACK_HOLE).use { waiting.monitor.wait() }
                }
            }
        }, segment)
    }

    private fun evaluateOwned(thunk: Thunk, continuation: SavedGuestContinuation?, resumeValue: Any?): Any? {
        try {
            if (metrics.enabled && continuation == null) {
                val target = thunk.target ?: fault("Unevaluated thunk has no body")
                metrics.incrementThunkEvaluations(); metrics.recordThunk(target.rootNode.name)
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
                try {
                    try { continuation.continueWith(resumeValue) }
                    catch (tail: TailCall) { tailCallProfile.enter(); trampoline.execute(tail) }
                } finally { SynchronousMasking.set(this, ambient) }
            }
            val result = if (returned is TailYield) returned.continuation else returned
            val saved = savedGuestContinuation(result)
            if (saved != null) {
                val expectedRoot = continuation?.sourceRoot
                    ?: thunk.target?.rootNode
                if (returned !is TailYield && !sameContinuationBody(saved.sourceRoot, expectedRoot))
                    throw IllegalStateException("Nested guest yield has no captured caller segment")
                val request = saved.asyncRequest()
                publishContinuation(thunk, saved)
                throw ThunkSuspended(thunk, request)
            }
            if (result is Thunk) fault("Thunk target violated WHNF convention")
            synchronized(thunk.monitor) {
                thunk.value = result
                thunk.target = null
                thunk.environment = null
                thunk.owner = null
                thunk.state = 2
                thunk.monitor.notifyAll()
            }
            return result
        } catch (e: ThunkSuspended) {
            // Without a captured caller segment, the outer update frame cannot
            // resume after this child. Release its owner and fail closed.
            if (e.thunk !== thunk) suspendOwned(thunk)
            throw e
        } catch (e: AsyncThunkUnwind) {
            suspendOwned(thunk)
            throw e
        } catch (e: GuestException) {
            publishFailure(thunk, MemoizedGuestFailure(e.payload, e.location ?: this))
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
                thunk.monitor.notifyAll()
            }
        } finally {
            safepoint.setAllowSideEffects(previous)
        }
    }

    private fun publishFailure(thunk: Thunk, failure: Any) = synchronized(thunk.monitor) {
        thunk.value = failure
        thunk.target = null
        thunk.environment = null
        thunk.owner = null
        thunk.state = 3
        thunk.monitor.notifyAll()
    }

    private fun suspendOwned(thunk: Thunk) = synchronized(thunk.monitor) {
        if (thunk.state != 1 || thunk.owner !== Thread.currentThread()) return@synchronized
        // An arbitrary Java/bytecode stack is not a resumable Haskell AP_STACK.
        // Retain the body for a future continuation implementation; never replay
        // effects by silently returning this thunk to state 0.
        thunk.owner = null
        thunk.state = 4
        thunk.monitor.notifyAll()
    }

    private fun rethrowFailure(thunk: Thunk): Nothing {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        when (val failure = thunk.value) {
            is MemoizedGuestFailure -> throw GuestException(failure.payload, failure.location)
            is Throwable -> throw failure
            else -> fault("Invalid failed thunk")
        }
    }

    @CompilerDirectives.TruffleBoundary
    private fun awaitOwner(thunk: Thunk) {
        TruffleSafepoint.setBlockedThreadInterruptible(this, TruffleSafepoint.Interruptible<Thunk> { waiting ->
            synchronized(waiting.monitor) {
                if (waiting.state == 1 && waiting.owner !== Thread.currentThread()) {
                    if (asyncMode) GuestThreads.pollCurrent(this, true)?.let { throw AsyncBlocked(it, this) }
                    GuestThreads.blocking(GuestThreadStatus.BLACK_HOLE).use { waiting.monitor.wait() }
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
        val result = force.execute(frame, original)
        // WHNF reads need no write. A failed force never reaches this point.
        if (original is Thunk && value is LocalRead) (value as LocalRead).writeForced(frame, original, result)
        return result
    }
    override fun execute(frame: VirtualFrame): Any? = when {
        value.representation.isLong -> value.executeRequiredLong(frame)
        value.representation.isFloat -> value.executeRequiredFloat(frame)
        value.representation.isDouble -> value.executeRequiredDouble(frame)
        value.representation.evaluated -> value.execute(frame)
        else -> forceResult(frame, value.execute(frame))
    }
    @CompilationFinal private var genericLong = false
    override fun executeFloat(frame: VirtualFrame): Float =
        if (value.representation.evaluated || value.representation.isFloat) value.executeFloat(frame)
        else RuntimeTypesGen.expectFloat(forceResult(frame, value.execute(frame)))
    override fun executeDouble(frame: VirtualFrame): Double =
        if (value.representation.evaluated || value.representation.isDouble) value.executeDouble(frame)
        else RuntimeTypesGen.expectDouble(forceResult(frame, value.execute(frame)))
    override fun executeLong(frame: VirtualFrame): Long {
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
        val fn = function.executeRequiredClosure(frame)
        val values = arrayOfNulls<Any>(ArgumentLayout.width(inputLayout, arguments.size))
        for (i in arguments.indices) {
            if (inputLayout?.isEmpty(i) == true) arguments[i].executeTuple(frame, EMPTY_TUPLE_SLOTS, 0)
            else values[ArgumentLayout.offset(inputLayout, i)] = arguments[i].execute(frame)
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
        if (vectorSlots != null) { value.executeTuple(frame, vectorSlots, 0); return }
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
        initialize(frame); return body.executeTuple(frame, slots, offset)
    }
    @ExplodeLoop private fun initialize(frame: VirtualFrame) {
        if (recursive) {
            // A closure captures these cells, never a mutable activation frame.
            for (slot in slots) FrameAccess.write(frame, slot, RecCell())
            for (i in slots.indices) {
                val cell = FrameAccess.read(frame, slots[i]) as? RecCell ?: fault("Invalid recursive cell")
                cell.value = bindings[i].evaluate(frame)
                cell.initialized = true
            }
            // Existing recursive captures retain the cells; the let body and
            // closures built after publication read the values directly.
            // Publish only after every RHS has captured the whole group.
            for (slot in slots) {
                val cell = FrameAccess.read(frame, slot) as? RecCell ?: fault("Invalid recursive cell")
                FrameAccess.write(frame, slot, cell.value)
            }
        } else for (binding in bindings) binding.write(frame)
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
        LITERAL_ALTERNATIVE -> if (value is Long && frame.isLong(slot)) frame.getLong(slot) == value
            else FrameAccess.read(frame, slot) == value
        else -> false
    })
}
private open class Case(scrutinee: Expr, protected val binderSlot: Int,
                   @field:Children protected var alternatives: Array<Alternative>, metrics: Metrics,
                   binderProof: CoreRepresentation? = null) : Expr() {
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
        representation = aggregate?.copy(evaluated = proofs.all { it.evaluated }) ?: CoreVectors.caseResult(proofs)
            ?: CoreRepresentation(kind, proofs.all { it.evaluated }, proofs.isNotEmpty() && proofs.all { it.present })
    }
    // Preserve primitive scrutinees through their frame write and literal comparisons.
    @Child private var scrutinee = LocalBinding(binderSlot, Evaluate(scrutinee, metrics).apply {
        if (binderProof != null) representation = representation.refine(binderProof.copy(evaluated = false))
    }, true)
    protected fun prepare(frame: VirtualFrame) { scrutinee.write(frame) }
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
        prepare(frame)
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
                val lanes = alt.vectorFields.getOrNull(i)
                if (lanes == null) layout.restore(data, i, frame, alt.fields[i])
                else layout.restoreVector(data, i, frame, lanes, 0)
            }
        }
    }
}
/** Category selection happens during lowering, never in the guest loop. */
private class DataCase(scrutinee: Expr, binder: Int, alternatives: Array<Alternative>, metrics: Metrics,
                       proof: CoreRepresentation) : Case(scrutinee, binder, alternatives, metrics, proof) {
    override fun matches(frame: VirtualFrame, alternative: Alternative): Boolean =
        alternative.matchesData(frame.getObject(binderSlot) as DataValue)
}
private class LongCase(scrutinee: Expr, binder: Int, alternatives: Array<Alternative>, metrics: Metrics,
                       proof: CoreRepresentation) : Case(scrutinee, binder, alternatives, metrics, proof) {
    override fun matches(frame: VirtualFrame, alternative: Alternative): Boolean =
        alternative.matchesLong(frame.getLong(binderSlot))
}
private class DefaultCase(scrutinee: Expr, binder: Int, alternatives: Array<Alternative>, metrics: Metrics,
                          proof: CoreRepresentation) : Case(scrutinee, binder, alternatives, metrics, proof) {
    override fun execute(frame: VirtualFrame): Any? { prepare(frame); return alternatives.last().body.execute(frame) }
    override fun executeLong(frame: VirtualFrame): Long { prepare(frame); return alternatives.last().body.executeLong(frame) }
    override fun executeFloat(frame: VirtualFrame): Float { prepare(frame); return alternatives.last().body.executeFloat(frame) }
    override fun executeDouble(frame: VirtualFrame): Double { prepare(frame); return alternatives.last().body.executeDouble(frame) }
    override fun executeClosure(frame: VirtualFrame): Closure { prepare(frame); return alternatives.last().body.executeClosure(frame) }
    override fun executeDataValue(frame: VirtualFrame): DataValue { prepare(frame); return alternatives.last().body.executeDataValue(frame) }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress { prepare(frame); return alternatives.last().body.executeAddress(frame) }
}
private class Construct(private val layout: DataLayout,
                        @field:Children private var fields: Array<Expr>,
                        @field:CompilationFinal(dimensions = 2) private val vectorSlots: Array<IntArray?> = emptyArray()) : Expr() {
    init { representation = CoreRepresentation(CoreKind.DATA, evaluated = true) }
    @ExplodeLoop override fun execute(frame: VirtualFrame): DataValue {
        if (layout.hasBoxedValueCache) return layout.createLong(fields[0].executeRequiredLong(frame))
        val value = layout.allocate()
        for (i in fields.indices) {
            if (layout.isVector(i)) {
                val lanes = vectorSlots[i] ?: fault("Missing vector constructor lane slots")
                fields[i].executeTuple(frame, lanes, 0)
                layout.initializeVector(value, i, frame, lanes, 0)
                for (slot in lanes) frame.clear(slot)
            } else if (layout.isLong(i)) layout.initializeLong(value, i, fields[i].executeRequiredLong(frame))
            else if (layout.isFloat(i)) layout.initializeFloat(value, i, fields[i].executeRequiredFloat(frame))
            else if (layout.isDouble(i)) layout.initializeDouble(value, i, fields[i].executeRequiredDouble(frame))
            else layout.initialize(value, i, fields[i].execute(frame))
        }
        return value
    }
    override fun executeDataValue(frame: VirtualFrame): DataValue = execute(frame)
}
/** Compare the operand references themselves, including untouched or updated thunks. */
private class PointerEquality(@field:Child private var left: Expr, @field:Child private var right: Expr) : Expr() {
    init { representation = CoreRepresentation(CoreKind.LONG, evaluated = true) }
    override fun execute(frame: VirtualFrame): Long = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long = if (left.execute(frame) === right.execute(frame)) 1L else 0L
}
private class Primitive(private val name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = scalar64PrimitiveOperation(name)
    private val wordMask = narrowWordPrimitiveMask(name)
    private val intShift = narrowIntPrimitiveShift(name)
    private val bitShift = scalarBitPrimitiveShift(name)
    private val bitMask = -1L ushr bitShift
    init {
        representation = CoreRepresentation(CoreKind.LONG, evaluated = true)
        val arity = when (operation) {
            "popCnt8#", "popCnt16#", "popCnt32#", "popCnt64#" -> 1
            "clz8#", "clz16#", "clz32#", "clz64#" -> 1
            "ctz8#", "ctz16#", "ctz32#", "ctz64#" -> 1
            "byteSwap16#", "byteSwap32#", "byteSwap64#", "byteSwap#" -> 1
            "bitReverse8#", "bitReverse16#", "bitReverse32#", "bitReverse64#", "bitReverse#" -> 1
            "pdep8#", "pdep16#", "pdep32#", "pdep64#", "pdep#",
            "pext8#", "pext16#", "pext32#", "pext64#", "pext#" -> 2

            "negateInt8#", "negateInt16#", "negateInt32#" -> 1
            "plusInt8#", "plusInt16#", "plusInt32#" -> 2
            "subInt8#", "subInt16#", "subInt32#" -> 2
            "timesInt8#", "timesInt16#", "timesInt32#" -> 2
            "quotInt8#", "quotInt16#", "quotInt32#" -> 2
            "remInt8#", "remInt16#", "remInt32#" -> 2
            "eqInt8#", "eqInt16#", "eqInt32#" -> 2
            "neInt8#", "neInt16#", "neInt32#" -> 2
            "ltInt8#", "ltInt16#", "ltInt32#" -> 2
            "leInt8#", "leInt16#", "leInt32#" -> 2
            "gtInt8#", "gtInt16#", "gtInt32#" -> 2
            "geInt8#", "geInt16#", "geInt32#" -> 2
            "uncheckedShiftLInt8#", "uncheckedShiftLInt16#", "uncheckedShiftLInt32#",
            "uncheckedShiftRAInt8#", "uncheckedShiftRAInt16#", "uncheckedShiftRAInt32#" -> 2

            "quotWord#" -> 2
            "remWord#" -> 2
            "gtWord#" -> 2
            "geWord#" -> 2
            "quotWord8#", "quotWord16#", "quotWord32#" -> 2
            "remWord8#", "remWord16#", "remWord32#" -> 2
            "eqWord8#", "eqWord16#", "eqWord32#" -> 2
            "neWord8#", "neWord16#", "neWord32#" -> 2
            "gtWord8#", "gtWord16#", "gtWord32#" -> 2
            "geWord8#", "geWord16#", "geWord32#" -> 2
            "andWord8#", "andWord16#", "andWord32#" -> 2
            "orWord8#", "orWord16#", "orWord32#" -> 2
            "xorWord8#", "xorWord16#", "xorWord32#" -> 2
            "notWord8#", "notWord16#", "notWord32#" -> 1
            "uncheckedShiftLWord8#", "uncheckedShiftLWord16#", "uncheckedShiftLWord32#" -> 2
            "uncheckedShiftRLWord8#", "uncheckedShiftRLWord16#", "uncheckedShiftRLWord32#" -> 2
            "negateInt#", "not#", "notI#", "clz#", "ctz#", "popCnt#", "int2Word#", "word2Int#", "ord#", "chr#",
            "narrow8Int#", "narrow16Int#", "narrow32Int#", "intToInt64#", "int64ToInt#",
            "intToInt8#", "int8ToInt#", "intToInt16#", "int16ToInt#", "intToInt32#", "int32ToInt#",
            "int8ToWord8#", "word8ToInt8#", "int16ToWord16#", "word16ToInt16#", "int32ToWord32#", "word32ToInt32#",
            "wordToWord8#", "word8ToWord#", "wordToWord16#", "word16ToWord#", "wordToWord32#", "word32ToWord#",
            "narrow8Word#", "narrow16Word#", "narrow32Word#" -> 1
            "+#", "plusWord#", "-#", "minusWord#", "*#", "timesWord#", "quotInt#", "remInt#",
            "==#", "eqWord#", "eqChar#", "/=#", "neWord#", "neChar#", "<#", "ltWord#", "ltChar#", "<=#", "leWord#", "leChar#",
            ">#", "gtChar#", ">=#", "geChar#", "and#", "andI#", "or#", "orI#", "xor#", "xorI#",
            "uncheckedIShiftL#", "uncheckedShiftL#", "uncheckedIShiftRA#", "uncheckedIShiftRL#", "uncheckedShiftRL#",
            "plusWord8#", "subWord8#", "timesWord8#", "ltWord8#", "leWord8#",
            "plusWord16#", "subWord16#", "timesWord16#", "ltWord16#", "leWord16#",
            "plusWord32#", "subWord32#", "timesWord32#", "ltWord32#", "leWord32#" -> 2
            else -> throw UnsupportedCore("Unsupported primitive $name")
        }
        if (arguments.size != arity) throw RuntimeFault("Primitive arity mismatch: $name")
    }
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val x = arguments[0].executeRequiredLong(frame)
        val y = if (arguments.size == 2) arguments[1].executeRequiredLong(frame) else 0L
        fun b(value: Boolean) = if (value) 1L else 0L
        return when (operation) {
            "popCnt8#", "popCnt16#", "popCnt32#", "popCnt64#" -> java.lang.Long.bitCount(x and bitMask).toLong()
            "clz8#", "clz16#", "clz32#", "clz64#" -> (java.lang.Long.numberOfLeadingZeros(x and bitMask) - bitShift).toLong()
            "ctz8#", "ctz16#", "ctz32#", "ctz64#" -> minOf(java.lang.Long.numberOfTrailingZeros(x and bitMask), 64 - bitShift).toLong()
            "byteSwap16#", "byteSwap32#", "byteSwap64#", "byteSwap#" -> java.lang.Long.reverseBytes(x) ushr bitShift
            "bitReverse8#", "bitReverse16#", "bitReverse32#", "bitReverse64#", "bitReverse#" -> java.lang.Long.reverse(x) ushr bitShift
            "pdep8#", "pdep16#", "pdep32#", "pdep64#", "pdep#" ->
                java.lang.Long.expand(x and bitMask, y and bitMask) and bitMask
            "pext8#", "pext16#", "pext32#", "pext64#", "pext#" ->
                java.lang.Long.compress(x and bitMask, y and bitMask) and bitMask
            "negateInt8#", "negateInt16#", "negateInt32#" -> signedNarrow(-x, intShift)
            "plusInt8#", "plusInt16#", "plusInt32#" -> signedNarrow(x + y, intShift)
            "subInt8#", "subInt16#", "subInt32#" -> signedNarrow(x - y, intShift)
            "timesInt8#", "timesInt16#", "timesInt32#" -> signedNarrow(x * y, intShift)
            "quotInt8#", "quotInt16#", "quotInt32#" -> signedNarrow(signedNarrow(x, intShift) / signedNarrow(y, intShift), intShift)
            "remInt8#", "remInt16#", "remInt32#" -> signedNarrow(signedNarrow(x, intShift) % signedNarrow(y, intShift), intShift)
            "eqInt8#", "eqInt16#", "eqInt32#" -> b(signedNarrow(x, intShift) == signedNarrow(y, intShift))
            "neInt8#", "neInt16#", "neInt32#" -> b(signedNarrow(x, intShift) != signedNarrow(y, intShift))
            "ltInt8#", "ltInt16#", "ltInt32#" -> b(signedNarrow(x, intShift) < signedNarrow(y, intShift))
            "leInt8#", "leInt16#", "leInt32#" -> b(signedNarrow(x, intShift) <= signedNarrow(y, intShift))
            "gtInt8#", "gtInt16#", "gtInt32#" -> b(signedNarrow(x, intShift) > signedNarrow(y, intShift))
            "geInt8#", "geInt16#", "geInt32#" -> b(signedNarrow(x, intShift) >= signedNarrow(y, intShift))
            "uncheckedShiftLInt8#", "uncheckedShiftLInt16#", "uncheckedShiftLInt32#" ->
                signedNarrow(x shl y.toInt(), intShift)
            "uncheckedShiftRAInt8#", "uncheckedShiftRAInt16#", "uncheckedShiftRAInt32#" ->
                signedNarrow(x, intShift) shr y.toInt()

            "quotWord#" -> java.lang.Long.divideUnsigned(x, y)
            "remWord#" -> java.lang.Long.remainderUnsigned(x, y)
            "gtWord#" -> b(java.lang.Long.compareUnsigned(x, y) > 0)
            "geWord#" -> b(java.lang.Long.compareUnsigned(x, y) >= 0)
            "quotWord8#", "quotWord16#", "quotWord32#" -> (x and wordMask) / (y and wordMask)
            "remWord8#", "remWord16#", "remWord32#" -> (x and wordMask) % (y and wordMask)
            "eqWord8#", "eqWord16#", "eqWord32#" -> b((x and wordMask) == (y and wordMask))
            "neWord8#", "neWord16#", "neWord32#" -> b((x and wordMask) != (y and wordMask))
            "gtWord8#", "gtWord16#", "gtWord32#" -> b((x and wordMask) > (y and wordMask))
            "geWord8#", "geWord16#", "geWord32#" -> b((x and wordMask) >= (y and wordMask))
            "andWord8#", "andWord16#", "andWord32#" -> (x and y) and wordMask
            "orWord8#", "orWord16#", "orWord32#" -> (x or y) and wordMask
            "xorWord8#", "xorWord16#", "xorWord32#" -> (x xor y) and wordMask
            "notWord8#", "notWord16#", "notWord32#" -> x.inv() and wordMask
            "uncheckedShiftLWord8#", "uncheckedShiftLWord16#", "uncheckedShiftLWord32#" -> (x shl y.toInt()) and wordMask
            "uncheckedShiftRLWord8#", "uncheckedShiftRLWord16#", "uncheckedShiftRLWord32#" -> (x and wordMask) ushr y.toInt()
            "+#", "plusWord#" -> x + y
            "-#", "minusWord#" -> x - y
            "*#", "timesWord#" -> x * y
            "plusWord8#", "plusWord16#", "plusWord32#" -> (x + y) and wordMask
            "subWord8#", "subWord16#", "subWord32#" -> (x - y) and wordMask
            "timesWord8#", "timesWord16#", "timesWord32#" -> (x * y) and wordMask
            "negateInt#" -> -x
            "quotInt#" -> x / y
            "remInt#" -> x % y
            "==#", "eqWord#", "eqChar#" -> b(x == y)
            "/=#", "neWord#", "neChar#" -> b(x != y)
            "<#", "ltChar#" -> b(x < y)
            "ltWord#" -> b(java.lang.Long.compareUnsigned(x, y) < 0)
            "<=#", "leChar#" -> b(x <= y)
            "leWord#" -> b(java.lang.Long.compareUnsigned(x, y) <= 0)
            // These masks fit below Long's sign bit, so signed order is unsigned order.
            "ltWord8#", "ltWord16#", "ltWord32#" -> b((x and wordMask) < (y and wordMask))
            "leWord8#", "leWord16#", "leWord32#" -> b((x and wordMask) <= (y and wordMask))
            ">#", "gtChar#" -> b(x > y)
            ">=#", "geChar#" -> b(x >= y)
            "and#", "andI#" -> x and y
            "or#", "orI#" -> x or y
            "xor#", "xorI#" -> x xor y
            "not#", "notI#" -> x.inv()
            "clz#" -> java.lang.Long.numberOfLeadingZeros(x).toLong()
            "ctz#" -> java.lang.Long.numberOfTrailingZeros(x).toLong()
            "popCnt#" -> java.lang.Long.bitCount(x).toLong()
            "uncheckedIShiftL#", "uncheckedShiftL#" -> x shl y.toInt()
            "uncheckedIShiftRA#" -> x shr y.toInt()
            "uncheckedIShiftRL#", "uncheckedShiftRL#" -> x ushr y.toInt()
            // Narrow signed values use sign-normalized Long carriers. Truncation
            // and signed widening therefore share the width-specific conversion.
            "narrow8Int#", "intToInt8#", "int8ToInt#", "word8ToInt8#" -> x.toByte().toLong()
            "narrow16Int#", "intToInt16#", "int16ToInt#", "word16ToInt16#" -> x.toShort().toLong()
            "narrow32Int#", "intToInt32#", "int32ToInt#", "word32ToInt32#" -> x.toInt().toLong()
            "wordToWord8#", "word8ToWord#", "int8ToWord8#", "wordToWord16#", "word16ToWord#", "int16ToWord16#",
            "wordToWord32#", "word32ToWord#", "int32ToWord32#",
            "narrow8Word#", "narrow16Word#", "narrow32Word#" -> x and wordMask
            "int2Word#", "word2Int#", "ord#", "chr#", "intToInt64#", "int64ToInt#" -> x
            else -> fault("Unsupported primitive")
        }
    }
}
private class FunctionBody(expression: Expr, metrics: Metrics, result: CoreRepresentation,
    private val tuple: TupleShape?, @field:CompilationFinal(dimensions = 1) private val tupleSlots: IntArray) : Node() {
    @Child private var value = Evaluate(expression, metrics)
    private val resultKind = if (result.kind == CoreKind.UNKNOWN) expression.representation.kind else result.kind
    private val exactLong = resultKind == CoreKind.LONG
    @CompilationFinal private var genericResult = result.present && !exactLong

    /** Keep a primitive body until the mandatory Object-returning root/call boundary. */
    fun execute(frame: VirtualFrame): Any? {
        val shape = tuple
        if (shape != null) {
            try { value.executeTuple(frame, tupleSlots, 0) }
            catch (cut: AstCapture) {
                throw cut.append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                        if (input != null) fault("Invalid AST tuple resume value")
                        return shape.finish(frame, tupleSlots)
                    }
                })
            }
            return shape.finish(frame, tupleSlots)
        }
        // Kotlin's enum when uses a mutable synthetic int[] mapping. Graal
        // cannot fold that lookup, even when this node's resultKind is constant.
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
}
private class SelfRepeater(@field:Child private var body: FunctionBody, private val metrics: Metrics) : Node(), RepeatingNode {
    fun once(frame: VirtualFrame): Any? {
        val root = rootNode as FunctionRoot
        root.pollBeforeBody(this)
        val entry = root.handoff
        return if (entry != null && entry.resultLong && entry.destination(frame) >= 0) entry.finishLong(frame, body.executeLong(frame))
        else body.execute(frame)
    }
    override fun executeRepeating(frame: VirtualFrame): Boolean = error("value loop")
    override fun executeRepeatingWithValue(frame: VirtualFrame): Any? = try { once(frame) }
    catch (_: AstSelfCall) {
        if (metrics.enabled) metrics.incrementSelfTailReentries()
        RepeatingNode.CONTINUE_LOOP_STATUS
    }
    catch (tail: HandoffTailCall) {
        val root = rootNode as FunctionRoot
        if (!root.isSelf(tail.target)) throw tail
        if (metrics.enabled) metrics.incrementSelfTailReentries()
        root.restoreHandoff(frame, tail.arguments, false)
        RepeatingNode.CONTINUE_LOOP_STATUS
    }
    catch (tail: TailCall) {
        val root = rootNode as FunctionRoot
        if (!root.isSelf(tail.target)) throw tail
        if (metrics.enabled) metrics.incrementSelfTailReentries()
        root.restoreTail(frame, tail)
        RepeatingNode.CONTINUE_LOOP_STATUS
    }
}
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
                            private val enableAsync: Boolean = false,
                            @field:CompilationFinal(dimensions = 2) private val environmentVectorSlots: Array<IntArray?> = emptyArray()) : GuestRoot(language, descriptor) {
    init { configureEntry(entryStrict, captureLayout != null); configureInput(inputLayout); configureTupleResult(tuple) }
    @field:CompilationFinal(dimensions = 1)
    private val argumentReferences = argumentProofs.map { it.referenceCarrier() }.toTypedArray()
    @field:CompilationFinal private var hasSelfTail = false
    private val tailCallProfile = BranchProfile.create()
    @Child private var loop: LoopNode = Truffle.getRuntime().createLoopNode(SelfRepeater(FunctionBody(body, metrics, resultProof, tuple, tupleSlots), metrics))
    override fun bloom(frame: VirtualFrame): Long = frame.getLong(FrameLayout.BLOOM_FILTER)
    @ExplodeLoop fun buildFrame(arguments: Array<Any?>, frame: VirtualFrame) {
        val offset = if (captureLayout == null) 1 else 2
        for (i in argumentSlots.indices) {
            val value = arguments[argumentIndices[i] + offset]
            val reference = argumentReferences.getOrNull(i)
            if (reference != null)
                FrameAccess.write(frame, argumentSlots[i], requireReferenceCarrier(value, reference))
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

    private class ResumeBody(private val root: FunctionRoot) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input !== Unit) fault("Invalid AST root poll resume value")
            return root.executeBody(frame)
        }
    }

    fun pollBeforeBody(node: Node) {
        if (!enableAsync) return
        val request = GuestThreads.pollCurrent(node, false) ?: return
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
    @ExplodeLoop private fun restoreTypedInput(frame: VirtualFrame, input: HandoffStorage, initial: Boolean) {
        val entry = typedInput ?: fault("Target does not support typed tuple inputs")
        try {
            if (initial) entry.validate(input) else entry.validateTail(input)
            if (initial) frame.setLong(FrameLayout.BLOOM_FILTER, entry.packet.getLong(input, 0) or mask)
            for (i in argumentSlots.indices) {
                val from = argumentIndices[i] + entry.header
                val to = argumentSlots[i]
                if (entry.packet.isLong(from)) FrameAccess.writeLong(frame, to, entry.packet.getLong(input, from))
                else if (entry.packet.isFloat(from)) FrameAccess.writeFloat(frame, to, entry.packet.getFloat(input, from))
                else if (entry.packet.isDouble(from)) FrameAccess.writeDouble(frame, to, entry.packet.getDouble(input, from))
                else {
                    val value = entry.packet.getObject(input, from)
                    val expected = argumentReferences.getOrNull(i)
                    writeInputReference(frame, to, if (expected == null) value else requireReferenceCarrier(value, expected))
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
                frame.setLong(FrameLayout.BLOOM_FILTER, entry.arguments.getLong(input, 0) or mask)
            }
            val offset = if (captureLayout == null) 1 else 2
            for (i in argumentSlots.indices) {
                val position = argumentIndices[i] + offset
                if (entry.arguments.isLong(position)) FrameAccess.writeLong(frame, argumentSlots[i], entry.arguments.getLong(input, position))
                else {
                    val value = entry.arguments.getObject(input, position)
                    val reference = argumentReferences.getOrNull(i)
                    FrameAccess.write(frame, argumentSlots[i], if (reference != null) requireReferenceCarrier(value, reference) else value)
                }
            }
            if (captureLayout != null) {
                val environment = entry.arguments.getObject(input, 1) as? CapturedFrame ?: fault("Invalid captured frame")
                restoreCaptured(frame, environment)
            }
        } finally { entry.state().arguments.release(input, entry.arguments) }
    }

    override fun execute(frame: VirtualFrame): Any? {
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
            frame.setLong(FrameLayout.BLOOM_FILTER, (frame.arguments[0] as? Long ?: fault("Invalid bloom argument")) or mask)
            buildFrame(frame.arguments, frame)
        }
        return try { executeBody(frame) }
        catch (cut: AstCapture) {
            // Continuations own a real frame only on the interrupted slow path.
            // Keep its escape out of ordinary compiled calls and foreign-call edges.
            CompilerDirectives.transferToInterpreter()
            cut.freeze(this, frame.materialize())
        }
    }

    private fun executeBody(frame: VirtualFrame): Any? {
        // Non-looping roots retain entry argument facts. Once self recursion
        // is observed, PE selects only the loop body instead of duplicating it.
        if (hasSelfTail) return loop.execute(frame)
        return try { (loop.repeatingNode as SelfRepeater).once(frame) }
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
             entry: BooleanArray? = null, arityCertificate: CoreApplicationCertificates.Arity? = null): Local =
        Local(layout.bind(id), if (proof.present) proof.isLong else primitive, proof, cell, entry,
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
 * Async AST admission is opt-in for internal proofs; Language.parse keeps it disabled
 * until every public closure/call boundary can consume a saved continuation.
 */
class Program(private val language: TruffleLanguage<*>?, moduleData: Map<String, Any?>,
              private val enableAsync: Boolean = false) : ExecutableProgram {
    init { thc.CoreForeignArtifacts.requireExecutableInput(moduleData) }
    private val foreignLinks = moduleData["foreignLinks"] as? List<thc.ForeignBitcode> ?: emptyList()
    private val stackTargetLayout = moduleData["targetLayout"]
    private val callDemandsEnabled = java.lang.Boolean.getBoolean(CALL_DEMANDS_PROPERTY)
    private val metrics = Metrics(moduleData["instrument"] != false)
    private val sources = CoreSources(moduleData)
    private var currentSource: CoreSourceLocation? = null
    private var attachedRootCount = 0
    private fun <T> withSource(location: CoreSourceLocation?, action: () -> T): T {
        val previous = currentSource
        currentSource = location
        return try { action() } finally { currentSource = previous }
    }
    private fun rootSource(body: Expr): CoreSourceLocation? = (body.coreSourceLocation ?: currentSource).also {
        if (it != null) attachedRootCount++
    }
    private val diagnosticUnsupported = moduleData["diagnosticUnsupported"] == true
    private val deferredUnsupported = linkedSetOf<String>()
    private val bindings = moduleData["bindings"] as? List<Map<String, Any?>> ?: throw RuntimeFault("Missing bindings")
    private val constructors = (moduleData["constructors"] as? List<Map<String, Any?>> ?: emptyList()).associateBy { it["id"] as String }
    private val dataLayouts = mutableMapOf<String, DataLayout>()
    private val globals = bindings.associate { it["id"] as String to GlobalBinding(it["name"] as String) }
    private val globalProofs = bindings.associate { binding ->
        val expression = binding["expr"] as List<Any?>
        val delayed = representation(binding) && expression[0] !in listOf("lam", "lit", "con", "void")
        (binding["id"] as String) to if (diagnosticUnsupported) CoreRepresentation.UNKNOWN
            else CoreRepresentations.binder(binding).copy(evaluated = !delayed && expression[0] in listOf("lam", "lit", "con", "void"))
    }
    private val indices = bindings.withIndex().associate { it.value["id"] as String to it.index }
    private val names = bindings.withIndex().groupBy({ it.value["name"] as String }, { it.index })
    private val hostEntries = mutableMapOf<Int, RootCallTarget>()
    private val globalEntries = bindings.associate { it["id"] as String to CoreEntries.binding(it) }
    private val globalArityCertificates = bindings.associate { it["id"] as String to CoreApplicationCertificates.binding(it) }
    init {
        if (enableAsync) AstAsyncAdmission.validate(bindings)
        ArrayOp.validateApplications(bindings)
        CoreStackForeign.validateHeads(bindings)
        CoreStackInfoForeign.validateHeads(bindings)
        CoreOriginalStdio.validateHeads(bindings)
        CoreStablePointers.validateHeads(bindings)
        CoreMainThreadForeign.validateHeads(bindings)
        CoreBoundThreadForeign.validateHeads(bindings)
        CoreManagedFiles.validateHeads(bindings)
        CoreMd5Foreign.validateHeads(bindings)
        CoreGmpForeign.validateHeads(bindings)
        CoreLibdwForeign.validateHeads(bindings)
        if (!diagnosticUnsupported) {
            CoreRepresentations.validateAggregates(bindings, constructors)
            CoreInputCalls.validate(bindings, constructors)
        }
        val scope = Scope(FrameLayout())
        val initializers = bindings.map { binding ->
            CoreRepresentations.requireNoSum(CoreRepresentations.binder(binding), "global binding")
            withSource(sources.binding(binding)) {
                val expr = binding["expr"] as List<Any?>
                CoreRepresentations.requireNoSum(CoreRepresentations.expression(expr), "global binding")
                if (representation(binding) && expr[0] !in listOf("lam", "lit", "con", "void")) delay(expr, scope, binding["name"] as String)
                else argument(expr, scope, representation(binding), binding["name"] as String)
            }
        }
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), scope.layout.build())
        bindings.forEachIndexed { index, binding ->
            val value = initializers[index].execute(frame)
            CoreFunctionIdentity.install(moduleData, binding, value, globalArityCertificates)
            globals.getValue(binding["id"] as String).initialize(value)
        }
    }
    private fun bindingIndex(name: String): Int = indices[name] ?: names[name]?.singleOrNull()
        ?: names.entries.singleOrNull { it.key.substringAfterLast('.') == name }?.value?.singleOrNull()
        ?: throw RuntimeFault("Unknown or ambiguous entry $name")
    @Synchronized override fun hostEntryTarget(arity: Int): RootCallTarget =
        hostEntries.getOrPut(arity) { EntryRoot(language, arity, metrics).callTarget }
    override fun entryValue(name: String): Any? = globals.getValue(bindings[bindingIndex(name)]["id"] as String).read()
    override fun constructorLayout(id: String): DataLayout = dataLayout(id)
    override fun entryTarget(name: String): RootCallTarget {
        var value = entryValue(name)
        while (value is Thunk && value.state == 2) value = value.value
        return when (value) { is Closure -> value.target; is Thunk -> value.target ?: hostEntryTarget(0); else -> hostEntryTarget(0) }
    }
    override fun diagnostics(): Map<String, Any> = linkedMapOf(
        "backend" to "ast", "sourceNotesEnabled" to sources.enabled, "sourceSpanCount" to sources.spanCount,
        "sourceRootCount" to attachedRootCount, "instrumented" to metrics.enabled, "thunkEvaluationsByLabel" to metrics.thunkCountsSnapshot(),
        "compiledEntries" to metrics.compiledEntries, "leadingCaseReturns" to metrics.leadingCaseReturns, "thunkEvaluations" to metrics.thunkEvaluations,
        "thunkHits" to metrics.thunkHits, "blackholes" to metrics.blackholes, "directCacheMisses" to metrics.directCacheMisses,
        "indirectCalls" to metrics.indirectCalls, "tailBounces" to metrics.tailBounces, "papAllocations" to metrics.papAllocations,
        "localJoinTransfers" to metrics.localJoinTransfers,
        "selfTailReentries" to metrics.selfTailReentries, "trampolineIterations" to metrics.trampolineIterations,
        "unsupportedPolicy" to (if (diagnosticUnsupported) "diagnostic-traps" else "reject-at-load"),
        "deferredUnsupported" to deferredUnsupported.toList(), "unsupportedTraps" to metrics.unsupportedTraps,
        "frames" to "indexed primitive slots; selective StaticShape captures",
        "stackPolicy" to "tail-safe; non-tail calls and nested thunk forcing use host stack", "threadPolicy" to "single guest thread")
    private fun representation(binding: Map<String, Any?>): Boolean = binding["lifted"] as? Boolean
        ?: throw UnsupportedCore("Unknown levity for ${binding["id"]}")
    private fun freeVariables(expr: List<Any?>): Set<String> = when (expr[0]) {
        "var" -> setOf(expr[1] as String)
        "lam" -> freeVariables(expr[2] as List<Any?>) - (expr[1] as List<Map<String, Any?>>).map { it["id"] as String }.toSet()
        "app" -> freeVariables(expr[1] as List<Any?>) + (expr[2] as List<List<Any?>>).flatMap { freeVariables(it) }
        "let" -> {
            val group = expr[2] as List<Map<String, Any?>>
            val ids = group.map { it["id"] as String }.toSet()
            val rhs = group.flatMap { freeVariables(it["expr"] as List<Any?>) }.toSet()
            (if (expr[1] == true) rhs - ids else rhs) + (freeVariables(expr[3] as List<Any?>) - ids)
        }
        "case" -> freeVariables(expr[1] as List<Any?>) + (expr[3] as List<List<Any?>>).flatMap {
            freeVariables(it[3] as List<Any?>) - (it[2] as List<String>).toSet() - (expr[2] as String)
        }
        else -> emptySet()
    }
    private fun function(label: String, args: List<Map<String, Any?>>, expression: List<Any?>, outer: Scope,
                         resultProof: CoreRepresentation = CoreRepresentations.expression(expression),
                         entryStrict: BooleanArray = BooleanArray(args.size)): FunctionSpec {
        if (entryStrict.size != args.size) throw RuntimeFault("Function entry contract arity mismatch")
        val scope = Scope(FrameLayout())
        val free = freeVariables(expression)
        val argumentIds = args.map { it["id"] as String }.toSet()
        args.forEach { CoreRepresentations.requireInput(CoreRepresentations.binder(it)) }
        val inputLayout = ArgumentLayout.fromProofs(args.map(CoreRepresentations::binder))
        val freeLocals = (free - argumentIds).filter { it in outer.locals }
        freeLocals.filter { outer.locals.getValue(it).let { local -> local.slot < 0 && local.proof.kind == CoreKind.VOID } }
            .forEach { scope.bindVoid(it, outer.locals.getValue(it).proof) }
        val captured = freeLocals.filter { outer.locals.getValue(it).let { local -> local.slot >= 0 || local.proof.kind != CoreKind.VOID } }
        captured.forEach { id ->
            val local = outer.locals.getValue(id)
            if (local.proof.isVector) {
                CoreRepresentations.requireInput(local.proof)
                if (local.cell || local.tupleSlots?.size != local.proof.vector!!.lanes)
                    throw UnsupportedCore("Vector capture requires primitive lane locals")
            } else CoreRepresentations.requireScalar(local.proof, "capture")
        }
        val captureSources = captured.flatMap { id ->
            val local = outer.locals.getValue(id)
            if (local.proof.isVector) local.tupleSlots!!.toList() else listOf(local.slot)
        }.toIntArray()
        val captureKinds = captured.map { id ->
            val local = outer.locals.getValue(id)
            !local.proof.isVector && local.primitive
        }.toBooleanArray()
        val environmentVectorSlots = arrayOfNulls<IntArray>(captured.size)
        val environmentSlots = captured.mapIndexed { index, id ->
            val local = outer.locals.getValue(id)
            if (local.proof.isVector) {
                val lanes = IntArray(local.proof.vector!!.lanes) { lane -> scope.layout.bind("$id captured vector lane $lane") }
                environmentVectorSlots[index] = lanes
                scope.bindTuple(id, local.proof, lanes).slot
            } else scope.bind(id, local.primitive, local.proof, local.cell, local.entry, local.arityCertificate).slot
        }.toIntArray()
        val argumentSlots = arrayListOf<Int>(); val argumentIndices = arrayListOf<Int>()
        val argumentProofs = arrayListOf<CoreRepresentation>()
        for ((index, arg) in args.withIndex()) {
            val lifted = representation(arg)
            val proof = CoreRepresentations.binder(arg).let { if (lifted) it.copy(evaluated = entryStrict[index]) else it }
            if (proof.isTypedTransport) {
                if (lifted) throw RuntimeFault("Typed formal cannot be lifted")
                val fields = TupleShape.flatten(proof)
                val slots = IntArray(fields.size) { leaf -> scope.layout.bind("${arg["id"]} typed input $leaf") }
                scope.bindTuple(arg["id"] as String, proof, slots)
                fields.forEachIndexed { leaf, field ->
                    argumentIndices += ArgumentLayout.offset(inputLayout, index) + leaf
                    argumentProofs += field
                    argumentSlots += slots[leaf]
                }
            } else if (arg["id"] in free) {
                argumentIndices += ArgumentLayout.offset(inputLayout, index); argumentProofs += proof
                argumentSlots += scope.bind(arg["id"] as String, !lifted && arg["coercion"] != true, proof).slot
            }
        }
        val captures = if (captured.isEmpty()) null else CaptureLayout.withVectors(requireNotNull(language),
            captured.map { id -> outer.locals.getValue(id).proof.takeIf { it.isVector } }.toTypedArray(), captureKinds,
            captured.map { outer.locals.getValue(it).let { local -> !local.cell && local.proof.isLong && local.proof.evaluated } }.toBooleanArray(),
            captured.map { outer.locals.getValue(it).let { local -> if (local.cell) null else local.proof.referenceCarrier() } }.toTypedArray(),
            captured.map { outer.locals.getValue(it).let { local -> !local.cell && local.proof.isFloat && local.proof.evaluated } }.toBooleanArray(),
            captured.map { outer.locals.getValue(it).let { local -> !local.cell && local.proof.isDouble && local.proof.evaluated } }.toBooleanArray())
        val allArgumentSlots = IntArray(args.size) { -1 }
        val allArgumentProofs = Array(args.size) { CoreRepresentation.UNKNOWN }
        args.forEachIndexed { index, arg ->
            scope.locals[arg["id"]]?.takeIf { !it.proof.isTypedTransport }?.let { local ->
                allArgumentSlots[index] = local.slot
                allArgumentProofs[index] = local.proof
            }
        }
        scope.self = AstSelfLayout(captures, environmentSlots, allArgumentSlots, allArgumentProofs,
            entryStrict.copyOf(), inputLayout, environmentVectorSlots)
        val body = compile(expression, scope, true)
        // Async AST has no caller capture around a typed handoff loan yet.
        // Keep admitted roots on the ordinary scalar call ABI.
        val handoff = if (enableAsync) null else HandoffEntry.create(language, scope.layout,
            args.map(CoreRepresentations::binder), resultProof, captures != null)
        if ((body.representation.isSum || resultProof.isSum) && (!body.representation.isSum || !resultProof.isSum))
            throw RuntimeFault("Sum function requires exact body and declared result proofs")
        val effectiveResult = body.representation.refine(resultProof)
        val tuple = if (effectiveResult.isTypedTransport) TupleShape(effectiveResult, language as thc.Language) else null
        val tupleSlots = IntArray(tuple?.width ?: 0) { scope.layout.bind("<typed return $it>") }
        val root = FunctionRoot(language, scope.layout.build(), label, captures, environmentSlots,
            argumentSlots.toIntArray(), argumentIndices.toIntArray(), body, metrics, argumentProofs.toTypedArray(), resultProof,
            rootSource(body), entryStrict, handoff, tuple, tupleSlots, inputLayout, enableAsync, environmentVectorSlots)
        if (language is thc.Language) root.configureTypedInput(TypedInputLayout.create(language, inputLayout, captures != null))
        if (body is Case && inputLayout == null) root.configureLeadingCaseReturn(LeadingCaseReturn.discover(args, expression,
            resultProof, root.entryArgumentOffset, free.intersect(argumentIds), captures != null,
            ::dataLayout, sources, body.coreSourceLocation))
        return FunctionSpec(root.callTarget, captures, captureSources)
    }
    private fun delay(expr: List<Any?>, scope: Scope, label: String): Expr {
        val fn = function(label, emptyList(), expr, scope)
        (fn.target.rootNode as GuestRoot).tupleResult?.let { CoreRepresentations.requireScalar(it.proof, "thunk") }
        return Delay(fn.target, fn.captureLayout, fn.captures).proven(CoreRepresentations.expression(expr).copy(evaluated = false))
            .located(sources.expression(expr, currentSource))
    }
    private fun argument(expr: List<Any?>, scope: Scope, lifted: Boolean, label: String = "argument thunk", allowEmpty: Boolean = false, declaredLifted: Boolean = lifted): Expr {
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
        if (CoreApplicationCertificates.eagerApplication(expr, arityCertificate))
            return compile(expr, scope, false).also { check(it.representation) }
        return when (expr[0]) { "var", "lit", "lam", "con", "prim", "void" -> compile(expr, scope, false); else -> delay(expr, scope, label) }.also { check(it.representation) }
    }
    private fun literal(kind: String, value: String, proof: CoreRepresentation? = null): Any = when (kind) {
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
        "bignat" -> BigNatLiterals.decode(value)
        else -> throw UnsupportedCore("Unsupported literal kind $kind")
    }
    private fun compile(expr: List<Any?>, scope: Scope, tail: Boolean): Expr =
        withSource(sources.expression(expr, currentSource)) { compileLocated(expr, scope, tail).located(currentSource) }
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
            intArrayOf(), intArrayOf(), intArrayOf(), body, metrics, coreSourceLocation = rootSource(body)).callTarget
        DiagnosticUnavailable(target, message, metrics)
    }
    private fun compileSupported(expr: List<Any?>, scope: Scope, tail: Boolean): Expr = when (expr[0]) {
        "var" -> {
            val id = expr[1] as String
            CoreVectors.requireVariableProof(scope.locals[id]?.proof ?: globalProofs[id], CoreRepresentations.expression(expr))
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
                ?: globals[id]?.let { GlobalRead(it).proven(globalProofs.getValue(id)) }
                ?: throw UnsupportedCore("Unresolved external binding $id")
        }
        "lit" -> Literal(literal(expr[1] as String, expr[2] as String, CoreRepresentations.expression(expr))).let {
            if (expr[1] in listOf("int8", "word8", "int16", "word16", "int32", "word32")) it.proven(CoreRepresentations.narrowLiteralProof(expr))
            else if (expr[1] == "bignat") it.proven(BigNatLiterals.proof(expr)) else it
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
            val defined = fn[0] == "var" && (fn[1] in globals || fn[1] in scope.locals)
            val stackClone = CoreStackForeign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags)
            val stackInfo = CoreStackInfoForeign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val originalStdio = CoreOriginalStdio.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val capi = CoreCapiForeign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags,
                CoreRepresentations.metadata(expr)?.get("rep"), foreignLinks)
            val stableFree = CoreStablePointers.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val sharedCAF = CoreSharedCAFStores.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val mainThreadForeign = CoreMainThreadForeign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val boundThreadForeign = CoreBoundThreadForeign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val managedFile = CoreManagedFiles.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val javascript = if (!stackClone && stackInfo == null && originalStdio == null && managedFile == null) CoreJavaScript.validate(expr, defined) else null
            val md5 = if (javascript == null) CoreMd5Foreign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep")) else null
            val gmp = CoreGmpForeign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val libdw = CoreLibdwForeign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val polyglot = if (!stackClone && stackInfo == null && originalStdio == null && capi == null &&
                !stableFree && !mainThreadForeign && !boundThreadForeign && sharedCAF == null && managedFile == null && javascript == null && md5 == null && gmp == null && libdw == null)
                CorePolyglot.validate(expr, defined) else null
            if (stackClone) {
                CoreStackForeign.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                val state = args.single()
                CoreStackForeign.validateBinding(if (state[0] == "var")
                    scope.locals[state[1]]?.proof ?: globalProofs[state[1]] else null)
                val operand = compile(state, scope, false)
                CoreStackForeign.validateState(operand.representation)
                CloneStackExpression(operand, tupleProof)
            } else if (stackInfo != null) {
                val layout = CoreStackInfoForeign.requireLayout(stackTargetLayout)
                CoreStackInfoForeign.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreStackInfoForeign.validateOperand(stackInfo, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                OriginalStackInfoExpression(stackInfo, layout, operands.toTypedArray(), tupleProof)
            } else if (originalStdio != null) {
                CoreOriginalStdio.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        if (originalStdio == OriginalStdioOp.SIGPROCMASK || originalStdio.readiness || originalStdio.seekConstant || originalStdio.stat || originalStdio.termios || originalStdio.sigset || originalStdio.savedTermios || originalStdio.readImage || originalStdio == OriginalStdioOp.TCSETATTR || originalStdio == OriginalStdioOp.OPEN ||
                            originalStdio.iconv || originalStdio.strerror || originalStdio.duplication || originalStdio.locking)
                            CoreOriginalStdio.validateScalarOperand(originalStdio, index,
                            operand.representation, if (argument[0] == "var")
                                scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                OriginalStdioExpression(originalStdio, operands.toTypedArray(), tupleProof)
            } else if (capi != null) {
                CoreCapiForeign.validateHead(fn, defined)
                CapiExpression(capi, args.map { compile(it, scope, false) }.toTypedArray(), tupleProof)
            } else if (stableFree) {
                CoreStablePointers.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
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
            } else if (boundThreadForeign) {
                CoreBoundThreadForeign.validateHead(fn, defined)
                val argument = args.single()
                val state = compile(argument, scope, false)
                CoreBoundThreadForeign.validateOperand(state.representation, if (argument[0] == "var")
                    scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                BoundThreadSupport(state).proven(tupleProof.copy(evaluated = true))
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
                CoreManagedFiles.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                ManagedFileExpression(managedFile, args.map { compile(it, scope, false) }.toTypedArray(), tupleProof)
            } else if (libdw != null) {
                CoreLibdwForeign.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreLibdwForeign.validateOperand(libdw, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                OriginalLibdwExpression(libdw, operands.toTypedArray(), tupleProof)
            } else if (gmp != null) {
                CoreGmpForeign.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreGmpForeign.validateOperand(gmp, index, operand.representation,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                GmpForeignExpression(gmp, operands.toTypedArray(), tupleProof)
            } else if (md5 != null) {
                CoreMd5Foreign.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                Md5ForeignExpression(md5, args.map { compile(it, scope, false) }.toTypedArray(), tupleProof)
            } else if (javascript != null) {
                JavaScriptExpression(javascript, args.map { argument(it, scope, false) }.toTypedArray())
                    .proven(tupleProof.copy(evaluated = true))
            } else if (polyglot != null) {
                PolyglotExpression(polyglot, args.mapIndexed { index, value ->
                    argument(value, scope, flags[index] as Boolean)
                }.toTypedArray()).proven(tupleProof.copy(evaluated = true))
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
                when (name) {
                    in GeneratedVectors.operations -> GeneratedVectors.expression(name, operands) { count ->
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
                RaiseArithmeticException(operand, RaiseException(GlobalRead(payload)))
            } else if (fn[0] == "prim" && fn[1] in setOf("raiseIO#", "catch#", "getMaskingState#",
                    "unmaskAsyncExceptions#", "maskAsyncExceptions#", "maskUninterruptible#")) {
                val name = fn[1] as String
                CoreSynchronousExceptions.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                if (name == "raiseIO#") RaiseIOException(operands[0], operands[1], tupleProof)
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
            } else if (fn[0] == "prim" && fn[1] == "yield#") {
                CoreYield.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                YieldThread(argument(args[0], scope, false), enableAsync, tupleProof)
            } else if (fn[0] == "prim" && fn[1] in listOf("myThreadId#", "threadStatus#")) {
                val name = fn[1] as String
                CoreGuestThreads.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.map { argument(it, scope, false) }
                CoreGuestThreads.validate(name, operands.map { it.representation }, flags, tupleProof)
                if (name == "myThreadId#") MyThreadId(operands[0], tupleProof)
                else ThreadStatus(operands[0], operands[1], tupleProof)
            } else if (fn[0] == "prim" && fn[1] == "getCurrentCCS#") {
                CoreCurrentCCS.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                GetCurrentCCS(argument(args[0], scope, true), argument(args[1], scope, false), tupleProof)
            } else if (fn[0] == "prim" && MVarOp.named(fn[1] as String) != null) {
                val operation = MVarOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                operation.validateBindings(args.map(CoreRepresentations::expression), args.map {
                    if (it[0] == "var") scope.locals[it[1]]?.proof ?: globalProofs[it[1]] else null
                })
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                operation.validate(operands.map { it.representation }, flags, tupleProof)
                mVarExpression(operation, tupleProof, operands.toTypedArray(), enableAsync)
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
            } else if (fn[0] == "prim" && VectorByteArrayOp.named(fn[1] as String) != null) {
                val operation = VectorByteArrayOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                VectorByteArrayExpression(operation, args.map { compile(it, scope, false) }.toTypedArray())
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
            } else if (fn[0] == "prim" && FloatingAddressOp.named(fn[1] as String) != null) {
                val operation = FloatingAddressOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                FloatingAddressExpression(operation, tupleProof, args.map { compile(it, scope, false) }.toTypedArray())
            } else if (fn[0] == "prim" && PinnedMemoryOp.named(fn[1] as String) != null) {
                val operation = PinnedMemoryOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                if (operation == PinnedMemoryOp.CONTENTS || operation == PinnedMemoryOp.MUTABLE_CONTENTS)
                    PinnedByteArrayContents(tupleProof, compile(args[0], scope, false))
                else if (operation == PinnedMemoryOp.INDEX_ADDR_OFF || operation == PinnedMemoryOp.INDEX_ADDR_ARRAY)
                    PinnedPointerIndexExpression(operation, tupleProof,
                        compile(args[0], scope, false), compile(args[1], scope, false))
                else if (!operation.tuple && operation.addressRead != null)
                    PinnedScalarIndexExpression(operation.addressRead, tupleProof,
                        compile(args[0], scope, false), compile(args[1], scope, false))
                else if (operation == PinnedMemoryOp.WRITE_ADDR_ARRAY)
                    PinnedPointerArrayWrite(tupleProof, compile(args[0], scope, false),
                        compile(args[1], scope, false), compile(args[2], scope, false),
                        compile(args[3], scope, false))
                else if (operation == PinnedMemoryOp.READ_ADDR_ARRAY)
                    PinnedPointerArrayRead(tupleProof, compile(args[0], scope, false),
                        compile(args[1], scope, false), compile(args[2], scope, false))
                else PinnedMemoryExpression(operation, tupleProof, args.map { compile(it, scope, false) }.toTypedArray())
            } else if (fn[0] == "prim" && ByteArrayOp.named(fn[1] as String) != null) {
                val operation = ByteArrayOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                byteArrayExpression(operation, tupleProof, args.map { compile(it, scope, false) }.toTypedArray())
            } else if (tupleOperation != null) {
                tupleOperation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                TupleArithmeticExpression(tupleOperation, tupleProof,
                    argument(args[0], scope, false), argument(args[1], scope, false))
            } else if (tupleProof.isSum && fn[0] == "con" && constructors[fn[1]]?.get("kind") == "unboxed-sum") {
                val tag = SumShape.constructor(tupleProof, constructors[fn[1]], fn[2])
                if (args.size != 1) throw RuntimeFault("Sum constructor must be saturated")
                val selected = tupleProof.alternatives!![tag - 1]
                val lifted = flags.single() as? Boolean ?: throw UnsupportedCore("Unknown sum payload levity")
                val payload = if (selected.isTuple) compile(args.single(), scope, false)
                    else argument(args.single(), scope, lifted)
                SumShape.payload(selected, payload.representation, lifted)
                SumConstruct(TupleShape(tupleProof, language as thc.Language), tag, payload)
            } else if (tupleProof.isTuple && fn[0] == "con" && constructors[fn[1]]?.get("kind") == "unboxed-tuple") {
                val shape = TupleShape(tupleProof, language as thc.Language)
                if (shape.components.size != args.size || (fn[2] as Number).toInt() != args.size ||
                    (constructors[fn[1]]?.get("arity") as? Number)?.toInt() != args.size)
                    throw RuntimeFault("Tuple constructor arity mismatch")
                TupleConstruct(shape, args.mapIndexed { index, arg ->
                    TupleShape.requireCompatible(shape.components[index], CoreRepresentations.expression(arg), component = true)
                    if (shape.components[index].isTuple) compile(arg, scope, false)
                    else argument(arg, scope, flags[index] as? Boolean ?: throw UnsupportedCore("Unknown tuple field levity"))
                }.toTypedArray())
            } else if (fn[0] == "var" && fn[1] in scope.joins) {
                joinJump(scope.joins.getValue(fn[1] as String), args, flags, scope, callStrict)
            } else {
            val constructorStrictFields = if (fn[0] == "con" && (fn[2] as Number).toInt() == args.size)
                strictConstructorFields(fn[1] as String, args.size) else null
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
                argument(arg, scope, lifted && !callStrict[i] && constructorStrictFields?.get(i) != true && entryStrict?.getOrNull(i) != true,
                    allowEmpty = fn[0] != "prim" && fn[0] != "con", declaredLifted = lifted)
            }.toTypedArray()
            when {
                fn[0] == "prim" -> {
                    ScalarPrimitiveSignatures.validate(fn[1] as String, nodes.map { it.representation }, tupleProof)
                    primitive(fn[1] as String, nodes)
                }
                constructorStrictFields != null -> {
                    val layout = dataLayout(fn[1] as String)
                    Construct(layout, nodes, constructorVectorSlots(layout, scope.layout))
                }
                else -> {
                    val function = compile(fn, scope, false)
                    if (ArgumentLayout.fromProofs(nodes.map { it.representation })?.requiresTyped == true)
                        AstTypedApplication(function, nodes, scope.layout, tail, metrics,
                            if (tupleProof.isTypedTransport) TupleShape(tupleProof, language as thc.Language) else null)
                    else if (tupleProof.isTypedTransport) {
                        val shape = TupleShape(tupleProof, language as thc.Language)
                        val vectorSlots = if (tupleProof.isVector) IntArray(shape.width) { scope.layout.bind("<vector call result $it>") } else null
                        TupleApplication(language as thc.Language, shape, function, nodes, tail, metrics, vectorSlots)
                    }
                    else {
                    val self = scope.self
                    if (tail && self != null && self.inputLayout == null && nodes.none { it.representation.isEmptyTuple } && self.arity > 0 && nodes.size <= self.arity) {
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
            val binder = local.bind(expr[2] as String, !binderProof.present || binderProof.isLong, binderProof).slot
            val alternatives = (expr[3] as List<List<Any?>>).map { alt ->
                val child = local.child(); val kind = alt[0] as String
                val value = when (kind) {
                    "lit" -> (alt[1] as List<String>).let {
                        if (it[0] == "bignat") throw UnsupportedCore("BigNat literal alternatives are invalid GHC Core")
                        if (it[0] in setOf("float", "double")) throw UnsupportedCore("Floating literal alternatives are invalid GHC Core")
                        literal(it[0], it[1])
                    }
                    "data" -> dataLayout(alt[1] as String)
                    else -> alt[1]
                }
                val ids = alt[2] as List<String>
                val layout = value as? DataLayout
                if (layout != null && layout.arity != ids.size) throw RuntimeFault("Constructor field/binder mismatch")
                val metadata = CoreRepresentations.alternativeBinders(alt)
                val strict = if (kind == "data") strictConstructorFields(alt[1] as String, ids.size) else null
                val vectorFields = arrayOfNulls<IntArray>(ids.size)
                val slots = ids.mapIndexed { index, id ->
                    val raw = metadata.getOrNull(index)?.let(CoreRepresentations::binder) ?: CoreRepresentation.UNKNOWN
                    val vector = layout?.vectorProof(index)
                    val proof = if (vector != null) raw.refine(vector)
                        else if (layout?.isLong(index) == true) raw.refine(CoreRepresentation(CoreKind.LONG, true))
                        else raw.copy(evaluated = strict?.get(index) == true ||
                            ((constructors[alt[1] as? String]?.get("fieldLifted") as? List<*>)?.getOrNull(index) == false))
                    if (vector != null) {
                        if (metadata.getOrNull(index)?.get("lifted") != false)
                            throw UnsupportedCore("Vector constructor binder must be unlifted")
                        val lanes = IntArray(vector.vector!!.lanes) { lane -> child.layout.bind("$id constructor vector lane $lane") }
                        vectorFields[index] = lanes
                        child.bindTuple(id, proof, lanes).slot
                    } else child.bind(id, layout?.isLong(index) == true, proof).slot
                }.toIntArray()
                val tag = when (kind) {
                    "default" -> DEFAULT_ALTERNATIVE
                    "data" -> DATA_ALTERNATIVE
                    "lit" -> LITERAL_ALTERNATIVE
                    else -> throw RuntimeFault("Invalid Core alternative kind $kind")
                }
                Alternative(tag, value, slots, compile(alt[3] as List<Any?>, child, tail), vectorFields)
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
                CaseCategory.DATA -> DataCase(scrutinee, binder, alternatives, metrics, binderProof)
                CaseCategory.LONG -> LongCase(scrutinee, binder, alternatives, metrics, binderProof)
                CaseCategory.DEFAULT_ONLY -> DefaultCase(scrutinee, binder, alternatives, metrics, binderProof)
                CaseCategory.GENERIC -> Case(scrutinee, binder, alternatives, metrics)
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
                        LocalRead(slot, cell = false).proven(proofs[index])
                    } else {
                        val lanes = IntArray(vector.vector!!.lanes) { lane -> layout.bind("field$index vector lane $lane") }
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
                    entryStrict = strictConstructorFields(id, arity), inputLayout = inputLayout)
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
                val projection = proof.alternativeSlots!![tag - 1].map { slots[it] }.toIntArray()
                if (component.isTuple) child.bindTuple(ids[0], field, projection)
                else if (component.kind == CoreKind.VOID) child.bindVoid(ids[0], field)
                else child.bindSlot(ids[0], Local(projection[0], component.isLong, field, false))
            }
            compile(alt[3] as List<Any?>, child, tail)
        }.toTypedArray()
        if (arms.isEmpty()) throw RuntimeFault("Empty sum case")
        val alternatives = expr[3] as List<List<Any?>>
        fun selected(tag: Int) = alternatives.indexOfFirst { it[0] == "data" && (constructors[it[1]]?.get("tag") as? Number)?.toInt() == tag }
            .let { if (it >= 0) it else fallback }
        val result = arms.first().representation.refine(CoreRepresentations.expression(expr))
        arms.forEach { result.refine(it.representation) }
        CoreRepresentations.validateFloatingCaseResult(result, arms.map { it.representation })
        CoreRepresentations.requireNoVector(result, "sum case result")
        return SumCase(scrutinee, slots, arms, selected(1), selected(2),
            result.copy(evaluated = arms.all { it.representation.evaluated }))
    }
    private fun compileVectorReadCase(read: VectorReadCase, scope: Scope, tail: Boolean): Expr {
        val local = scope.child()
        local.bindVoid(read.stateBinder, CoreVectorMemory.stateProof)
        val vectorProof = read.operation.vectorProof
        val lanes = IntArray(TupleShape.flatten(vectorProof).size) { local.layout.bind("<vector read lane $it>") }
        local.bindTuple(read.vectorBinder, vectorProof, lanes)
        val operands = read.arguments.map { compile(it, scope, false) }.toTypedArray()
        val value = VectorByteArrayExpression(read.operation, operands).located(currentSource)
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
        if (alternatives.size != 1) throw RuntimeFault("Tuple case requires one alternative")
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
        return TupleCase(scrutinee, slots, compile(alt[3] as List<Any?>, local, tail))
    }
    private fun joinJump(target: LocalJoinTarget, args: List<List<Any?>>, flags: List<*>, scope: Scope,
                         callStrict: BooleanArray = BooleanArray(args.size)): Expr {
        if (args.size != target.slots.size) throw RuntimeFault("Local join arity mismatch")
        val nodes = args.mapIndexed { index, arg ->
            val lifted = flags.getOrNull(index) as? Boolean ?: throw RuntimeFault("Missing join argument levity")
            argument(arg, scope, lifted && !callStrict[index] && !target.entryStrict[index],
                allowEmpty = target.proofs[index].isEmptyTuple, declaredLifted = lifted).also {
                CoreRepresentations.requireJoinArgument(target.proofs[index], it.representation)
            }
        }.toTypedArray()
        val vectorTemps = arrayOfNulls<IntArray>(nodes.size)
        val temps = IntArray(nodes.size) { index ->
            if (target.proofs[index].isVector) {
                vectorTemps[index] = IntArray(TupleShape.flatten(target.proofs[index]).size) {
                    scope.layout.bind("<join vector argument $index lane $it>")
                }
                -1
            } else if (target.proofs[index].isEmptyTuple) -1
            else scope.layout.bind("<join argument $index>")
        }
        return LocalJoinCall(target, nodes, temps, metrics, vectorTemps)
    }
    private fun compileJoins(expr: List<Any?>, outer: Scope, tail: Boolean,
                             definitions: List<CoreJoinDefinition>): Expr {
        val recursive = expr[1] == true
        val shadowed = if (recursive) definitions.map { it.id }.toSet() else emptySet()
        definitions.forEach { definition ->
            definition.parameters.forEach {
                val proof = CoreRepresentations.binder(it)
                CoreRepresentations.requireJoinInput(proof)
                if (proof.isTypedTransport && representation(it)) throw RuntimeFault("Typed join formal must be unlifted")
            }
            val formals = definition.parameters.map { it["id"] as String }.toSet()
            (freeVariables(definition.body) - formals - shadowed).forEach { id ->
                outer.locals[id]?.let { captured ->
                    if (captured.proof.isTypedTransport) {
                        // A join stays in this activation: its lexical tuple is already
                        // held in typed frame slots, not in a closure environment.
                        CoreRepresentations.requireInput(captured.proof)
                        val slots = captured.tupleSlots ?: throw RuntimeFault("Missing tuple join capture slots")
                        if (slots.size != TupleShape.flatten(captured.proof).size || slots.any { it < 0 })
                            throw RuntimeFault("Tuple join capture disagrees with its physical slots")
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
                if (proof.isVector) {
                    val lanes = IntArray(TupleShape.flatten(proof).size) {
                        scope.layout.bind("${parameter["id"]} join vector lane $it")
                    }
                    scope.bindTuple(parameter["id"] as String, proof.copy(evaluated = true), lanes)
                } else if (proof.isEmptyTuple) scope.bindTuple(parameter["id"] as String, proof.copy(evaluated = true), intArrayOf())
                else scope.bind(parameter["id"] as String, !lifted && parameter["coercion"] != true, proof)
            }
            scope
        }
        val targets = definitions.mapIndexed { index, definition ->
            val parameters = definition.parameters.map { bodyScopes[index].locals.getValue(it["id"] as String) }
            LocalJoinTarget(identity, index + 1, parameters.map { it.slot }.toIntArray(),
                parameters.map { it.proof }.toTypedArray(), entryContracts[index], definition.result,
                parameters.map { parameter -> if (parameter.proof.isVector) parameter.tupleSlots else null }.toTypedArray())
        }
        definitions.forEachIndexed { index, definition -> local.bindJoin(definition.id, targets[index]) }
        if (recursive) bodyScopes.forEachIndexed { index, scope ->
            val parameters = definitions[index].parameters.map { it["id"] as String }.toSet()
            definitions.forEachIndexed { targetIndex, definition ->
                if (definition.id !in parameters) scope.bindJoin(definition.id, targets[targetIndex])
            }
        }
        val entry = compile(expr[3] as List<Any?>, local, tail)
        CoreRepresentations.requireNoSum(entry.representation, "join result")
        val bodies = definitions.mapIndexed { index, definition ->
            withSource(sources.binding(definition.binding, currentSource)) {
                compile(definition.body, bodyScopes[index], tail).also { node ->
                    CoreRepresentations.requireNoSum(node.representation, "join result")
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
            (listOf(entry) + bodies).toTypedArray(), result, recursive, tuple, tupleSlots)
    }
    private fun dataLayout(id: String): DataLayout = dataLayouts.getOrPut(id) {
        val info = constructors[id] ?: throw RuntimeFault("Missing constructor metadata $id")
        val fields = CoreFields(info)
        DataLayout.fromFields(language ?: throw RuntimeFault("Constructor layout requires a guest language"),
            id, info["name"] as String, fields)
    }
    private fun primitive(name: String, args: Array<Expr>): Expr = floatingPrimitive(name, args) ?: when (name) {
        "reallyUnsafePtrEquality#" -> {
            if (args.size != 2) throw RuntimeFault("Primitive arity mismatch: $name")
            PointerEquality(args[0], args[1])
        }
        "raise#" -> {
            if (args.size != 1) throw RuntimeFault("Primitive arity mismatch: $name")
            RaiseException(args[0])
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
        "plusAddr#", "indexCharOffAddr#", "indexWord8OffAddr#", "indexInt8OffAddr#" -> {
            if (args.size != 2) throw RuntimeFault("Primitive arity mismatch: $name")
            if (name == "plusAddr#") PlusManagedAddress(args[0], args[1])
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
        Array(layout.arity) { index ->
            if (layout.isVector(index)) IntArray(layout.fieldWidth(index)) { lane ->
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
