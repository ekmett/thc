// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Managed allocation addresses, not physical JVM heap or native process pointers. */
internal object PinnedMemory {
    @JvmStatic fun allocate(size: Long, alignment: Long): ByteArray {
        // A managed allocation's base is aligned in its own address space. No
        // address-to-integer/native-pointer operation is provided by this slice.
        if (alignment <= 0 || alignment and (alignment - 1) != 0L)
            fault("Pinned ByteArray# alignment must be a positive power of two")
        return ManagedByteArray.allocate(size)
    }
}

internal enum class PinnedMemoryOp(val primitive: String, val arguments: List<List<String>>, val tuple: Boolean) {
    NEW("newPinnedByteArray#", listOf(listOf("IntRep"), emptyList()), true),
    NEW_ALIGNED("newAlignedPinnedByteArray#", listOf(listOf("IntRep"), listOf("IntRep"), emptyList()), true),
    CONTENTS("byteArrayContents#", listOf(listOf("BoxedRep (Just Unlifted)")), false),
    READ("readWord8OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true),
    WRITE("writeWord8OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("Word8Rep"), emptyList()), false);

    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun exact(proof: CoreRepresentation, reps: List<String>): Boolean = !proof.isAggregate && !proof.isVector &&
            proof.primReps == reps && proof.kind == when (reps.singleOrNull()) {
                null -> CoreKind.VOID
                "BoxedRep (Just Unlifted)" -> CoreKind.OBJECT
                "AddrRep" -> CoreKind.ADDRESS
                else -> CoreKind.LONG
            }
        if (actual.size != arguments.size || flags != List(arguments.size) { false } ||
            actual.indices.any { !exact(actual[it], arguments[it]) })
            throw RuntimeFault("Pinned memory argument representation mismatch: $primitive")
        val payload = if (this == READ) listOf("Word8Rep") else listOf("BoxedRep (Just Unlifted)")
        val valid = if (tuple) result.isTuple && result.kind == CoreKind.UNKNOWN && result.components!!.size == 2 &&
            exact(result.components[0], emptyList()) && exact(result.components[1], payload) && result.primReps == payload
        else exact(result, if (this == CONTENTS) listOf("AddrRep") else emptyList())
        if (!valid) throw RuntimeFault("Pinned memory result representation mismatch: $primitive")
    }
    companion object { fun named(name: String): PinnedMemoryOp? = entries.firstOrNull { it.primitive == name } }
}

internal class PinnedMemoryExpression(private val operation: PinnedMemoryOp, proof: CoreRepresentation,
    @field:Children private var operands: Array<Expr>) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = when (operation) {
        PinnedMemoryOp.CONTENTS -> ManagedAddress.fromByteArray(ManagedByteArray.require(operands[0].execute(frame)))
        PinnedMemoryOp.WRITE -> {
            val address = operands[0].executeRequiredAddress(frame)
            val offset = operands[1].executeRequiredLong(frame)
            val value = operands[2].executeRequiredLong(frame)
            ManagedByteArray.requireState(operands[3].execute(frame))
            address.writeWord8(offset, value)
            Unit
        }
        else -> fault("Pinned memory tuple operation requires a destination")
    }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress =
        if (operation == PinnedMemoryOp.CONTENTS)
            ManagedAddress.fromByteArray(ManagedByteArray.require(operands[0].execute(frame)))
        else super.executeAddress(frame)
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        when (operation) {
            PinnedMemoryOp.NEW, PinnedMemoryOp.NEW_ALIGNED -> {
                val size = operands[0].executeRequiredLong(frame)
                val alignment = if (operation == PinnedMemoryOp.NEW_ALIGNED) operands[1].executeRequiredLong(frame) else 1L
                ManagedByteArray.requireState(operands.last().execute(frame))
                FrameAccess.write(frame, slots[offset], PinnedMemory.allocate(size, alignment))
            }
            PinnedMemoryOp.READ -> {
                val address = operands[0].executeRequiredAddress(frame)
                val index = operands[1].executeRequiredLong(frame)
                ManagedByteArray.requireState(operands[2].execute(frame))
                FrameAccess.writeLong(frame, slots[offset], address.readWord8(index))
            }
            else -> fault("Pinned memory scalar operation has no tuple destination")
        }
        return null
    }
}
