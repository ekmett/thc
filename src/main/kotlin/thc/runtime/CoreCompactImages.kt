// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import thc.Language

internal enum class CompactImageOp(val primitive: String, private val roles: List<String>, private val results: List<String>) {
    FIRST("compactGetFirstBlock#", listOf("region", "state"), listOf("state", "address", "long")),
    NEXT("compactGetNextBlock#", listOf("region", "address", "state"), listOf("state", "address", "long")),
    ALLOCATE("compactAllocateBlock#", listOf("long", "address", "state"), listOf("state", "address")),
    FIXUP("compactFixupPointers#", listOf("address", "address", "state"), listOf("state", "region", "address")),
    TO_ADDRESS("anyToAddr#", listOf("lifted", "state"), listOf("state", "address")),
    FROM_ADDRESS("addrToAny#", listOf("address"), listOf("boxed"));

    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun matches(proof: CoreRepresentation, role: String): Boolean = proof.present && !proof.isAggregate && !proof.isVector && when (role) {
            "long" -> proof.kind == CoreKind.LONG
            "address" -> proof.kind == CoreKind.ADDRESS
            "state" -> proof.kind == CoreKind.VOID
            "region" -> proof.kind == CoreKind.OBJECT && proof.primReps == listOf("BoxedRep (Just Unlifted)")
            "lifted" -> proof.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
                proof.primReps == listOf("BoxedRep (Just Lifted)")
            else -> proof.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
                proof.primReps?.singleOrNull() in listOf("BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)")
        }
        if (arguments.size != roles.size || flags != roles.map { it == "lifted" } ||
            roles.indices.any { !matches(arguments[it], roles[it]) }) fault("Invalid $primitive arguments")
        val components = result.components
        if (!result.isTuple || components?.size != results.size ||
            results.indices.any { !matches(components[it], results[it]) } ||
            result.primReps != components.flatMap { it.primReps!! }) fault("Invalid $primitive result")
    }
    companion object { fun named(name: String): CompactImageOp? = entries.firstOrNull { it.primitive == name } }
}

internal class CompactImageExpression(private val op: CompactImageOp,
    @field:Children private var operands: Array<Expr>) : Expr() {
    override fun execute(frame: VirtualFrame): Any? = fault("Compact image operation needs tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val state = Language.currentState(this)
        when (op) {
            CompactImageOp.FIRST, CompactImageOp.NEXT -> {
                val region = state.compactRegions.require(operands[0].execute(frame))
                val previous = if (op == CompactImageOp.NEXT) operands[1].executeRequiredAddress(frame) else null
                requireVoidCarrier(operands.last().execute(frame))
                val address = if (previous == null) state.compactImages.first(region) else state.compactImages.next(region, previous)
                FrameAccess.write(frame, slots[offset], address)
                FrameAccess.writeLong(frame, slots[offset + 1], if (address === ManagedAddress.nullAddress()) 0 else address.availableBytes())
            }
            CompactImageOp.ALLOCATE -> {
                val size = operands[0].executeRequiredLong(frame)
                val previous = operands[1].executeRequiredAddress(frame)
                requireVoidCarrier(operands[2].execute(frame))
                FrameAccess.write(frame, slots[offset], state.compactImages.allocate(size, previous))
            }
            CompactImageOp.FIXUP -> {
                val first = operands[0].executeRequiredAddress(frame)
                val oldRoot = operands[1].executeRequiredAddress(frame)
                requireVoidCarrier(operands[2].execute(frame))
                val fixed = state.compactImages.fixup(first, oldRoot)
                FrameAccess.write(frame, slots[offset], fixed.region)
                FrameAccess.write(frame, slots[offset + 1], fixed.root)
            }
            CompactImageOp.TO_ADDRESS -> {
                val value = operands[0].execute(frame)
                requireVoidCarrier(operands[1].execute(frame))
                FrameAccess.write(frame, slots[offset], state.heapAddresses.address(value))
            }
            CompactImageOp.FROM_ADDRESS -> FrameAccess.write(frame, slots[offset],
                state.heapAddresses.dereference(operands[0].executeRequiredAddress(frame)))
        }
        return null
    }
}
