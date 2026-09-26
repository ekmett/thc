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
        val value = try { action.execute(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? = loadHandler(frame, input)
            })
        }
        return loadHandler(frame, value)
    }

    private fun loadHandler(frame: VirtualFrame, actionValue: Any?): Any? {
        val value = try { handler.execute(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? =
                    loadState(frame, actionValue, input)
            })
        }
        return loadState(frame, actionValue, value)
    }

    private fun loadState(frame: VirtualFrame, actionValue: Any?, handlerValue: Any?): Any? {
        val value = try { state.execute(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? {
                    requireVoidCarrier(input)
                    return runAction(frame, actionValue, handlerValue)
                }
            })
        }
        requireVoidCarrier(value)
        return runAction(frame, actionValue, handlerValue)
    }

    private fun runAction(frame: VirtualFrame, actionValue: Any?, handlerValue: Any?): Any? =
        protect(frame, handlerValue) {
            val closure = try { AstControl.force(frame, this, force, actionValue) }
            catch (cut: AstCapture) {
                throw cut.append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                        actionCall!!.execute(frame, requireClosure(input), arrayOf(Unit))
                        return null
                    }
                })
            }
            actionCall!!.execute(frame, requireClosure(closure), arrayOf(Unit))
            null
        }

    /** A resumed child must still throw into this same catch frame. A live
     * request is accepted here before the masked handler can itself suspend. */
    private inline fun protect(frame: VirtualFrame, handlerValue: Any?, body: () -> Any?): Any? {
        return try { body() }
        catch (guest: GuestException) { runHandler(frame, handlerValue, guest.payload) }
        catch (delivered: AsyncDelivery) {
            delivered.request.acknowledge()
            runHandler(frame, handlerValue, delivered.request.payload)
        } catch (cut: AstCapture) {
            val request = cut.asyncRequest()
            if (request == null) throw cut.enclose { CatchScope(this, handlerValue, it) }
            check(request.target === Thread.currentThread() && request.state == AsyncRequestState.CLAIMED) {
                "AST catch delivery left its target thread or was already consumed"
            }
            request.acknowledge()
            runHandler(frame, handlerValue, request.payload)
        }
    }

    private class CatchScope(private val node: CatchException, private val handler: Any?,
                             private val steps: List<AstResumeStep>) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? =
            node.protect(frame, handler) { resumeAstSteps(frame, steps, input) }
    }

    private fun runHandler(frame: VirtualFrame, handlerValue: Any?, payload: Any?): Any? {
        val prior = SynchronousMasking.current(this)
        if (prior == MaskingState.UNMASKED) SynchronousMasking.set(this, MaskingState.MASKED_INTERRUPTIBLE)
        return withAstMaskRestore(this, prior) {
            val closure = try { AstControl.force(frame, this, force, handlerValue) }
            catch (cut: AstCapture) {
                throw cut.append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                        handlerCall!!.execute(frame, requireClosure(input), arrayOf(payload, Unit))
                        return null
                    }
                })
            }
            handlerCall!!.execute(frame, requireClosure(closure), arrayOf(payload, Unit))
            null
        }
    }
}

/** Mask scopes restore on success and every exceptional exit. Recapture saves
 * the same logical return mask while the current carrier unwinds normally. */
private class AstMaskScope(private val node: Node, private val prior: MaskingState,
                           private val steps: List<AstResumeStep>) : AstResumeStep {
    override fun resume(frame: VirtualFrame, input: Any?): Any? =
        withAstMaskRestore(node, prior) { resumeAstSteps(frame, steps, input) }
}

private inline fun withAstMaskRestore(node: Node, prior: MaskingState, body: () -> Any?): Any? {
    return try { body() }
    catch (cut: AstCapture) { throw cut.enclose { AstMaskScope(node, prior, it) } }
    finally { SynchronousMasking.set(node, prior) }
}

/** The three GHC masking-state tags, local to a guest context and guest thread. */
enum class MaskingState(val tag: Long) {
    UNMASKED(0), MASKED_UNINTERRUPTIBLE(1), MASKED_INTERRUPTIBLE(2)
}

object SynchronousMasking {
    @JvmStatic fun current(node: Node): MaskingState = Language.currentState(node).threadMaskingState.get().value
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
        val value = try { action.execute(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? = loadState(frame, input)
            })
        }
        return loadState(frame, value)
    }

    private fun loadState(frame: VirtualFrame, actionValue: Any?): Any? {
        val value = try { state.execute(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? {
                    requireVoidCarrier(input)
                    return runAction(frame, actionValue)
                }
            })
        }
        requireVoidCarrier(value)
        return runAction(frame, actionValue)
    }

    private fun runAction(frame: VirtualFrame, actionValue: Any?): Any? {
        val prior = SynchronousMasking.current(this)
        // These are raw GHC primops: maskAsyncExceptions# deliberately sets
        // interruptible masking even inside maskUninterruptible#.
        SynchronousMasking.set(this, target)
        return withAstMaskRestore(this, prior) {
            val closure = try { AstControl.force(frame, this, force, actionValue) }
            catch (cut: AstCapture) {
                throw cut.append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                        actionCall!!.execute(frame, requireClosure(input), arrayOf(Unit))
                        return null
                    }
                })
            }
            actionCall!!.execute(frame, requireClosure(closure), arrayOf(Unit))
            null
        }
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
