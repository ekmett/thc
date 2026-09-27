// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Checked ordinary ZIP/ZIP64 directory over one immutable mapped snapshot.
 * Opening reads framing only. Payloads are neither decoded nor CRC-scanned.
 * Bounds are member-relative after a handle has been acquired. */
internal class CoreCbdArchive private constructor(private var mapping: CoreFileMappings.Lease?,
                                                private val slabs: CoreCbdSlabs) : AutoCloseable {
    companion object {
        val NAMES = setOf("header", "data", "strings", "names", "filenames", "line-columns", "symbols")
        fun open(mapping: CoreFileMappings.Lease, slabs: CoreCbdSlabs = CoreCbdSlabs.shared): CoreCbdArchive =
            try { CoreCbdArchive(mapping, slabs) } catch (failure: Throwable) {
                try { mapping.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
    }
    data class Member(val name: String, val method: Int, val start: Long, val compressed: Long,
                      val length: Long, val crc: Long)
    class Handle internal constructor(private var owner: AutoCloseable?, private val view: MemorySegment,
                                     val member: Member, val inflated: Boolean, val cacheHit: Boolean) : AutoCloseable {
        val bytes: MemorySegment get() = synchronized(this) {
            check(owner != null) { "CBD member handle is closed" }; view
        }
        @Synchronized override fun close() {
            val retained = owner ?: return
            owner = null
            retained.close()
        }
    }
    private val directory = Directory(checkNotNull(mapping).bytes)
    private val members = directory.read()
    val directoryBytesRead: Long get() = directory.reads

    @Synchronized fun member(name: String): Member {
        check(mapping != null) { "CBD archive is closed" }
        return requireNotNull(members[name]) { "Unknown CBD member: $name" }
    }
    fun read(name: String): Handle {
        val member: Member
        val retained: CoreFileMappings.Lease
        synchronized(this) {
            member = member(name)
            retained = checkNotNull(mapping).retain()
        }
        if (member.method == 0) return try {
            Handle(retained, retained.bytes.asSlice(member.start, member.length), member, false, false)
        } catch (failure: Throwable) { retained.close(); throw failure }
        try {
            val slab = slabs.acquire(CoreCbdSlabs.Key(retained.snapshot, name)) {
                inflate(retained.bytes.asSlice(member.start, member.compressed), member)
            }
            return Handle(slab, slab.bytes, member, slab.inflated, !slab.inflated)
        } finally { retained.close() }
    }
    @Synchronized override fun close() { val retained = mapping; mapping = null; retained?.close() }

    private fun inflate(source: MemorySegment, member: Member): CoreCbdSlabs.Slab {
        val arena = Arena.ofShared()
        try {
            val output = arena.allocate(member.length)
            val crc = CRC32()
            Inflater(true).use { inflater ->
                var inputAt = 0L
                var outputAt = 0L
                val overflow = ByteBuffer.allocate(1)
                while (!inflater.finished()) {
                    if (inflater.needsInput() && inputAt < source.byteSize()) {
                        val amount = minOf(65536L, source.byteSize() - inputAt)
                        inflater.setInput(source.asSlice(inputAt, amount).asByteBuffer())
                        inputAt += amount
                    }
                    val target = if (outputAt < member.length)
                        output.asSlice(outputAt, minOf(65536L, member.length - outputAt)).asByteBuffer()
                    else overflow.clear()
                    val amount = inflater.inflate(target)
                    require(amount.toLong() <= member.length - outputAt) { "CBD inflated member exceeds declared length: ${member.name}" }
                    if (amount != 0) {
                        target.flip()
                        crc.update(target)
                        outputAt += amount
                    } else if (!inflater.finished()) {
                        require(!inflater.needsDictionary()) { "CBD member requires a Deflate dictionary" }
                        require(inflater.needsInput() && inputAt < source.byteSize()) { "Truncated or stalled CBD Deflate stream: ${member.name}" }
                    }
                }
                require(outputAt == member.length && inflater.bytesRead == member.compressed) {
                    "CBD Deflate length mismatch: ${member.name}"
                }
                require(crc.value == member.crc) { "CBD member CRC mismatch: ${member.name}" }
            }
            return CoreCbdSlabs.Slab(arena, output.asReadOnly())
        } catch (failure: Throwable) {
            try { arena.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private class Directory(private val bytes: MemorySegment) {
        var reads = 0L
        private val size = bytes.byteSize()
        private fun extent(at: Long, length: Long, end: Long = size): Long {
            require(at >= 0 && length >= 0 && at <= end && length <= end - at) { "Invalid CBD ZIP extent" }
            return at + length
        }
        private fun number(at: Long, width: Int): Long {
            extent(at, width.toLong())
            reads += width
            var value = 0L
            for (i in 0 until width) value = value or ((bytes.get(ValueLayout.JAVA_BYTE, at + i).toLong() and 255) shl (8 * i))
            return value
        }
        private fun u16(at: Long) = number(at, 2).toInt()
        private fun u32(at: Long) = number(at, 4)
        private fun u64(at: Long) = number(at, 8).also { require(it >= 0) { "CBD ZIP64 value exceeds supported address range" } }
        private fun name(at: Long, length: Int): String {
            require(length in 1..12) { "Unknown CBD ZIP member" }
            extent(at, length.toLong())
            reads += length
            val name = String(bytes.asSlice(at, length.toLong()).toArray(ValueLayout.JAVA_BYTE), Charsets.US_ASCII)
            require(name in NAMES) { "Unknown CBD ZIP member: $name" }
            return name
        }
        private fun zip64(at: Long, length: Int): Pair<Long, Long>? {
            val end = extent(at, length.toLong())
            var cursor = at
            var result: Pair<Long, Long>? = null
            while (cursor < end) {
                extent(cursor, 4, end)
                val tag = u16(cursor)
                val count = u16(cursor + 2)
                val next = extent(cursor + 4, count.toLong(), end)
                if (tag == 1) {
                    require(result == null) { "Duplicate CBD ZIP64 extra field" }
                    result = cursor + 4 to next
                }
                cursor = next
            }
            return result
        }
        fun read(): Map<String, Member> {
            require(size >= 22) { "Truncated CBD ZIP directory" }
            var eocd = size - 22
            val minimum = maxOf(0, size - 65557)
            while (eocd >= minimum) {
                if (u32(eocd) == 0x06054b50L && u16(eocd + 20).toLong() == size - eocd - 22) break
                eocd--
            }
            require(eocd >= minimum) { "Missing CBD ZIP directory" }
            require(u16(eocd + 4) == 0 && u16(eocd + 6) == 0) { "Multi-disk CBD ZIP is unsupported" }
            val diskCount = u16(eocd + 8)
            val totalCount = u16(eocd + 10)
            val size32 = u32(eocd + 12)
            val offset32 = u32(eocd + 16)
            var count = totalCount.toLong()
            var directorySize = size32
            var directoryAt = offset32
            var directoryEnd = eocd
            if (eocd >= 20 && u32(eocd - 20) == 0x07064b50L) {
                val locator = eocd - 20
                require(u32(locator + 4) == 0L && u32(locator + 16) == 1L) { "Multi-disk CBD ZIP64 is unsupported" }
                val record = u64(locator + 8)
                extent(record, 56, locator)
                require(u32(record) == 0x06064b50L) { "Invalid CBD ZIP64 directory" }
                val recordSize = u64(record + 4)
                require(recordSize >= 44 && extent(record + 12, recordSize, locator) == locator) { "Invalid CBD ZIP64 directory extent" }
                require(u16(record + 14) <= 45 && u32(record + 16) == 0L && u32(record + 20) == 0L) { "Unsupported CBD ZIP64 directory" }
                count = u64(record + 32)
                require(u64(record + 24) == count) { "Multi-disk CBD ZIP64 member count" }
                directorySize = u64(record + 40)
                directoryAt = u64(record + 48)
                directoryEnd = record
                require((diskCount == 65535 || diskCount.toLong() == count) &&
                    (totalCount == 65535 || totalCount.toLong() == count) &&
                    (size32 == 0xffffffffL || size32 == directorySize) &&
                    (offset32 == 0xffffffffL || offset32 == directoryAt)) { "CBD ZIP/ZIP64 directory disagreement" }
            } else require(diskCount == totalCount && totalCount != 65535 && size32 != 0xffffffffL && offset32 != 0xffffffffL) {
                "Missing CBD ZIP64 directory"
            }
            require(count == NAMES.size.toLong() && extent(directoryAt, directorySize, directoryEnd) == directoryEnd) {
                "Invalid CBD ZIP directory count or extent"
            }
            val result = LinkedHashMap<String, Member>()
            val occupied = ArrayList<Pair<Long, Long>>()
            var cursor = directoryAt
            repeat(NAMES.size) {
                extent(cursor, 46, directoryEnd)
                require(u32(cursor) == 0x02014b50L) { "Invalid CBD ZIP central entry" }
                val version = u16(cursor + 6)
                val flags = u16(cursor + 8)
                val method = u16(cursor + 10)
                require(version in 10..45 && flags and 0x80e.inv() == 0 && method in listOf(0, 8) &&
                    (method == 8 || flags and 6 == 0)) { "Unsupported CBD ZIP method, flags or version" }
                val crc = u32(cursor + 16)
                var compressed = u32(cursor + 20)
                var length = u32(cursor + 24)
                val nameLength = u16(cursor + 28)
                val extraLength = u16(cursor + 30)
                val commentLength = u16(cursor + 32)
                var disk = u16(cursor + 34).toLong()
                var local = u32(cursor + 42)
                val extraAt = extent(cursor + 46, nameLength.toLong(), directoryEnd)
                val commentAt = extent(extraAt, extraLength.toLong(), directoryEnd)
                val next = extent(commentAt, commentLength.toLong(), directoryEnd)
                val name = name(cursor + 46, nameLength)
                require(name !in result) { "Duplicate CBD ZIP member: $name" }
                val extra = zip64(extraAt, extraLength)
                var extraCursor = extra?.first ?: 0
                fun longExtra(): Long {
                    val end = requireNotNull(extra) { "Missing CBD ZIP64 extra field" }.second
                    extent(extraCursor, 8, end)
                    return u64(extraCursor).also { extraCursor += 8 }
                }
                if (length == 0xffffffffL) length = longExtra()
                if (compressed == 0xffffffffL) compressed = longExtra()
                if (local == 0xffffffffL) local = longExtra()
                if (disk == 65535L) {
                    extent(extraCursor, 4, requireNotNull(extra).second)
                    disk = u32(extraCursor)
                }
                require(disk == 0L && (method != 0 || compressed == length)) { "Invalid CBD ZIP member sizes or disk" }
                extent(local, 30, directoryAt)
                require(u32(local) == 0x04034b50L && u16(local + 4) == version && u16(local + 6) == flags &&
                    u16(local + 8) == method) { "CBD ZIP local/central header disagreement" }
                val localNameLength = u16(local + 26)
                val localExtraLength = u16(local + 28)
                val localExtraAt = extent(local + 30, localNameLength.toLong(), directoryAt)
                val payload = extent(localExtraAt, localExtraLength.toLong(), directoryAt)
                require(name(local + 30, localNameLength) == name) { "CBD ZIP local/central name disagreement" }
                var localCompressed = u32(local + 18)
                var localLength = u32(local + 22)
                val local64 = localCompressed == 0xffffffffL || localLength == 0xffffffffL
                val localExtra = zip64(localExtraAt, localExtraLength)
                if (local64) {
                    val pair = requireNotNull(localExtra) { "Missing local CBD ZIP64 sizes" }
                    extent(pair.first, 16, pair.second)
                    val actualLength = u64(pair.first)
                    val actualCompressed = u64(pair.first + 8)
                    require((localLength == 0xffffffffL || localLength == actualLength) &&
                        (localCompressed == 0xffffffffL || localCompressed == actualCompressed)) { "CBD ZIP64 local size disagreement" }
                    localLength = actualLength
                    localCompressed = actualCompressed
                }
                val localCrc = u32(local + 14)
                var end = extent(payload, compressed, directoryAt)
                if (flags and 8 == 0) {
                    require(localCrc == crc && localLength == length && localCompressed == compressed) { "CBD ZIP local/central size or CRC disagreement" }
                } else {
                    require((localCrc == 0L || localCrc == crc) && (localLength == 0L || localLength == length) &&
                        (localCompressed == 0L || localCompressed == compressed)) { "CBD ZIP descriptor header disagreement" }
                    val wide = local64 || length >= 0xffffffffL || compressed >= 0xffffffffL
                    fun descriptor(at: Long): Long? {
                        val width = if (wide) 8 else 4
                        val needed = 4L + width * 2
                        if (at > directoryAt || needed > directoryAt - at) return null
                        if (u32(at) != crc) return null
                        val a = if (wide) u64(at + 4) else u32(at + 4)
                        val b = if (wide) u64(at + 12) else u32(at + 8)
                        return if (a == compressed && b == length) at + needed else null
                    }
                    end = (if (end <= directoryAt - 4 && u32(end) == 0x08074b50L) descriptor(end + 4) else null)
                        ?: descriptor(end) ?: throw IllegalArgumentException("Invalid CBD ZIP data descriptor")
                }
                occupied.add(local to end)
                result[name] = Member(name, method, payload, compressed, length, crc)
                cursor = next
            }
            require(cursor == directoryEnd) { "Trailing CBD ZIP directory bytes" }
            var end = 0L
            for ((start, next) in occupied.sortedBy { it.first }) {
                require(start >= end) { "Overlapping CBD ZIP members" }
                end = next
            }
            return result
        }
    }
}
