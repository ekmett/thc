// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.ref.Reference
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap

/** Native transport for the unchanged GHC MD5 C algorithm on Windows.
 * ManagedMd5 validates every range and memcpy overlap before entering here.
 * One native image per backing array preserves even the defined input/output
 * aliases. Only the context and output ranges are copied back, including when
 * invocation unwinds; unrelated managed bytes are never overwritten.
 *
 * C retains no pointers and has no callbacks or global state. Invocation memory
 * is confined to one call; the stateless library code has process lifetime.
 * This requires native authority, never additional guest filesystem authority.
 */
internal object WindowsMd5 {
    private object Api {
        private val lookup: SymbolLookup = run {
            check(System.getProperty("os.name").startsWith("Windows"))
            val library = Files.createTempFile("thc-md5-", ".dll")
            library.toFile().deleteOnExit()
            WindowsMd5::class.java.getResourceAsStream("/thc/cbits/md5.dll").use { source ->
                if (source == null) fault("Missing native Windows MD5 bridge")
                Files.copy(source, library, StandardCopyOption.REPLACE_EXISTING)
            }
            SymbolLookup.libraryLookup(library, Arena.global())
        }
        private val linker = Linker.nativeLinker()
        private fun function(name: String, vararg parameters: java.lang.foreign.MemoryLayout) =
            linker.downcallHandle(lookup.find(name).orElseThrow(), FunctionDescriptor.ofVoid(*parameters))
        val init = function("thc_md5_init", ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        val update = function("thc_md5_update", ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)
        val finish = function("thc_md5_final", ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    }

    private class Call : AutoCloseable {
        private val arena = Arena.ofConfined()
        private val images = IdentityHashMap<ByteArray, MemorySegment>()
        init {
            if (!thc.Language.currentState().env.isNativeAccessAllowed)
                fault("Native Windows MD5 requires native access")
        }
        fun image(address: ManagedAddress): MemorySegment {
            val bytes = address.cbitsBacking()
            return images[bytes] ?: arena.allocate(maxOf(1L, bytes.size.toLong()), 8).also {
                MemorySegment.copy(MemorySegment.ofArray(bytes), 0, it, 0, bytes.size.toLong())
                images[bytes] = it
            }
        }
        fun copyBack(address: ManagedAddress, image: MemorySegment, size: Long) {
            val offset = address.cbitsOffset()
            MemorySegment.copy(image, offset, MemorySegment.ofArray(address.cbitsBacking()), offset, size)
            Reference.reachabilityFence(address)
        }
        override fun close() = arena.close()
    }

    fun init(context: ManagedAddress) = Call().use { call ->
        val image = call.image(context)
        try { Api.init.invokeExact(image, context.cbitsOffset()); Unit }
        finally { call.copyBack(context, image, 88) }
    }

    fun update(context: ManagedAddress, input: ManagedAddress, length: Int) = Call().use { call ->
        val contextImage = call.image(context)
        val inputImage = call.image(input)
        try { Api.update.invokeExact(contextImage, context.cbitsOffset(), inputImage, input.cbitsOffset(), length); Unit }
        finally {
            call.copyBack(context, contextImage, 88)
            Reference.reachabilityFence(input)
        }
    }

    fun finish(output: ManagedAddress, context: ManagedAddress) = Call().use { call ->
        val outputImage = call.image(output)
        val contextImage = call.image(context)
        try { Api.finish.invokeExact(outputImage, output.cbitsOffset(), contextImage, context.cbitsOffset()); Unit }
        finally {
            call.copyBack(output, outputImage, 16)
            call.copyBack(context, contextImage, 88)
        }
    }
}
