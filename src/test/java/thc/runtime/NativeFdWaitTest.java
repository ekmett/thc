// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import thc.NativeIO;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Real opened FIFOs and private service protocol, not exported-Core or primop
 * admission proof. In particular these tests cannot replace the required exact
 * installed blockedOnBadFD payload and continuation-resumption fixture. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(40)
class NativeFdWaitTest {
    @TempDir Path directory;
    private ManagedAddress path(Path path) { return ManagedAddress.fromByteArray((path + "\0").getBytes(StandardCharsets.UTF_8)); }
    private ManagedAddress bytes(byte... values) { return ManagedAddress.fromByteArray(values); }
    private long physicalDescriptors() throws IOException {
        long count = 0;
        try (var entries = Files.newDirectoryStream(Path.of("/proc/self/fd"))) {
            for (var entry : entries) try { if (Files.readSymbolicLink(entry).startsWith(directory)) count++; }
            catch (NoSuchFileException ignored) { }
        }
        return count;
    }
    private <T> T entered(Context context, Callable<T> action) throws Exception {
        context.initialize("thc"); context.enter(); try { return action.call(); } finally { context.leave(); }
    }
    private record Pipe(long read, long write) {}
    private Pipe fifo(Context context) throws Exception {
        var file = directory.resolve("pipe-" + System.nanoTime()); var process = new ProcessBuilder("mkfifo", file.toString()).start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        return entered(context, () -> {
            var io = Language.currentState(null).getStdio(); long read = io.open(path(file), 0x800, 0); // Linux O_RDONLY | O_NONBLOCK.
            assertTrue(read >= 3); long write = io.open(path(file), 0x801, 0); assertTrue(write >= 3); return new Pipe(read, write);
        });
    }
    private void awaitRegistered(ManagedFiles files, long fd) throws InterruptedException {
        // Bounded test barrier only; production never polls this observer.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (files.pendingReadiness(fd) == 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(1, files.pendingReadiness(fd));
    }
    private static final class WaitRoot extends RootNode {
        private final ManagedFiles.WaitToken token; private final AtomicLong id; private final MaskingState mask;
        WaitRoot(Language language, ManagedFiles.WaitToken token, AtomicLong id, MaskingState mask) {
            super(language); this.token = token; this.id = id; this.mask = mask;
        }
        @Override public Object execute(VirtualFrame frame) {
            var state = Language.currentState(this); state.getThreads().enterCurrent(mask, false, true, null);
            id.set(state.getThreads().currentId());
            try {
                try {
                    token.await(this, true, false);
                    // A pending request under uninterruptible masking must not
                    // have been claimed just because native poll was woken.
                    assertNull(state.getThreads().poll(this, true)); state.getMaskingState().set(MaskingState.UNMASKED);
                    var request = state.getThreads().poll(this, false);
                    if (request != null) { request.acknowledge(); return request.getPayload() != null ? request.getPayload() : thc.runtime.Unit.INSTANCE; }
                    return thc.runtime.Unit.INSTANCE;
                } catch (AsyncBlocked blocked) {
                    assertNotEquals(MaskingState.MASKED_UNINTERRUPTIBLE, mask); blocked.getRequest().acknowledge();
                    return blocked.getRequest().getPayload() != null ? blocked.getRequest().getPayload() : thc.runtime.Unit.INSTANCE;
                }
            } finally { state.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
        }
    }
    @Test void originalDirectionTimeoutEofAndStickyErrnoUseActualFifoReadiness() throws Exception {
        try (var context = NativeIO.createContext(Set.of())) {
            var pipe = fifo(context); entered(context, () -> {
                var io = Language.currentState(null).getStdio(); assertEquals(-1L, io.close(-1)); long error = io.errno();
                assertEquals(0L, io.ready(pipe.read, 0, 0, 0)); assertEquals(1L, io.ready(pipe.write, 1, 0, 0));
                long started = System.nanoTime(); assertEquals(0L, io.ready(pipe.read, 0, 30, 0));
                assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(25)); assertEquals(error, io.errno());
                assertEquals(1L, io.write(pipe.write, bytes((byte) 42), 1)); assertEquals(1L, io.ready(pipe.read, 0, 0, 0));
                var result = bytes((byte) 0); assertEquals(1L, io.read(pipe.read, result, 1)); assertEquals(42L, result.readWord8(0));
                assertEquals(0L, io.close(pipe.write)); assertEquals(1L, io.ready(pipe.read, 0, -1, 0), "Hangup is an event; EOF read must not hang");
                assertEquals(0L, io.read(pipe.read, result, 1)); assertEquals(error, io.errno()); return null;
            });
        }
    }
    @Test void fullPipeWaitsForWriteCapacityAndDoesNotConsumeReadData() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var context = NativeIO.createContext(Set.of());
        try {
            var pipe = fifo(context); var state = entered(context, () -> Language.currentState(null));
            entered(context, () -> {
                var bytes = new byte[4096]; Arrays.fill(bytes, (byte) 7); var chunk = ManagedAddress.fromByteArray(bytes);
                boolean full = false; for (int i = 0; i < 1024; i++) if (!full && state.getStdio().write(pipe.write, chunk, 4096) < 0) full = true;
                assertTrue(full, "Bounded fixture must fill the actual pipe"); assertEquals(0L, state.getStdio().ready(pipe.write, 1, 0, 0)); return null;
            });
            var future = pool.submit(() -> entered(context, () -> state.getStdio().ready(pipe.write, 1, -1, 0)));
            awaitRegistered(state.getFiles(), pipe.write); assertFalse(future.isDone());
            entered(context, () -> {
                var output = ManagedAddress.fromByteArray(new byte[4096]); assertEquals(4096L, state.getStdio().read(pipe.read, output, 4096)); return null;
            });
            assertEquals(1L, future.get(5, TimeUnit.SECONDS)); assertEquals(0, state.getFiles().pendingReadiness(pipe.write));
        } finally { context.close(true); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void readWaitUsesMaskAwareAsyncDeliveryAndReleasesItsNativeRequest() throws Exception {
        for (var mask : MaskingState.values()) {
            var pool = Executors.newSingleThreadExecutor(); var context = NativeIO.createContext(Set.of());
            try {
                var pipe = fifo(context); var state = entered(context, () -> Language.currentState(null)); var id = new AtomicLong(-1);
                var target = entered(context, () -> {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    return new WaitRoot(language, state.getFiles().waitToken(pipe.read, false), id, mask).getCallTarget();
                });
                var future = pool.submit(() -> entered(context, target::call)); awaitRegistered(state.getFiles(), pipe.read);
                var request = entered(context, () -> state.getThreads().send(id.get(), "interrupt " + mask));
                if (mask == MaskingState.MASKED_UNINTERRUPTIBLE) {
                    assertFalse(future.isDone()); assertEquals(AsyncRequestState.PENDING, request.getState());
                    entered(context, () -> { assertEquals(1L, state.getStdio().write(pipe.write, bytes((byte) 1), 1)); return null; });
                }
                assertEquals("interrupt " + mask, future.get(5, TimeUnit.SECONDS)); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                assertEquals(0, state.getFiles().pendingReadiness(pipe.read)); assertEquals(2L, physicalDescriptors(), "Interrupted wait must release its native duplicate");
            } finally { context.close(true); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
            assertEquals(0L, physicalDescriptors());
        }
    }
    @Test void closeAndDup2WakeOnlyTheOriginalDescriptorAndSavedTokenCannotFollowReuse() throws Exception {
        for (boolean replace : new boolean[] {false, true}) {
            var pool = Executors.newSingleThreadExecutor(); var context = NativeIO.createContext(Set.of());
            try {
                long read = fifo(context).read; var state = entered(context, () -> Language.currentState(null));
                var old = entered(context, () -> state.getFiles().waitToken(read, false));
                var future = pool.submit(() -> entered(context, () -> {
                    try { old.await(new Node() {}, false, false); return false; }
                    catch (Exception failure) { if (failure instanceof ClosedChannelException) return true; throw failure; }
                }));
                awaitRegistered(state.getFiles(), read);
                entered(context, () -> {
                    var regular = directory.resolve("ready-" + replace); Files.writeString(regular, "ready");
                    long ready = state.getFiles().open(path(regular), 0, ForeignSafety.UNSAFE);
                    if (replace) assertEquals(read, state.getFiles().duplicateTo(ready, read));
                    else { assertEquals(0L, state.getFiles().close(read, ForeignSafety.UNSAFE)); assertEquals(read, state.getFiles().duplicateTo(ready, read)); }
                    assertEquals(1L, state.getFiles().ready(read, 0, false, null)); return null;
                });
                assertTrue(future.get(5, TimeUnit.SECONDS), "Old wait must observe close, not replacement readiness");
                entered(context, () -> { assertThrows(ClosedChannelException.class, () -> old.await(new Node() {}, false, false)); return null; });
            } finally { context.close(true); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }
    @Test void hostCancellationUnblocksNativePollAndOpaqueStreamsRemainDenied() throws Exception {
        try (var context = Context.newBuilder("thc").build()) { entered(context, () -> {
            var io = Language.currentState(null).getStdio(); assertEquals(-1L, io.ready(0, 0, 0, 0));
            assertEquals(StdioHostAbi.load().error(7), io.errno()); return null;
        }); }
        var pool = Executors.newSingleThreadExecutor(); var context = NativeIO.createContext(Set.of());
        try {
            long read = fifo(context).read; var state = entered(context, () -> Language.currentState(null));
            var future = pool.submit(() -> entered(context, () -> state.getStdio().ready(read, 0, -1, 0)));
            awaitRegistered(state.getFiles(), read); context.close(true);
            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)); assertEquals(0, state.getFiles().pendingReadiness(read));
            assertEquals(0L, physicalDescriptors(), "Context cancellation must release owners and wait duplicates");
        } finally { context.close(true); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
    @Test void wakeFailureCannotInterruptDescriptorRefcountsOrPhysicalRetirement() throws Exception {
        for (var operation : List.of("close", "dup2", "dispose")) {
            var pool = Executors.newSingleThreadExecutor(); var context = NativeIO.createContext(Set.of()); ManagedFiles files = null;
            try {
                var pipe = directory.resolve("wake-failure-" + operation); var created = new ProcessBuilder("mkfifo", pipe.toString()).start();
                assertTrue(created.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, created.exitValue());
                var service = entered(context, () -> {
                    var state = Language.currentState(null);
                    var result = new ManagedFiles(state.getEnv(), state.getThreads(), (long) Integer.MAX_VALUE + 1, wait -> {
                        wait.descriptorClosed(); // Always wake the real native poll.
                        throw propagate(new IOException("injected post-wake failure"));
                    });
                    result.installNative(Objects.requireNonNull(state.getNativeFiles()), Set.of()); return result;
                });
                files = service;
                long read = entered(context, () -> {
                    long descriptor = service.openOriginal(path(pipe), 0x800, 0, OriginalStdioOp.OPEN, null); assertTrue(descriptor >= 3);
                    assertTrue(service.openOriginal(path(pipe), 0x801, 0, OriginalStdioOp.OPEN, null) >= 3); return descriptor;
                });
                var token = entered(context, () -> service.waitToken(read, false));
                var future = pool.submit(() -> entered(context, () -> {
                    try { token.await(new Node() {}, false, false); return false; }
                    catch (Exception failure) { if (failure instanceof ClosedChannelException) return true; throw failure; }
                }));
                awaitRegistered(service, read);
                entered(context, () -> {
                    switch (operation) {
                        case "close" -> {
                            assertEquals(-1L, service.close(read, ForeignSafety.UNSAFE), "Close reports the wake error after consuming fd");
                            assertEquals(-1L, service.close(read, ForeignSafety.UNSAFE)); assertEquals(4L, service.errorKind());
                        }
                        case "dup2" -> {
                            var regular = directory.resolve("wake-failure-replacement"); Files.writeString(regular, "replacement");
                            long source = service.open(path(regular), 0, ForeignSafety.UNSAFE);
                            assertThrows(IOException.class, () -> service.duplicateTo(source, read));
                            assertEquals(1L, service.ready(read, 0, false, null), "Replacement is committed despite wake failure");
                        }
                        default -> assertThrows(RuntimeFault.class, service::dispose);
                    }
                    return null;
                });
                assertTrue(future.get(5, TimeUnit.SECONDS)); assertEquals(0, service.pendingReadiness(read));
                entered(context, () -> { service.dispose(); return null; }); assertEquals(0L, physicalDescriptors(), "Even a failing notifier must retire every native owner");
            } finally {
                try { if (files != null) { var service = files; entered(context, () -> { service.dispose(); return null; }); } }
                finally { try { context.close(true); } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); } }
            }
        }
    }
}
