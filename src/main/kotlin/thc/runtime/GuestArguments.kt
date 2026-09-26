// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** Context-owned copies of GHC's argc/argv, including argv[0] and the final
 * null pointer. No process-global RTS state or host command line is consulted. */
internal class GuestArguments(private val env: TruffleLanguage.Env) {
    private data class Image(val count: Int, val vector: ManagedAddress, val strings: List<ManagedAddress>)
    private var initial = arrayOf("") + env.applicationArguments
    private var image: Image? = null

    private fun current(): ManagedNativeAllocations {
        val state = Language.currentState()
        if (state.arguments !== this) fault("Program arguments belong to another context")
        return state.nativeAllocations
    }

    /** The owning CLI supplies argv before any guest code has observed it. */
    @Synchronized @TruffleBoundary
    fun initialize(programName: String, arguments: Array<String>) {
        current()
        if (image != null) fault("Program arguments have already been observed")
        initial = arrayOf(programName) + arguments
    }

    @Synchronized @TruffleBoundary
    fun get(argc: ManagedAddress, argv: ManagedAddress) {
        current()
        val nil = ManagedAddress.nullAddress()
        if (argc !== nil) argc.requireByteRegion(4, writable = true)
        if (argv !== nil) argv.requireRange(0, 8, writable = true)
        val value = image ?: create(initial.map(::encode)).also { image = it; initial = emptyArray() }
        if (argc !== nil) argc.writeNativeScalar(0, 4, value.count.toLong())
        if (argv !== nil) argv.writeAddressElementIndex(0, value.vector)
    }

    /** The original setProgArgv copies strings before its caller releases the
     * temporary vector. Read every input before retiring the previous image. */
    @Synchronized @TruffleBoundary
    fun set(argc: Long, argv: ManagedAddress) {
        val allocations = current()
        if (argc !in 0..Int.MAX_VALUE.toLong()) fault("Program argument count is outside CInt range")
        val bytes = if (argc == 0L) emptyList() else argv.withNativeBorrow {
            argv.requireRange(0, argc * 8)
            List(argc.toInt()) { index ->
                val address = argv.readAddressElementIndex(index.toLong())
                address.withNativeBorrow {
                    val size = address.cStringLength()
                    if (size >= Int.MAX_VALUE) fault("Program argument exceeds managed byte capacity")
                    ByteArray(size.toInt()) { address.readWord8(it.toLong()).toByte() }
                }
            }
        }
        val replacement = create(bytes)
        val old = image
        image = replacement
        initial = emptyArray()
        old?.let { release(allocations, it) }
    }

    private fun encode(value: String): ByteArray {
        if ('\u0000' in value) fault("A program argument cannot contain NUL")
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val buffer = encoder.encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun create(bytes: List<ByteArray>): Image {
        val allocations = current()
        val owned = ArrayList<ManagedAddress>(bytes.size + 1)
        fun allocate(size: Long): ManagedAddress = allocations.malloc(size).also {
            if (it === ManagedAddress.nullAddress()) throw OutOfMemoryError("Unable to allocate program arguments")
            owned.add(it)
        }
        try {
            val vector = allocate((bytes.size.toLong() + 1) * 8)
            val strings = bytes.mapIndexed { index, value ->
                allocate(value.size.toLong() + 1).also { address ->
                    for (offset in value.indices) address.writeWord8(offset.toLong(), value[offset].toLong())
                    address.writeWord8(value.size.toLong(), 0)
                    vector.writeAddressElementIndex(index.toLong(), address)
                }
            }
            vector.writeAddressElementIndex(bytes.size.toLong(), ManagedAddress.nullAddress())
            return Image(bytes.size, vector, strings)
        } catch (failure: Throwable) {
            for (address in owned.asReversed()) try { allocations.free(address) }
                catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    private fun release(allocations: ManagedNativeAllocations, value: Image) {
        for (address in value.strings) allocations.free(address)
        allocations.free(value.vector)
    }
    // The existing native allocation registry releases the final image during
    // context disposal, and all escaped addresses retain its lifetime checks.
    companion object {
        @JvmStatic fun current(node: Node?): GuestArguments = Language.currentState(node).arguments
    }
}
