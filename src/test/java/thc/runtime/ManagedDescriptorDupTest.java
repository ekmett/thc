// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.Node;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import java.io.*;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ManagedFileFixtures.*;

/** Ownership tests for context descriptors, not native host fd/RTS lock emulation. */
class ManagedDescriptorDupTest {
    @TempDir Path directory;
    private ManagedAddress address(byte[] bytes) { return ManagedAddress.fromByteArray(bytes); }
    private ManagedAddress path(Path value) { return address((value + "\0").getBytes(StandardCharsets.UTF_8)); }
    private Context.Builder builder() { return Context.newBuilder("thc").allowIO(IOAccess.ALL).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false"); }
    private <T> T entered(Context context, Callable<T> action) throws Exception {
        context.initialize("thc"); context.enter(); try { return action.call(); } finally { context.leave(); }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
    private void replacementFlushFailure(Exception error) throws Exception {
        var node = new Node() {}; var threads = new GuestThreads[1]; var armed = new boolean[1]; var oldFlushes = new int[1]; var replacementFlushes = new int[1];
        var old = new ByteArrayOutputStream() {
            @Override public void flush() throws IOException {
                oldFlushes[0]++;
                if (armed[0]) { assertNull(threads[0].poll(node, false), "Retirement callback must remain foreign execution"); throw propagate(error); }
            }
        };
        var replacement = new ByteArrayOutputStream() { @Override public void flush() { replacementFlushes[0]++; } };
        try (var context = builder().out(old).err(replacement).build()) { entered(context, () -> {
            var state = Language.currentState(null); threads[0] = state.getThreads(); long id = threads[0].enterCurrent(null, false, true, null);
            try {
                var files = state.getFiles(); var stdio = state.getStdio(); assertEquals(-1L, stdio.close(-1));
                long errno = stdio.errno(), kind = files.errorKind(); var pending = threads[0].send(id, "after retirement");
                int oldBefore = oldFlushes[0], replacementBefore = replacementFlushes[0]; armed[0] = true;
                try {
                    if (error instanceof IOException) assertEquals(1L, stdio.duplicateTo(2, 1));
                    else assertSame(error, assertThrows(error.getClass(), () -> stdio.duplicateTo(2, 1)));
                } finally { armed[0] = false; } // Polyglot may flush its embeddings at Context.close.
                assertEquals(errno, stdio.errno()); assertEquals(kind, files.errorKind());
                assertSame(pending, threads[0].poll(node, false), "Exceptional retirement restores guest delivery permission"); pending.acknowledge();
                assertEquals(oldBefore + 1, oldFlushes[0]); assertEquals(0L, files.close(2, ForeignSafety.UNSAFE));
                assertEquals(2L, files.write(1, address(new byte[] {7, 8}), 2, ForeignSafety.UNSAFE)); assertArrayEquals(new byte[] {7, 8}, replacement.toByteArray());
                assertEquals(0, old.size(), "The installed replacement survives the callback failure");
                assertEquals(replacementBefore, replacementFlushes[0], "Closing one alias does not retire its owner");
                assertEquals(0L, files.close(1, ForeignSafety.UNSAFE)); assertEquals(replacementBefore + 1, replacementFlushes[0]); files.dispose();
                assertEquals(oldBefore + 1, oldFlushes[0], "Failed retirement is never retried");
                assertEquals(replacementBefore + 1, replacementFlushes[0], "Shared replacement retires exactly once");
            } finally { armed[0] = false; threads[0].leaveCurrent(GuestThreadStatus.FINISHED); }
            return null;
        }); }
    }
    @Test void replacementSecurityFailureEscapesWithoutErrnoConversion() throws Exception { replacementFlushFailure(new SecurityException("flush denied")); }
    @Test void replacementUnsupportedFailureEscapesWithoutErrnoConversion() throws Exception { replacementFlushFailure(new UnsupportedOperationException("flush unsupported")); }
    @Test void replacementInvalidPathFailureEscapesWithoutErrnoConversion() throws Exception { replacementFlushFailure(new InvalidPathException("provider", "flush path failure")); }
    @Test void replacementCheckedFlushFailureRemainsSilent() throws Exception { replacementFlushFailure(new IOException("checked flush failure")); }
    @Test void aliasesShareOffsetsAndClaimUntilTheLastIndependentClose() throws Exception {
        var file = directory.resolve("shared"); Files.write(file, new byte[] {10, 20, 30, 40});
        try (var context = builder().build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); long one = files.open(path(file), 3, ForeignSafety.UNSAFE), two = files.duplicate(one); var target = new byte[2];
            assertNotEquals(one, two); assertEquals(1L, files.read(one, address(target), 1, ForeignSafety.UNSAFE)); assertEquals(10, (int) target[0]);
            assertEquals(1L, files.read(two, address(target), 1, ForeignSafety.UNSAFE)); assertEquals(20, (int) target[0]);
            assertEquals(1L, files.write(two, address(new byte[] {99}), 1, ForeignSafety.UNSAFE)); assertEquals(3L, files.seek(one, 0, 1, ForeignSafety.UNSAFE));
            assertEquals(0L, files.close(one, ForeignSafety.UNSAFE)); assertEquals(-1L, files.open(path(file), 1, ForeignSafety.UNSAFE)); assertEquals(8L, files.errorKind());
            assertEquals(1L, files.read(two, address(target), 1, ForeignSafety.UNSAFE)); assertEquals(40, (int) target[0]);
            assertEquals(0L, files.close(two, ForeignSafety.UNSAFE)); assertArrayEquals(new byte[] {10, 20, 99, 40}, Files.readAllBytes(file));
            assertTrue(files.open(path(file), 1, ForeignSafety.UNSAFE) >= 3); return null;
        }); }
    }
    @Test void sparseLowestFreeAllocationSameFdAndStickyErrnoAreExact() throws Exception {
        var output = new ByteArrayOutputStream() { int flushes; @Override public void flush() { flushes++; } };
        try (var context = builder().out(output).build()) { entered(context, () -> {
            var state = Language.currentState(null); var files = state.getFiles(); var stdio = state.getStdio(); long ebadf = StdioHostAbi.load().error(4);
            int flushes = output.flushes;
            assertEquals(-1L, stdio.duplicateTo(-1, -1)); assertEquals(ebadf, stdio.errno());
            assertEquals(1L, stdio.duplicateTo(1, 1)); assertEquals(flushes, output.flushes); assertEquals(ebadf, stdio.errno());
            assertEquals(-1L, stdio.duplicateTo(-1, 1)); assertEquals(ebadf, stdio.errno());
            assertEquals(1L, files.write(1, address(new byte[] {7}), 1, ForeignSafety.UNSAFE));
            for (long fd : new long[] {0, 2}) { assertEquals(0L, files.close(fd, ForeignSafety.UNSAFE)); assertEquals(fd, stdio.duplicate(1)); }
            assertEquals((long) Integer.MAX_VALUE, stdio.duplicateTo(1, Integer.MAX_VALUE)); assertEquals(3L, stdio.duplicate(1));
            assertEquals(0L, files.close(1, ForeignSafety.UNSAFE)); assertEquals(1L, stdio.duplicate(3));
            assertEquals(flushes, output.flushes, "Aliased streams flush only at final retirement");
            for (long bad : new long[] {(long) Integer.MAX_VALUE + 1, (long) Integer.MIN_VALUE - 1, Long.MAX_VALUE}) {
                assertThrows(RuntimeFault.class, () -> stdio.duplicate(bad)); assertThrows(RuntimeFault.class, () -> stdio.duplicateTo(1, bad));
                assertThrows(RuntimeFault.class, () -> stdio.duplicateTo(bad, 1));
            }
            assertEquals(ebadf, stdio.errno()); assertEquals(-1L, stdio.duplicateTo(1, -1)); assertEquals(ebadf, stdio.errno());
            assertEquals(1L, files.write(1, address(new byte[] {8}), 1, ForeignSafety.UNSAFE)); assertArrayEquals(new byte[] {7, 8}, output.toByteArray()); return null;
        }); }
    }
    @Test void boundedNamespaceReportsTheProbedEmfileWithoutChangingExistingDescriptors() throws Exception {
        try (var context = builder().build()) { entered(context, () -> {
            var state = Language.currentState(null); var files = new ManagedFiles(state.getEnv(), state.getThreads(), 4); var stdio = new ManagedStdio(files);
            try {
                assertEquals(3L, stdio.duplicate(1)); assertEquals(-1L, stdio.duplicate(1)); assertEquals(StdioHostAbi.load().error(10), stdio.errno());
                assertEquals(-1L, stdio.duplicateTo(1, 4)); assertEquals(StdioHostAbi.load().error(4), stdio.errno());
                assertEquals(3L, stdio.duplicateTo(1, 3)); assertEquals(0L, stdio.close(0)); assertEquals(0L, stdio.duplicate(1));
                assertEquals(StdioHostAbi.load().error(4), stdio.errno());
            } finally { files.dispose(); }
            return null;
        }); }
    }
    @Test void replacingAnAliasAndDisposingClosesEachOpenDescriptionOnce() throws Exception {
        var file = directory.resolve("once"); Files.writeString(file, "keep"); var delegate = FileSystem.newDefaultFileSystem(); var closes = new int[1];
        var fs = new FileSystemDelegate(delegate) {
            @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
                var channel = delegate.newByteChannel(path, options, attrs);
                return new ChannelDelegate(channel) { @Override public void close() throws IOException { closes[0]++; channel.close(); } };
            }
        };
        try (var context = builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); long one = files.open(path(file), 0, ForeignSafety.UNSAFE), two = files.duplicate(one);
            for (int i = 0; i < 4; i++) { assertEquals(two, files.duplicateTo(one, two)); assertEquals(0, closes[0]); }
            assertEquals(0L, files.close(one, ForeignSafety.UNSAFE)); assertEquals(0, closes[0]); files.dispose(); assertEquals(1, closes[0]);
            files.dispose(); assertEquals(1, closes[0]); assertEquals(-1L, files.duplicate(two)); assertEquals(4L, files.errorKind()); return null;
        }); }
    }
    @Test void replacementIsVisibleAndClaimSurvivesCheckedCloseFailureAndReentrantDisposal() throws Exception {
        for (boolean dispose : new boolean[] {false, true}) {
            var file = directory.resolve("retiring-" + dispose); Files.writeString(file, "keep"); var delegate = FileSystem.newDefaultFileSystem();
            var closes = new int[1]; var files = new ManagedFiles[1]; var target = new long[] {-1};
            var fs = new FileSystemDelegate(delegate) {
                @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
                    var channel = delegate.newByteChannel(path, options, attrs);
                    return new ChannelDelegate(channel) {
                        @Override public void close() throws IOException {
                            closes[0]++;
                            if (closes[0] == 1) {
                                assertEquals(1L, files[0].deviceType(target[0]), "Replacement already names the stream");
                                assertEquals(-1L, files[0].open(ManagedDescriptorDupTest.this.path(file), 1, ForeignSafety.UNSAFE));
                                assertEquals(8L, files[0].errorKind(), "Retiring owner retains its file claim"); if (dispose) files[0].dispose();
                            }
                            channel.close(); if (closes[0] == 1) throw new IOException("Target close failed after replacement");
                        }
                    };
                }
            };
            try (var context = builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build()) { entered(context, () -> {
                var state = Language.currentState(null); files[0] = state.getFiles(); target[0] = files[0].open(path(file), 0, ForeignSafety.UNSAFE);
                long errno = state.getStdio().errno(); assertEquals(target[0], state.getStdio().duplicateTo(1, target[0]));
                assertEquals(errno, state.getStdio().errno(), "Silently closing the old target must not publish errno");
                assertEquals(1, closes[0]); assertEquals("keep", Files.readString(file));
                if (!dispose) {
                    assertEquals(1L, files[0].deviceType(target[0]));
                    // The old claim was removed even though its provider threw.
                    assertTrue(files[0].open(path(file), 1, ForeignSafety.UNSAFE) >= 3);
                }
                files[0].dispose(); assertEquals(dispose ? 1 : 2, closes[0]); return null;
            }); }
        }
    }
    @Test void waitingAliasCannotUseItsOldOwnerAfterAtomicReplacement() throws Exception {
        var reading = new CountDownLatch(1); var release = new CountDownLatch(1); var first = new AtomicBoolean(true);
        var input = new InputStream() {
            @Override public int read() { throw new IllegalStateException("Unexpected byte read"); }
            @Override public int read(byte[] bytes, int offset, int count) {
                if (!first.compareAndSet(true, false)) throw new IllegalStateException("Stale alias reached the old input");
                reading.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Check failed."); }
                catch (InterruptedException failure) { throw propagate(failure); }
                bytes[offset] = 42; return 1;
            }
        };
        try (var context = builder().in(input).build()) {
            var files = entered(context, () -> Language.currentState(null).getFiles()); long alias = entered(context, () -> files.duplicate(0));
            var pool = Executors.newFixedThreadPool(2); var waiting = new AtomicReference<Thread>();
            try {
                var read = pool.submit(() -> entered(context, () -> files.read(0, address(new byte[1]), 1, ForeignSafety.UNSAFE)));
                assertTrue(reading.await(10, TimeUnit.SECONDS));
                var stale = pool.submit(() -> entered(context, () -> { waiting.set(Thread.currentThread()); return files.read(alias, address(new byte[1]), 1, ForeignSafety.UNSAFE); }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while ((waiting.get() == null || waiting.get().getState() != Thread.State.BLOCKED) && System.nanoTime() < deadline) Thread.yield();
                assertEquals(Thread.State.BLOCKED, waiting.get() == null ? null : waiting.get().getState());
                assertEquals(alias, entered(context, () -> files.duplicateTo(1, alias))); release.countDown();
                assertEquals(1L, read.get(10, TimeUnit.SECONDS)); assertEquals(-1L, stale.get(10, TimeUnit.SECONDS));
                assertEquals(1L, entered(context, () -> files.deviceType(alias)));
            } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }
}
