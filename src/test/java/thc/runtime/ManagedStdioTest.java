// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ManagedFileFixtures.*;

class ManagedStdioTest {
    @TempDir Path directory;
    private ManagedAddress bytes(byte... value) { return ManagedAddress.fromByteArray(value); }
    private Context context(ByteArrayOutputStream output) { return context(output, new ByteArrayOutputStream()); }
    private Context context(ByteArrayOutputStream output, ByteArrayOutputStream errors) { return Context.newBuilder("thc").allowIO(IOAccess.NONE).out(output).err(errors).build(); }
    private <T> T entered(Context context, Callable<T> action) throws Exception {
        context.initialize("thc"); context.enter(); try { return action.call(); } finally { context.leave(); }
    }
    private ManagedAddress path(Path path) { return ManagedAddress.fromByteArray((path + "\0").getBytes(StandardCharsets.UTF_8)); }
    @Test void truncateClassifiesReadOnlyWithoutASecondProviderOperation() throws Exception {
        var path = directory.resolve("read-only.bin"); Files.writeString(path, "abcdef"); var sizeCalls = new AtomicInteger();
        var backing = FileSystem.newDefaultFileSystem();
        var fs = new FileSystemDelegate(backing) {
            @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
                var channel = backing.newByteChannel(path, options, attributes);
                return new ChannelDelegate(channel) {
                    @Override public long size() throws IOException { sizeCalls.incrementAndGet(); throw new IOException("size must not be queried to classify ftruncate"); }
                };
            }
        };
        try (var context = Context.newBuilder("thc").allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build()) { entered(context, () -> {
            var stdio = Language.currentState(null).getStdio(); var files = Language.currentState(null).getFiles();
            long fd = files.open(path(path), 0L, ForeignSafety.UNSAFE); assertTrue(fd >= 3L);
            // The managed operation keeps its existing EBADF category.
            assertEquals(-1L, files.setSize(fd, 3L)); assertEquals(4L, files.errorKind());
            assertEquals(-1L, stdio.truncate(fd, 3L)); assertEquals(StdioHostAbi.load().error(5), stdio.errno()); assertEquals(0, sizeCalls.get());
            assertEquals(0L, files.close(fd, ForeignSafety.UNSAFE)); assertEquals(-1L, stdio.truncate(fd, 3L));
            assertEquals(StdioHostAbi.load().error(4), stdio.errno()); assertEquals(0, sizeCalls.get()); return null;
        }); }
        assertEquals("abcdef", Files.readString(path));
    }
    @Test void truncateSeparatesKnownStreamsFromUnsupportedProviders() throws Exception {
        var path = directory.resolve("unsupported-size.bin"); Files.writeString(path, "abcdef"); var backing = FileSystem.newDefaultFileSystem();
        var fs = new FileSystemDelegate(backing) {
            @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
                return new ChannelDelegate(backing.newByteChannel(path, options, attributes)) {
                    @Override public long size() { throw new UnsupportedOperationException("provider cannot resize"); }
                };
            }
        };
        try (var context = Context.newBuilder("thc").allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build()) { entered(context, () -> {
            var stdio = Language.currentState(null).getStdio(); var files = Language.currentState(null).getFiles(); var abi = StdioHostAbi.load();
            // A known embedding stream is EINVAL in the original C ABI,
            // while its managed service category remains Unsupported.
            assertEquals(-1L, files.setSize(1L, 3L)); assertEquals(7L, files.errorKind());
            assertEquals(-1L, stdio.truncate(1L, 3L)); assertEquals(abi.error(5), stdio.errno());
            long fd = files.open(path(path), 3L, ForeignSafety.UNSAFE); assertTrue(fd >= 3L);
            assertEquals(-1L, stdio.truncate(fd, 3L)); assertEquals(abi.error(7), stdio.errno());
            assertEquals(7L, files.errorKind()); assertEquals(0L, files.close(fd, ForeignSafety.UNSAFE)); return null;
        }); }
        assertEquals("abcdef", Files.readString(path));
    }
    @Test void extendingAppendModeRemainsExplicitlyUnsupported() throws Exception {
        var path = directory.resolve("append-size.bin"); Files.writeString(path, "abcdef");
        try (var context = Context.newBuilder("thc").allowIO(IOAccess.ALL).build()) { entered(context, () -> {
            var stdio = Language.currentState(null).getStdio(); var files = Language.currentState(null).getFiles();
            long fd = files.open(path(path), 2L, ForeignSafety.UNSAFE); assertTrue(fd >= 3L);
            assertEquals(-1L, stdio.truncate(fd, 9L)); assertEquals(StdioHostAbi.load().error(7), stdio.errno());
            assertEquals(0L, files.close(fd, ForeignSafety.UNSAFE)); return null;
        }); }
        assertEquals("abcdef", Files.readString(path));
    }
    @Test void originalWritesUseContextStreamsBinaryRangesAndActualStickyErrno() throws Exception {
        var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream(); long badDescriptor = StdioHostAbi.load().error(4);
        try (var context = context(output, errors)) { entered(context, () -> {
            var stdio = Language.currentState(null).getStdio(); var address = bytes((byte) 99, (byte) 0, (byte) 127, (byte) -128, (byte) -1, (byte) 98);
            assertEquals(0L, stdio.errno()); assertEquals(4L, stdio.write(1, address.plus(1), 4));
            assertArrayEquals(new byte[] {0, 127, -128, -1}, output.toByteArray());
            assertEquals(-1L, stdio.write(-1, address, 1)); assertEquals(badDescriptor, stdio.errno());
            assertEquals(2L, stdio.write(2, address.plus(3), 2)); assertArrayEquals(new byte[] {-128, -1}, errors.toByteArray());
            assertEquals(badDescriptor, stdio.errno()); assertEquals(0L, stdio.write(1, address.plus(6), 0));
            assertEquals(-1L, stdio.write(0, address.plus(6), 0)); assertEquals(badDescriptor, stdio.errno()); assertEquals(4, output.size()); return null;
        }); }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
    @Test void ordinaryIoFailureSetsHostEioButMalformedValuesFaultBeforeEffects() throws Exception {
        var output = new ByteArrayOutputStream() {
            @Override public void write(byte[] value, int offset, int length) { throw propagate(new IOException("host stream failed")); }
        };
        try (var context = context(output)) { entered(context, () -> {
            var stdio = Language.currentState(null).getStdio(); var address = bytes((byte) 7); long ioError = StdioHostAbi.load().error(6);
            assertEquals(-1L, stdio.write(1, address, 1)); assertEquals(ioError, stdio.errno());
            for (long count : new long[] {-1, 2, Long.MIN_VALUE, Long.MAX_VALUE}) assertThrows(RuntimeFault.class, () -> stdio.write(1, address, count));
            for (long fd : new long[] {(long) Integer.MAX_VALUE + 1, (long) Integer.MIN_VALUE - 1}) assertThrows(RuntimeFault.class, () -> stdio.write(fd, address, 1));
            assertEquals(ioError, stdio.errno()); assertEquals(0, output.size()); return null;
        }); }
    }
    @Test void errnoAndStreamsDoNotLeakBetweenContexts() throws Exception {
        var one = new ByteArrayOutputStream(); var two = new ByteArrayOutputStream();
        try (var first = context(one); var second = context(two)) {
            entered(first, () -> { assertEquals(-1L, Language.currentState(null).getStdio().write(-1, bytes((byte) 1), 1)); return null; });
            entered(second, () -> { var stdio = Language.currentState(null).getStdio(); assertEquals(0L, stdio.errno()); assertEquals(1L, stdio.write(1, bytes((byte) 2), 1)); return null; });
            entered(first, () -> { var stdio = Language.currentState(null).getStdio(); assertEquals(StdioHostAbi.load().error(4), stdio.errno()); assertEquals(1L, stdio.write(1, bytes((byte) 3), 1)); return null; });
        }
        assertArrayEquals(new byte[] {3}, one.toByteArray()); assertArrayEquals(new byte[] {2}, two.toByteArray());
    }
    @Test void partialCountsArePreservedAndNonemptyZeroProgressCannotSpin() throws Exception {
        var backing = FileSystem.newDefaultFileSystem(); var stalled = new boolean[1];
        var fs = new FileSystemDelegate(backing) {
            @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
                var delegate = backing.newByteChannel(path, options, attributes);
                return new ChannelDelegate(delegate) {
                    @Override public int write(ByteBuffer source) throws IOException {
                        if (stalled[0]) return 0; int limit = source.limit(); source.limit(source.position() + Math.min(2, source.remaining()));
                        try { return delegate.write(source); } finally { source.limit(limit); }
                    }
                };
            }
        };
        try (var context = Context.newBuilder("thc").allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build()) { entered(context, () -> {
            var stdio = Language.currentState(null).getStdio(); var path = directory.resolve("partial.bin");
            long fd = Language.currentState(null).getFiles().open(path(path), 1, ForeignSafety.UNSAFE); assertTrue(fd >= 3);
            assertEquals(2L, stdio.write(fd, bytes((byte) 1, (byte) 2, (byte) 3, (byte) 4), 4));
            assertEquals(0L, stdio.errno()); assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(path)); stalled[0] = true;
            assertEquals(-1L, stdio.write(fd, bytes((byte) 3, (byte) 4), 2)); assertEquals(StdioHostAbi.load().error(6), stdio.errno());
            assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(path)); assertEquals(0L, stdio.write(fd, bytes(), 0)); return null;
        }); }
    }
}
