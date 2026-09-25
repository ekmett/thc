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
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Ownership tests for context descriptors, not native host fd/RTS lock emulation. */
class ManagedDescriptorDupTest {
    @TempDir lateinit var directory: Path
    private fun address(bytes: ByteArray) = ManagedAddress.fromByteArray(bytes)
    private fun path(value: Path) = address(value.toString().toByteArray() + byteArrayOf(0))
    private fun builder() = Context.newBuilder("thc").allowIO(IOAccess.ALL)
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
    private fun <T> entered(context: Context, action: (Language.State) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(Language.currentState()) } finally { context.leave() }
    }

    @Test fun aliasesShareOffsetsAndClaimUntilTheLastIndependentClose() {
        val file = directory.resolve("shared"); Files.write(file, byteArrayOf(10, 20, 30, 40))
        builder().build().use { context -> entered(context) { state ->
            val files = state.files
            val one = files.open(path(file), 3)
            val two = files.duplicate(one)
            val target = ByteArray(2)
            assertNotEquals(one, two)
            assertEquals(1L, files.read(one, address(target), 1)); assertEquals(10, target[0].toInt())
            assertEquals(1L, files.read(two, address(target), 1)); assertEquals(20, target[0].toInt())
            assertEquals(1L, files.write(two, address(byteArrayOf(99)), 1))
            assertEquals(3L, files.seek(one, 0, 1))
            assertEquals(0L, files.close(one))
            assertEquals(-1L, files.open(path(file), 1)); assertEquals(8L, files.errorKind())
            assertEquals(1L, files.read(two, address(target), 1)); assertEquals(40, target[0].toInt())
            assertEquals(0L, files.close(two))
            assertArrayEquals(byteArrayOf(10, 20, 99, 40), Files.readAllBytes(file))
            assertTrue(files.open(path(file), 1) >= 3)
        } }
    }

    @Test fun sparseLowestFreeAllocationSameFdAndStickyErrnoAreExact() {
        val output = object : ByteArrayOutputStream() { var flushes = 0; override fun flush() { flushes++ } }
        builder().out(output).build().use { context -> entered(context) { state ->
            val files = state.files; val stdio = state.stdio; val ebadf = StdioHostAbi.load().error(4)
            val flushes = output.flushes
            assertEquals(-1L, stdio.duplicateTo(-1, -1)); assertEquals(ebadf, stdio.errno())
            assertEquals(1L, stdio.duplicateTo(1, 1)); assertEquals(flushes, output.flushes)
            assertEquals(ebadf, stdio.errno())
            assertEquals(-1L, stdio.duplicateTo(-1, 1)); assertEquals(ebadf, stdio.errno())
            assertEquals(1L, files.write(1, address(byteArrayOf(7)), 1))
            for (fd in listOf(0L, 2L)) {
                assertEquals(0L, files.close(fd)); assertEquals(fd, stdio.duplicate(1))
            }
            assertEquals(Int.MAX_VALUE.toLong(), stdio.duplicateTo(1, Int.MAX_VALUE.toLong()))
            assertEquals(3L, stdio.duplicate(1))
            assertEquals(0L, files.close(1)); assertEquals(1L, stdio.duplicate(3))
            assertEquals(flushes, output.flushes, "Aliased streams flush only at final retirement")
            for (bad in listOf(Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1, Long.MAX_VALUE)) {
                assertThrows(RuntimeFault::class.java) { stdio.duplicate(bad) }
                assertThrows(RuntimeFault::class.java) { stdio.duplicateTo(1, bad) }
                assertThrows(RuntimeFault::class.java) { stdio.duplicateTo(bad, 1) }
            }
            assertEquals(ebadf, stdio.errno())
            assertEquals(-1L, stdio.duplicateTo(1, -1)); assertEquals(ebadf, stdio.errno())
            assertEquals(1L, files.write(1, address(byteArrayOf(8)), 1))
            assertArrayEquals(byteArrayOf(7, 8), output.toByteArray())
        } }
    }

    @Test fun boundedNamespaceReportsTheProbedEmfileWithoutChangingExistingDescriptors() {
        builder().build().use { context -> entered(context) { state ->
            val files = ManagedFiles(state.env, state.threads, 4)
            val stdio = ManagedStdio(files)
            try {
                assertEquals(3L, stdio.duplicate(1))
                assertEquals(-1L, stdio.duplicate(1)); assertEquals(StdioHostAbi.load().error(10), stdio.errno())
                assertEquals(-1L, stdio.duplicateTo(1, 4)); assertEquals(StdioHostAbi.load().error(4), stdio.errno())
                assertEquals(3L, stdio.duplicateTo(1, 3))
                assertEquals(0L, stdio.close(0)); assertEquals(0L, stdio.duplicate(1))
                assertEquals(StdioHostAbi.load().error(4), stdio.errno())
            } finally { files.dispose() }
        } }
    }

    @Test fun replacingAnAliasAndDisposingClosesEachOpenDescriptionOnce() {
        val file = directory.resolve("once"); Files.writeString(file, "keep")
        val delegate = FileSystem.newDefaultFileSystem(); var closes = 0
        val fs = object : FileSystem by delegate {
            override fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
                val channel = delegate.newByteChannel(path, options, *attrs)
                return object : SeekableByteChannel by channel { override fun close() { closes++; channel.close() } }
            }
        }
        builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build().use { context -> entered(context) { state ->
            val files = state.files; val one = files.open(path(file), 0); val two = files.duplicate(one)
            repeat(4) { assertEquals(two, files.duplicateTo(one, two)); assertEquals(0, closes) }
            assertEquals(0L, files.close(one)); assertEquals(0, closes)
            files.dispose(); assertEquals(1, closes)
            files.dispose(); assertEquals(1, closes)
            assertEquals(-1L, files.duplicate(two)); assertEquals(4L, files.errorKind())
        } }
    }

    @Test fun replacementIsVisibleAndClaimSurvivesCheckedCloseFailureAndReentrantDisposal() {
        for (dispose in listOf(false, true)) {
            val file = directory.resolve("retiring-$dispose"); Files.writeString(file, "keep")
            val delegate = FileSystem.newDefaultFileSystem(); var closes = 0
            lateinit var files: ManagedFiles
            var target = -1L
            val fs = object : FileSystem by delegate {
                override fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
                    val channel = delegate.newByteChannel(path, options, *attrs)
                    return object : SeekableByteChannel by channel {
                        override fun close() {
                            closes++
                            if (closes == 1) {
                                assertEquals(1L, files.deviceType(target), "Replacement already names the stream")
                                assertEquals(-1L, files.open(this@ManagedDescriptorDupTest.path(file), 1))
                                assertEquals(8L, files.errorKind(), "Retiring owner retains its file claim")
                                if (dispose) files.dispose()
                            }
                            channel.close()
                            if (closes == 1) throw IOException("Target close failed after replacement")
                        }
                    }
                }
            }
            builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build().use { context -> entered(context) { state ->
                files = state.files; target = files.open(path(file), 0)
                val errno = state.stdio.errno()
                assertEquals(target, state.stdio.duplicateTo(1, target))
                assertEquals(errno, state.stdio.errno(), "Silently closing the old target must not publish errno")
                assertEquals(1, closes); assertEquals("keep", Files.readString(file))
                if (!dispose) {
                    assertEquals(1L, files.deviceType(target))
                    // The old claim was removed even though its provider threw.
                    assertTrue(files.open(path(file), 1) >= 3)
                }
                files.dispose(); assertEquals(if (dispose) 1 else 2, closes)
            } }
        }
    }

    @Test fun waitingAliasCannotUseItsOldOwnerAfterAtomicReplacement() {
        val reading = CountDownLatch(1); val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val input = object : InputStream() {
            override fun read(): Int = error("Unexpected byte read")
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                check(first.compareAndSet(true, false)) { "Stale alias reached the old input" }
                reading.countDown(); check(release.await(10, TimeUnit.SECONDS)); bytes[offset] = 42; return 1
            }
        }
        builder().`in`(input).build().use { context ->
            val files = entered(context) { it.files }
            val alias = entered(context) { files.duplicate(0) }
            val pool = Executors.newFixedThreadPool(2)
            val waiting = AtomicReference<Thread>()
            try {
                val read = pool.submit<Long> { entered(context) { files.read(0, address(ByteArray(1)), 1) } }
                assertTrue(reading.await(10, TimeUnit.SECONDS))
                val stale = pool.submit<Long> { entered(context) {
                    waiting.set(Thread.currentThread()); files.read(alias, address(ByteArray(1)), 1)
                } }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (waiting.get()?.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, waiting.get()?.state)
                assertEquals(alias, entered(context) { files.duplicateTo(1, alias) })
                release.countDown()
                assertEquals(1L, read.get(10, TimeUnit.SECONDS)); assertEquals(-1L, stale.get(10, TimeUnit.SECONDS))
                assertEquals(1L, entered(context) { files.deviceType(alias) })
            } finally {
                release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }
}
