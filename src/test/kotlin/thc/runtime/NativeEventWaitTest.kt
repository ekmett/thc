// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.OS
import thc.Language
import thc.NativeIO
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(30)
class NativeEventWaitTest {
    private fun <T> entered(context: Context, action: () -> T): T {
        context.enter()
        return try { action() } finally { context.leave() }
    }
    private fun image(vararg rows: Pair<Long, Int>): ManagedAddress {
        val result = ManagedAddress.fromByteArray(ByteArray(rows.size * 8))
        for ((index, row) in rows.withIndex()) {
            for (byte in 0..3) result.writeWord8(index * 8L + byte, row.first ushr (byte * 8))
            for (byte in 0..1) result.writeWord8(index * 8L + 4 + byte, row.second.toLong() ushr (byte * 8))
            result.writeWord8(index * 8L + 6, 0xff)
        }
        return result
    }
    private fun revents(image: ManagedAddress, index: Int): Long =
        image.readWord8(index * 8L + 6) or (image.readWord8(index * 8L + 7) shl 8)

    @Test fun pollPreservesInputsAndReportsKernelReadinessTimeoutAndInvalidDescriptors() {
        NativeIO.createContext().use { context -> entered(context) {
            val io = Language.currentState().stdio
            val fd = io.eventfd(0, io.flagConstant(OriginalStdioOp.O_NONBLOCK))
            val empty = image(fd to 1)
            assertEquals(0L, io.poll(empty, 1, 0, null)); assertEquals(0L, revents(empty, 0))
            val start = System.nanoTime()
            assertEquals(0L, io.poll(empty, 1, 20, null))
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(15))
            assertEquals(0L, io.eventfdWrite(fd, 7))
            val mixed = image(fd to 1, -1L to 1, 9999L to 1, fd to 4)
            val input = (0L until 32).map(mixed::readWord8)
            assertEquals(3L, io.poll(mixed, 4, -1, null))
            assertEquals(listOf(1L, 0L, 32L, 4L), (0..3).map { revents(mixed, it) })
            for (index in 0 until 32) if (index % 8 < 6) assertEquals(input[index], mixed.readWord8(index.toLong()))
            val value = ManagedAddress.fromByteArray(ByteArray(8))
            assertEquals(8L, io.read(fd, value, 8)); assertEquals(7L, value.readWord8(0))
            assertEquals(0L, io.close(fd))
            assertEquals(0L, io.poll(ManagedAddress.nullAddress(), 0, 0, null))
            assertThrows(RuntimeFault::class.java) { io.poll(image(0L to 1), Long.MAX_VALUE, 0, null) }
        } }
    }

    private fun pending(files: ManagedFiles, fd: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (files.pendingEventWaits(fd) == 0 && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(1, files.pendingEventWaits(fd))
    }

    @Test fun blockedPollWakesOnLogicalCloseWithoutFollowingDescriptorReuse() {
        val pool = Executors.newSingleThreadExecutor()
        NativeIO.createContext().use { context ->
            try {
                val state = entered(context) { Language.currentState() }
                val fd = entered(context) { state.stdio.eventfd(0, 0) }
                val output = image(fd to 1)
                val root = entered(context) {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    object : RootNode(language) {
                        override fun execute(frame: VirtualFrame): Any = state.stdio.poll(output, 1, -1, this)
                    }.callTarget
                }
                val result = pool.submit<Long> { entered(context) { root.call() as Long } }
                pending(state.files, fd)
                entered(context) {
                    assertEquals(0L, state.stdio.close(fd))
                    assertEquals(fd, state.stdio.eventfd(0, 0))
                }
                assertEquals(1L, result.get(5, TimeUnit.SECONDS))
                assertEquals(32L, revents(output, 0))
                entered(context) { assertEquals(0L, state.stdio.close(fd)) }
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun blockedPollRespondsToContextCancellation() {
        val pool = Executors.newSingleThreadExecutor()
        val context = NativeIO.createContext()
        try {
            val state = entered(context) { Language.currentState() }
            val fd = entered(context) { state.stdio.eventfd(0, 0) }
            val output = image(fd to 1)
            val root = entered(context) {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                object : RootNode(language) {
                    override fun execute(frame: VirtualFrame): Any = state.stdio.poll(output, 1, -1, this)
                }.callTarget
            }
            val result = pool.submit<Throwable?> {
                try { entered(context) { root.call() }; null } catch (failure: Throwable) { failure }
            }
            pending(state.files, fd)
            context.close(true)
            assertNotNull(result.get(5, TimeUnit.SECONDS))
            assertEquals(0, state.files.pendingEventWaits(fd))
        } finally { context.close(true); pool.shutdownNow() }
    }
}
