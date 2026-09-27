// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.runtime.CoreSources

class CoreCompactDebugTest {
    @TempDir lateinit var directory: Path
    private class Bytes : ByteArrayOutputStream() {
        fun u(value: Int) { var n = value; do { write((n and 127) or if (n > 127) 128 else 0); n = n ushr 7 } while (n != 0) }
        fun fixed(value: Long) { write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()) }
        fun text(value: String) { val bytes = value.toByteArray(); u(bytes.size); write(bytes) }
    }
    private fun fixture(badRange: Boolean = false, badPrimary: Boolean = false): ByteArray {
        val strings = Bytes()
        fun common(out: Bytes, value: String) { val bytes = value.toByteArray(); out.u(strings.size()); out.u(bytes.size); strings.write(bytes) }
        val files = Bytes().apply {
            write(1); u(1); common(this, "original"); common(this, "Actual.hs")
            write(2); common(this, "first\nsecond\n")
        }
        val noFile = files.size().toLong(); files.write(0)
        fun finishTable(out: Bytes, rows: List<Triple<Long, Long, Long>>) {
            val start = out.size().toLong()
            rows.forEach { (a, b, p) -> out.fixed(a); out.fixed(b); out.fixed(p) }
            out.fixed(start); out.fixed(rows.size.toLong())
        }
        finishTable(files, listOf(Triple(0, if (badRange) 101 else 30, 0), Triple(30, 40, noFile), Triple(40, 60, 0)))
        val lines = Bytes()
        fun note(id: String, line: Int, index: Int, length: Int): Long = lines.size().toLong().also {
            lines.write(1); lines.u(if (badPrimary) 1 else 0); lines.u(1); lines.text(id)
            lines.write(2); lines.text("label:$id")
            lines.u(line); lines.u(1); lines.u(line); lines.u(length + 1)
            lines.write(2); lines.u(index); lines.write(2); lines.u(length)
        }
        val first = note("first-span", 1, 0, 5)
        val second = note("second-span", 2, 6, 6)
        val none = lines.size().toLong(); lines.write(0)
        finishTable(lines, listOf(Triple(0, 10, first), Triple(10, 30, second), Triple(30, 40, none),
            Triple(40, 50, first), Triple(50, 60, second)))
        val names = Bytes().apply {
            val a = "outer".toByteArray(); val b = "λlocal".toByteArray(); val c = "Constructor".toByteArray()
            write(a); write(b); write(c)
            val start = size().toLong()
            fixed(0); fixed(0); fixed(0); fixed(a.size.toLong())
            fixed(0); fixed(1); fixed(a.size.toLong()); fixed(b.size.toLong())
            fixed(-1); fixed(1); fixed((a.size + b.size).toLong()); fixed(c.size.toLong())
            fixed(start); fixed(3)
        }
        val segments = listOf(ByteArray(100), strings.toByteArray(), names.toByteArray(), files.toByteArray(), lines.toByteArray(), byteArrayOf())
        val out = ByteBuffer.allocate(24 + segments.sumOf { it.size } + 128).order(ByteOrder.LITTLE_ENDIAN)
        out.put(byteArrayOf(84, 72, 67, 67, 77, 80, 0, 0)).putShort(1).putShort(0).putInt(0).putLong(0)
        segments.forEach(out::put)
        out.put(byteArrayOf(84, 72, 67, 67, 69, 78, 68, 49))
        var offset = 24L
        segments.forEach { out.putLong(offset).putLong(it.size.toLong()); offset += it.size }
        out.putLong(0).putInt(0).putInt(7).putLong(0)
        return out.array()
    }
    private fun withFile(bytes: ByteArray = fixture(), action: (CoreCompactFile) -> Unit) {
        val path = directory.resolve("debug.thcc")
        Files.write(path, bytes)
        val identity = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        CoreFileMappings(0, 0).use { cache -> CoreCompactFile(path, identity, mappings = cache).use(action) }
    }

    @Test fun selectedIntervalsRestoreSourcesAndKeepGapsExplicit() = withFile { file ->
        val debug = CoreCompactDebug(file)
        val origin = CoreCompactRecords.Origin("identity", 20, 0, debug)
        val location = CoreSources(emptyMap()).binding(mapOf("compactOrigin" to origin))!!
        assertSame(origin, location.compactOrigin)
        assertEquals(0L, file.counters.statistics().debugBytesRead)
        assertEquals("second", location.section!!.characters.toString())
        assertEquals("Actual.hs", location.section!!.source.name)
        assertEquals(listOf("second-span"), location.notes.map { it.id })
        val after = file.counters.statistics().debugBytesRead
        assertEquals("second", location.section!!.characters.toString())
        assertEquals(after, file.counters.statistics().debugBytesRead)
        assertNull(debug.location(30))
        assertEquals("first", debug.location(40)!!.section!!.characters.toString())
        assertNull(debug.location(60))
        assertNull(debug.location(99))
        assertEquals("outer", debug.name(0, 0))
        assertEquals("λlocal", debug.name(0, 1))
        assertNull(debug.name(0, 2))
        assertNull(debug.name(1, 0))
        assertEquals("Constructor", debug.constructorName(0))
        assertNull(debug.constructorName(1))
        assertThrows(IllegalArgumentException::class.java) { debug.name(-1, 1) }
        assertEquals(0L, file.counters.statistics().dataBytesRead)
        assertEquals(0L, file.counters.statistics().hashBytesRead)
    }

    @Test fun disabledSourceNotesKeepOriginWithoutReadingDebugPayloads() = withFile { file ->
        val origin = CoreCompactRecords.Origin("identity", 10, 0, CoreCompactDebug(file))
        val location = CoreSources(mapOf("sourceNotesEnabled" to false)).binding(mapOf("compactOrigin" to origin))!!
        assertSame(origin, location.compactOrigin)
        assertNull(location.section)
        assertTrue(location.notes.isEmpty())
        assertEquals(0L, file.counters.statistics().acquisitions)
        assertEquals(0L, file.counters.statistics().debugBytesRead)
    }

    @Test fun selectedMalformedRangesAndPrimaryIndicesFailLocally() {
        withFile(fixture(badRange = true)) { file ->
            assertThrows(IllegalArgumentException::class.java) { CoreCompactDebug(file).location(20) }
        }
        withFile(fixture(badPrimary = true)) { file ->
            assertThrows(IllegalArgumentException::class.java) { CoreCompactDebug(file).location(20) }
        }
    }
}
