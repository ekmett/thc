// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.Language
import thc.NativeIO
import java.nio.channels.ClosedChannelException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Real opened FIFOs and private service protocol, not exported-Core or primop
 * admission proof. In particular these tests cannot replace the required exact
 * installed blockedOnBadFD payload and continuation-resumption fixture. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(40)
class NativeFdWaitTest {
    @TempDir lateinit var directory: Path
    private fun path(path: Path) = ManagedAddress.fromByteArray(path.toString().toByteArray() + byteArrayOf(0))
    private fun bytes(vararg values: Byte) = ManagedAddress.fromByteArray(values)
    private fun physicalDescriptors(): Long = Files.list(Path.of("/proc/self/fd")).use { entries ->
        entries.filter { try { Files.readSymbolicLink(it).startsWith(directory) }
            catch (_: java.nio.file.NoSuchFileException) { false } }.count()
    }
    private fun <T> entered(context: Context, action: () -> T): T {
        context.initialize("thc"); context.enter()
        return try { action() } finally { context.leave() }
    }
    private fun fifo(context: Context): Pair<Long, Long> {
        val file = directory.resolve("pipe-${System.nanoTime()}")
        val process = ProcessBuilder("mkfifo", file.toString()).start()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
        return entered(context) {
            val io = Language.currentState().stdio
            val read = io.open(path(file), 0x800, 0) // Linux O_RDONLY | O_NONBLOCK.
            assertTrue(read >= 3)
            val write = io.open(path(file), 0x801, 0)
            assertTrue(write >= 3)
            read to write
        }
    }
    private fun awaitRegistered(files: ManagedFiles, fd: Long) {
        // Bounded test barrier only; production never polls this observer.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (files.pendingReadiness(fd) == 0 && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(1, files.pendingReadiness(fd))
    }
    private class WaitRoot(language: Language, private val token: ManagedFiles.WaitToken,
                           private val id: AtomicLong, private val mask: MaskingState) : RootNode(language) {
        override fun execute(frame: VirtualFrame): Any {
            val state = Language.currentState(this)
            state.threads.enterCurrent(mask)
            id.set(state.threads.currentId())
            return try {
                try {
                    token.await(this, true)
                    // A pending request under uninterruptible masking must not
                    // have been claimed just because native poll was woken.
                    assertNull(state.threads.poll(this, true))
                    state.maskingState.set(MaskingState.UNMASKED)
                    state.threads.poll(this)?.let { it.acknowledge(); return it.payload ?: Unit }
                    Unit
                } catch (blocked: AsyncBlocked) {
                    assertNotEquals(MaskingState.MASKED_UNINTERRUPTIBLE, mask)
                    blocked.request.acknowledge()
                    blocked.request.payload ?: Unit
                }
            } finally { state.threads.leaveCurrent() }
        }
    }

    @Test fun originalDirectionTimeoutEofAndStickyErrnoUseActualFifoReadiness() {
        NativeIO.createContext(emptySet()).use { context ->
            val (read, write) = fifo(context)
            entered(context) {
                val io = Language.currentState().stdio
                assertEquals(-1L, io.close(-1)); val error = io.errno()
                assertEquals(0L, io.ready(read, 0, 0, 0))
                assertEquals(1L, io.ready(write, 1, 0, 0))
                val started = System.nanoTime()
                assertEquals(0L, io.ready(read, 0, 30, 0))
                assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(25))
                assertEquals(error, io.errno())
                assertEquals(1L, io.write(write, bytes(42), 1))
                assertEquals(1L, io.ready(read, 0, 0, 0))
                val result = bytes(0)
                assertEquals(1L, io.read(read, result, 1))
                assertEquals(42L, result.readWord8(0))
                assertEquals(0L, io.close(write))
                assertEquals(1L, io.ready(read, 0, -1, 0), "Hangup is an event; EOF read must not hang")
                assertEquals(0L, io.read(read, result, 1))
                assertEquals(error, io.errno())
            }
        }
    }

    @Test fun fullPipeWaitsForWriteCapacityAndDoesNotConsumeReadData() {
        val pool = Executors.newSingleThreadExecutor()
        val context = NativeIO.createContext(emptySet())
        try {
            val (read, write) = fifo(context)
            val state = entered(context) { Language.currentState() }
            entered(context) {
                val chunk = ManagedAddress.fromByteArray(ByteArray(4096) { 7 })
                var full = false
                repeat(1024) {
                    if (!full && state.stdio.write(write, chunk, 4096) < 0) full = true
                }
                assertTrue(full, "Bounded fixture must fill the actual pipe")
                assertEquals(0L, state.stdio.ready(write, 1, 0, 0))
            }
            val future = pool.submit<Long> { entered(context) { state.stdio.ready(write, 1, -1, 0) } }
            awaitRegistered(state.files, write)
            assertFalse(future.isDone)
            entered(context) {
                val output = ManagedAddress.fromByteArray(ByteArray(4096))
                assertEquals(4096L, state.stdio.read(read, output, 4096))
            }
            assertEquals(1L, future.get(5, TimeUnit.SECONDS))
            assertEquals(0, state.files.pendingReadiness(write))
        } finally {
            context.close(true); pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun readWaitUsesMaskAwareAsyncDeliveryAndReleasesItsNativeRequest() {
        for (mask in MaskingState.entries) {
            val pool = Executors.newSingleThreadExecutor()
            val context = NativeIO.createContext(emptySet())
            try {
                val (read, write) = fifo(context)
                val state = entered(context) { Language.currentState() }
                val id = AtomicLong(-1)
                val target = entered(context) {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    WaitRoot(language, state.files.waitToken(read, false), id, mask).callTarget
                }
                val future = pool.submit<Any> { entered(context) { target.call() } }
                awaitRegistered(state.files, read)
                val request = entered(context) { state.threads.send(id.get(), "interrupt $mask") }
                if (mask == MaskingState.MASKED_UNINTERRUPTIBLE) {
                    assertFalse(future.isDone)
                    assertEquals(AsyncRequestState.PENDING, request.state)
                    entered(context) { assertEquals(1L, state.stdio.write(write, bytes(1), 1)) }
                }
                assertEquals("interrupt $mask", future.get(5, TimeUnit.SECONDS))
                assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
                assertEquals(0, state.files.pendingReadiness(read))
                assertEquals(2L, physicalDescriptors(), "Interrupted wait must release its native duplicate")
            } finally {
                context.close(true); pool.shutdownNow()
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            }
            assertEquals(0L, physicalDescriptors())
        }
    }

    @Test fun closeAndDup2WakeOnlyTheOriginalDescriptorAndSavedTokenCannotFollowReuse() {
        for (replace in listOf(false, true)) {
            val pool = Executors.newSingleThreadExecutor()
            val context = NativeIO.createContext(emptySet())
            try {
                val (read, _) = fifo(context)
                val state = entered(context) { Language.currentState() }
                val old = entered(context) { state.files.waitToken(read, false) }
                val future = pool.submit<Boolean> { entered(context) {
                    try { old.await(object : Node() {}, false); false }
                    catch (_: ClosedChannelException) { true }
                } }
                awaitRegistered(state.files, read)
                entered(context) {
                    val regular = directory.resolve("ready-$replace")
                    Files.writeString(regular, "ready")
                    val ready = state.files.open(path(regular), 0)
                    if (replace) assertEquals(read, state.files.duplicateTo(ready, read))
                    else {
                        assertEquals(0L, state.files.close(read))
                        assertEquals(read, state.files.duplicateTo(ready, read))
                    }
                    assertEquals(1L, state.files.ready(read, 0))
                }
                assertTrue(future.get(5, TimeUnit.SECONDS), "Old wait must observe close, not replacement readiness")
                entered(context) { assertThrows(ClosedChannelException::class.java) {
                    old.await(object : Node() {}, false)
                } }
            } finally {
                context.close(true); pool.shutdownNow()
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun hostCancellationUnblocksNativePollAndOpaqueStreamsRemainDenied() {
        Context.newBuilder("thc").build().use { context -> entered(context) {
            val io = Language.currentState().stdio
            assertEquals(-1L, io.ready(0, 0, 0, 0))
            assertEquals(StdioHostAbi.load().error(7), io.errno())
        } }
        val pool = Executors.newSingleThreadExecutor()
        val context = NativeIO.createContext(emptySet())
        try {
            val (read, _) = fifo(context)
            val state = entered(context) { Language.currentState() }
            val future = pool.submit<Long> { entered(context) { state.stdio.ready(read, 0, -1, 0) } }
            awaitRegistered(state.files, read)
            context.close(true)
            assertThrows(java.util.concurrent.ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
            assertEquals(0, state.files.pendingReadiness(read))
            assertEquals(0L, physicalDescriptors(), "Context cancellation must release owners and wait duplicates")
        } finally {
            context.close(true); pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
