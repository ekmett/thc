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
class NativeEventWaitTest {
    private <T> T entered(Context context, Supplier<T> action) {
        context.enter(); try { return action.get(); } finally { context.leave(); }
    }
    private void entered(Context context, Runnable action) {
        context.enter(); try { action.run(); } finally { context.leave(); }
    }
    private record Row(long descriptor, int events) {}
    private ManagedAddress image(Row... rows) {
        var result = ManagedAddress.fromByteArray(new byte[rows.length * 8]);
        for (int i = 0; i < rows.length; i++) {
            var row = rows[i];
            for (int b = 0; b <= 3; b++) result.writeWord8(i * 8L + b, row.descriptor >>> (b * 8));
            for (int b = 0; b <= 1; b++) result.writeWord8(i * 8L + 4 + b, (long) row.events >>> (b * 8));
            result.writeWord8(i * 8L + 6, 0xff);
        }
        return result;
    }
    private long revents(ManagedAddress image, int index) { return image.readWord8(index * 8L + 6) | (image.readWord8(index * 8L + 7) << 8); }
    @Test void pollPreservesInputsAndReportsKernelReadinessTimeoutAndInvalidDescriptors() {
        try (var context = NativeIO.createContext(Set.of())) { entered(context, () -> {
            var io = Language.currentState(null).getStdio(); long fd = io.eventfd(0, io.flagConstant(OriginalStdioOp.O_NONBLOCK));
            var empty = image(new Row(fd, 1));
            assertEquals(0L, io.poll(empty, 1, 0, null)); assertEquals(0L, revents(empty, 0));
            long start = System.nanoTime(); assertEquals(0L, io.poll(empty, 1, 20, null));
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(15));
            assertEquals(0L, io.eventfdWrite(fd, 7));
            var mixed = image(new Row(fd, 1), new Row(-1, 1), new Row(9999, 1), new Row(fd, 4));
            var input = new long[32]; for (int i = 0; i < input.length; i++) input[i] = mixed.readWord8(i);
            assertEquals(3L, io.poll(mixed, 4, -1, null));
            var events = new ArrayList<Long>(); for (int i = 0; i <= 3; i++) events.add(revents(mixed, i));
            assertEquals(List.of(1L, 0L, 32L, 4L), events);
            for (int i = 0; i < 32; i++) if (i % 8 < 6) assertEquals(input[i], mixed.readWord8(i));
            var value = ManagedAddress.fromByteArray(new byte[8]);
            assertEquals(8L, io.read(fd, value, 8)); assertEquals(7L, value.readWord8(0)); assertEquals(0L, io.close(fd));
            assertEquals(0L, io.poll(ManagedAddress.nullAddress(), 0, 0, null));
            assertThrows(RuntimeFault.class, () -> io.poll(image(new Row(0, 1)), Long.MAX_VALUE, 0, null));
        }); }
    }
    private void pending(ManagedFiles files, long fd) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (files.pendingEventWaits(fd) == 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(1, files.pendingEventWaits(fd));
    }
    @Test void blockedPollWakesOnLogicalCloseWithoutFollowingDescriptorReuse() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try (var context = NativeIO.createContext(Set.of())) {
            try {
                var state = entered(context, () -> Language.currentState(null));
                long fd = entered(context, () -> state.getStdio().eventfd(0, 0)); var output = image(new Row(fd, 1));
                var root = entered(context, () -> {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    return new RootNode(language) {
                        @Override public Object execute(VirtualFrame frame) { return state.getStdio().poll(output, 1, -1, this); }
                    }.getCallTarget();
                });
                var result = pool.submit(() -> entered(context, () -> (Long) root.call()));
                pending(state.getFiles(), fd);
                entered(context, () -> {
                    assertEquals(0L, state.getStdio().close(fd)); assertEquals(fd, state.getStdio().eventfd(0, 0));
                });
                assertEquals(1L, result.get(5, TimeUnit.SECONDS)); assertEquals(32L, revents(output, 0));
                entered(context, () -> assertEquals(0L, state.getStdio().close(fd)));
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void blockedPollRespondsToContextCancellation() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var context = NativeIO.createContext(Set.of());
        try {
            var state = entered(context, () -> Language.currentState(null));
            long fd = entered(context, () -> state.getStdio().eventfd(0, 0)); var output = image(new Row(fd, 1));
            var root = entered(context, () -> {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                return new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { return state.getStdio().poll(output, 1, -1, this); }
                }.getCallTarget();
            });
            Future<Throwable> result = pool.submit(() -> {
                try { entered(context, () -> root.call()); return null; } catch (Throwable failure) { return failure; }
            });
            pending(state.getFiles(), fd); context.close(true);
            assertNotNull(result.get(5, TimeUnit.SECONDS)); assertEquals(0, state.getFiles().pendingEventWaits(fd));
        } finally { context.close(true); pool.shutdownNow(); }
    }
}
