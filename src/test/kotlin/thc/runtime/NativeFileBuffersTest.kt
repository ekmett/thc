// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.Language
import thc.NativeIO
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Storage/lifetime extension only: no new foreign-call or descriptor admission. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class NativeFileBuffersTest {
    @TempDir lateinit var directory: Path
    private fun <T> entered(context: Context, body: () -> T): T {
        context.initialize("thc"); context.enter()
        return try { body() } finally { context.leave() }
    }
    private fun buffer(native: Boolean, size: Int): ManagedAddress =
        if (native) Language.currentState().nativeAllocations.malloc(size.toLong()).also { address ->
            assertNotSame(ManagedAddress.nullAddress(), address)
            address.withNativeSegment { it.fill(90.toByte()) }
        } else ManagedAddress.fromByteArray(ByteArray(size) { 90 })
    private fun release(address: ManagedAddress) {
        if (address.nativeAllocation() != null) Language.currentState().nativeAllocations.free(address)
    }
    private fun bytes(address: ManagedAddress, size: Int) = (0 until size).map { address.readWord8(it.toLong()).toInt() }

    @Test fun nativeAndManagedFileBuffersPreserveOffsetsShortReadsEofAndStickyErrno() {
        NativeIO.createContext().use { context -> entered(context) {
            val state = Language.currentState(); val files = state.files; val stdio = state.stdio
            val path = directory.resolve("bytes")
            val name = ManagedAddress.fromByteArray(path.toString().toByteArray() + byteArrayOf(0))
            for (native in listOf(false, true)) {
                Files.write(path, byteArrayOf(1, 2, 3, 4, 5, 6))
                val fd = files.open(name, 3)
                assertTrue(fd >= 3)
                val base = buffer(native, 12)
                try {
                    assertEquals(-1L, stdio.close(-1)); val errno = stdio.errno()
                    assertEquals(6L, stdio.read(fd, base.plus(3), 8))
                    assertEquals(listOf(90, 90, 90, 1, 2, 3, 4, 5, 6, 90, 90, 90), bytes(base, 12))
                    assertEquals(errno, stdio.errno())
                    assertEquals(0L, stdio.read(fd, base.plus(3), 2))
                    assertEquals(0L, stdio.read(fd, base.plus(12), 0))
                    assertEquals(0L, files.seek(fd, 0, 0))
                    assertEquals(3L, stdio.write(fd, base.plus(4), 3))
                    assertEquals(errno, stdio.errno())
                    assertArrayEquals(byteArrayOf(2, 3, 4, 4, 5, 6), Files.readAllBytes(path))
                    assertEquals(listOf(90, 90, 90, 1, 2, 3, 4, 5, 6, 90, 90, 90), bytes(base, 12))
                } finally { release(base); assertEquals(0L, files.close(fd)) }
            }
        } }
    }

    @Test fun streamWindowsReportOnlyTransferredBytesAndInvalidMemoryPrecedesEffects() {
        val reads = AtomicInteger()
        val input = object : ByteArrayInputStream(ByteArray(2 * 1024 * 1024) { 17 }) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                reads.incrementAndGet()
                return super.read(target, offset, length)
            }
        }
        val output = ByteArrayOutputStream()
        Context.newBuilder("thc").allowNativeAccess(true).`in`(input).out(output).build().use { context -> entered(context) {
            val state = Language.currentState(); val stdio = state.stdio
            val size = 2 * 1024 * 1024 + 8
            val base = buffer(true, size)
            val alias = base.plus(3)
            try {
                for (address in listOf(alias.plus((size - 3).toLong()),
                    ManagedAddress.unownedNumeric(base.toNativeBits()), ManagedAddress.nullAddress())) {
                    assertThrows(RuntimeFault::class.java) { stdio.read(0, address, 1) }
                    assertThrows(RuntimeFault::class.java) { stdio.write(1, address, 1) }
                }
                for (count in listOf(-1L, Long.MAX_VALUE, size.toLong())) {
                    assertThrows(RuntimeFault::class.java) { stdio.read(0, alias, count) }
                    assertThrows(RuntimeFault::class.java) { stdio.write(1, alias, count) }
                }
                assertEquals(0, reads.get()); assertEquals(0, output.size())
                assertEquals(1024 * 1024L, stdio.read(0, alias, 2 * 1024 * 1024L))
                assertEquals(90L, base.readWord8(2)); assertEquals(17L, alias.readWord8(0))
                assertEquals(17L, alias.readWord8(1024 * 1024L - 1))
                assertEquals(90L, alias.readWord8(1024 * 1024L))
                assertEquals(1024 * 1024L, stdio.write(1, alias, 2 * 1024 * 1024L))
                assertEquals(1024 * 1024, output.size())
                assertTrue(output.toByteArray().all { it == 17.toByte() })
                Context.newBuilder("thc").allowNativeAccess(true).build().use { other -> entered(other) {
                    assertThrows(RuntimeFault::class.java) { Language.currentState().stdio.read(0, alias, 1) }
                    assertThrows(RuntimeFault::class.java) { Language.currentState().stdio.write(1, alias, 1) }
                } }
            } finally { release(base) }
            val before = reads.get(); val written = output.size()
            assertThrows(RuntimeFault::class.java) { stdio.read(0, alias, 1) }
            assertThrows(RuntimeFault::class.java) { stdio.write(1, alias, 1) }
            assertEquals(before, reads.get()); assertEquals(written, output.size())
        } }
    }

    @Test fun borrowedAliasKeepsAllocationAliveUntilBlockedReadAndCopybackComplete() {
        val reading = CountDownLatch(1); val releaseRead = CountDownLatch(1)
        lateinit var base: ManagedAddress
        val input = object : InputStream() {
            override fun read(): Int = error("Unexpected single-byte read")
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                reading.countDown(); check(releaseRead.await(10, TimeUnit.SECONDS))
                base.writeWord8(0, 73) // Same owner, reentrant borrow while free is queued.
                target[offset] = 41; target[offset + 1] = 42
                return 2
            }
        }
        Context.newBuilder("thc").allowNativeAccess(true).`in`(input).build().use { context ->
            val state = entered(context) { Language.currentState().also { base = buffer(true, 8) } }
            val alias = entered(context) { base.plus(3) }
            val workers = Executors.newFixedThreadPool(2)
            try {
                val read = workers.submit<Long> { entered(context) { state.stdio.read(0, alias, 4) } }
                assertTrue(reading.await(10, TimeUnit.SECONDS))
                val freeing = CountDownLatch(1)
                val freed = workers.submit { entered(context) { freeing.countDown(); state.nativeAllocations.free(base) } }
                assertTrue(freeing.await(10, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { freed.get(100, TimeUnit.MILLISECONDS) }
                releaseRead.countDown()
                assertEquals(2L, read.get(10, TimeUnit.SECONDS)); freed.get(10, TimeUnit.SECONDS)
                entered(context) { assertThrows(RuntimeFault::class.java) { alias.readWord8(0) } }
            } finally {
                releaseRead.countDown(); workers.shutdownNow()
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun partialStreamFailuresPreserveBytesAndReleaseNativeBorrows() {
        for (native in listOf(false, true)) for (read in listOf(false, true)) for (hard in listOf(false, true)) {
            val death = ThreadDeath()
            fun failure(): Nothing = if (hard) throw death else throw IOException("expected transfer failure")
            val input = object : InputStream() {
                override fun read(): Int = failure()
                override fun read(target: ByteArray, offset: Int, length: Int): Int {
                    target[offset] = 41; target[offset + 1] = 42
                    failure()
                }
            }
            val output = object : OutputStream() {
                override fun write(value: Int) = failure()
                override fun write(source: ByteArray, offset: Int, length: Int) = failure()
            }
            Context.newBuilder("thc").allowNativeAccess(true).`in`(input).out(output).build().use { context -> entered(context) {
                val state = Language.currentState(); val base = buffer(native, 8)
                fun transfer() = if (read) state.stdio.read(0, base.plus(2), 3) else state.stdio.write(1, base.plus(2), 3)
                if (hard) assertSame(death, assertThrows(ThreadDeath::class.java) { transfer() })
                else {
                    assertEquals(-1L, transfer()); assertEquals(StdioHostAbi.load().error(6), state.stdio.errno())
                }
                assertEquals(if (read) listOf(90, 90, 41, 42, 90, 90, 90, 90) else List(8) { 90 }, bytes(base, 8))
                // Free rejects a borrow held by this thread instead of hanging,
                // so this also proves every error path released its borrow.
                release(base)
                assertEquals(0, state.nativeAllocations.liveCount())
            } }
        }
    }
}
