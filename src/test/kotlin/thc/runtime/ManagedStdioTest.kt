// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.FileSystem
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.Language
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute

class ManagedStdioTest {
    @TempDir lateinit var directory: Path
    private fun bytes(vararg value: Byte) = ManagedAddress.fromByteArray(value)
    private fun context(output: ByteArrayOutputStream, errors: ByteArrayOutputStream = ByteArrayOutputStream()) =
        Context.newBuilder("thc").allowIO(IOAccess.NONE).out(output).err(errors).build()
    private fun <T> entered(context: Context, action: (ManagedStdio) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(Language.currentState().stdio) } finally { context.leave() }
    }

    @Test fun originalWritesUseContextStreamsBinaryRangesAndActualStickyErrno() {
        val output = ByteArrayOutputStream(); val errors = ByteArrayOutputStream()
        val badDescriptor = StdioHostAbi.load().error(4)
        context(output, errors).use { context -> entered(context) { stdio ->
            val address = bytes(99, 0, 127, -128, -1, 98)
            assertEquals(0L, stdio.errno())
            assertEquals(4L, stdio.write(1, address.plus(1), 4))
            assertArrayEquals(byteArrayOf(0, 127, -128, -1), output.toByteArray())
            assertEquals(-1L, stdio.write(-1, address, 1)); assertEquals(badDescriptor, stdio.errno())
            assertEquals(2L, stdio.write(2, address.plus(3), 2))
            assertArrayEquals(byteArrayOf(-128, -1), errors.toByteArray())
            assertEquals(badDescriptor, stdio.errno())
            assertEquals(0L, stdio.write(1, address.plus(6), 0))
            assertEquals(-1L, stdio.write(0, address.plus(6), 0))
            assertEquals(badDescriptor, stdio.errno()); assertEquals(4, output.size())
        } }
    }

    @Test fun ordinaryIoFailureSetsHostEioButMalformedValuesFaultBeforeEffects() {
        val output = object : ByteArrayOutputStream() {
            override fun write(value: ByteArray, offset: Int, length: Int) { throw IOException("host stream failed") }
        }
        context(output).use { context -> entered(context) { stdio ->
            val address = bytes(7)
            val ioError = StdioHostAbi.load().error(6)
            assertEquals(-1L, stdio.write(1, address, 1)); assertEquals(ioError, stdio.errno())
            for (count in listOf(-1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE))
                assertThrows(RuntimeFault::class.java) { stdio.write(1, address, count) }
            for (fd in listOf(Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1))
                assertThrows(RuntimeFault::class.java) { stdio.write(fd, address, 1) }
            assertEquals(ioError, stdio.errno()); assertEquals(0, output.size())
        } }
    }

    @Test fun errnoAndStreamsDoNotLeakBetweenContexts() {
        val one = ByteArrayOutputStream(); val two = ByteArrayOutputStream()
        context(one).use { first -> context(two).use { second ->
            entered(first) { assertEquals(-1L, it.write(-1, bytes(1), 1)) }
            entered(second) {
                assertEquals(0L, it.errno()); assertEquals(1L, it.write(1, bytes(2), 1))
            }
            entered(first) {
                assertEquals(StdioHostAbi.load().error(4), it.errno())
                assertEquals(1L, it.write(1, bytes(3), 1))
            }
        } }
        assertArrayEquals(byteArrayOf(3), one.toByteArray()); assertArrayEquals(byteArrayOf(2), two.toByteArray())
    }

    @Test fun partialCountsArePreservedAndNonemptyZeroProgressCannotSpin() {
        val backing = FileSystem.newDefaultFileSystem()
        var stalled = false
        val fs = object : FileSystem by backing {
            override fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): SeekableByteChannel {
                val delegate = backing.newByteChannel(path, options, *attributes)
                return object : SeekableByteChannel by delegate {
                    override fun write(source: ByteBuffer): Int {
                        if (stalled) return 0
                        val limit = source.limit()
                        source.limit(source.position() + minOf(2, source.remaining()))
                        return try { delegate.write(source) } finally { source.limit(limit) }
                    }
                }
            }
        }
        Context.newBuilder("thc").allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build().use { context -> entered(context) { stdio ->
            val path = directory.resolve("partial.bin")
            val fd = Language.currentState().files.open(ManagedAddress.fromByteArray((path.toString() + "\u0000").toByteArray()), 1)
            assertTrue(fd >= 3)
            assertEquals(2L, stdio.write(fd, bytes(1, 2, 3, 4), 4))
            assertEquals(0L, stdio.errno()); assertArrayEquals(byteArrayOf(1, 2), Files.readAllBytes(path))
            stalled = true
            assertEquals(-1L, stdio.write(fd, bytes(3, 4), 2))
            assertEquals(StdioHostAbi.load().error(6), stdio.errno())
            assertArrayEquals(byteArrayOf(1, 2), Files.readAllBytes(path))
            assertEquals(0L, stdio.write(fd, bytes(), 0))
        } }
    }
}
