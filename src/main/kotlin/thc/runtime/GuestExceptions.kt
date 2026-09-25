// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.exception.AbstractTruffleException
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.ExplodeLoop
import java.util.concurrent.Callable
import thc.Language

/** A synchronous Haskell exception payload, kept separate from unsupported-runtime diagnostics. */
class GuestException(val payload: Any?, location: Node) :
    AbstractTruffleException("Haskell exception (payload retained lazily)", location)

/** The observed GHC 9.14.1 synchronous IO contract, not polymorphic RuntimeRep. */
internal object CoreSynchronousExceptions {
    private val lifted = listOf("BoxedRep (Just Lifted)")
    private fun state(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
        proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
    private fun boxed(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
        proof.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) && proof.primReps == lifted
    private fun closure(proof: CoreRepresentation) = boxed(proof) && proof.kind == CoreKind.CLOSURE
    fun validate(name: String, arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        val validArguments = when (name) {
            "raiseIO#" -> arguments.size == 2 && boxed(arguments[0]) && state(arguments[1]) && flags == listOf(true, false)
            "catch#" -> arguments.size == 3 && closure(arguments[0]) && closure(arguments[1]) &&
                state(arguments[2]) && flags == listOf(true, true, false)
            "unmaskAsyncExceptions#", "maskAsyncExceptions#", "maskUninterruptible#" ->
                arguments.size == 2 && closure(arguments[0]) && state(arguments[1]) &&
                flags == listOf(true, false)
            "getMaskingState#" -> arguments.size == 1 && state(arguments[0]) && flags == listOf(false)
            else -> false
        }
        val components = result.components
        if (!validArguments || result.kind != CoreKind.UNKNOWN || !result.isTuple || result.isSum || result.isVector ||
            components?.size != 2 || !state(components[0]) ||
            (if (name == "getMaskingState#") components[1].kind != CoreKind.LONG ||
                components[1].primReps != listOf("IntRep") || result.primReps != listOf("IntRep")
             else !boxed(components[1]) || result.primReps != lifted))
            throw RuntimeFault("$name: expected exact lifted exception, State# and boxed tuple contract")
    }
}

/** raiseIO# consumes its State# token but never forces its lifted payload. */
internal class RaiseIOException(@field:Child private var payload: Expr,
    @field:Child private var state: Expr, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("raiseIO# requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Nothing {
        val value = payload.execute(frame)
        requireVoidCarrier(state.execute(frame))
        throw GuestException(value, this)
    }
}

/** The handler is outside its own catch frame; successful lifted results stay lazy. */
internal class CatchException(private val shape: TupleShape,
    @field:Child private var action: Expr, @field:Child private var handler: Expr,
    @field:Child private var state: Expr, private val metrics: Metrics) : Expr() {
    @Child private var force = Force(metrics)
    @Child @Volatile private var actionCall: TupleDispatch? = null
    @Child @Volatile private var handlerCall: TupleDispatch? = null
    @field:CompilationFinal(dimensions = 1) private var destinationSlots: IntArray? = null
    @CompilationFinal private var destinationOffset = -1
    init { representation = shape.proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("catch# requires a tuple destination")
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        requireVoidCarrier(state.execute(frame))
        if (actionCall == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            atomic(Callable {
                if (actionCall == null) {
                    val destination = AstTupleDestination(shape, slots, offset)
                    actionCall = insert(TupleDispatch(destination, metrics, 1, false))
                    handlerCall = insert(TupleDispatch(destination, metrics, 2, false))
                    destinationSlots = slots
                    destinationOffset = offset
                }
            })
        }
        check(destinationSlots === slots && destinationOffset == offset)
        val failure = try {
            actionCall!!.execute(frame, requireClosure(force.execute(frame, action.execute(frame))), arrayOf(Unit))
            null
        } catch (guest: GuestException) { guest }
        if (failure != null) {
            val prior = SynchronousMasking.current(this)
            if (prior == MaskingState.UNMASKED) SynchronousMasking.set(this, MaskingState.MASKED_INTERRUPTIBLE)
            try {
                handlerCall!!.execute(frame, requireClosure(force.execute(frame, handler.execute(frame))),
                    arrayOf(failure.payload, Unit))
            } finally { SynchronousMasking.set(this, prior) }
        }
        return null
    }
}

/** The three GHC masking-state tags, local to a guest context and guest thread. */
enum class MaskingState(val tag: Long) {
    UNMASKED(0), MASKED_UNINTERRUPTIBLE(1), MASKED_INTERRUPTIBLE(2)
}

object SynchronousMasking {
    @JvmStatic @TruffleBoundary fun current(node: Node): MaskingState = Language.currentState(node).maskingState.get()
    @JvmStatic @TruffleBoundary fun set(node: Node, state: MaskingState) {
        Language.currentState(node).maskingState.set(state)
    }
}

internal class GetMaskingState(@field:Child private var state: Expr, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("getMaskingState# requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        requireVoidCarrier(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], SynchronousMasking.current(this).tag)
        return null
    }
}

internal class MaskAction(private val shape: TupleShape, private val target: MaskingState,
    @field:Child private var action: Expr, @field:Child private var state: Expr,
    private val metrics: Metrics) : Expr() {
    @Child private var force = Force(metrics)
    @Child @Volatile private var actionCall: TupleDispatch? = null
    @field:CompilationFinal(dimensions = 1) private var destinationSlots: IntArray? = null
    @CompilationFinal private var destinationOffset = -1
    init { representation = shape.proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("mask action requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        requireVoidCarrier(state.execute(frame))
        if (actionCall == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            atomic(Callable {
                if (actionCall == null) {
                    actionCall = insert(TupleDispatch(AstTupleDestination(shape, slots, offset), metrics, 1, false))
                    destinationSlots = slots
                    destinationOffset = offset
                }
            })
        }
        check(destinationSlots === slots && destinationOffset == offset)
        val prior = SynchronousMasking.current(this)
        // These are raw GHC primops: maskAsyncExceptions# deliberately sets
        // interruptible masking even inside maskUninterruptible#.
        SynchronousMasking.set(this, target)
        try {
            actionCall!!.execute(frame, requireClosure(force.execute(frame, action.execute(frame))), arrayOf(Unit))
        } finally { SynchronousMasking.set(this, prior) }
        return null
    }
}

/** noDuplicate# is satisfied by Force's atomic, exclusive ownership of every thunk.
 * THC never clones an active guest stack, and an escaped owner remains fail-closed
 * rather than restarting effects. An explicit function call is not a duplicated
 * evaluation of a suspension. */
internal object CoreNoDuplicate {
    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun state(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
        if (arguments.size != 1 || !state(arguments[0]) || flags != listOf(false) || !state(result))
            throw RuntimeFault("noDuplicate#: exact State# input and result required")
    }
}

internal class NoDuplicate(@field:Child private var state: Expr, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        requireVoidCarrier(state.execute(frame))
        return Unit
    }
}

/** yield# validates its State# before scheduling, then checks only a resumable guest cut. */
internal object CoreYield {
    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun state(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
        if (arguments.size != 1 || !state(arguments[0]) || flags != listOf(false) || !state(result))
            throw RuntimeFault("yield#: exact State# input and result required")
    }

    @JvmStatic @TruffleBoundary fun giveWay() { Thread.yield() }
}

internal class YieldThread(@field:Child private var state: Expr, private val async: Boolean,
    proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }

    private object Resume : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any {
            if (input !== Unit) fault("Invalid yield# continuation")
            return Unit
        }
    }

    override fun execute(frame: VirtualFrame): Any {
        requireVoidCarrier(state.execute(frame))
        CoreYield.giveWay()
        if (async) GuestThreads.pollCurrent(this, false)?.let { request ->
            throw AstCapture(request, SynchronousMasking.current(this)).append(Resume)
        }
        return Unit
    }
}

/**
 * GHC 9.14.1 rts/Exception.cmm: stg_raisezh passes its exception closure unchanged
 * to the handler; it does not enter that closure. In particular, a handler that
 * ignores the payload can catch a raise# whose payload is itself bottom.
 */
internal class RaiseException(@field:Child private var exception: Expr) : Expr() {
    // This branch produces no value. It must not weaken the value proof of
    // another case branch; this does not grant permission to speculate a raise.
    init { representation = CoreRepresentation(CoreKind.UNKNOWN, evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing {
        val payload = exception.execute(frame)
        raise(payload, this)
    }

    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Nothing = execute(frame)

    companion object {
        @TruffleBoundary
        private fun raise(payload: Any?, location: Node): Nothing = throw GuestException(payload, location)
    }
}

/** GHC 9.14.1 Exception.cmm forwards these original SomeException CAFs to raise#.
 * The empty tuple is a strict zero-width argument, not an exception payload.
 * Linkers must retain these dependencies even though Core has no Var for them. */
internal object CoreArithmeticExceptions {
    fun payload(name: String): String? = when (name) {
        "raiseDivZero#" -> "divZeroException"
        "raiseOverflow#" -> "overflowException"
        "raiseUnderflow#" -> "underflowException"
        else -> null
    }?.let { "ghc-internal:GHC.Internal.Exception.Type.$it" }

    fun validate(name: String, arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        if (payload(name) == null || arguments.size != 1 || !arguments[0].isEmptyTuple || flags != listOf(false))
            throw RuntimeFault("$name: expected one exact unlifted empty tuple argument")
        CoreRepresentations.requireNoVector(result, "$name result")
        CoreRepresentations.requireNoSum(result, "$name result")
    }
}

internal class RaiseArithmeticException(@field:Child private var argument: Expr,
    @field:Child private var raise: RaiseException) : Expr() {
    @field:CompilationFinal(dimensions = 1) private val emptySlots = IntArray(0)
    init { representation = CoreRepresentation(CoreKind.UNKNOWN, evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing {
        argument.executeTuple(frame, emptySlots, 0)
        raise.execute(frame)
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Nothing = execute(frame)
}
