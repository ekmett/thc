package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.nio.ByteOrder

/* Port of GHC 9.14.1 libraries/ghc-internal/cbits/md5.c, git blob
 * 4fa83bda7aacc8a1656d7e2d78251bbe70a04b56, and include/md5.h,
 * a87296687a2f3dc6748264ff2a8a0c919518db55.
 *
 * The algorithm is due to Ron Rivest. This code was written by Colin Plumb
 * in 1993, no copyright is claimed. This code is in the public domain;
 * do with it what you wish.
 *
 * Only the three checked managed-memory ABI entry points are exposed. The
 * complete context lives in its 88 guest-visible bytes, not a digest object or
 * identity table. These are managed addresses, never native process pointers. */
internal object ManagedMd5 {
    private const val CONTEXT_SIZE = 88L
    private const val INPUT = 24L

    @TruffleBoundary
    fun init(context: ManagedAddress) {
        context.requireRange(0, CONTEXT_SIZE, true)
        writeWord(context, 0, 0x67452301)
        writeWord(context, 4, 0xefcdab89.toInt())
        writeWord(context, 8, 0x98badcfe.toInt())
        writeWord(context, 12, 0x10325476)
        writeWord(context, 16, 0)
        writeWord(context, 20, 0)
        // C deliberately leaves all 64 scratch bytes untouched.
    }

    @TruffleBoundary
    fun update(context: ManagedAddress, input: ManagedAddress, length: Long) {
        if (length < 0 || length > Int.MAX_VALUE.toLong()) fault("MD5Update length outside nonnegative CInt domain")
        context.requireRange(0, CONTEXT_SIZE, true)
        input.requireRange(0, length)
        val previous = readWord(context, 16)
        val space = 64L - (previous.toLong() and 63L)
        // Match the actual C memcpy preconditions, not blanket allocation
        // disjointness. Validate every planned copy before updating the count.
        var source = 0L
        var destination = INPUT + 64L - space
        var chunk = minOf(space, length)
        while (true) {
            if (context.overlaps(destination, chunk, input, source, chunk))
                fault("MD5Update overlapping memcpy regions")
            source += chunk
            if (source == length) break
            destination = INPUT
            chunk = minOf(64L, length - source)
        }
        val next = previous + length.toInt()
        writeWord(context, 16, next)
        if (Integer.compareUnsigned(next, previous) < 0)
            writeWord(context, 20, readWord(context, 20) + 1)

        if (space > length) {
            copy(input, 0, context, INPUT + 64L - space, length)
            return
        }
        copy(input, 0, context, INPUT + 64L - space, space)
        byteSwap(context, INPUT, 16)
        transform(context)
        source = space
        while (length - source >= 64L) {
            copy(input, source, context, INPUT, 64)
            byteSwap(context, INPUT, 16)
            transform(context)
            source += 64
        }
        copy(input, source, context, INPUT, length - source)
    }

    @TruffleBoundary
    fun finish(output: ManagedAddress, context: ManagedAddress) {
        context.requireRange(0, CONTEXT_SIZE, true)
        output.requireRange(0, 16, true)
        // C copies only ctx->buf into digest, then clears the entire context.
        // Output overlapping other context bytes is defined and cleared too.
        if (context.overlaps(0, 16, output, 0, 16)) fault("MD5Final overlapping memcpy regions")
        val used = readWord(context, 16).toLong() and 63L
        var cursor = INPUT + used
        context.writeWord8(cursor++, 0x80)
        var count = 55L - used
        if (count < 0) {
            zero(context, cursor, count + 8)
            byteSwap(context, INPUT, 16)
            transform(context)
            cursor = INPUT
            count = 56
        }
        zero(context, cursor, count + 8)
        byteSwap(context, INPUT, 14)
        val low = readWord(context, 16)
        writeWord(context, INPUT + 56, low shl 3)
        writeWord(context, INPUT + 60, (readWord(context, 20) shl 3) or (low ushr 29))
        transform(context)
        byteSwap(context, 0, 4)
        copy(context, 0, output, 0, 16)
        zero(context, 0, CONTEXT_SIZE)
    }

    private fun readWord(address: ManagedAddress, offset: Long): Int {
        var value = 0
        for (byte in 0..3) {
            val shift = 8 * if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 3 - byte
            value = value or (address.readWord8(offset + byte).toInt() shl shift)
        }
        return value
    }

    private fun writeWord(address: ManagedAddress, offset: Long, value: Int) {
        for (byte in 0..3) {
            val shift = 8 * if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 3 - byte
            address.writeWord8(offset + byte, (value ushr shift).toLong())
        }
    }

    private fun byteSwap(address: ManagedAddress, offset: Long, words: Int) {
        for (word in 0 until words) {
            val start = offset + 4L * word
            var value = 0
            for (byte in 0..3) value = value or (address.readWord8(start + byte).toInt() shl (8 * byte))
            writeWord(address, start, value)
        }
    }

    private fun copy(source: ManagedAddress, sourceOffset: Long, destination: ManagedAddress,
        destinationOffset: Long, count: Long) {
        var index = 0L
        while (index < count) {
            destination.writeWord8(destinationOffset + index, source.readWord8(sourceOffset + index))
            index++
        }
    }

    private fun zero(address: ManagedAddress, offset: Long, count: Long) {
        var index = 0L
        while (index < count) address.writeWord8(offset + index++, 0)
    }

    private fun transform(context: ManagedAddress) {
        var a = readWord(context, 0)
        var b = readWord(context, 4)
        var c = readWord(context, 8)
        var d = readWord(context, 12)
        for (step in 0..63) {
            val function: Int
            val word: Int
            when (step / 16) {
                0 -> { function = d xor (b and (c xor d)); word = step }
                1 -> { function = c xor (d and (b xor c)); word = (5 * step + 1) % 16 }
                2 -> { function = b xor c xor d; word = (3 * step + 5) % 16 }
                else -> { function = c xor (b or d.inv()); word = (7 * step) % 16 }
            }
            val rotation = rotations[(step / 16) * 4 + step % 4]
            val next = b + Integer.rotateLeft(a + function + readWord(context, INPUT + 4L * word) + constants[step], rotation)
            a = d; d = c; c = b; b = next
        }
        writeWord(context, 0, readWord(context, 0) + a)
        writeWord(context, 4, readWord(context, 4) + b)
        writeWord(context, 8, readWord(context, 8) + c)
        writeWord(context, 12, readWord(context, 12) + d)
    }

    private val rotations = intArrayOf(7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21)
    private val constants = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt())
}
