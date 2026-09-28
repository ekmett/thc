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

/** Managed service contract tests, not a claim of ordinary GHC Handle execution. */
class ManagedFilesTest {
    @TempDir Path directory;
    private ManagedAddress text(String value) { return ManagedAddress.fromByteArray((value + "\0").getBytes(StandardCharsets.UTF_8)); }
    private ManagedAddress bytes(String value) { return ManagedAddress.fromByteArray(value.getBytes(StandardCharsets.UTF_8)); }
    private Context.Builder builder() { return Context.newBuilder("thc").allowIO(IOAccess.ALL).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false"); }
    private <T> T entered(Context context, Callable<T> action) throws Exception {
        context.initialize("thc"); context.enter(); try { return action.call(); } finally { context.leave(); }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
    private static void await(CountDownLatch latch, long seconds) {
        try { if (!latch.await(seconds, TimeUnit.SECONDS)) throw new IllegalStateException("Check failed."); }
        catch (InterruptedException failure) { throw propagate(failure); }
    }
    private static class TrackingFileSystem extends FileSystemDelegate {
        final List<SeekableByteChannel> channels = new ArrayList<>();
        TrackingFileSystem() { this(FileSystem.newDefaultFileSystem()); }
        TrackingFileSystem(FileSystem delegate) { super(delegate); }
        @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
            var channel = delegate.newByteChannel(path, options, attributes); channels.add(channel); return channel;
        }
    }
    private record OpenResult(long descriptor, long error) {}
    @Test void blockedInputDoesNotHoldOtherDescriptorsOrTheirClose() throws Exception {
        var reading = new CountDownLatch(1); var release = new CountDownLatch(1);
        var input = new InputStream() {
            @Override public int read() { throw new IllegalStateException("Unexpected single-byte read"); }
            @Override public int read(byte[] target, int offset, int length) { reading.countDown(); await(release, 10); target[offset] = 42; return 1; }
        };
        var output = new ByteArrayOutputStream();
        try (var context = builder().in(input).out(output).build()) {
            var files = entered(context, () -> Language.currentState(null).getFiles()); var workers = Executors.newFixedThreadPool(3);
            try {
                var read = workers.submit(() -> { context.enter(); try { return files.read(0, ManagedAddress.fromByteArray(new byte[1]), 1, ForeignSafety.UNSAFE); } finally { context.leave(); } });
                assertTrue(reading.await(10, TimeUnit.SECONDS));
                var close = workers.submit(() -> { context.enter(); try { return files.close(0, ForeignSafety.UNSAFE); } finally { context.leave(); } });
                var write = workers.submit(() -> { context.enter(); try { return files.write(1, bytes("ready"), 5, ForeignSafety.UNSAFE); } finally { context.leave(); } });
                assertEquals(5L, write.get(3, TimeUnit.SECONDS)); assertEquals("ready", output.toString(StandardCharsets.UTF_8));
                assertFalse(close.isDone(), "Close must wait for the read on its own descriptor"); release.countDown();
                assertEquals(1L, read.get(10, TimeUnit.SECONDS)); assertEquals(0L, close.get(10, TimeUnit.SECONDS));
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }
    @Test void providerCallbackCanUseAClaimedDescriptorWithoutRegistryLockInversion() throws Exception {
        var target = directory.resolve("provider.bin"); var other = directory.resolve("callback.bin");
        Files.writeString(target, "target"); Files.writeString(other, "other");
        var outputEntered = new CountDownLatch(1); var providerEntered = new CountDownLatch(1); var firstWrite = new AtomicBoolean(false);
        var files = new ManagedFiles[1]; var context = new Context[1]; var nestedOpens = Executors.newSingleThreadExecutor();
        var output = new ByteArrayOutputStream() {
            @Override public void write(byte[] value, int offset, int length) {
                if (firstWrite.compareAndSet(true, false)) {
                    outputEntered.countDown(); await(providerEntered, 5);
                    var nested = nestedOpens.submit(() -> { context[0].enter(); try { return files[0].open(text(other.toString()), 0, ForeignSafety.UNSAFE); } finally { context[0].leave(); } });
                    try { if (nested.get(5, TimeUnit.SECONDS) < 3) throw new IllegalStateException("Check failed."); }
                    catch (Exception failure) { throw propagate(failure); }
                }
                super.write(value, offset, length);
            }
        };
        var backing = FileSystem.newDefaultFileSystem();
        var fs = new TrackingFileSystem(backing) {
            @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
                if (path.equals(target)) { providerEntered.countDown(); if (files[0].write(1, bytes("nested"), 6, ForeignSafety.UNSAFE) != 6L) throw new IllegalStateException("Check failed."); }
                return super.newByteChannel(path, options, attributes);
            }
        };
        try (var active = builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).out(output).build()) {
            context[0] = active; files[0] = entered(active, () -> Language.currentState(null).getFiles()); var workers = Executors.newFixedThreadPool(2);
            try {
                firstWrite.set(true);
                var write = workers.submit(() -> { active.enter(); try { return files[0].write(1, bytes("outer"), 5, ForeignSafety.UNSAFE); } finally { active.leave(); } });
                assertTrue(outputEntered.await(10, TimeUnit.SECONDS));
                var open = workers.submit(() -> { active.enter(); try { return files[0].open(text(target.toString()), 0, ForeignSafety.UNSAFE); } finally { active.leave(); } });
                assertEquals(5L, write.get(10, TimeUnit.SECONDS)); assertTrue(open.get(10, TimeUnit.SECONDS) >= 3); assertEquals("outernested", output.toString(StandardCharsets.UTF_8));
            } finally {
                workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); nestedOpens.shutdownNow(); assertTrue(nestedOpens.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }
    @Test void pendingWriterClaimPreventsConcurrentTruncation() throws Exception {
        var path = directory.resolve("pending-writer.bin"); Files.writeString(path, "keep");
        var channelOpened = new CountDownLatch(1); var release = new CountDownLatch(1); var first = new AtomicBoolean(true);
        var fs = new TrackingFileSystem() {
            @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
                var channel = super.newByteChannel(path, options, attributes); if (first.compareAndSet(true, false)) { channelOpened.countDown(); await(release, 10); } return channel;
            }
        };
        try (var context = builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build()) {
            var files = entered(context, () -> Language.currentState(null).getFiles()); var workers = Executors.newFixedThreadPool(2);
            try {
                var writer = workers.submit(() -> { context.enter(); try { return files.open(text(path.toString()), 1, ForeignSafety.UNSAFE); } finally { context.leave(); } });
                assertTrue(channelOpened.await(10, TimeUnit.SECONDS));
                var competing = workers.submit(() -> { context.enter(); try { long fd = files.open(text(path.toString()), 1, ForeignSafety.UNSAFE); return new OpenResult(fd, files.errorKind()); } finally { context.leave(); } });
                assertEquals(new OpenResult(-1, 8), competing.get(10, TimeUnit.SECONDS));
                assertEquals("keep", Files.readString(path), "Neither open may truncate before its writer claim"); release.countDown();
                assertTrue(writer.get(10, TimeUnit.SECONDS) >= 3); assertEquals("", Files.readString(path)); assertEquals(1, fs.channels.size());
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }
    @Test void disposalDuringOpenClosesUnregisteredChannelWithoutTruncating() throws Exception {
        var path = directory.resolve("closing-open.bin"); Files.writeString(path, "keep");
        var channelOpened = new CountDownLatch(1); var disposing = new CountDownLatch(1); var release = new CountDownLatch(1); var armed = new AtomicBoolean();
        var output = new ByteArrayOutputStream() { @Override public void flush() throws IOException { if (armed.get()) disposing.countDown(); super.flush(); } };
        var fs = new TrackingFileSystem() {
            @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
                var channel = super.newByteChannel(path, options, attributes); channelOpened.countDown(); await(release, 10); return channel;
            }
        };
        try (var context = builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).out(output).build()) {
            var files = entered(context, () -> Language.currentState(null).getFiles()); var workers = Executors.newFixedThreadPool(2);
            try {
                var opening = workers.submit(() -> { context.enter(); try { long fd = files.open(text(path.toString()), 1, ForeignSafety.UNSAFE); return new OpenResult(fd, files.errorKind()); } finally { context.leave(); } });
                assertTrue(channelOpened.await(10, TimeUnit.SECONDS)); armed.set(true);
                var disposal = workers.submit(() -> { context.enter(); try { files.dispose(); } finally { context.leave(); } });
                assertTrue(disposing.await(10, TimeUnit.SECONDS)); assertFalse(disposal.isDone(), "Dispose must await the pending channel's cleanup"); release.countDown();
                assertEquals(new OpenResult(-1, 4), opening.get(10, TimeUnit.SECONDS)); disposal.get(10, TimeUnit.SECONDS);
                assertEquals(1, fs.channels.size()); assertFalse(fs.channels.getFirst().isOpen()); assertEquals("keep", Files.readString(path));
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }
    @Test void standardStreamsAreContextOwnedAndClosingThemDoesNotCloseEmbeddingStreams() throws Exception {
        class Input extends ByteArrayInputStream {
            boolean closed; Input() { super(new byte[] {0, 127, -1}); }
            @Override public void close() throws IOException { closed = true; super.close(); }
        }
        class Output extends ByteArrayOutputStream {
            boolean closed; int flushes;
            @Override public void close() throws IOException { closed = true; super.close(); }
            @Override public void flush() throws IOException { flushes++; super.flush(); }
        }
        var input = new Input(); var output = new Output(); var errors = new Output();
        try (var context = builder().in(input).out(output).err(errors).build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); var target = new byte[6]; Arrays.fill(target, (byte) 42);
            assertEquals(2L, files.read(0, ManagedAddress.fromByteArray(target).plus(1), 2, ForeignSafety.UNSAFE));
            assertArrayEquals(new byte[] {42, 0, 127, 42, 42, 42}, target);
            assertEquals(1L, files.read(0, ManagedAddress.fromByteArray(target).plus(3), 3, ForeignSafety.UNSAFE));
            assertEquals(0L, files.read(0, ManagedAddress.fromByteArray(target), 1, ForeignSafety.UNSAFE));
            assertEquals(2L, files.write(1, ManagedAddress.fromByteArray(target).plus(2), 2, ForeignSafety.UNSAFE));
            assertEquals(3L, files.write(2, bytes("err"), 3, ForeignSafety.UNSAFE)); assertArrayEquals(new byte[] {127, -1}, output.toByteArray());
            assertEquals("err", errors.toString(StandardCharsets.UTF_8)); assertEquals(1L, files.deviceType(0)); assertEquals(0L, files.isTerminal(1, ForeignSafety.UNSAFE));
            int outputFlushes = output.flushes, errorFlushes = errors.flushes;
            for (long fd = 0; fd <= 2; fd++) assertEquals(0L, files.close(fd, ForeignSafety.UNSAFE));
            assertEquals(outputFlushes + 1, output.flushes); assertEquals(errorFlushes + 1, errors.flushes);
            assertEquals(-1L, files.write(1, bytes("bad"), 3, ForeignSafety.UNSAFE)); assertEquals(4L, files.errorKind()); return null;
        }); }
        assertFalse(input.closed); assertFalse(output.closed); assertFalse(errors.closed);
        // Polyglot itself may flush the embedding streams again on Context.close.
    }
    @Test void embeddingStreamCallDefersDeliveryUntilAnExplicitGuestCallback() {
        var node = new Node() {}; var threads = new GuestThreads[1]; var id = new long[] {-1};
        var output = new ByteArrayOutputStream() {
            @Override public void write(byte[] bytes, int offset, int length) {
                assertNull(threads[0].poll(node, false), "Embedding stream code is foreign execution");
                assertNotEquals(id[0], threads[0].enterCurrent(null, false, true, null));
                try {
                    assertNull(threads[0].poll(node, false), "A callback cannot claim its suspended caller's request");
                    var callbackRequest = threads[0].send(threads[0].currentIdentity(), "callback self");
                    assertSame(callbackRequest, threads[0].poll(node, false)); callbackRequest.acknowledge();
                } finally { threads[0].leaveCurrent(GuestThreadStatus.FINISHED); }
                assertNull(threads[0].poll(node, false), "Stream execution resumes as foreign"); super.write(bytes, offset, length);
            }
        };
        try (var context = builder().out(output).build()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(null); threads[0] = state.getThreads(); id[0] = threads[0].enterCurrent(null, false, true, null);
                try {
                    var request = threads[0].send(id[0], "stream callback"); assertEquals(3L, state.getFiles().write(1, bytes("abc"), 3, ForeignSafety.SAFE));
                    assertEquals(AsyncRequestState.PENDING, request.getState());
                    assertSame(request, threads[0].poll(node, false), "The completed write returns to the original caller"); request.acknowledge();
                    assertEquals("abc", output.toString(StandardCharsets.UTF_8)); var after = threads[0].send(id[0], "after stream");
                    assertSame(after, threads[0].poll(node, false)); after.acknowledge();
                } finally { threads[0].leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
    @Test void unsafeStreamServiceDoesNotInheritAnOuterSafeDeclaration() {
        var threads = new GuestThreads[1]; var callbacks = new int[1];
        var output = new ByteArrayOutputStream() {
            @Override public void write(byte[] bytes, int offset, int length) {
                threads[0].enterCurrent(null, false, true, null);
                try { callbacks[0]++; super.write(bytes, offset, length); } finally { threads[0].leaveCurrent(GuestThreadStatus.FINISHED); }
            }
        };
        try (var context = builder().out(output).build()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(null); threads[0] = state.getThreads(); threads[0].enterCurrent(null, false, true, null);
                var caller = threads[0].currentIdentity();
                try {
                    var safe = threads[0].enterForeign(ForeignSafety.SAFE);
                    try {
                        assertThrows(RuntimeFault.class, () -> state.getFiles().write(1, bytes("abc"), 3, ForeignSafety.UNSAFE));
                        assertEquals(0, callbacks[0]); assertEquals(0, output.size()); assertSame(caller, threads[0].currentIdentity());
                        assertFalse(caller.getAllocationSuspended());
                    } finally { threads[0].leaveForeign(safe); }
                    assertEquals(3L, state.getFiles().write(1, bytes("abc"), 3, ForeignSafety.SAFE));
                    assertEquals(1, callbacks[0]); assertEquals("abc", output.toString(StandardCharsets.UTF_8));
                } finally { threads[0].leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
    @Test void teardownFlushRejectsAnotherContextCallbackBeforeGuestMutation() {
        var other = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.discover(false), ignored -> {});
        var armed = new AtomicBoolean(); var observed = new AtomicReference<RuntimeFault>();
        var output = new ByteArrayOutputStream() {
            @Override public void flush() throws IOException {
                if (armed.compareAndSet(true, false)) {
                    observed.set(assertThrows(RuntimeFault.class, () -> other.enterCurrent(null, false, true, null)));
                    assertThrows(RuntimeFault.class, other::currentIdentity);
                }
                super.flush();
            }
        };
        var context = builder().out(output).build(); context.initialize("thc"); armed.set(true); context.close();
        assertTrue(Objects.requireNonNull(observed.get().getMessage()).contains("closed")); other.enterCurrent(null, false, true, null);
        try { assertFalse(other.isCurrentBound()); } finally { other.leaveCurrent(GuestThreadStatus.FINISHED); other.close(); }
    }
    @Test void realFileRoundTripSupportsOffsetsUnicodeNamesEofAndNonReusedDescriptors() throws Exception {
        var path = directory.resolve("café-λ.bin");
        try (var context = builder().build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); long fd = files.open(text(path.toString()), 1, ForeignSafety.UNSAFE); assertTrue(fd >= 3);
            var data = new byte[] {99, 0, 127, -128, -1, 98};
            assertEquals(4L, files.write(fd, ManagedAddress.fromByteArray(data).plus(1), 4, ForeignSafety.UNSAFE));
            assertEquals(4L, files.size(fd)); assertEquals(0L, files.deviceType(fd)); assertEquals(0L, files.close(fd, ForeignSafety.UNSAFE));
            assertArrayEquals(Arrays.copyOfRange(data, 1, 5), Files.readAllBytes(path));
            long reader = files.open(text(path.toString()), 0, ForeignSafety.UNSAFE); assertTrue(reader > fd);
            var target = new byte[8]; Arrays.fill(target, (byte) 33);
            assertEquals(4L, files.read(reader, ManagedAddress.fromByteArray(target).plus(2), 6, ForeignSafety.UNSAFE));
            assertArrayEquals(new byte[] {33, 33, 0, 127, -128, -1, 33, 33}, target);
            assertEquals(0L, files.read(reader, ManagedAddress.fromByteArray(target), 8, ForeignSafety.UNSAFE));
            assertEquals(-1L, files.read(fd, ManagedAddress.fromByteArray(target), 1, ForeignSafety.UNSAFE)); assertEquals(4L, files.errorKind()); return null;
        }); }
    }
    @Test void incompatibleOpenDoesNotTruncateAndReadClaimsPermitOtherReaders() throws Exception {
        var path = directory.resolve("claimed.bin"); Files.writeString(path, "keep");
        try (var context = builder().build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); long one = files.open(text(path.toString()), 0, ForeignSafety.UNSAFE), two = files.open(text(path.toString()), 0, ForeignSafety.UNSAFE);
            assertTrue(one >= 3); assertTrue(two > one); assertEquals(-1L, files.open(text(path.toString()), 1, ForeignSafety.UNSAFE));
            assertEquals(8L, files.errorKind()); assertEquals("keep", Files.readString(path)); files.close(one, ForeignSafety.UNSAFE); files.close(two, ForeignSafety.UNSAFE);
            long writer = files.open(text(path.toString()), 1, ForeignSafety.UNSAFE); assertTrue(writer >= 3); assertEquals(0L, files.size(writer));
            assertEquals(-1L, files.open(text(path.toString()), 0, ForeignSafety.UNSAFE)); assertEquals(8L, files.errorKind()); files.close(writer, ForeignSafety.UNSAFE);
            assertTrue(files.open(text(path.toString()), 0, ForeignSafety.UNSAFE) >= 3); return null;
        }); }
    }
    @Test void aliasesShareTheReaderWriterClaim() throws Exception {
        var path = directory.resolve("original.bin"); Files.writeString(path, "unchanged"); var hard = directory.resolve("hard.bin"); Files.createLink(hard, path);
        var symbolic = directory.resolve("symbolic.bin"); Files.createSymbolicLink(symbolic, path.getFileName());
        try (var context = builder().build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); long reader = files.open(text(path.toString()), 0, ForeignSafety.UNSAFE);
            for (var alias : List.of(hard, symbolic)) {
                assertEquals(-1L, files.open(text(alias.toString()), 1, ForeignSafety.UNSAFE)); assertEquals(8L, files.errorKind()); assertEquals("unchanged", Files.readString(path));
            }
            files.close(reader, ForeignSafety.UNSAFE); assertTrue(files.open(text(hard.toString()), 1, ForeignSafety.UNSAFE) >= 3); assertEquals("", Files.readString(path)); return null;
        }); }
    }
    @Test void readerClaimFollowsFileIdentityAcrossRenameAndReplacement() throws Exception {
        var original = directory.resolve("original.bin"); Files.writeString(original, "keep"); var renamed = directory.resolve("renamed.bin");
        try (var context = builder().build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); long reader = files.open(text(original.toString()), 0, ForeignSafety.UNSAFE); assertTrue(reader >= 3);
            Files.move(original, renamed); Files.writeString(original, "replacement"); assertEquals(-1L, files.open(text(renamed.toString()), 1, ForeignSafety.UNSAFE));
            assertEquals(8L, files.errorKind()); assertEquals("keep", Files.readString(renamed));
            long replacement = files.open(text(original.toString()), 1, ForeignSafety.UNSAFE); assertTrue(replacement >= 3); assertEquals("", Files.readString(original));
            var content = new byte[4]; assertEquals(4L, files.read(reader, ManagedAddress.fromByteArray(content), 4, ForeignSafety.UNSAFE));
            assertEquals("keep", new String(content, StandardCharsets.UTF_8)); files.close(reader, ForeignSafety.UNSAFE);
            assertTrue(files.open(text(renamed.toString()), 1, ForeignSafety.UNSAFE) >= 3); return null;
        }); }
    }
    @Test void unlinkingAnOpenFileDoesNotPreventUnrelatedOpens() throws Exception {
        var original = directory.resolve("unlinked.bin"); Files.writeString(original, "keep"); var unrelated = directory.resolve("unrelated.bin"); Files.writeString(unrelated, "replace");
        try (var context = builder().build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); long reader = files.open(text(original.toString()), 0, ForeignSafety.UNSAFE); assertTrue(reader >= 3);
            Files.delete(original); assertTrue(files.open(text(unrelated.toString()), 1, ForeignSafety.UNSAFE) >= 3);
            var content = new byte[4]; assertEquals(4L, files.read(reader, ManagedAddress.fromByteArray(content), 4, ForeignSafety.UNSAFE));
            assertEquals("keep", new String(content, StandardCharsets.UTF_8)); return null;
        }); }
    }
    @Test void unavailableStableIdentityFailsBeforeOpeningOrTruncatingExistingFile() throws Exception {
        var path = directory.resolve("no-identity.bin"); Files.writeString(path, "keep"); var backing = FileSystem.newDefaultFileSystem();
        var fs = new TrackingFileSystem(backing) {
            @Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
                var result = new LinkedHashMap<>(backing.readAttributes(path, attributes, options)); result.remove("dev"); return result;
            }
        };
        try (var context = builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); assertEquals(-1L, files.open(text(path.toString()), 1, ForeignSafety.UNSAFE));
            assertEquals(7L, files.errorKind()); assertEquals("keep", Files.readString(path)); assertTrue(fs.channels.isEmpty()); return null;
        }); }
    }
    @Test void detectedIdentityChangeClosesTheAcquiredChannelWithoutTruncation() throws Exception {
        var path = directory.resolve("changing.bin"); Files.writeString(path, "original"); var moved = directory.resolve("moved.bin");
        var fs = new TrackingFileSystem() {
            @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
                var channel = super.newByteChannel(path, options, attributes); Files.move(path, moved); Files.writeString(path, "replacement"); return channel;
            }
        };
        try (var context = builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); assertEquals(-1L, files.open(text(path.toString()), 1, ForeignSafety.UNSAFE)); assertEquals(8L, files.errorKind());
            assertEquals("original", Files.readString(moved)); assertEquals("replacement", Files.readString(path)); assertEquals(1, fs.channels.size());
            assertFalse(fs.channels.getFirst().isOpen()); return null;
        }); }
    }
    @Test void seekReadWriteAndResizePreservePositionAndValidateOffsets() throws Exception {
        var path = directory.resolve("update.bin");
        try (var context = builder().build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); long fd = files.open(text(path.toString()), 3, ForeignSafety.UNSAFE);
            assertEquals(6L, files.write(fd, bytes("abcdef"), 6, ForeignSafety.UNSAFE)); assertEquals(2L, files.seek(fd, 2, 0, ForeignSafety.UNSAFE));
            var read = new byte[2]; assertEquals(2L, files.read(fd, ManagedAddress.fromByteArray(read), 2, ForeignSafety.UNSAFE));
            assertEquals("cd", new String(read, StandardCharsets.UTF_8)); assertEquals(1L, files.seek(fd, -3, 1, ForeignSafety.UNSAFE));
            assertEquals(1L, files.write(fd, bytes("X"), 1, ForeignSafety.UNSAFE)); assertEquals("aXcdef", Files.readString(path));
            assertEquals(5L, files.seek(fd, -1, 2, ForeignSafety.UNSAFE)); assertEquals(0L, files.setSize(fd, 3));
            assertEquals(5L, files.seek(fd, 0, 1, ForeignSafety.UNSAFE)); assertEquals(3L, files.size(fd)); assertEquals(0L, files.setSize(fd, 7));
            assertEquals(5L, files.seek(fd, 0, 1, ForeignSafety.UNSAFE)); assertArrayEquals(new byte[] {97, 88, 99, 0, 0, 0, 0}, Files.readAllBytes(path));
            assertEquals(-1L, files.seek(fd, Long.MAX_VALUE, 2, ForeignSafety.UNSAFE)); assertEquals(5L, files.errorKind());
            assertEquals(-1L, files.seek(fd, -1, 0, ForeignSafety.UNSAFE)); assertEquals(5L, files.errorKind());
            assertEquals(-1L, files.seek(fd, 0, 3, ForeignSafety.UNSAFE)); assertEquals(5L, files.errorKind());
            assertEquals(5L, files.seek(fd, 0, 1, ForeignSafety.UNSAFE)); assertEquals(-1L, files.setSize(fd, -1)); assertEquals(7L, files.size(fd)); return null;
        }); }
    }
    @Test void appendWritesAtEndEvenAfterSeek() throws Exception {
        var path = directory.resolve("append.bin"); Files.writeString(path, "abc");
        try (var context = builder().build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); long fd = files.open(text(path.toString()), 2, ForeignSafety.UNSAFE);
            assertEquals(0L, files.seek(fd, 0, 0, ForeignSafety.UNSAFE)); assertEquals(2L, files.write(fd, bytes("de"), 2, ForeignSafety.UNSAFE));
            assertEquals("abcde", Files.readString(path)); assertEquals(-1L, files.setSize(fd, 8)); assertEquals(7L, files.errorKind()); assertEquals(5L, files.size(fd)); return null;
        }); }
    }
    @Test void expectedFailuresBecomePortableErrorsAndDoNotClearOnSuccess() throws Exception {
        try (var context = builder().build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); assertEquals(-1L, files.open(text(directory.resolve("absent").toString()), 0, ForeignSafety.UNSAFE));
            assertEquals(1L, files.errorKind()); var message = files.errorMessage(); assertTrue(message.utf8().contains("absent"));
            assertEquals(1L, files.deviceType(1)); assertEquals(1L, files.errorKind()); assertEquals(-1L, files.open(text(directory.toString()), 0, ForeignSafety.UNSAFE));
            assertEquals(9L, files.errorKind()); assertEquals(-1L, files.open(text(""), 0, ForeignSafety.UNSAFE)); assertEquals(1L, files.errorKind());
            assertEquals(-1L, files.open(text(directory.resolve("new").toString()), 8, ForeignSafety.UNSAFE)); assertEquals(5L, files.errorKind()); assertFalse(Files.exists(directory.resolve("new")));
            assertEquals(-1L, files.seek(1, 0, 0, ForeignSafety.UNSAFE)); assertEquals(7L, files.errorKind()); assertEquals(-1L, files.size(1)); assertEquals(7L, files.errorKind());
            assertEquals(-1L, files.close(-1, ForeignSafety.UNSAFE)); assertEquals(4L, files.errorKind()); assertTrue(message.utf8().contains("absent")); // Detached from later failures.
            return null;
        }); }
    }
    @Test void embeddingFilePolicyIsHonoredWithoutNativeAccess() throws Exception {
        var path = directory.resolve("denied.bin");
        try (var context = Context.newBuilder("thc").allowIO(IOAccess.NONE).build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); assertEquals(-1L, files.open(text(path.toString()), 1, ForeignSafety.UNSAFE));
            assertEquals(2L, files.errorKind()); assertFalse(Files.exists(path)); return null;
        }); }
    }
    @Test void invalidManagedMemoryCannotConsumeInputOrWriteAnything() throws Exception {
        var input = new ByteArrayInputStream(new byte[] {7, 8}); var output = new ByteArrayOutputStream();
        try (var context = builder().in(input).out(output).build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); var data = new byte[] {42};
            for (long length : new long[] {-1, 2, Long.MAX_VALUE}) {
                assertThrows(RuntimeFault.class, () -> files.read(0, ManagedAddress.fromByteArray(data), length, ForeignSafety.UNSAFE));
                assertThrows(RuntimeFault.class, () -> files.write(1, ManagedAddress.fromByteArray(data), length, ForeignSafety.UNSAFE));
            }
            assertThrows(RuntimeFault.class, () -> files.read(0, ManagedAddress.fromHex("00"), 1, ForeignSafety.UNSAFE));
            assertEquals(2, input.available()); assertEquals(0, output.size()); assertArrayEquals(new byte[] {42}, data);
            var onePast = ManagedAddress.fromByteArray(data).plus(1);
            assertEquals(0L, files.read(0, onePast, 0, ForeignSafety.UNSAFE)); assertEquals(0L, files.write(1, onePast, 0, ForeignSafety.UNSAFE));
            assertEquals(-1L, files.read(1, onePast, 0, ForeignSafety.UNSAFE)); assertEquals(4L, files.errorKind());
            assertThrows(RuntimeFault.class, () -> files.open(ManagedAddress.fromByteArray(new byte[] {-1, 0}), 1, ForeignSafety.UNSAFE));
            assertThrows(RuntimeFault.class, () -> files.open(bytes("unterminated"), 1, ForeignSafety.UNSAFE)); return null;
        }); }
    }
    @Test void contextDisposalReleasesOwnedFilesAndContextStateIsIsolated() throws Exception {
        var path = directory.resolve("dispose.bin"); var retained = new ManagedFiles[1]; var fd = new long[] {-1};
        try (var context = builder().build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); retained[0] = files; fd[0] = files.open(text(path.toString()), 1, ForeignSafety.UNSAFE);
            assertTrue(fd[0] >= 3); assertEquals(-1L, files.close(99, ForeignSafety.UNSAFE)); assertEquals(4L, files.errorKind()); return null;
        }); }
        assertEquals(-1L, Objects.requireNonNull(retained[0]).size(fd[0])); assertEquals(4L, retained[0].errorKind());
        try (var context = builder().build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); assertEquals(0L, files.errorKind()); assertEquals(1L, files.deviceType(1));
            assertTrue(files.open(text(path.toString()), 1, ForeignSafety.UNSAFE) >= 3); return null;
        }); }
    }
    @Test void disposalClosesOwnedChannelsAfterCheckedAndUncheckedEmbeddingFailures() throws Exception {
        for (var first : List.of(new IOException("checked flush"), new IllegalStateException("unchecked flush"))) {
            var second = new IllegalArgumentException("second flush"); var failing = new boolean[1];
            var output = new ByteArrayOutputStream() { @Override public void flush() { if (failing[0]) throw propagate(first); } };
            var errors = new ByteArrayOutputStream() { @Override public void flush() { if (failing[0]) throw second; } };
            var fs = new TrackingFileSystem();
            try (var context = builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).out(output).err(errors).build()) { entered(context, () -> {
                var files = Language.currentState(null).getFiles(); var path = directory.resolve("cleanup-" + first.getClass().getSimpleName() + ".bin");
                assertTrue(files.open(text(path.toString()), 1, ForeignSafety.UNSAFE) >= 3); assertEquals(1, fs.channels.size()); assertTrue(fs.channels.getFirst().isOpen());
                failing[0] = true;
                try {
                    var thrown = assertThrows(RuntimeException.class, files::dispose); var cause = first instanceof IOException ? thrown.getCause() : thrown;
                    assertSame(first, cause); assertArrayEquals(new Throwable[] {second}, first.getSuppressed()); assertFalse(fs.channels.getFirst().isOpen());
                    assertDoesNotThrow(files::dispose);
                } finally { failing[0] = false; }
                return null;
            }); }
        }
    }
    @Test void streamIoFailureIsNotSuccessfulOutputAndDoesNotCatchRuntimeFaults() throws Exception {
        var output = new ByteArrayOutputStream() {
            @Override public void write(byte[] bytes, int offset, int length) { throw propagate(new IOException("write failed")); }
        };
        try (var context = builder().out(output).build()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); assertEquals(-1L, files.write(1, bytes("hello"), 5, ForeignSafety.UNSAFE));
            assertEquals(6L, files.errorKind()); assertTrue(files.errorMessage().utf8().contains("write failed")); return null;
        }); }
    }
}
