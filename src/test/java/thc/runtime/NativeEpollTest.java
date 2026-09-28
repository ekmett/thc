// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import thc.Language;
import thc.NativeIO;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(30)
class NativeEpollTest {
    private <T> T entered(Context context, Supplier<T> action) {
        context.enter(); try { return action.get(); } finally { context.leave(); }
    }
    private void entered(Context context, Runnable action) {
        context.enter(); try { action.run(); } finally { context.leave(); }
    }
    private long integer(ManagedAddress address, long offset, int width) {
        long result = 0; for (int b = 0; b < width; b++) result |= address.readWord8(offset + b) << (b * 8); return result;
    }
    private ManagedAddress event(long data) { return event(data, 1); }
    private ManagedAddress event(long data, long events) {
        var address = ManagedAddress.fromByteArray(new byte[12]);
        for (int b = 0; b <= 3; b++) address.writeWord8(b, events >>> (b * 8));
        for (int b = 0; b <= 7; b++) address.writeWord8(4L + b, data >>> (b * 8)); return address;
    }
    private record Pipe(long reader, long writer) {}
    private Pipe pipe(ManagedStdio io) {
        var descriptors = ManagedAddress.fromByteArray(new byte[8]); assertEquals(0L, io.pipe(descriptors));
        var result = new Pipe(integer(descriptors, 0, 4), integer(descriptors, 4, 4));
        for (long fd : new long[] {result.reader, result.writer})
            assertEquals(0L, io.fcntl(fd, io.flagConstant(OriginalStdioOp.F_SETFL), io.flagConstant(OriginalStdioOp.O_NONBLOCK), true));
        return result;
    }
    @Test void epollKeepsOpaqueDataDistinctAliasesAndLastCloseIdentity() {
        try (var context = NativeIO.createContext(Set.of())) { entered(context, () -> {
            var io = Language.currentState(null).getStdio(); long epoll = io.epollCreate(1);
            long fd = io.eventfd(1, io.flagConstant(OriginalStdioOp.O_NONBLOCK)), alias = io.duplicate(fd);
            var output = ManagedAddress.fromByteArray(new byte[24]); long token = 0xfedcba9876543210L;
            assertEquals(0L, io.epollControl(epoll, 1, fd, event(token)));
            assertEquals(0L, io.epollControl(epoll, 1, alias, event(2)));
            assertEquals(2L, io.epollWait(epoll, output, 2, 0, null));
            assertEquals(Set.of(token, 2L), new HashSet<>(List.of(integer(output, 4, 8), integer(output, 16, 8))));
            assertEquals(-1L, io.epollControl(epoll, 1, fd, event(7))); assertEquals(17L, io.errno()); // EEXIST from the real kernel.
            assertEquals(0L, io.epollControl(epoll, 3, fd, event(3))); assertEquals(17L, io.errno(), "success preserves errno");
            assertEquals(0L, io.epollControl(epoll, 2, alias, ManagedAddress.nullAddress()));
            assertEquals(0L, io.close(fd)); assertEquals(1L, io.epollWait(epoll, output, 2, 0, null));
            assertEquals(3L, integer(output, 4, 8)); long reused = io.eventfd(0, io.flagConstant(OriginalStdioOp.O_NONBLOCK));
            assertEquals(fd, reused); assertEquals(-1L, io.epollControl(epoll, 3, reused, event(4)));
            assertEquals(2L, io.errno()); // ENOENT: never modify the retired number's registration.
            assertEquals(0L, io.epollControl(epoll, 1, reused, event(4))); assertEquals(0L, io.close(alias));
            assertEquals(0L, io.epollWait(epoll, output, 2, 0, null), "last alias removes old registration");
            assertEquals(0L, io.eventfdWrite(reused, 1)); long setAlias = io.duplicate(epoll);
            assertEquals(0L, io.close(epoll)); assertEquals(1L, io.epollWait(setAlias, output, 2, 0, null));
            assertEquals(4L, integer(output, 4, 8)); assertEquals(0L, io.close(reused)); assertEquals(0L, io.close(setAlias));
        }); }
    }
    @Test void epollTimeoutErrorsAndOneShotRearmUseRealKernelState() {
        try (var context = NativeIO.createContext(Set.of())) { entered(context, () -> {
            var io = Language.currentState(null).getStdio(); assertEquals(-1L, io.epollCreate(0)); assertEquals(22L, io.errno());
            long epoll = io.epollCreate(1), fd = io.eventfd(1, 0); var output = event(99);
            assertEquals(-1L, io.epollWait(epoll, ManagedAddress.nullAddress(), 0, 0, null)); assertEquals(22L, io.errno());
            assertEquals(-1L, io.epollControl(epoll, 1, epoll, event(1))); assertEquals(22L, io.errno());
            assertEquals(0L, io.epollControl(epoll, 1, fd, event(7, 1L | (1L << 30))));
            assertEquals(1L, io.epollWait(epoll, output, 1, 0, null)); long start = System.nanoTime();
            assertEquals(0L, io.epollWait(epoll, output, 1, 20, null));
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(15));
            assertEquals(7L, integer(output, 4, 8), "timeout does not overwrite output");
            assertEquals(0L, io.epollControl(epoll, 3, fd, event(8, 1L | (1L << 30))));
            assertEquals(1L, io.epollWait(epoll, output, 1, 0, null)); assertEquals(8L, integer(output, 4, 8));
            assertEquals(0L, io.close(fd)); assertEquals(0L, io.close(epoll));
        }); }
    }
    private void blocked(boolean cancel) throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var context = NativeIO.createContext(Set.of());
        try {
            var state = entered(context, () -> Language.currentState(null));
            long epoll = entered(context, () -> state.getStdio().epollCreate(1));
            var root = entered(context, () -> {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                return new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { return state.getStdio().epollWait(epoll, event(0), 1, -1, this); }
                }.getCallTarget();
            });
            Future<Object> future = pool.submit(() -> { try { return entered(context, () -> root.call()); } catch (Throwable failure) { return failure; } });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (state.getFiles().pendingReadiness(epoll) == 0 && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, state.getFiles().pendingReadiness(epoll));
            if (cancel) context.close(true); else entered(context, () -> {
                assertEquals(0L, state.getStdio().close(epoll)); assertEquals(epoll, state.getStdio().epollCreate(1));
            });
            var result = future.get(5, TimeUnit.SECONDS);
            if (cancel) assertInstanceOf(Throwable.class, result); else assertEquals(-1L, result);
            assertEquals(0, state.getFiles().pendingReadiness(epoll));
        } finally { context.close(true); pool.shutdownNow(); }
    }
    @Test void blockedEpollWakesOnLogicalCloseWithoutFollowingReuse() throws Exception { blocked(false); }
    @Test void blockedEpollRespondsToContextCancellation() throws Exception { blocked(true); }
    @Test void controlRegistrationsWriteOriginalProtocolAndHonorReplacementUnregisterAndReuse() {
        try (var context = NativeIO.createContext(Set.of())) { entered(context, () -> {
            var state = Language.currentState(null); var io = state.getStdio();
            long wake = io.eventfd(0, io.flagConstant(OriginalStdioOp.O_NONBLOCK));
            var timer = pipe(io); var control = pipe(io); var removed = pipe(io);
            io.controlFd(OriginalStdioOp.IO_WAKEUP_FD, wake, 0);
            io.controlFd(OriginalStdioOp.TIMER_CONTROL_FD, timer.writer, 0);
            io.controlFd(OriginalStdioOp.IO_CONTROL_FD, 0, removed.writer);
            io.controlFd(OriginalStdioOp.IO_CONTROL_FD, 0, control.writer);
            io.controlFd(OriginalStdioOp.IO_CONTROL_FD, 1, removed.writer);
            io.controlFd(OriginalStdioOp.IO_CONTROL_FD, 1, -1);
            assertThrows(RuntimeFault.class, () -> io.controlFd(OriginalStdioOp.TIMER_CONTROL_FD, timer.reader, 0));
            assertThrows(RuntimeFault.class, () -> io.controlFd(OriginalStdioOp.IO_WAKEUP_FD, 9999, 0));
            state.getFiles().shutdownEventManagers(); state.getFiles().shutdownEventManagers();
            var bytes = ManagedAddress.fromByteArray(new byte[8]);
            assertEquals(8L, io.read(wake, bytes, 8)); assertEquals(255L, integer(bytes, 0, 8));
            for (long fd : new long[] {timer.reader, control.reader}) {
                assertEquals(1L, io.read(fd, bytes, 1)); assertEquals(254L, bytes.readWord8(0));
                assertEquals(-1L, io.read(fd, bytes, 1)); assertEquals(11L, io.errno());
            }
            assertEquals(-1L, io.read(removed.reader, bytes, 1)); assertEquals(11L, io.errno());
            io.controlFd(OriginalStdioOp.IO_WAKEUP_FD, wake, 0); assertEquals(0L, io.close(wake));
            assertEquals(wake, io.eventfd(0, io.flagConstant(OriginalStdioOp.O_NONBLOCK)));
            state.getFiles().shutdownEventManagers();
            assertEquals(-1L, io.read(wake, bytes, 8)); assertEquals(11L, io.errno());
        }); }
    }
}
