// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame

/** A managed Addr#, never a native pointer. Non-null addresses have exactly one
 * final backing reference. Literal contents are immutable compilation constants;
 * mutable contents are ordinary array elements, even after unsafeFreezeByteArray#.
 * Each derived address strongly retains its allocation without exposing it. */
internal class ManagedAddress private constructor(
    @field:CompilationFinal(dimensions = 1) private val literalBytes: ByteArray?,
    private val mutableBytes: ByteArray?,
    private val offset: Long,
    private val owner: ManagedAllocation? = null
) {
    // Package-internal views for original C bitcode; callers never obtain a
    // process pointer and the byte storage is not copied or replaced.
    internal fun rawBacking(): ByteArray = owner?.rawBytesIfPointerFree()
        ?: literalBytes ?: mutableBytes ?: fault("Null Addr# has no backing storage")
    internal fun cbitsBacking(): ByteArray = owner?.exposeToNative() ?: rawBacking()
    internal fun cbitsWritable(): Boolean = owner?.isWritable ?: (mutableBytes != null)
    internal fun cbitsOffset(): Long { size(); return offset }

    private fun size(): Long = owner?.size ?: (literalBytes ?: mutableBytes)?.size?.toLong()
        ?: fault("Null Addr# has no backing storage")

    /** GHC pointer equality compares allocation identity and byte offset. */
    fun sameLocation(other: ManagedAddress): Boolean =
        offset == other.offset && when {
            this === NULL || other === NULL -> this === other
            owner != null -> owner === other.owner
            literalBytes != null -> literalBytes === other.literalBytes
            else -> mutableBytes != null && mutableBytes === other.mutableBytes
        }

    /** Like pointer arithmetic within this allocation, including its one-past address. */
    fun plus(displacement: Long): ManagedAddress {
        if (this === NULL) {
            if (displacement == 0L) return this
            fault("Cannot offset null Addr#")
        }
        // Check before adding so even Long.MIN/MAX_VALUE cannot wrap into range.
        if (displacement < -offset || displacement > size() - offset)
            fault("Managed Addr# offset outside its backing storage")
        return if (displacement == 0L) this else ManagedAddress(literalBytes, mutableBytes, offset + displacement, owner)
    }

    private fun index(displacement: Long): Int {
        if (displacement < -offset || displacement >= size() - offset)
            fault("Managed Addr# access outside its backing storage")
        return (offset + displacement).toInt()
    }

    /** indexCharOffAddr# reads an unsigned eight-bit byte, not a UTF-8 code point. */
    fun indexChar(displacement: Long): Long = readWord8(displacement)

    /** The polyglot text ABI reads a checked NUL-terminated UTF-8 region. */
    @TruffleBoundary
    fun utf8(): String {
        val bytes = rawBacking()
        val start = offset.toInt()
        var end = start
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        if (end == bytes.size) fault("Unterminated polyglot UTF-8 address")
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, start, end - start)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            fault("Invalid UTF-8 at the polyglot boundary")
        }
    }

    /** Both backing variants use byte offsets and return zero-extended Word8#. */
    fun readWord8(displacement: Long): Long {
        val index = index(displacement)
        owner?.let { return it.readByte(index.toLong()) }
        // Keep the immutable and mutable loads distinct: only the former may fold.
        val literal = literalBytes
        return (if (literal != null) literal[index] else mutableBytes!![index]).toLong() and 0xffL
    }

    /** The caller evaluates State# before reaching storage. Invalid writes have
     * no effect; the value contributes only its low eight bits, like writeWord8Array#. */
    fun writeWord8(displacement: Long, value: Long) {
        owner?.let { it.writeByte(index(displacement).toLong(), value); return }
        val bytes = mutableBytes ?: fault("Cannot write through an immutable literal Addr#")
        val index = index(displacement)
        bytes[index] = value.toByte()
    }

    /** Validate a complete byte region before any effect. An empty region may
     * start one past the allocation; an immutable destination is never writable. */
    fun requireRange(displacement: Long, count: Long, writable: Boolean = false) {
        if (writable && owner == null && mutableBytes == null)
            fault("Cannot write through an immutable literal Addr#")
        if (writable && owner != null && !owner.isWritable)
            fault("Cannot write through an immutable managed allocation")
        if (count < 0 || displacement < -offset || displacement > size() - offset)
            fault("Managed Addr# range outside its backing storage")
        val start = offset + displacement
        if (count > size() - start) fault("Managed Addr# range outside its backing storage")
    }

    /** Exact overlap of two checked byte regions, including independently made
     * addresses of the same array. Adjacent and empty regions do not overlap. */
    fun overlaps(displacement: Long, count: Long, other: ManagedAddress,
        otherDisplacement: Long, otherCount: Long): Boolean {
        requireRange(displacement, count)
        other.requireRange(otherDisplacement, otherCount)
        if (count == 0L || otherCount == 0L) return false
        val shared = if (owner != null) owner === other.owner
            else if (literalBytes != null) literalBytes === other.literalBytes
            else mutableBytes === other.mutableBytes
        if (!shared) return false
        val start = offset + displacement
        val otherStart = other.offset + otherDisplacement
        return start < otherStart + otherCount && otherStart < start + count
    }

    @TruffleBoundary
    override fun toString(): String = if (this === NULL) "Addr#(null)"
        else "Addr#(${if (literalBytes != null) "literal" else "managed"}+$offset)"

    /** Pointer cells contain references, not process address bits. */
    fun readAddressElementIndex(elementOffset: Long): ManagedAddress {
        val allocation = owner ?: fault("Addr# has no allocation-owned pointer cells")
        val width = allocation.addressWidth.toLong()
        if (elementOffset < Long.MIN_VALUE / width || elementOffset > Long.MAX_VALUE / width)
            fault("Managed Addr# element offset overflow")
        val displacement = elementOffset * width
        requireRange(displacement, width)
        return allocation.readAddressByteOffset(offset + displacement)
    }

    fun writeAddressElementIndex(elementOffset: Long, value: ManagedAddress) {
        val allocation = owner ?: fault("Addr# has no allocation-owned pointer cells")
        val width = allocation.addressWidth.toLong()
        if (elementOffset < Long.MIN_VALUE / width || elementOffset > Long.MAX_VALUE / width)
            fault("Managed Addr# element offset overflow")
        val displacement = elementOffset * width
        requireRange(displacement, width, writable = true)
        allocation.writeAddressByteOffset(offset + displacement, value)
    }

    companion object {
        private val NULL = ManagedAddress(null, null, 0L)
        fun nullAddress(): ManagedAddress = NULL

        /** Logical pinning means stable managed backing and a strong lifetime,
         * not physical pinning or a process address. Do not copy: views must alias. */
        fun fromByteArray(bytes: ByteArray): ManagedAddress = ManagedAddress(null, bytes, 0L)
        fun fromAllocation(allocation: ManagedAllocation): ManagedAddress = ManagedAddress(null, null, 0L, allocation)
        fun fromGuestByteArray(value: Any?): ManagedAddress = when (value) {
            is ManagedAllocation -> fromAllocation(value)
            is ByteArray -> fromByteArray(value)
            else -> fault("Expected a managed ByteArray#")
        }

        /** GHC's LitString stores raw bytes; the static allocation adds a final NUL. */
        @TruffleBoundary
        fun fromHex(hex: String): ManagedAddress {
            if (hex.length % 2 != 0) throw RuntimeFault("Malformed string-bytes literal")
            val bytes = ByteArray(hex.length / 2 + 1)
            for (index in 0 until bytes.size - 1) {
                val high = digit(hex[index * 2])
                val low = digit(hex[index * 2 + 1])
                bytes[index] = ((high shl 4) or low).toByte()
            }
            return ManagedAddress(bytes, null, 0L)
        }

        private fun digit(char: Char): Int = when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> throw RuntimeFault("Malformed string-bytes literal")
        }
    }
}

internal class PlusManagedAddress(@field:Child private var address: Expr,
                                  @field:Child private var displacement: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): ManagedAddress {
        val value = address.executeRequiredAddress(frame)
        return value.plus(displacement.executeRequiredLong(frame))
    }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress = execute(frame)
}

internal class IndexLiteralChar(@field:Child private var address: Expr,
                                @field:Child private var displacement: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val value = address.executeRequiredAddress(frame)
        return value.indexChar(displacement.executeRequiredLong(frame))
    }
}
