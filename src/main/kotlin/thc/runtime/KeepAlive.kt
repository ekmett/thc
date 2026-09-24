// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import java.lang.ref.Reference

internal object CoreKeepAlive {
    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation,
                 continuation: Pair<List<CoreRepresentation>, CoreRepresentation>?) {
        fun state(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
        fun reference(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE) &&
            proof.primReps in listOf(listOf("BoxedRep (Just Lifted)"), listOf("BoxedRep (Just Unlifted)"))
        if (arguments.size != 3 || !reference(arguments[0]) || !state(arguments[1]) ||
            arguments[2].kind !in setOf(CoreKind.CLOSURE, CoreKind.OBJECT) ||
            arguments[2].isAggregate || arguments[2].isVector ||
            arguments[2].primReps != listOf("BoxedRep (Just Lifted)") ||
            flags != listOf(arguments[0].primReps == listOf("BoxedRep (Just Lifted)"), false, true))
            throw RuntimeFault("keepAlive#: exact reference, State and continuation operands required")
        if (!result.present) throw RuntimeFault("keepAlive#: exact continuation result required")
        CoreRepresentations.requireNoVector(result, "keepAlive result")
        when {
            result.isSum -> SumShape.validate(result)
            result.isTuple -> TupleShape.validate(result)
            else -> {
                CoreRepresentations.requireScalar(result, "keepAlive result")
                if (result.primReps == null || result.kind == CoreKind.UNKNOWN)
                    throw RuntimeFault("keepAlive#: exact scalar result required")
            }
        }
        if (continuation != null) {
            if (continuation.first.isEmpty() || !state(continuation.first[0]))
                throw RuntimeFault("keepAlive#: continuation requires a logical State operand")
            if (continuation.first.size == 1) {
                result.refine(continuation.second)
                if (result.isAggregate || continuation.second.isAggregate)
                    TupleShape.requireCompatible(result, continuation.second)
            } else if (!reference(result) || result.primReps != listOf("BoxedRep (Just Lifted)"))
                throw RuntimeFault("keepAlive#: partially applied continuation must return a lifted function")
        }
    }
}

/** Do not tail-transfer the action: the fence belongs after its actual completion. */
internal class KeepAliveExpression(@field:Child private var kept: Expr,
    @field:Child private var state: Expr, @field:Child private var action: Expr,
    proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    private inline fun <T> retaining(frame: VirtualFrame, block: () -> T): T {
        // The lifted expression is compiled as a lazy argument, never forced here.
        val value = kept.execute(frame)
        ManagedByteArray.requireState(state.execute(frame))
        return try { block() } finally { Reference.reachabilityFence(value) }
    }
    override fun execute(frame: VirtualFrame): Any? = retaining(frame) { action.execute(frame) }
    override fun executeLong(frame: VirtualFrame): Long = retaining(frame) { action.executeLong(frame) }
    override fun executeFloat(frame: VirtualFrame): Float = retaining(frame) { action.executeFloat(frame) }
    override fun executeDouble(frame: VirtualFrame): Double = retaining(frame) { action.executeDouble(frame) }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress = retaining(frame) { action.executeAddress(frame) }
    override fun executeDataValue(frame: VirtualFrame): DataValue = retaining(frame) { action.executeDataValue(frame) }
    override fun executeClosure(frame: VirtualFrame): Closure = retaining(frame) { action.executeClosure(frame) }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? =
        retaining(frame) { action.executeTuple(frame, slots, offset) }
}
