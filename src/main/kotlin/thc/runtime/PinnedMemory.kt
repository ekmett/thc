// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import java.lang.foreign.ValueLayout

/** Managed allocation addresses, not physical JVM heap or native process pointers. */
internal object PinnedMemory {
    @JvmStatic fun allocate(size: Long, alignment: Long): ManagedAllocation {
        // A managed allocation's base is aligned in its own address space. No
        // address-to-integer/native-pointer operation is provided by this slice.
        if (alignment <= 0 || alignment and (alignment - 1) != 0L)
            fault("Pinned ByteArray# alignment must be a positive power of two")
        return ManagedAllocation.mutable(size, ValueLayout.ADDRESS.byteSize().toInt())
    }

    private fun pointerArray(value: Any?): ManagedAllocation = value as? ManagedAllocation
        ?: fault("AddrArray# requires an allocation-owned pinned ByteArray#")

    private fun byteOffset(array: ManagedAllocation, index: Long): Long {
        val width = array.addressWidth.toLong()
        if (index < 0 || index > Long.MAX_VALUE / width)
            fault("AddrArray# element offset outside its backing storage")
        return index * width
    }

    @JvmStatic fun readAddressArray(value: Any?, index: Long): ManagedAddress {
        val array = pointerArray(value)
        return array.readAddressByteOffset(byteOffset(array, index))
    }

    @JvmStatic fun writeAddressArray(value: Any?, index: Long, address: ManagedAddress) {
        val array = pointerArray(value)
        array.writeAddressByteOffset(byteOffset(array, index), address)
    }
}

internal enum class PinnedMemoryOp(val primitive: String, val arguments: List<List<String>>, val tuple: Boolean,
    val addressRead: ManagedAddressRead? = null) {
    NEW("newPinnedByteArray#", listOf(listOf("IntRep"), emptyList()), true),
    NEW_ALIGNED("newAlignedPinnedByteArray#", listOf(listOf("IntRep"), listOf("IntRep"), emptyList()), true),
    CONTENTS("byteArrayContents#", listOf(listOf("BoxedRep (Just Unlifted)")), false),
    READ("readWord8OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true),
    READ_WORD32("readWord32OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true, ManagedAddressRead.WORD32),
    READ_WORD("readWordOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true, ManagedAddressRead.WORD),
    READ_INT32("readInt32OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true, ManagedAddressRead.INT32),
    READ_INT("readIntOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true, ManagedAddressRead.INT),
    READ_ADDR("readAddrOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true),
    INDEX_ADDR_OFF("indexAddrOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep")), false),
    INDEX_ADDR_ARRAY("indexAddrArray#", listOf(listOf("BoxedRep (Just Unlifted)"), listOf("IntRep")), false),
    READ_ADDR_ARRAY("readAddrArray#", listOf(listOf("BoxedRep (Just Unlifted)"), listOf("IntRep"), emptyList()), true),
    WRITE("writeWord8OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("Word8Rep"), emptyList()), false),
    WRITE_ADDR("writeAddrOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("AddrRep"), emptyList()), false),
    WRITE_ADDR_ARRAY("writeAddrArray#", listOf(listOf("BoxedRep (Just Unlifted)"), listOf("IntRep"),
        listOf("AddrRep"), emptyList()), false);

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
        val payload = when {
            addressRead != null -> listOf(addressRead.payload)
            this == READ_ADDR || this == READ_ADDR_ARRAY -> listOf("AddrRep")
            this == READ -> listOf("Word8Rep")
            else -> listOf("BoxedRep (Just Unlifted)")
        }
        val valid = if (tuple) result.isTuple && result.kind == CoreKind.UNKNOWN && result.components!!.size == 2 &&
            exact(result.components[0], emptyList()) && exact(result.components[1], payload) && result.primReps == payload
        else exact(result, if (this == CONTENTS || this == INDEX_ADDR_OFF || this == INDEX_ADDR_ARRAY)
            listOf("AddrRep") else emptyList())
        if (!valid) throw RuntimeFault("Pinned memory result representation mismatch: $primitive")
    }
    companion object { fun named(name: String): PinnedMemoryOp? = entries.firstOrNull { it.primitive == name } }
}

internal class PinnedMemoryExpression(private val operation: PinnedMemoryOp, proof: CoreRepresentation,
    @field:Children private var operands: Array<Expr>) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = when (operation) {
        PinnedMemoryOp.CONTENTS -> ManagedAddress.fromGuestByteArray(operands[0].execute(frame))
        PinnedMemoryOp.WRITE -> {
            val address = operands[0].executeRequiredAddress(frame)
            val offset = operands[1].executeRequiredLong(frame)
            val value = operands[2].executeRequiredLong(frame)
            ManagedByteArray.requireState(operands[3].execute(frame))
            address.writeWord8(offset, value)
            Unit
        }
        PinnedMemoryOp.WRITE_ADDR -> {
            val address = operands[0].executeRequiredAddress(frame)
            val offset = operands[1].executeRequiredLong(frame)
            val value = operands[2].executeRequiredAddress(frame)
            ManagedByteArray.requireState(operands[3].execute(frame))
            address.writeAddressElementIndex(offset, value)
            Unit
        }
        PinnedMemoryOp.WRITE_ADDR_ARRAY -> {
            val array = operands[0].execute(frame)
            val index = operands[1].executeRequiredLong(frame)
            val address = operands[2].executeRequiredAddress(frame)
            ManagedByteArray.requireState(operands[3].execute(frame))
            PinnedMemory.writeAddressArray(array, index, address)
            Unit
        }
        PinnedMemoryOp.INDEX_ADDR_OFF -> operands[0].executeRequiredAddress(frame)
            .readAddressElementIndex(operands[1].executeRequiredLong(frame))
        PinnedMemoryOp.INDEX_ADDR_ARRAY -> PinnedMemory.readAddressArray(operands[0].execute(frame),
            operands[1].executeRequiredLong(frame))
        else -> fault("Pinned memory tuple operation requires a destination")
    }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress =
        when (operation) {
            PinnedMemoryOp.CONTENTS -> ManagedAddress.fromGuestByteArray(operands[0].execute(frame))
            PinnedMemoryOp.INDEX_ADDR_OFF -> operands[0].executeRequiredAddress(frame)
                .readAddressElementIndex(operands[1].executeRequiredLong(frame))
            PinnedMemoryOp.INDEX_ADDR_ARRAY -> PinnedMemory.readAddressArray(operands[0].execute(frame),
                operands[1].executeRequiredLong(frame))
            else -> super.executeAddress(frame)
        }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        when (operation) {
            PinnedMemoryOp.NEW, PinnedMemoryOp.NEW_ALIGNED -> {
                val size = operands[0].executeRequiredLong(frame)
                val alignment = if (operation == PinnedMemoryOp.NEW_ALIGNED) operands[1].executeRequiredLong(frame) else 1L
                ManagedByteArray.requireState(operands.last().execute(frame))
                FrameAccess.write(frame, slots[offset], PinnedMemory.allocate(size, alignment))
            }
            PinnedMemoryOp.READ, PinnedMemoryOp.READ_WORD32, PinnedMemoryOp.READ_WORD,
            PinnedMemoryOp.READ_INT32, PinnedMemoryOp.READ_INT -> {
                val address = operands[0].executeRequiredAddress(frame)
                val index = operands[1].executeRequiredLong(frame)
                ManagedByteArray.requireState(operands[2].execute(frame))
                val value = operation.addressRead?.read(address, index) ?: address.readWord8(index)
                FrameAccess.writeLong(frame, slots[offset], value)
            }
            PinnedMemoryOp.READ_ADDR -> {
                val address = operands[0].executeRequiredAddress(frame)
                val index = operands[1].executeRequiredLong(frame)
                ManagedByteArray.requireState(operands[2].execute(frame))
                FrameAccess.write(frame, slots[offset], address.readAddressElementIndex(index))
            }
            PinnedMemoryOp.READ_ADDR_ARRAY -> {
                val array = operands[0].execute(frame)
                val index = operands[1].executeRequiredLong(frame)
                ManagedByteArray.requireState(operands[2].execute(frame))
                FrameAccess.write(frame, slots[offset], PinnedMemory.readAddressArray(array, index))
            }
            else -> fault("Pinned memory scalar operation has no tuple destination")
        }
        return null
    }
}
