package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame

/** A concrete managed Addr#, never a native pointer. Exactly one final backing
 * reference is present. Literal contents are immutable compilation constants;
 * mutable contents are ordinary array elements, even after unsafeFreezeByteArray#.
 * Each derived address strongly retains its allocation without exposing it. */
internal class ManagedAddress private constructor(
    @field:CompilationFinal(dimensions = 1) private val literalBytes: ByteArray?,
    private val mutableBytes: ByteArray?,
    private val offset: Long
) {
    private fun size(): Long = (literalBytes?.size ?: mutableBytes!!.size).toLong()

    /** Like pointer arithmetic within this allocation, including its one-past address. */
    fun plus(displacement: Long): ManagedAddress {
        // Check before adding so even Long.MIN/MAX_VALUE cannot wrap into range.
        if (displacement < -offset || displacement > size() - offset)
            fault("Managed Addr# offset outside its backing storage")
        return if (displacement == 0L) this else ManagedAddress(literalBytes, mutableBytes, offset + displacement)
    }

    private fun index(displacement: Long): Int {
        if (displacement < -offset || displacement >= size() - offset)
            fault("Managed Addr# access outside its backing storage")
        return (offset + displacement).toInt()
    }

    /** indexCharOffAddr# reads an unsigned eight-bit byte, not a UTF-8 code point. */
    fun indexChar(displacement: Long): Long = readWord8(displacement)

    /** Both backing variants use byte offsets and return zero-extended Word8#. */
    fun readWord8(displacement: Long): Long {
        val index = index(displacement)
        // Keep the immutable and mutable loads distinct: only the former may fold.
        val literal = literalBytes
        return (if (literal != null) literal[index] else mutableBytes!![index]).toLong() and 0xffL
    }

    /** The caller evaluates State# before reaching storage. Invalid writes have
     * no effect; the value contributes only its low eight bits, like writeWord8Array#. */
    fun writeWord8(displacement: Long, value: Long) {
        val bytes = mutableBytes ?: fault("Cannot write through an immutable literal Addr#")
        val index = index(displacement)
        bytes[index] = value.toByte()
    }

    @TruffleBoundary
    override fun toString(): String = "Addr#(${if (literalBytes != null) "literal" else "managed"}+$offset)"

    companion object {
        /** Logical pinning means stable managed backing and a strong lifetime,
         * not physical pinning or a process address. Do not copy: views must alias. */
        fun fromByteArray(bytes: ByteArray): ManagedAddress = ManagedAddress(null, bytes, 0L)

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

internal class PlusLiteralAddress(@field:Child private var address: Expr,
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
