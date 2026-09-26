// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Pinned GHC 9.14.1 address atomics. All results are the pre-operation value,
 * including unsuccessful CAS. Monitors and volatile VarHandles provide full
 * barriers (stronger than the exchange primops' required read barrier). */
internal enum class AtomicAddressOp(val primitive: String, val width: Int = 8,
    val pointer: Boolean = false, val cas: Boolean = false) {
    READ("atomicReadWordAddr#"), WRITE("atomicWriteWordAddr#"),
    EXCHANGE("atomicExchangeWordAddr#"), EXCHANGE_ADDR("atomicExchangeAddrAddr#", pointer = true),
    CAS("atomicCasWordAddr#", cas = true), CAS8("atomicCasWord8Addr#", 1, cas = true),
    CAS16("atomicCasWord16Addr#", 2, cas = true), CAS32("atomicCasWord32Addr#", 4, cas = true),
    CAS64("atomicCasWord64Addr#", cas = true), CAS_ADDR("atomicCasAddrAddr#", pointer = true, cas = true),
    ADD("fetchAddWordAddr#"), SUB("fetchSubWordAddr#"), AND("fetchAndWordAddr#"),
    NAND("fetchNandWordAddr#"), OR("fetchOrWordAddr#"), XOR("fetchXorWordAddr#");

    val arity: Int get() = if (this == READ) 2 else if (cas) 4 else 3
    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun scalar(proof: CoreRepresentation, kind: CoreKind) =
            !proof.isAggregate && !proof.isVector && proof.kind == kind
        val payload = if (pointer) CoreKind.ADDRESS else CoreKind.LONG
        if (arguments.size != arity || flags != List(arity) { false } ||
            !scalar(arguments[0], CoreKind.ADDRESS) || !scalar(arguments.last(), CoreKind.VOID) ||
            arguments.subList(1, arguments.size - 1).any { !scalar(it, payload) })
            fault("Atomic Addr# argument carrier mismatch: $primitive")
        if (this == WRITE) {
            if (!scalar(result, CoreKind.VOID)) fault("Atomic Addr# write requires State#")
        } else if (!result.isTuple || result.isSum || result.isVector || result.components?.size != 2 ||
            !scalar(result.components[0], CoreKind.VOID) || !scalar(result.components[1], payload))
            fault("Atomic Addr# requires a State#/value tuple: $primitive")
    }

    fun narrow(value: Long): Long = if (width == 8) value else value and ((1L shl (width * 8)) - 1)
    private fun update(old: Long, operand: Long, replacement: Long): Long =
        if (cas) { if (old == narrow(operand)) narrow(replacement) else old }
        else if (this == ADD) old + operand else if (this == SUB) old - operand
        else if (this == AND) old and operand else if (this == NAND) (old and operand).inv()
        else if (this == OR) old or operand else if (this == XOR) old xor operand
        else if (this == READ) old else operand

    fun numeric(address: ManagedAddress, operand: Long = 0, replacement: Long = 0): Long {
        if (pointer) fault("Pointer atomic requires address operands")
        if (address.hasNativeStorage()) return address.withNativeSegment { segment ->
            address.requireByteRegion(width.toLong(), writable = this != READ)
            if (segment.address() % width != 0L) fault("Misaligned native atomic Addr#")
            native(segment, operand, replacement)
        }
        return address.withAtomicBytes(width, this != READ) { bytes, start ->
            var old = 0L
            for (i in 0 until width) old = old or ((bytes.get(ValueLayout.JAVA_BYTE, start.toLong() + i).toLong() and 255L) shl
                (8 * if (little) i else width - i - 1))
            if (this != READ) {
                val value = update(old, operand, replacement)
                for (i in 0 until width) bytes.set(ValueLayout.JAVA_BYTE, start.toLong() + i,
                    (value ushr (8 * if (little) i else width - i - 1)).toByte())
            }
            old
        }
    }

    private fun native(segment: MemorySegment, operand: Long, replacement: Long): Long {
        if (width <= 2) return NativeNarrowAtomic.cas(segment, width.toLong(), operand, replacement)
        if (width == 4) return (intHandle.compareAndExchange(segment, 0L,
            operand.toInt(), replacement.toInt()) as Int).toLong() and 0xffff_ffffL
        if (cas) return longHandle.compareAndExchange(segment, 0L, operand, replacement) as Long
        if (this == READ) return longHandle.getVolatile(segment, 0L) as Long
        if (this == WRITE) { longHandle.setVolatile(segment, 0L, operand); return 0L }
        if (this == EXCHANGE) return longHandle.getAndSet(segment, 0L, operand) as Long
        var old = longHandle.getVolatile(segment, 0L) as Long
        while (true) {
            val witnessed = longHandle.compareAndExchange(segment, 0L, old, update(old, operand, replacement)) as Long
            if (witnessed == old) return old
            old = witnessed
        }
    }

    fun address(location: ManagedAddress, operand: ManagedAddress, replacement: ManagedAddress?): ManagedAddress {
        if (!pointer) fault("Numeric atomic requires integral operands")
        if (location.nativeAllocation() == null)
            return location.atomicPointer(if (cas) operand else null, if (cas) replacement!! else operand)
        // Native memory carries real pointer bits only. Managed heap identity is
        // never projected to a fake address; toNativeBits enforces that boundary.
        val expected = operand.toNativeBits()
        val desired = if (cas) replacement!!.toNativeBits() else expected
        val old = location.withNativeSegment { segment ->
            location.requireRange(0, 8, writable = true)
            if (segment.address() % 8 != 0L) fault("Misaligned native atomic pointer Addr#")
            if (cas) longHandle.compareAndExchange(segment, 0L, expected, desired) as Long
            else longHandle.getAndSet(segment, 0L, desired) as Long
        }
        return ManagedNativeAllocations.current(null).recoverAddress(old) ?: NativeAddresses.current(null).recover(old)
    }

    companion object {
        fun named(name: String): AtomicAddressOp? = entries.firstOrNull { it.primitive == name }
        private val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        private val intHandle = ValueLayout.JAVA_INT.varHandle()
        private val longHandle = ValueLayout.JAVA_LONG.varHandle()
    }
}

/** JDK 25 segment handles deliberately exclude byte/short atomic updates.
 * The native helper touches exactly the checked element, including end tails. */
private object NativeNarrowAtomic {
    private val library = run {
        val path = Files.createTempFile("thc-atomic-", ".so")
        try {
            NativeNarrowAtomic::class.java.getResourceAsStream("/thc/native/native-atomic-api.so").use { input ->
                if (input == null) fault("Native narrow atomics require the Linux x86_64 provider")
                Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
            }
            SymbolLookup.libraryLookup(path, Arena.global())
        } finally { Files.deleteIfExists(path) }
    }
    private val compare = Linker.nativeLinker().downcallHandle(library.find("thc_atomic_cas_narrow").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG))
    @TruffleBoundary fun cas(segment: MemorySegment, width: Long, expected: Long, desired: Long): Long =
        compare.invokeExact(segment, width, expected, desired) as Long
}

internal class AtomicAddressExpression(private val operation: AtomicAddressOp, proof: CoreRepresentation,
    @field:Children private var operands: Array<Expr>) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        if (operation != AtomicAddressOp.WRITE) fault("Atomic Addr# result requires a tuple destination")
        val address = operands[0].executeRequiredAddress(frame)
        val value = operands[1].executeRequiredLong(frame)
        ManagedByteArray.requireState(operands[2].execute(frame))
        operation.numeric(address, value)
        return Unit
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        if (operation == AtomicAddressOp.WRITE) fault("Atomic Addr# write does not produce a tuple")
        val location = operands[0].executeRequiredAddress(frame)
        if (operation.pointer) {
            val operand = operands[1].executeRequiredAddress(frame)
            val replacement = if (operation.cas) operands[2].executeRequiredAddress(frame) else null
            ManagedByteArray.requireState(operands.last().execute(frame))
            FrameAccess.write(frame, slots[offset], operation.address(location, operand, replacement))
        } else {
            val operand = if (operation == AtomicAddressOp.READ) 0L else operands[1].executeRequiredLong(frame)
            val replacement = if (operation.cas) operands[2].executeRequiredLong(frame) else 0L
            ManagedByteArray.requireState(operands.last().execute(frame))
            FrameAccess.writeLong(frame, slots[offset], operation.numeric(location, operand, replacement))
        }
        return null
    }
}
