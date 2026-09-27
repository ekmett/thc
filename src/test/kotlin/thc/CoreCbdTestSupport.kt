// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Independent standard ZIP writer; payload bytes remain the existing test models. */
internal object CoreCbdTestSupport {
    fun header(facts: ByteArray = byteArrayOf(), count: Long = 0, summaries: Int = 0, debug: Int = 0): ByteArray =
        ByteBuffer.allocate(32 + facts.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("THCCBD1\u0000".toByteArray()).putShort(1).putShort(0).putInt(summaries)
            .putLong(count).putInt(debug).putInt(0).put(facts).array()

    fun archive(header: ByteArray = header(), segments: List<ByteArray> = List(6) { byteArrayOf() },
                deflated: Set<String> = emptySet(), reverse: Boolean = false): ByteArray {
        val names = listOf("header", "data", "strings", "names", "filenames", "line-columns", "symbols")
        val members = names.zip(listOf(header) + segments).let { if (reverse) it.reversed() else it }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in members) {
                val entry = ZipEntry(name)
                entry.time = 315532800000L
                if (name !in deflated) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().also { it.update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    /** Small ZIP64 model uses sentinel fields despite small actual members. */
    fun zip64(offsetsOnly: Boolean = false): ByteArray {
        val members = listOf("header" to header(), "data" to byteArrayOf(42), "strings" to byteArrayOf(),
            "names" to byteArrayOf(), "filenames" to byteArrayOf(), "line-columns" to byteArrayOf(), "symbols" to byteArrayOf())
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Long>()
        fun fields(size: Int, emit: ByteBuffer.() -> Unit) { out.write(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(emit).array()) }
        for ((name, bytes) in members) {
            offsets += out.size().toLong()
            fields(30) {
                putInt(0x04034b50); putShort(if (offsetsOnly) 20 else 45); putShort(0); putShort(0); putInt(0)
                putInt(CRC32().also { it.update(bytes) }.value.toInt())
                putInt(if (offsetsOnly) bytes.size else -1); putInt(if (offsetsOnly) bytes.size else -1)
                putShort(name.length.toShort()); putShort(if (offsetsOnly) 0 else 20)
            }
            out.write(name.toByteArray())
            if (!offsetsOnly) fields(20) { putShort(1); putShort(16); putLong(bytes.size.toLong()); putLong(bytes.size.toLong()) }
            out.write(bytes)
        }
        val directory = out.size().toLong()
        for ((index, pair) in members.withIndex()) {
            val (name, bytes) = pair
            fields(46) {
                putInt(0x02014b50); putShort(45); putShort(45); putShort(0); putShort(0); putInt(0)
                putInt(CRC32().also { it.update(bytes) }.value.toInt())
                putInt(if (offsetsOnly) bytes.size else -1); putInt(if (offsetsOnly) bytes.size else -1)
                putShort(name.length.toShort()); putShort(if (offsetsOnly) 12 else 28); putShort(0); putShort(0); putShort(0); putInt(0); putInt(-1)
            }
            out.write(name.toByteArray())
            if (offsetsOnly) fields(12) { putShort(1); putShort(8); putLong(offsets[index]) }
            else fields(28) { putShort(1); putShort(24); putLong(bytes.size.toLong()); putLong(bytes.size.toLong()); putLong(offsets[index]) }
        }
        val directorySize = out.size() - directory
        val record = out.size().toLong()
        fields(56) {
            putInt(0x06064b50); putLong(44); putShort(45); putShort(45); putInt(0); putInt(0)
            putLong(7); putLong(7); putLong(directorySize); putLong(directory)
        }
        fields(20) { putInt(0x07064b50); putInt(0); putLong(record); putInt(1) }
        fields(22) { putInt(0x06054b50); putShort(0); putShort(0); putShort(-1); putShort(-1); putInt(-1); putInt(-1); putShort(0) }
        return out.toByteArray()
    }
}
