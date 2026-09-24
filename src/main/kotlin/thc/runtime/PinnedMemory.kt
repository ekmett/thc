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
    READ_INT8("readInt8OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true),
    READ_CHAR("readCharOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true),
    READ_WORD16("readWord16OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true, ManagedAddressRead.WORD16),
    READ_INT16("readInt16OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true, ManagedAddressRead.INT16),
    READ_WORD32("readWord32OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true, ManagedAddressRead.WORD32),
    READ_WORD("readWordOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true, ManagedAddressRead.WORD),
    READ_INT32("readInt32OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true, ManagedAddressRead.INT32),
    READ_INT("readIntOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true, ManagedAddressRead.INT),
    READ_ADDR("readAddrOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), emptyList()), true),
    INDEX_ADDR_OFF("indexAddrOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep")), false),
    INDEX_ADDR_ARRAY("indexAddrArray#", listOf(listOf("BoxedRep (Just Unlifted)"), listOf("IntRep")), false),
    READ_ADDR_ARRAY("readAddrArray#", listOf(listOf("BoxedRep (Just Unlifted)"), listOf("IntRep"), emptyList()), true),
    WRITE("writeWord8OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("Word8Rep"), emptyList()), false),
    WRITE_INT8("writeInt8OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("Int8Rep"), emptyList()), false),
    WRITE_INT16("writeInt16OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("Int16Rep"), emptyList()), false),
    WRITE_WORD16("writeWord16OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("Word16Rep"), emptyList()), false),
    WRITE_INT32("writeInt32OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("Int32Rep"), emptyList()), false),
    WRITE_WORD32("writeWord32OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("Word32Rep"), emptyList()), false),
    WRITE_INT("writeIntOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("IntRep"), emptyList()), false),
    WRITE_WORD("writeWordOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("WordRep"), emptyList()), false),
    WRITE_INT64("writeInt64OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("Int64Rep"), emptyList()), false),
    WRITE_WORD64("writeWord64OffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("Word64Rep"), emptyList()), false),
    WRITE_CHAR("writeCharOffAddr#", listOf(listOf("AddrRep"), listOf("IntRep"), listOf("WordRep"), emptyList()), false),
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
            this == READ_INT8 -> listOf("Int8Rep")
            this == READ_CHAR -> listOf("WordRep")
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

/** Keep the two pure index operands as adopted children so compiled Truffle
 * does not have to materialize a virtual frame through an array-selected Expr. */
internal class PinnedPointerIndexExpression(private val operation: PinnedMemoryOp, proof: CoreRepresentation,
    @field:Child private var base: Expr, @field:Child private var index: Expr) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = executeAddress(frame)
    override fun executeAddress(frame: VirtualFrame): ManagedAddress = when (operation) {
        PinnedMemoryOp.INDEX_ADDR_OFF -> base.executeRequiredAddress(frame)
            .readAddressElementIndex(index.executeRequiredLong(frame))
        PinnedMemoryOp.INDEX_ADDR_ARRAY -> PinnedMemory.readAddressArray(base.execute(frame),
            index.executeRequiredLong(frame))
        else -> fault("Expected a pointer index primitive")
    }
}

internal class PinnedPointerArrayWrite(proof: CoreRepresentation,
    @field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var address: Expr, @field:Child private var state: Expr) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        val allocation = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        val value = address.executeRequiredAddress(frame)
        ManagedByteArray.requireState(state.execute(frame))
        PinnedMemory.writeAddressArray(allocation, element, value)
        return Unit
    }
}

internal class PinnedPointerArrayRead(proof: CoreRepresentation,
    @field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var state: Expr) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = fault("AddrArray# read requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val allocation = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.write(frame, slots[offset], PinnedMemory.readAddressArray(allocation, element))
        return null
    }
}

internal class PinnedMemoryExpression(private val operation: PinnedMemoryOp, proof: CoreRepresentation,
    @field:Children private var operands: Array<Expr>) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = when (operation) {
        PinnedMemoryOp.CONTENTS -> ManagedAddress.fromGuestByteArray(operands[0].execute(frame))
        PinnedMemoryOp.WRITE, PinnedMemoryOp.WRITE_INT8, PinnedMemoryOp.WRITE_CHAR -> {
            val address = operands[0].executeRequiredAddress(frame)
            val offset = operands[1].executeRequiredLong(frame)
            val value = operands[2].executeRequiredLong(frame)
            ManagedByteArray.requireState(operands[3].execute(frame))
            address.writeWord8(offset, value)
            Unit
        }
        PinnedMemoryOp.WRITE_INT16, PinnedMemoryOp.WRITE_WORD16 -> {
            val address = operands[0].executeRequiredAddress(frame)
            val offset = operands[1].executeRequiredLong(frame)
            val value = operands[2].executeRequiredLong(frame)
            ManagedByteArray.requireState(operands[3].execute(frame))
            address.writeWord16(offset, value)
            Unit
        }
        PinnedMemoryOp.WRITE_INT32, PinnedMemoryOp.WRITE_WORD32,
        PinnedMemoryOp.WRITE_INT, PinnedMemoryOp.WRITE_WORD,
        PinnedMemoryOp.WRITE_INT64, PinnedMemoryOp.WRITE_WORD64 -> {
            val address = operands[0].executeRequiredAddress(frame)
            val offset = operands[1].executeRequiredLong(frame)
            val value = operands[2].executeRequiredLong(frame)
            ManagedByteArray.requireState(operands[3].execute(frame))
            val width = if (operation == PinnedMemoryOp.WRITE_INT32 || operation == PinnedMemoryOp.WRITE_WORD32) 4 else 8
            address.writeNativeScalar(offset, width, value)
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
        else -> fault("Pinned memory tuple operation requires a destination")
    }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress =
        when (operation) {
            PinnedMemoryOp.CONTENTS -> ManagedAddress.fromGuestByteArray(operands[0].execute(frame))
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
            PinnedMemoryOp.READ, PinnedMemoryOp.READ_INT8, PinnedMemoryOp.READ_CHAR,
            PinnedMemoryOp.READ_WORD16, PinnedMemoryOp.READ_INT16,
            PinnedMemoryOp.READ_WORD32, PinnedMemoryOp.READ_WORD,
            PinnedMemoryOp.READ_INT32, PinnedMemoryOp.READ_INT -> {
                val address = operands[0].executeRequiredAddress(frame)
                val index = operands[1].executeRequiredLong(frame)
                ManagedByteArray.requireState(operands[2].execute(frame))
                val value = operation.addressRead?.read(address, index)
                    ?: address.readWord8(index).let { if (operation == PinnedMemoryOp.READ_INT8) it.toByte().toLong() else it }
                FrameAccess.writeLong(frame, slots[offset], value)
            }
            PinnedMemoryOp.READ_ADDR -> {
                val address = operands[0].executeRequiredAddress(frame)
                val index = operands[1].executeRequiredLong(frame)
                ManagedByteArray.requireState(operands[2].execute(frame))
                FrameAccess.write(frame, slots[offset], address.readAddressElementIndex(index))
            }
            else -> fault("Pinned memory scalar operation has no tuple destination")
        }
        return null
    }
}
