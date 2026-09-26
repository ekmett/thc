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
class NativeEpollTest {
    private fun <T> entered(context: Context, action: () -> T): T {
        context.enter()
        return try { action() } finally { context.leave() }
    }
    private fun integer(address: ManagedAddress, offset: Long, width: Int): Long =
        (0 until width).fold(0L) { result, byte -> result or (address.readWord8(offset + byte) shl (byte * 8)) }
    private fun event(data: Long, events: Long = 1): ManagedAddress =
        ManagedAddress.fromByteArray(ByteArray(12)).also { address ->
            for (byte in 0..3) address.writeWord8(byte.toLong(), events ushr (byte * 8))
            for (byte in 0..7) address.writeWord8(4L + byte, data ushr (byte * 8))
        }
    private fun pipe(io: ManagedStdio): Pair<Long, Long> {
        val descriptors = ManagedAddress.fromByteArray(ByteArray(8))
        assertEquals(0L, io.pipe(descriptors))
        val result = integer(descriptors, 0, 4) to integer(descriptors, 4, 4)
        for (fd in listOf(result.first, result.second))
            assertEquals(0L, io.fcntl(fd, io.flagConstant(OriginalStdioOp.F_SETFL),
                io.flagConstant(OriginalStdioOp.O_NONBLOCK), true))
        return result
    }

    @Test fun epollKeepsOpaqueDataDistinctAliasesAndLastCloseIdentity() {
        NativeIO.createContext().use { context -> entered(context) {
            val io = Language.currentState().stdio
            val epoll = io.epollCreate(1)
            val fd = io.eventfd(1, io.flagConstant(OriginalStdioOp.O_NONBLOCK))
            val alias = io.duplicate(fd)
            val output = ManagedAddress.fromByteArray(ByteArray(24))
            val token = 0xfedcba9876543210uL.toLong()
            assertEquals(0L, io.epollControl(epoll, 1, fd, event(token)))
            assertEquals(0L, io.epollControl(epoll, 1, alias, event(2)))
            assertEquals(2L, io.epollWait(epoll, output, 2, 0, null))
            assertEquals(setOf(token, 2L), setOf(integer(output, 4, 8), integer(output, 16, 8)))
            assertEquals(-1L, io.epollControl(epoll, 1, fd, event(7)))
            assertEquals(17L, io.errno()) // EEXIST from the real kernel.
            assertEquals(0L, io.epollControl(epoll, 3, fd, event(3)))
            assertEquals(17L, io.errno(), "success preserves errno")
            assertEquals(0L, io.epollControl(epoll, 2, alias, ManagedAddress.nullAddress()))
            assertEquals(0L, io.close(fd))
            assertEquals(1L, io.epollWait(epoll, output, 2, 0, null))
            assertEquals(3L, integer(output, 4, 8))
            val reused = io.eventfd(0, io.flagConstant(OriginalStdioOp.O_NONBLOCK))
            assertEquals(fd, reused)
            assertEquals(-1L, io.epollControl(epoll, 3, reused, event(4)))
            assertEquals(2L, io.errno()) // ENOENT: never modify the retired number's registration.
            assertEquals(0L, io.epollControl(epoll, 1, reused, event(4)))
            assertEquals(0L, io.close(alias))
            assertEquals(0L, io.epollWait(epoll, output, 2, 0, null), "last alias removes old registration")
            assertEquals(0L, io.eventfdWrite(reused, 1))
            val setAlias = io.duplicate(epoll)
            assertEquals(0L, io.close(epoll))
            assertEquals(1L, io.epollWait(setAlias, output, 2, 0, null))
            assertEquals(4L, integer(output, 4, 8))
            assertEquals(0L, io.close(reused)); assertEquals(0L, io.close(setAlias))
        } }
    }

    @Test fun epollTimeoutErrorsAndOneShotRearmUseRealKernelState() {
        NativeIO.createContext().use { context -> entered(context) {
            val io = Language.currentState().stdio
            assertEquals(-1L, io.epollCreate(0)); assertEquals(22L, io.errno())
            val epoll = io.epollCreate(1)
            val fd = io.eventfd(1, 0)
            val output = event(99)
            assertEquals(-1L, io.epollWait(epoll, ManagedAddress.nullAddress(), 0, 0, null))
            assertEquals(22L, io.errno())
            assertEquals(-1L, io.epollControl(epoll, 1, epoll, event(1))); assertEquals(22L, io.errno())
            assertEquals(0L, io.epollControl(epoll, 1, fd, event(7, 1L or (1L shl 30))))
            assertEquals(1L, io.epollWait(epoll, output, 1, 0, null))
            val start = System.nanoTime()
            assertEquals(0L, io.epollWait(epoll, output, 1, 20, null))
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(15))
            assertEquals(7L, integer(output, 4, 8), "timeout does not overwrite output")
            assertEquals(0L, io.epollControl(epoll, 3, fd, event(8, 1L or (1L shl 30))))
            assertEquals(1L, io.epollWait(epoll, output, 1, 0, null)); assertEquals(8L, integer(output, 4, 8))
            assertEquals(0L, io.close(fd)); assertEquals(0L, io.close(epoll))
        } }
    }

    private fun blocked(cancel: Boolean) {
        val pool = Executors.newSingleThreadExecutor()
        val context = NativeIO.createContext()
        try {
            val state = entered(context) { Language.currentState() }
            val epoll = entered(context) { state.stdio.epollCreate(1) }
            val root = entered(context) {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                object : RootNode(language) {
                    override fun execute(frame: VirtualFrame): Any = state.stdio.epollWait(epoll, event(0), 1, -1, this)
                }.callTarget
            }
            val future = pool.submit<Any> {
                try { entered(context) { root.call() } } catch (failure: Throwable) { failure }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (state.files.pendingReadiness(epoll) == 0 && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(1, state.files.pendingReadiness(epoll))
            if (cancel) context.close(true) else entered(context) {
                assertEquals(0L, state.stdio.close(epoll))
                assertEquals(epoll, state.stdio.epollCreate(1))
            }
            val result = future.get(5, TimeUnit.SECONDS)
            if (cancel) assertInstanceOf(Throwable::class.java, result) else assertEquals(-1L, result)
            assertEquals(0, state.files.pendingReadiness(epoll))
        } finally { context.close(true); pool.shutdownNow() }
    }
    @Test fun blockedEpollWakesOnLogicalCloseWithoutFollowingReuse() = blocked(false)
    @Test fun blockedEpollRespondsToContextCancellation() = blocked(true)

    @Test fun controlRegistrationsWriteOriginalProtocolAndHonorReplacementUnregisterAndReuse() {
        NativeIO.createContext().use { context -> entered(context) {
            val state = Language.currentState()
            val io = state.stdio
            val wake = io.eventfd(0, io.flagConstant(OriginalStdioOp.O_NONBLOCK))
            val timer = pipe(io)
            val control = pipe(io)
            val removed = pipe(io)
            io.controlFd(OriginalStdioOp.IO_WAKEUP_FD, wake, 0)
            io.controlFd(OriginalStdioOp.TIMER_CONTROL_FD, timer.second, 0)
            io.controlFd(OriginalStdioOp.IO_CONTROL_FD, 0, removed.second)
            io.controlFd(OriginalStdioOp.IO_CONTROL_FD, 0, control.second)
            io.controlFd(OriginalStdioOp.IO_CONTROL_FD, 1, removed.second)
            io.controlFd(OriginalStdioOp.IO_CONTROL_FD, 1, -1)
            assertThrows(RuntimeFault::class.java) { io.controlFd(OriginalStdioOp.TIMER_CONTROL_FD, timer.first, 0) }
            assertThrows(RuntimeFault::class.java) { io.controlFd(OriginalStdioOp.IO_WAKEUP_FD, 9999, 0) }
            state.files.shutdownEventManagers()
            state.files.shutdownEventManagers()
            val bytes = ManagedAddress.fromByteArray(ByteArray(8))
            assertEquals(8L, io.read(wake, bytes, 8)); assertEquals(255L, integer(bytes, 0, 8))
            for (fd in listOf(timer.first, control.first)) {
                assertEquals(1L, io.read(fd, bytes, 1)); assertEquals(254L, bytes.readWord8(0))
                assertEquals(-1L, io.read(fd, bytes, 1)); assertEquals(11L, io.errno())
            }
            assertEquals(-1L, io.read(removed.first, bytes, 1)); assertEquals(11L, io.errno())
            io.controlFd(OriginalStdioOp.IO_WAKEUP_FD, wake, 0)
            assertEquals(0L, io.close(wake))
            assertEquals(wake, io.eventfd(0, io.flagConstant(OriginalStdioOp.O_NONBLOCK)))
            state.files.shutdownEventManagers()
            assertEquals(-1L, io.read(wake, bytes, 8)); assertEquals(11L, io.errno())
        } }
    }
}
