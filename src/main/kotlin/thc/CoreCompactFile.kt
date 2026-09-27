// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32

/** One lazily acquired immutable CBD archive. Mapped and inflated bytes are
 * process-wide; cursors, decoded objects and detached counters belong to this load. */
internal class CoreCompactFile(private val path: Path, private val identity: String,
                               private val verifyArtifacts: Boolean = false,
                               private val mappings: CoreFileMappings = CoreFileMappings.shared,
                               private val slabs: CoreCbdSlabs = CoreCbdSlabs.shared) : AutoCloseable {
    data class Statistics(val acquisitions: Long, val physicalOpens: Long, val cacheHits: Long,
        val mappedBytes: Long, val headerBytesRead: Long, val lookupBytesRead: Long,
        val lookupComparisons: Long, val dataBytesRead: Long, val stringBytesRead: Long,
        val debugBytesRead: Long, val hashBytesRead: Long, val decodedBindings: Long, val decodedModules: Long,
        val directoryBytesRead: Long, val memberInflations: Long, val inflatedBytes: Long,
        val compressedBytesRead: Long, val slabCacheHits: Long, val verifiedStoredBytes: Long)
    class Counters {
        var acquisitions = 0L
        var physicalOpens = 0L
        var cacheHits = 0L
        var mappedBytes = 0L
        var headerBytesRead = 0L
        var lookupBytesRead = 0L
        var lookupComparisons = 0L
        var dataBytesRead = 0L
        var stringBytesRead = 0L
        var debugBytesRead = 0L
        var hashBytesRead = 0L
        var decodedBindings = 0L
        var decodedModules = 0L
        var directoryBytesRead = 0L
        var memberInflations = 0L
        var inflatedBytes = 0L
        var compressedBytesRead = 0L
        var slabCacheHits = 0L
        var verifiedStoredBytes = 0L
        @Synchronized fun statistics() = Statistics(acquisitions, physicalOpens, cacheHits, mappedBytes,
            headerBytesRead, lookupBytesRead, lookupComparisons, dataBytesRead, stringBytesRead, debugBytesRead, hashBytesRead,
            decodedBindings, decodedModules, directoryBytesRead, memberInflations, inflatedBytes,
            compressedBytesRead, slabCacheHits, verifiedStoredBytes)
    }
    val counters = Counters()
    private var closed = false
    private var mapped: Mapped? = null
    private val digest by lazy { MessageDigest.getInstance("MD5") }
    private class Mapped(val archive: CoreCbdArchive, val header: CoreCompactFormat.Header,
                         val handles: MutableMap<String, CoreCbdArchive.Handle>) : AutoCloseable {
        override fun close() { try { handles.values.forEach { it.close() }; handles.clear() } finally { archive.close() } }
    }

    private fun account(handle: CoreCbdArchive.Handle) {
        if (handle.inflated) {
            counters.memberInflations++
            counters.inflatedBytes += handle.member.length
            counters.compressedBytesRead += handle.member.compressed
        }
        if (handle.cacheHit) counters.slabCacheHits++
    }
    private fun member(current: Mapped, name: String): MemorySegment = current.handles.getOrPut(name) {
        current.archive.read(name).also(::account)
    }.bytes

    init { require(identity.matches(Regex("[0-9a-f]{64}"))) { "Missing compact Core producer identity" } }

    private fun mapping(): Mapped {
        check(!closed) { "Compact Core file is closed" }
        mapped?.let { return it }
        counters.acquisitions++
        // Verification observes the currently named file and retains that SAME
        // fresh lease. Normal loads trust producer identity and share mappings.
        val lease = if (verifyArtifacts) mappings.acquireUncached(path) else mappings.acquire(path, identity)
        if (lease.opened) counters.physicalOpens++ else counters.cacheHits++
        var archive: CoreCbdArchive? = null
        val handles = LinkedHashMap<String, CoreCbdArchive.Handle>()
        try {
            val bytes = lease.bytes
            counters.mappedBytes += bytes.byteSize()
            if (verifyArtifacts) {
                val sha = MessageDigest.getInstance("SHA-256")
                var at = 0L
                while (at < bytes.byteSize()) {
                    val length = minOf(8192L, bytes.byteSize() - at)
                    sha.update(bytes.asSlice(at, length).asByteBuffer())
                    at += length
                    counters.hashBytesRead += length
                }
                require(sha.digest().joinToString("") { "%02x".format(it) } == identity) {
                    "Compact Core artifact hash mismatch: $path"
                }
            }
            archive = CoreCbdArchive.open(lease, slabs)
            counters.directoryBytesRead += archive.directoryBytesRead
            val head = archive.read("header").also { handles["header"] = it; account(it) }
            val header = CoreCompactFormat.read(head.bytes, CoreCompactFormat.Segment.values().map { archive.member(it.member).length })
            counters.headerBytesRead += CoreCompactFormat.HEADER_BYTES
            val result = Mapped(archive, header, handles)
            if (verifyArtifacts) for (name in CoreCbdArchive.NAMES) {
                val payload = member(result, name)
                val entry = archive.member(name)
                if (entry.method == 0) {
                    val crc = CRC32()
                    var at = 0L
                    while (at < payload.byteSize()) {
                        val length = minOf(65536L, payload.byteSize() - at)
                        crc.update(payload.asSlice(at, length).asByteBuffer())
                        at += length
                        counters.verifiedStoredBytes += length
                    }
                    require(crc.value == entry.crc) { "CBD member CRC mismatch: $name" }
                }
            }
            return result.also { mapped = it }
        } catch (failure: Throwable) {
            handles.values.forEach { it.close() }
            archive?.close() ?: lease.close()
            throw failure
        }
    }

    fun header(): CoreCompactFormat.Header = synchronized(counters) { mapping().header }

    /** Fixed-width MD5 search; no strings or executable records are decoded. */
    fun lookup(id: String): Long? = synchronized(counters) {
        val current = mapping()
        val symbols = current.header[CoreCompactFormat.Segment.SYMBOLS]
        val bytes = member(current, "symbols")
        val key = digest.digest(id.toByteArray(Charsets.UTF_8))
        var low = 0L
        var high = current.header.bindingCount
        while (low < high) {
            val middle = low + (high - low) / 2
            val start = symbols.offset + middle * CoreCompactFormat.SYMBOL_BYTES
            counters.lookupComparisons++
            var comparison = 0
            for (index in key.indices) {
                counters.lookupBytesRead++
                comparison = (bytes.get(ValueLayout.JAVA_BYTE, start + index).toInt() and 255) -
                    (key[index].toInt() and 255)
                if (comparison != 0) break
            }
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle
                else -> {
                    val offset = bytes.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), start + 16)
                    counters.lookupBytesRead += 8
                    require(offset >= 0 && offset < current.header[CoreCompactFormat.Segment.DATA].length) {
                        "Invalid compact Core binding offset: $offset"
                    }
                    return@synchronized offset
                }
            }
        }
        null
    }

    /** Exhaustive verification only. Ordinary lookup never walks this table. */
    fun verifyBindingOffsets(visit: (Long) -> Unit) = synchronized(counters) {
        check(verifyArtifacts) { "Complete compact binding inspection requires explicit verification" }
        val current = mapping()
        val span = current.header[CoreCompactFormat.Segment.SYMBOLS]
        val cursor = CoreCompactCursor(CoreCompactCursor.slice(member(current, "symbols"), span.offset, span.length))
        var previous: ByteArray? = null
        try {
            while (cursor.remaining != 0L) {
                val digest = cursor.bytes(16)
                previous?.let { require(java.util.Arrays.compareUnsigned(it, digest) < 0) {
                    "Unordered or duplicate compact Core fingerprint"
                } }
                previous = digest
                val offset = cursor.offset()
                require(offset < current.header[CoreCompactFormat.Segment.DATA].length) {
                    "Invalid compact Core binding offset: $offset"
                }
                visit(offset)
            }
        } finally { counters.lookupBytesRead += cursor.position }
    }

    /** The callback must finish while this file owns its member leases. */
    fun <T> data(offset: Long, decode: (CoreCompactCursor) -> T): T = synchronized(counters) {
        val current = mapping()
        val span = current.header[CoreCompactFormat.Segment.DATA]
        val cursor = CoreCompactCursor(CoreCompactCursor.slice(member(current, "data"), span.offset, span.length), offset)
        try { decode(cursor) } finally { counters.dataBytesRead += cursor.position - offset }
    }

    fun <T> facts(decode: (CoreCompactCursor) -> T): T = synchronized(counters) {
        val current = mapping()
        val span = current.header.facts
        val cursor = CoreCompactCursor(CoreCompactCursor.slice(member(current, "header"), span.offset, span.length))
        try { decode(cursor) } finally { counters.headerBytesRead += cursor.position }
    }

    fun string(offset: Long, length: Long): String = synchronized(counters) {
        val current = mapping()
        val span = current.header[CoreCompactFormat.Segment.STRINGS]
        val strings = CoreCompactCursor.slice(member(current, "strings"), span.offset, span.length)
        // Count only a valid selected range, including a failing UTF8 decode.
        CoreCompactCursor.slice(strings, offset, length)
        counters.stringBytesRead += length
        CoreCompactCursor.utf8(strings, offset, length)
    }

    fun <T> debug(segment: CoreCompactFormat.Segment, decode: (CoreCompactCursor) -> T): T = synchronized(counters) {
        debugAt(segment, 0, mapping().header[segment].length, decode)
    }

    fun <T> debugAt(segment: CoreCompactFormat.Segment, offset: Long, length: Long,
                    decode: (CoreCompactCursor) -> T): T = synchronized(counters) {
        require(segment in setOf(CoreCompactFormat.Segment.NAMES, CoreCompactFormat.Segment.FILENAMES,
            CoreCompactFormat.Segment.LINE_COLUMNS)) { "Not a compact Core debug segment" }
        val current = mapping()
        val span = current.header[segment]
        val selected = CoreCompactCursor.slice(member(current, segment.member), span.offset, span.length)
        val cursor = CoreCompactCursor(CoreCompactCursor.slice(selected, offset, length))
        try { decode(cursor) } finally { counters.debugBytesRead += cursor.position }
    }

    override fun close() = synchronized(counters) {
        if (!closed) {
            closed = true
            val retained = mapped
            mapped = null
            retained?.close()
        }
    }
}
