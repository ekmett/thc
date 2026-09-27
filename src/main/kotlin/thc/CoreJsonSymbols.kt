// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** On-disk unsigned-UTF8-sorted `binding.id SPACE byte-offset NEWLINE` rows.
 *
 * Binary search touches only candidate lines; there is no per-row heap index.
 * The JSON file is mapped only after finding a symbol. Only its selected object
 * is decoded. A mapping pins the opened file, not its pathname; callers must not
 * mutate an opened file in place. Already decoded bindings own their values.
 */
internal class CoreJsonSymbols(private val sourcePath: Path, private val directoryPath: Path,
                              private val verifyArtifacts: Boolean = false,
                              private val sourceSha256: String? = null,
                              private val directorySha256: String? = null) : AutoCloseable {
    data class Statistics(val directoryOpens: Long, val sourceOpens: Long,
        val directoryMappedBytes: Long, val sourceMappedBytes: Long,
        val directoryByteReads: Long, val sourceByteReads: Long,
        val hashBytesScanned: Long, val lookupComparisons: Long,
        val decodedBindings: Long, val decodedBytes: Long,
        val decodedModules: Long, val metadataBytes: Long)

    /** Detached scalar totals: retaining diagnostics never retains a mapping. */
    class Counters {
        var directoryOpens = 0L
        var sourceOpens = 0L
        var directoryMappedBytes = 0L
        var sourceMappedBytes = 0L
        var directoryByteReads = 0L
        var sourceByteReads = 0L
        var hashBytesScanned = 0L
        var lookupComparisons = 0L
        var decodedBindings = 0L
        var decodedBytes = 0L
        var decodedModules = 0L
        var metadataBytes = 0L
        @Synchronized fun statistics() = Statistics(directoryOpens, sourceOpens,
            directoryMappedBytes, sourceMappedBytes, directoryByteReads, sourceByteReads,
            hashBytesScanned, lookupComparisons, decodedBindings, decodedBytes, decodedModules, metadataBytes)
    }

    data class ModuleSpan(val start: Long, val end: Long, val bindingsStart: Long, val bindingsEnd: Long)
    val counters = Counters()

    private var closed = false
    private var directory: Mapping? = null
    private var source: Mapping? = null
    private data class Selected(val start: Long, val end: Long, val binding: Map<String, Any?>)
    private val selected = HashMap<String, Result<Selected?>>()
    private val modules = HashMap<ModuleSpan, Result<Map<String, Any?>>>()

    fun statistics() = counters.statistics()

    private class Mapping(path: Path) : AutoCloseable {
        private val arena = Arena.ofShared()
        val bytes: MemorySegment = try {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena)
            }
        } catch (failure: Throwable) { arena.close(); throw failure }
        val size: Long get() = bytes.byteSize()
        override fun close() = arena.close()
    }

    private fun open(path: Path, expected: String?, sourceFile: Boolean): Mapping {
        if (sourceFile) counters.sourceOpens++ else counters.directoryOpens++
        val mapping = Mapping(path)
        if (sourceFile) counters.sourceMappedBytes += mapping.size else counters.directoryMappedBytes += mapping.size
        try {
            if (verifyArtifacts) {
                require(expected != null && expected.matches(Regex("[0-9a-f]{64}"))) {
                    "Explicit symbol verification requires SHA-256 for $path"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var at = 0L
                while (at < mapping.size) {
                    val length = minOf(8192L, mapping.size - at)
                    digest.update(mapping.bytes.asSlice(at, length).asByteBuffer())
                    at += length
                }
                counters.hashBytesScanned += mapping.size
                require(digest.digest().joinToString("") { "%02x".format(it) } == expected) {
                    "Core symbol artifact hash mismatch: $path"
                }
            }
            return mapping
        } catch (failure: Throwable) { mapping.close(); throw failure }
    }

    private fun directory(): Mapping = directory ?: open(directoryPath, directorySha256, false).also { directory = it }
    private fun source(): Mapping = source ?: open(sourcePath, sourceSha256, true).also { source = it }
    private fun directoryByte(mapping: Mapping, at: Long): Int {
        counters.directoryByteReads++
        return mapping.bytes.get(ValueLayout.JAVA_BYTE, at).toInt() and 255
    }
    private fun sourceByte(mapping: Mapping, at: Long): Int {
        counters.sourceByteReads++
        return mapping.bytes.get(ValueLayout.JAVA_BYTE, at).toInt() and 255
    }

    /** Returns the selected row's original JSON position, never its successor's
     * position: directory order is unrelated to the module's binding order. */
    private fun lookup(id: String): Long? {
        require(id.isNotEmpty() && '\n' !in id && '\r' !in id) { "Invalid line-based Core symbol" }
        val key = id.toByteArray(Charsets.UTF_8)
        val mapped = directory()
        var low = 0L
        var high = mapped.size
        while (low < high) {
            var start = low + (high - low) / 2
            while (start > low && directoryByte(mapped, start - 1) != 10) start--
            var end = start
            while (end < mapped.size && directoryByte(mapped, end) != 10) end++
            require(end < mapped.size) { "Unterminated Core symbol directory row" }
            var separator = end - 1
            while (separator > start && directoryByte(mapped, separator) != 32) separator--
            require(separator > start && directoryByte(mapped, separator) == 32 && separator + 1 < end) {
                "Malformed Core symbol directory row"
            }
            counters.lookupComparisons++
            var compared = 0
            var index = 0
            while (index < key.size && start + index < separator) {
                compared = directoryByte(mapped, start + index) - (key[index].toInt() and 255)
                if (compared != 0) break
                index++
            }
            if (compared == 0) compared = (separator - start).compareTo(key.size.toLong())
            when {
                compared < 0 -> low = end + 1
                compared > 0 -> high = start
                else -> {
                    var offset = 0L
                    var position = separator + 1
                    while (position < end) {
                        val digit = directoryByte(mapped, position++) - 48
                        require(digit in 0..9) { "Invalid Core symbol byte offset" }
                        offset = Math.addExact(Math.multiplyExact(offset, 10), digit.toLong())
                    }
                    return offset
                }
            }
        }
        return null
    }

    private fun decode(id: String, start: Long): Selected {
        val mapped = source()
        require(start in 0 until mapped.size && sourceByte(mapped, start) == 123) {
            "Core symbol does not point to a binding object: $id at $start"
        }
        var at = start
        var depth = 0L
        var quoted = false
        var escaped = false
        do {
            require(at < mapped.size) { "Unterminated Core binding: $id at $start" }
            val c = sourceByte(mapped, at++)
            if (quoted) {
                if (escaped) escaped = false
                else if (c == 92) escaped = true
                else if (c == 34) quoted = false
            } else when (c) {
                34 -> quoted = true
                123, 91 -> depth++
                125, 93 -> depth--
            }
        } while (depth > 0)
        val length = Math.toIntExact(at - start)
        val bytes = mapped.bytes.asSlice(start, length.toLong()).toArray(ValueLayout.JAVA_BYTE)
        counters.sourceByteReads += length
        counters.decodedBindings++; counters.decodedBytes += length
        val binding = parseObject(bytes)
        require(binding["id"] == id) { "Core symbol directory identity mismatch: $id" }
        return Selected(start, at, binding)
    }

    private fun parseObject(bytes: ByteArray): Map<String, Any?> {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        @Suppress("UNCHECKED_CAST")
        return Json.parse(text) as? Map<String, Any?> ?: error("Expected Core object")
    }

    /** The manifest locates the binding array, including its brackets. Read only
     * this demanded module's metadata on either side, not its binding bodies. */
    fun moduleMetadata(span: ModuleSpan): Map<String, Any?> = synchronized(counters) {
        check(!closed) { "Core symbol source is closed" }
        modules[span]?.let { return@synchronized it.getOrThrow() }
        val result = cacheable {
            require(0 <= span.start && span.start < span.bindingsStart &&
                span.bindingsStart < span.bindingsEnd && span.bindingsEnd < span.end) {
                "Invalid Core module byte ranges"
            }
            val mapped = source()
            require(span.end <= mapped.size && sourceByte(mapped, span.start) == 123 &&
                sourceByte(mapped, span.end - 1) == 125 &&
                sourceByte(mapped, span.bindingsStart) == 91 &&
                sourceByte(mapped, span.bindingsEnd - 1) == 93) { "Invalid Core module extent" }
            val prefixLength = Math.toIntExact(span.bindingsStart - span.start)
            val suffixLength = Math.toIntExact(span.end - span.bindingsEnd)
            val bytes = ByteArray(Math.addExact(Math.addExact(prefixLength, suffixLength), 2))
            MemorySegment.copy(mapped.bytes, ValueLayout.JAVA_BYTE, span.start, bytes, 0, prefixLength)
            bytes[prefixLength] = 91
            bytes[prefixLength + 1] = 93
            MemorySegment.copy(mapped.bytes, ValueLayout.JAVA_BYTE, span.bindingsEnd,
                bytes, prefixLength + 2, suffixLength)
            val read = prefixLength.toLong() + suffixLength
            counters.sourceByteReads += read
            counters.metadataBytes += read
            counters.decodedModules++
            parseObject(bytes).also {
                require(it["bindings"] is List<*> && (it["bindings"] as List<*>).isEmpty()) {
                    "Core module locator must replace the binding array"
                }
            }
        }
        modules[span] = result
        result.getOrThrow()
    }

    private fun <T> cacheable(action: () -> T): Result<T> = try { Result.success(action()) }
    catch (failure: CancellationException) { throw failure }
    catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
    catch (failure: Exception) { Result.failure(failure) }

    /** Cache only requested bindings/errors. No cold row is decoded to populate
     * this cache. Cancellation is a caller event, not a shared format failure. */
    fun binding(id: String, module: ModuleSpan? = null): Map<String, Any?>? = synchronized(counters) {
        check(!closed) { "Core symbol source is closed" }
        val result = selected.getOrPut(id) { cacheable { lookup(id)?.let { decode(id, it) } } }.getOrThrow()
        if (module != null && result != null) require(result.start > module.bindingsStart && result.end < module.bindingsEnd) {
            "Core symbol lies outside its declared module: $id"
        }
        result?.binding
    }

    override fun close(): Unit = synchronized(counters) {
        if (closed) return@synchronized
        closed = true
        selected.clear()
        modules.clear()
        try { source?.close() } finally { source = null; directory?.close(); directory = null }
    }
}
