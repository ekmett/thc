// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** The non-profiling GHC 9.14.1 contract; the lifted dummy is never forced. */
internal object CoreCurrentCCS {
    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun state(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
        fun address(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind == CoreKind.ADDRESS && proof.primReps == listOf("AddrRep")
        val dummy = arguments.getOrNull(0)
        val fields = result.components
        if (arguments.size != 2 || dummy == null || dummy.isAggregate || dummy.isVector ||
            dummy.kind !in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE) ||
            dummy.primReps != listOf("BoxedRep (Just Lifted)") || !state(arguments[1]) ||
            flags != listOf(true, false) || !result.isTuple || result.isSum || result.isVector ||
            result.kind != CoreKind.UNKNOWN || fields?.size != 2 || !state(fields[0]) ||
            !address(fields[1]) || result.primReps != listOf("AddrRep"))
            throw RuntimeFault("getCurrentCCS#: expected lifted dummy, State# and State#/Addr# tuple")
    }
}

internal class CompareManagedAddress(@field:Child private var left: Expr,
    @field:Child private var right: Expr, private val negate: Boolean) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val same = left.executeRequiredAddress(frame).sameLocation(right.executeRequiredAddress(frame))
        return if (same != negate) 1L else 0L
    }
}

internal enum class ManagedAddressOrder {
    LT, LE, GT, GE;

    // Keep the constant operation visible to partial evaluation; an enum when
    // introduces a mutable ordinal table on this hot path.
    fun accepts(comparison: Int): Boolean =
        if (this == LT) comparison < 0 else if (this == LE) comparison <= 0
        else if (this == GT) comparison > 0 else comparison >= 0
}

internal class CompareOrderedManagedAddress(@field:Child private var left: Expr,
    @field:Child private var right: Expr, private val order: ManagedAddressOrder) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long =
        if (order.accepts(left.executeRequiredAddress(frame)
                .compareWithinAllocation(right.executeRequiredAddress(frame)))) 1L else 0L
}

internal class GetCurrentCCS(@field:Child private var dummy: Expr,
    @field:Child private var state: Expr, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("getCurrentCCS# requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        // The dummy is retained as a child for source/provenance, but never entered.
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedAddress.nullAddress())
        return null
    }
}
