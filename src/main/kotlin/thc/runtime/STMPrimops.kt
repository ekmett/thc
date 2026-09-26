// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.exception.AbstractTruffleException
import java.util.concurrent.Callable
import thc.Language

/** Pinned GHC 9.14.1: boxed-at-known-levity payloads, never arbitrary scalar RuntimeRep. */
internal enum class STMOp(val primitive: String, val arguments: List<String>, val results: List<String>) {
    ATOMICALLY("atomically#", listOf("action", "state"), listOf("state", "boxed")),
    RETRY("retry#", listOf("state"), listOf("state", "boxed")),
    OR_ELSE("catchRetry#", listOf("action", "action", "state"), listOf("state", "boxed")),
    CATCH("catchSTM#", listOf("action", "action", "state"), listOf("state", "boxed")),
    NEW("newTVar#", listOf("boxed", "state"), listOf("state", "tvar")),
    READ("readTVar#", listOf("tvar", "state"), listOf("state", "boxed")),
    READ_IO("readTVarIO#", listOf("tvar", "state"), listOf("state", "boxed")),
    WRITE("writeTVar#", listOf("tvar", "boxed", "state"), listOf("state"));

    val callback: Boolean get() = this == ATOMICALLY || this == OR_ELSE || this == CATCH
    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        if (actual.size != arguments.size || flags.size != arguments.size || actual.indices.any {
                !matches(actual[it], arguments[it]) || flags[it] != (actual[it].primReps == listOf(LIFTED)) })
            throw RuntimeFault("STM primitive argument representation mismatch: $primitive")
        val parts = result.components
        val valid = if (this == WRITE) matches(result, "state") else
            result.kind == CoreKind.UNKNOWN && result.isTuple && !result.isSum && !result.isVector &&
                parts?.size == results.size && parts.indices.all { matches(parts[it], results[it]) } &&
                result.primReps == parts.flatMap { it.primReps!! }
        if (!valid) throw RuntimeFault("STM primitive result representation mismatch: $primitive")
    }
    companion object {
        const val NESTED = "ghc-internal:GHC.Internal.Control.Exception.Base.nestedAtomically"
        private const val LIFTED = "BoxedRep (Just Lifted)"
        private const val UNLIFTED = "BoxedRep (Just Unlifted)"
        fun named(name: String): STMOp? = entries.firstOrNull { it.primitive == name }
        private fun matches(proof: CoreRepresentation, role: String) = !proof.isAggregate && !proof.isVector && when (role) {
            "state" -> proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
            "tvar" -> proof.kind == CoreKind.OBJECT && proof.primReps == listOf(UNLIFTED)
            "action" -> proof.kind == CoreKind.CLOSURE && proof.primReps == listOf(LIFTED)
            else -> proof.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
                proof.primReps?.singleOrNull() in setOf(LIFTED, UNLIFTED)
        }
    }
}

/** An aborted STM scope leaves no child continuation or mutable log behind. The
 * enclosing backend saves a fresh invocation of the original callback scope. */
internal class STMRestart(val request: AsyncRequest) :
    AbstractTruffleException("Restart interrupted STM scope", null, 0, null)

/** A callback yielded before writing its result. Kept distinct from synchronous
 * exceptions so catchSTM and catchRetry cannot intercept asynchronous delivery. */
private class STMActionSuspended(val request: AsyncRequest) :
    AbstractTruffleException("Interrupted STM callback", null, 0, null)

private fun stmRequest(marker: Any?): AsyncRequest = when (marker) {
    is AsyncRequest -> marker
    is ThunkSuspended -> marker.asyncRequest
    is CallSegmentSuspended -> marker.asyncRequest
    else -> null
} ?: fault("STM cannot save a non-asynchronous transaction continuation")

private class STMAsyncDestination(private val destination: TupleDestination) : TupleDestination(destination.shape) {
    override fun delimitedResult(frame: VirtualFrame, node: Node): Any? = destination.delimitedResult(frame, node)
    override fun consume(frame: VirtualFrame, node: Node, result: Any?) {
        val saved = savedGuestContinuation(if (result is TailYield) result.continuation else result)
        if (saved != null) throw STMActionSuspended(stmRequest(saved.yielded))
        destination.consume(frame, node, result)
    }
}

/** Shared callback boundary, with a backend-native tuple destination. */
internal class STMCall @JvmOverloads constructor(private val operation: STMOp, destination: TupleDestination,
    metrics: Metrics, private val async: Boolean = false) : Node() {
    @Child private var force = Force(metrics, async)
    @Child private var actionCall = TupleDispatch(if (async) STMAsyncDestination(destination) else destination, metrics, 1, false)
    @Child private var otherCall = TupleDispatch(if (async) STMAsyncDestination(destination) else destination,
        metrics, if (operation == STMOp.CATCH) 2 else 1, false)
    fun execute(frame: VirtualFrame, action: Any?, alternative: Any?, nested: Any?) {
        val stm = Language.currentState(this).stm
        val mask = if (async) SynchronousMasking.current(this) else null
        val annotations = if (async) StackAnnotations.current(this) else null
        try {
            when (operation) {
                STMOp.ATOMICALLY ->
                    stm.atomically(this, { throw GuestException(nested, this) }, async) {
                        actionCall.execute(frame, requireClosure(force.execute(frame, action)), arrayOf(Unit))
                    }
                STMOp.OR_ELSE -> stm.orElse({
                    actionCall.execute(frame, requireClosure(force.execute(frame, action)), arrayOf(Unit))
                }, {
                    otherCall.execute(frame, requireClosure(force.execute(frame, alternative)), arrayOf(Unit))
                })
                STMOp.CATCH -> stm.catchSTM({
                    actionCall.execute(frame, requireClosure(force.execute(frame, action)), arrayOf(Unit))
                }, { payload ->
                    otherCall.execute(frame, requireClosure(force.execute(frame, alternative)), arrayOf(payload, Unit))
                })
                else -> error("Not an STM callback: $operation")
            }
        } catch (failure: Throwable) {
            if (!async) throw failure
            // ManagedSTM's finally has already detached this scope's log and
            // restored its parent (if any). Do not retain the yielded child:
            // Force follows those children before reaching an outer restart.
            val request = when (failure) {
                is STMRestart -> failure.request
                is STMActionSuspended -> failure.request
                is AsyncBlocked -> failure.request
                is AstCapture -> stmRequest(failure.yielded)
                is ThunkSuspended -> stmRequest(failure)
                is CallSegmentSuspended -> stmRequest(failure)
                else -> throw failure
            }
            SynchronousMasking.set(this, mask!!)
            StackAnnotations.set(this, annotations!!)
            throw STMRestart(request)
        }
    }
}

internal class STMExpression(private val operation: STMOp, proof: CoreRepresentation,
    @field:Children private var operands: Array<Expr>, private val shape: TupleShape?,
    private val metrics: Metrics, @field:Child private var nested: Expr?, private val async: Boolean = false) : Expr() {
    @Child @Volatile private var call: STMCall? = null
    @field:CompilationFinal(dimensions = 1) private var destinationSlots: IntArray? = null
    @CompilationFinal private var destinationOffset = -1
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        if (operation != STMOp.WRITE) fault("STM tuple primitive requires a destination")
        val cell = operands[0].execute(frame)
        val value = operands[1].execute(frame)
        requireVoidCarrier(operands[2].execute(frame))
        Language.currentState(this).stm.write(cell, value)
        return Unit
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val first = if (operation == STMOp.RETRY) null else operands[0].execute(frame)
        val second = if (operation == STMOp.OR_ELSE || operation == STMOp.CATCH) operands[1].execute(frame) else null
        requireVoidCarrier(operands.last().execute(frame))
        if (operation.callback) {
            if (call == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                atomic(Callable {
                    if (call == null) {
                        call = insert(STMCall(operation, AstTupleDestination(checkNotNull(shape), slots, offset), metrics, async))
                        destinationSlots = slots; destinationOffset = offset
                    }
                })
            }
            check(destinationSlots === slots && destinationOffset == offset)
            invoke(frame, first, second, nested?.execute(frame))
        } else {
            val stm = Language.currentState(this).stm
            val value = when (operation) {
                STMOp.NEW -> stm.newTVar(first)
                STMOp.READ -> stm.read(first)
                STMOp.READ_IO -> stm.readIO(first)
                STMOp.RETRY -> stm.retry()
                else -> error("Not an STM tuple primitive: $operation")
            }
            FrameAccess.write(frame, slots[offset], value)
        }
        return null
    }

    private fun invoke(frame: VirtualFrame, action: Any?, alternative: Any?, nested: Any?) {
        try {
            call!!.execute(frame, action, alternative, nested)
        } catch (restart: STMRestart) {
            // This is deliberately a NEW capture. Resuming the abandoned child
            // first would run transactional operations without any attempt.
            throw AstCapture(restart.request, SynchronousMasking.current(this)).append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? {
                    invoke(frame, action, alternative, nested)
                    return null
                }
            })
        }
    }
}
