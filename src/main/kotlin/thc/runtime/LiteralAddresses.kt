package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame

/** Managed Addr# for immutable GHC string literals only; never a native pointer. */
internal class LiteralAddress private constructor(
    @field:CompilationFinal(dimensions = 1) private val bytes: ByteArray,
    private val offset: Long
) {
    /** Like pointer arithmetic within this allocation, including its one-past address. */
    fun plus(displacement: Long): LiteralAddress {
        // Check before adding so even Long.MIN/MAX_VALUE cannot wrap into range.
        if (displacement < -offset || displacement > bytes.size.toLong() - offset)
            fault("Literal Addr# offset outside its backing storage")
        return if (displacement == 0L) this else LiteralAddress(bytes, offset + displacement)
    }

    /** indexCharOffAddr# reads an unsigned eight-bit byte, not a UTF-8 code point. */
    fun indexChar(displacement: Long): Long {
        if (displacement < -offset || displacement >= bytes.size.toLong() - offset)
            fault("Literal Addr# read outside its backing storage")
        return bytes[(offset + displacement).toInt()].toLong() and 0xffL
    }

    /** Text at the polyglot ABI is a NUL-terminated UTF-8 literal, not a pointer. */
    @TruffleBoundary
    fun utf8(): String {
        val start = offset.toInt()
        var end = start
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        if (end == bytes.size) fault("Unterminated polyglot UTF-8 literal")
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, start, end - start)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            fault("Invalid UTF-8 at the polyglot boundary")
        }
    }

    @TruffleBoundary
    override fun toString(): String = "Addr#(literal+$offset)"

    companion object {
        /** GHC's LitString stores raw bytes; the static allocation adds a final NUL. */
        @TruffleBoundary
        fun fromHex(hex: String): LiteralAddress {
            if (hex.length % 2 != 0) throw RuntimeFault("Malformed string-bytes literal")
            val bytes = ByteArray(hex.length / 2 + 1)
            for (index in 0 until bytes.size - 1) {
                val high = digit(hex[index * 2])
                val low = digit(hex[index * 2 + 1])
                bytes[index] = ((high shl 4) or low).toByte()
            }
            return LiteralAddress(bytes, 0L)
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
    override fun execute(frame: VirtualFrame): LiteralAddress {
        val value = address.executeRequiredAddress(frame)
        return value.plus(displacement.executeRequiredLong(frame))
    }
    override fun executeAddress(frame: VirtualFrame): LiteralAddress = execute(frame)
}

internal class IndexLiteralChar(@field:Child private var address: Expr,
                                @field:Child private var displacement: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val value = address.executeRequiredAddress(frame)
        return value.indexChar(displacement.executeRequiredLong(frame))
    }
}
