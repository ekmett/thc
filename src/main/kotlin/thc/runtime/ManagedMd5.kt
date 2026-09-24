package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.nio.ByteOrder

/** Checked ABI for the original pinned GHC MD5 C bitcode. All preconditions
 * remain before the first C store; context bytes remain the entire MD5 state. */
internal object ManagedMd5 {
    private const val CONTEXT_SIZE = 88L
    private const val INPUT = 24L

    @TruffleBoundary
    fun init(context: ManagedAddress) {
        context.requireRange(0, CONTEXT_SIZE, true)
        thc.Language.currentState().cbits().init(context)
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
        thc.Language.currentState().cbits().update(context, input, length.toInt())
    }

    @TruffleBoundary
    fun finish(output: ManagedAddress, context: ManagedAddress) {
        context.requireRange(0, CONTEXT_SIZE, true)
        output.requireRange(0, 16, true)
        // C copies only ctx->buf into digest, then clears the entire context.
        // Output overlapping other context bytes is defined and cleared too.
        if (context.overlaps(0, 16, output, 0, 16)) fault("MD5Final overlapping memcpy regions")
        thc.Language.currentState().cbits().finish(output, context)
    }

    private fun readWord(address: ManagedAddress, offset: Long): Int {
        var value = 0
        for (byte in 0..3) {
            val shift = 8 * if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 3 - byte
            value = value or (address.readWord8(offset + byte).toInt() shl shift)
        }
        return value
    }
}
