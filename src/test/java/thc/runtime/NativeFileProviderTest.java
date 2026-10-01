// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import thc.NativeFileSystem;
import thc.NativeIO;
import thc.NativeIO.StandardEndpoint;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static thc.Main.executionContext;
import static thc.runtime.ManagedFileFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Provider/ownership proof only, deliberately not original FCall admission. */
@EnabledIf("supportedFileTarget")
class NativeFileProviderTest {
    private static boolean supportedFileTarget() {
        return NativeIO.supportedPosixHost() || "Mac OS X".equals(System.getProperty("os.name")) &&
            Set.of("amd64", "x86_64", "aarch64", "arm64").contains(System.getProperty("os.arch"));
    }
    @TempDir Path directory;
    private Context nativeContext() { return nativeContext(Set.of()); }
    private Context nativeContext(Set<StandardEndpoint> endpoints) { return NativeIO.createContext(endpoints); }
    private <T> T entered(Context context, Callable<T> body) throws Exception {
        context.initialize("thc"); context.enter(); try { return body.call(); } finally { context.leave(); }
    }
    private NativeFileProvider provider() { return NativeFileProvider.current(); }
    private long field(byte[] image, OriginalStdioOp op) { return PosixStat.execute(op, ManagedAddress.fromByteArray(image), 0); }
    private record Identity(long device, long inode) {}
    private Identity identity(OpenedNativeFile file) throws IOException {
        var image = file.statImage(); return new Identity(field(image, OriginalStdioOp.ST_DEV), field(image, OriginalStdioOp.ST_INO));
    }
    private ManagedAddress path(Path path) { return ManagedAddress.fromByteArray((path + "\0").getBytes(StandardCharsets.UTF_8)); }
    private Identity identity(ManagedFiles files, long fd) {
        var image = files.statImage(fd); return new Identity(field(image, OriginalStdioOp.ST_DEV), field(image, OriginalStdioOp.ST_INO));
    }
    // Test-only kernel observation; no fd integer enters production or Core.
    private long nativeDescriptors(Path path) throws IOException {
        long count = 0;
        try (var entries = Files.newDirectoryStream(Path.of("/proc/self/fd"))) {
            for (var entry : entries) try { if (Files.readSymbolicLink(entry).equals(path.toAbsolutePath())) count++; }
            catch (NoSuchFileException ignored) { }
        }
        return count;
    }
    @Test void nativeAndCommandLineContextsPermitGuestThreads() throws Exception {
        for (var factory : List.<Supplier<Context>>of(this::nativeContext, () -> executionContext(true))) try (var context = factory.get()) { entered(context, () -> {
            assertNotNull(Language.currentState(null).getNativeFiles()); var ran = new CountDownLatch(1);
            var thread = Language.currentState(null).getEnv().newTruffleThreadBuilder(ran::countDown).build(); thread.start();
            assertTrue(ran.await(5, TimeUnit.SECONDS), "Guest thread must run in the native context"); thread.join(5000); assertFalse(thread.isAlive()); return null;
        }); }
        try (var context = executionContext(true)) { entered(context, () -> {
            for (var endpoint : StandardEndpoint.values()) try (var opened = provider().standard(endpoint)) {
                assertTrue(opened.isOpen(), "The command line explicitly grants " + endpoint);
            }
            for (long fd = 0; fd <= 2; fd++) assertTrue(Language.currentState(null).getFiles().statImage(fd).length != 0); return null;
        }); }
    }
    @Test void metadataAndBytesRetainOneOpenedResourceAcrossRenameUnlinkAndHostChanges() throws Exception {
        var original = directory.resolve("original"); var renamed = directory.resolve("renamed"); Files.writeString(original, "before");
        try (var context = nativeContext()) { entered(context, () -> {
            try (var opened = provider().open(original.toString(), 3)) {
                var id = identity(opened); assertEquals(6L, opened.size()); Files.move(original, renamed); Files.writeString(original, "replacement");
                Files.setPosixFilePermissions(renamed, PosixFilePermissions.fromString("r--------")); long mode = field(opened.statImage(), OriginalStdioOp.ST_MODE);
                assertEquals(0x100L, mode & 0x1ff); assertEquals(id, identity(opened));
                assertEquals(5, opened.write(ByteBuffer.wrap("guest".getBytes(StandardCharsets.UTF_8)))); assertEquals("gueste", Files.readString(renamed));
                Files.setPosixFilePermissions(renamed, PosixFilePermissions.fromString("rw-------"));
                try (var channel = Files.newByteChannel(renamed, StandardOpenOption.WRITE)) { channel.truncate(2); }
                assertEquals(2L, opened.size()); Files.delete(renamed); assertEquals(id, identity(opened)); assertEquals(2L, opened.size()); opened.position(0);
                var bytes = ByteBuffer.allocate(2); assertEquals(2, opened.read(bytes)); assertArrayEquals("gu".getBytes(StandardCharsets.UTF_8), bytes.array());
                var copy = opened.statImage(); Arrays.fill(copy, (byte) 0); assertEquals(id, identity(opened), "Metadata must not be an exposed mutable cache");
                assertEquals("replacement", Files.readString(original));
            }
            return null;
        }); }
    }
    @Test void nontruncatingAcquisitionAndChannelPreflightsPreserveBytesAndPosition() throws Exception {
        var path = directory.resolve("bytes"); Files.writeString(path, "123456");
        try (var context = nativeContext()) { entered(context, () -> {
            var provider = provider(); try (var opened = provider.open(path.toString(), 1)) { assertEquals(6L, opened.size()); }
            assertEquals("123456", Files.readString(path));
            try (var opened = provider.open(path.toString(), 0)) {
                assertThrows(NonWritableChannelException.class, () -> opened.truncate(6));
                assertThrows(ReadOnlyBufferException.class, () -> opened.read(ByteBuffer.allocate(2).asReadOnlyBuffer())); assertEquals(0L, opened.position());
                var bytes = new byte[8]; Arrays.fill(bytes, (byte) 90); var destination = ByteBuffer.wrap(bytes); destination.position(2); destination.limit(5);
                assertEquals(3, opened.read(destination)); assertArrayEquals(new byte[] {90, 90, 49, 50, 51, 90, 90, 90}, destination.array());
                opened.position(6); assertEquals(-1, opened.read(ByteBuffer.allocate(1))); assertEquals(0, opened.read(ByteBuffer.allocate(0)));
            }
            try (var opened = provider.open(path.toString(), 3)) { opened.position(6); opened.truncate(2); assertEquals(2L, opened.position()); opened.truncate(20); assertEquals(2L, opened.size()); }
            return null;
        }); }
    }
    @Test void nativeRelativeAcquisitionRetainsDirectoryIdentityAcrossRenameAndReplacement() throws Exception {
        var original = Files.createDirectory(directory.resolve("relative")); var moved = directory.resolve("relative-moved");
        Files.writeString(original.resolve("value"), "owned");
        try (var context = nativeContext()) { entered(context, () -> {
            var env = Language.currentState(null).getEnv();
            env.setCurrentWorkingDirectory(env.getPublicTruffleFile(original.toUri()));
            Files.move(original, moved); Files.createDirectory(original); Files.writeString(original.resolve("value"), "replacement");
            try (var opened = provider().open("value", 0)) {
                var bytes = ByteBuffer.allocate(5); assertEquals(5, opened.read(bytes));
                assertArrayEquals("owned".getBytes(StandardCharsets.UTF_8), bytes.array());
            }
            assertEquals("replacement", Files.readString(original.resolve("value")));
            assertEquals(moved.toRealPath(), Path.of(env.getCurrentWorkingDirectory().toUri()));
            assertThrows(IllegalArgumentException.class, () -> env.setCurrentWorkingDirectory(env.getPublicTruffleFile(moved.resolve("missing").toUri())));
            try (var opened = provider().open("value", 0)) { assertEquals(5, opened.size()); }
            return null;
        }); }
    }
    private void rejectUnauthenticatedFactories() throws Exception {
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) { entered(context, () -> { assertThrows(SecurityException.class, this::provider); return null; }); }
        try (var context = Context.newBuilder("thc").allowIO(IOAccess.ALL).build()) { entered(context, () -> { assertThrows(SecurityException.class, this::provider); return null; }); }
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.ALL).build()) { entered(context, () -> {
            assertThrows(SecurityException.class, () -> provider().open(directory.resolve("never").toString(), 3)); assertFalse(Files.exists(directory.resolve("never"))); return null;
        }); }
        var readOnlyNative = new NativeFileSystem();
        try {
            try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.newBuilder().fileSystem(FileSystem.newReadOnlyFileSystem(readOnlyNative)).build()).build()) { entered(context, () -> {
                assertThrows(SecurityException.class, () -> provider().open(directory.resolve("never").toString(), 3)); assertFalse(Files.exists(directory.resolve("never"))); return null;
            }); }
        } finally { readOnlyNative.getDirectoryOwner().close(); }
    }
    @Test void nativeAndFilesystemAuthorityRemainRequiredBeforeAcquisition() throws Exception {
        rejectUnauthenticatedFactories();
    }
    @EnabledOnOs(OS.LINUX)
    @Test void nativeAndFilesystemAuthorityAreBothRequiredAndErrorsAreReal() throws Exception {
        rejectUnauthenticatedFactories();
        try (var context = nativeContext()) { entered(context, () -> {
            var error = assertThrows(NativeFileException.class, () -> provider().open(directory.resolve("absent").toString(), 0));
            assertEquals(StdioHostAbi.load().error(1), (long) error.getErrno()); assertThrows(SecurityException.class, () -> provider().standard(StandardEndpoint.OUTPUT));
            assertThrows(UnsupportedOperationException.class, () -> provider().open(directory.toString(), 0));
            assertEquals(0L, nativeDescriptors(directory), "Rejected opened type must close its native fd"); return null;
        }); }
    }
    @Test void explicitStandardResourcesAreOwnedDuplicatesNotEmbeddingStreamMetadata() throws Exception {
        try (var context = nativeContext(EnumSet.allOf(StandardEndpoint.class))) { entered(context, () -> {
            var provider = provider(); var first = provider.standard(StandardEndpoint.OUTPUT); var id = identity(first); first.close(); first.close();
            try (var second = provider.standard(StandardEndpoint.OUTPUT)) {
                assertEquals(id, identity(second), "Closing an owned duplicate must not close process stdout");
                var line = "native opened-resource provider proof\n".getBytes(StandardCharsets.UTF_8); assertEquals(line.length, second.write(ByteBuffer.wrap(line)));
            }
            for (var endpoint : List.of(StandardEndpoint.INPUT, StandardEndpoint.ERROR)) {
                var original = provider.standard(endpoint); var originalIdentity = identity(original); original.close(); original.close();
                try (var alias = provider.standard(endpoint)) { assertEquals(originalIdentity, identity(alias)); }
            }
            return null;
        }); }
        var unrelated = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.ALL).out(unrelated).build()) { entered(context, () -> {
            assertThrows(SecurityException.class, () -> provider().standard(StandardEndpoint.OUTPUT));
            assertEquals(1L, Language.currentState(null).getFiles().write(1, ManagedAddress.fromByteArray(new byte[] {42}), 1, ForeignSafety.UNSAFE));
            assertArrayEquals(new byte[] {42}, unrelated.toByteArray(), "Ordinary embedding streams remain unchanged"); return null;
        }); }
    }
    @EnabledOnOs(OS.LINUX)
    @Test void contextDisposalAndRepeatedCloseCannotCloseAReusedResource() throws Exception {
        var context = nativeContext();
        record OpenPair(NativeFileProvider provider, OpenedNativeFile opened) {}
        var pair = entered(context, () -> {
            var provider = provider(); var old = provider.open(directory.resolve("old").toString(), 3); old.close();
            var current = provider.open(directory.resolve("current").toString(), 3); old.close();
            assertEquals(0L, nativeDescriptors(directory.resolve("old"))); assertEquals(1L, nativeDescriptors(directory.resolve("current")));
            assertEquals(1, current.write(ByteBuffer.wrap(new byte[] {42}))); assertEquals(1L, current.size()); return new OpenPair(provider, current);
        });
        context.close(); assertEquals(0L, nativeDescriptors(directory.resolve("current")), "Host close must release the real kernel fd after LLVM disposal");
        assertFalse(pair.opened.isOpen()); pair.opened.close(); pair.provider.close();
    }
    @EnabledOnOs(OS.LINUX)
    @Test void completedAcquisitionThenProviderFailureRetainsRollbackOwnership() throws Exception {
        var acquired = new SeekableByteChannel[1]; var failure = new IOException("after completion");
        try (var context = nativeContext()) { entered(context, () -> {
            var request = new NativeOpenRequest(null, Set.of(), (path, ignored) -> provider().open(Objects.requireNonNull(path).toString(), 3));
            assertSame(failure, assertThrows(IOException.class, () -> {
                try (request) { acquired[0] = request.acquire(directory.resolve("created"), Set.of()); throw failure; }
            }));
            assertFalse(Objects.requireNonNull(acquired[0]).isOpen(), "Completed token must retain cleanup when channel return throws");
            assertEquals(0L, nativeDescriptors(directory.resolve("created"))); return null;
        }); }
    }
    @EnabledOnOs(OS.LINUX)
    @Test void reentrantProviderDisposalAfterAcquisitionCannotPublishAClosedResource() throws Exception {
        try (var context = nativeContext()) { entered(context, () -> {
            var selected = provider(); var request = new NativeOpenRequest(null, Set.of(), (path, ignored) -> selected.open(Objects.requireNonNull(path).toString(), 3));
            try (request) {
                var acquired = request.acquire(directory.resolve("reentrant"), Set.of()); selected.close();
                assertThrows(ClosedChannelException.class, () -> request.commit(acquired)); assertFalse(acquired.isOpen());
            }
            assertEquals(0L, nativeDescriptors(directory.resolve("reentrant"))); return null;
        }); }
    }
    @EnabledOnOs(OS.LINUX)
    @Test void duplicateAndLateCompletionAreRejectedWithoutLeakingChannels() throws Exception {
        try (var context = nativeContext()) { entered(context, () -> {
            var selected = provider(); var request = new NativeOpenRequest(null, Set.of(), (path, ignored) -> selected.open(Objects.requireNonNull(path).toString(), 3));
            var acquired = request.acquire(directory.resolve("twice"), Set.of());
            assertThrows(IllegalStateException.class, () -> request.acquire(directory.resolve("ignored"), Set.of())); request.close(); assertFalse(acquired.isOpen());
            assertEquals(0L, nativeDescriptors(directory.resolve("twice"))); assertThrows(IllegalStateException.class, () -> request.acquire(directory.resolve("ignored"), Set.of()));
            var once = new NativeOpenRequest(null, Set.of(), (path, ignored) -> selected.open(Objects.requireNonNull(path).toString(), 3));
            try (var opened = once.commit(once.acquire(directory.resolve("once"), Set.of()))) {
                assertThrows(IllegalStateException.class, () -> once.acquire(directory.resolve("ignored"), Set.of())); assertEquals(0L, opened.size());
            }
            return null;
        }); }
    }
    @Test void arbitraryFilesystemCannotAcquireAThenSubstituteChannelB() throws Exception {
        var nativeFs = new NativeFileSystem(); var calls = new int[1];
        var fs = new FileSystemDelegate(nativeFs) {
            @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
                calls[0]++; nativeFs.newByteChannel(path, options, attrs);
                return Files.newByteChannel(directory.resolve("substitute"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }
        };
        try {
            try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build()) { entered(context, () -> {
                assertThrows(SecurityException.class, () -> provider().open(directory.resolve("victim").toString(), 3));
                assertEquals(0, calls[0], "Reject unauthenticated embedding before native acquisition"); assertFalse(Files.exists(directory.resolve("victim")));
                assertFalse(Files.exists(directory.resolve("substitute"))); return null;
            }); }
        } finally { nativeFs.getDirectoryOwner().close(); }
    }
    @Test void metadataCannotCrossContextsAndClosedResourcesStayClosed() throws Exception {
        try (var first = nativeContext(); var second = nativeContext()) {
            var opened = entered(first, () -> provider().open(directory.resolve("private").toString(), 3));
            entered(second, () -> { assertThrows(RuntimeFault.class, opened::statImage); return null; });
            entered(first, () -> { assertEquals(0L, opened.size()); opened.close(); assertThrows(ClosedChannelException.class, opened::statImage); return null; });
        }
    }
    @EnabledOnOs(OS.LINUX)
    @Test void managedAliasesKeepAuthoritativeIdentityAndClaimsAcrossHostMutation() throws Exception {
        var original = directory.resolve("managed"); var renamed = directory.resolve("managed-renamed"); Files.writeString(original, "abcdef");
        try (var context = nativeContext()) { entered(context, () -> {
            var files = Language.currentState(null).getFiles(); long first = files.open(path(original), 3, ForeignSafety.UNSAFE); assertTrue(first >= 3);
            long alias = files.duplicate(first); var id = identity(files, first); assertEquals(id, identity(files, alias));
            assertEquals(1L, nativeDescriptors(original), "Managed dup shares one physical resource"); Files.move(original, renamed); Files.writeString(original, "replacement");
            Files.setPosixFilePermissions(renamed, PosixFilePermissions.fromString("r--------")); assertEquals(0x100L, field(files.statImage(alias), OriginalStdioOp.ST_MODE) & 0x1ff);
            Files.setPosixFilePermissions(renamed, PosixFilePermissions.fromString("rw-------")); assertEquals(-1L, files.open(path(renamed), 1, ForeignSafety.UNSAFE));
            assertEquals(8L, files.errorKind()); assertEquals("abcdef", Files.readString(renamed), "Claim rejection must precede truncation");
            assertEquals(1L, nativeDescriptors(renamed), "Rejected writer closes only its new acquisition"); long replacement = files.open(path(original), 3, ForeignSafety.UNSAFE);
            assertTrue(replacement >= 3); assertNotEquals(id, identity(files, replacement)); assertEquals(0L, files.close(replacement, ForeignSafety.UNSAFE));
            var value = ManagedAddress.fromByteArray(new byte[] {0});
            assertEquals(1L, files.read(first, value, 1, ForeignSafety.UNSAFE)); assertEquals(97L, value.readWord8(0));
            assertEquals(1L, files.read(alias, value, 1, ForeignSafety.UNSAFE)); assertEquals(98L, value.readWord8(0)); assertEquals(0L, files.close(first, ForeignSafety.UNSAFE));
            assertThrows(IOException.class, () -> files.statImage(first)); assertEquals(1L, nativeDescriptors(renamed)); Files.delete(renamed); assertEquals(id, identity(files, alias));
            assertEquals(1L, files.write(alias, ManagedAddress.fromByteArray(new byte[] {90}), 1, ForeignSafety.UNSAFE)); assertEquals(3L, files.seek(alias, 3, 0, ForeignSafety.UNSAFE));
            assertEquals(1L, files.read(alias, value, 1, ForeignSafety.UNSAFE)); assertEquals(100L, value.readWord8(0)); assertEquals(0L, files.close(alias, ForeignSafety.UNSAFE));
            assertEquals("replacement", Files.readString(original)); return null;
        }); }
    }
    @EnabledOnOs(OS.LINUX)
    @Test void fcntlDuplicateClosesEachNativeLeaseIndependently() throws Exception {
        var file = directory.resolve("fcntl-lifetime"); Files.writeString(file, "abc");
        try (var context = nativeContext()) { entered(context, () -> {
            var stdio = Language.currentState(null).getStdio();
            long fd = stdio.open(path(file), StdioHostAbi.load().flagConstant(OriginalStdioOp.O_RDWR), 0);
            assertTrue(fd >= 3); assertEquals(70L, stdio.fcntl(fd, 0, 70, true));
            assertEquals(2L, nativeDescriptors(file));
            assertEquals(0L, stdio.close(fd));
            assertEquals(1L, nativeDescriptors(file), "Closing the source must close its native fd even while F_DUPFD survives");
            assertEquals(1L, stdio.write(70, ManagedAddress.fromByteArray(new byte[]{42}), 1));
            assertEquals(0L, stdio.close(70)); assertEquals(0L, nativeDescriptors(file)); return null;
        }); }
    }
    @EnabledOnOs(OS.LINUX)
    @Test void managedReplacementMovesCapabilityAndLastOwnerClosesExactlyOnce() throws Exception {
        var a = directory.resolve("owner-a"); var b = directory.resolve("owner-b"); var context = nativeContext(); var files = new ManagedFiles[1];
        entered(context, () -> {
            files[0] = Language.currentState(null).getFiles(); long one = files[0].open(path(a), 3, ForeignSafety.UNSAFE), two = files[0].open(path(b), 3, ForeignSafety.UNSAFE);
            long alias = files[0].duplicate(one); var oldIdentity = identity(files[0], one); var newIdentity = identity(files[0], two);
            assertNotEquals(oldIdentity, newIdentity); assertEquals(one, files[0].duplicateTo(two, one));
            assertEquals(newIdentity, identity(files[0], one)); assertEquals(oldIdentity, identity(files[0], alias));
            assertEquals(1L, nativeDescriptors(a)); assertEquals(1L, nativeDescriptors(b)); assertEquals(0L, files[0].close(alias, ForeignSafety.UNSAFE));
            assertEquals(0L, nativeDescriptors(a)); assertEquals(one, files[0].duplicateTo(two, one), "Replacing same-owner alias must not close it");
            assertEquals(0L, files[0].close(two, ForeignSafety.UNSAFE)); assertEquals(1L, nativeDescriptors(b));
            assertEquals(1L, files[0].write(one, ManagedAddress.fromByteArray(new byte[] {42}), 1, ForeignSafety.UNSAFE)); return null;
        });
        context.close(); assertEquals(0L, nativeDescriptors(b), "Context disposal closes the final alias physically"); files[0].dispose();
        assertEquals(0L, nativeDescriptors(b)); assertArrayEquals(new byte[] {42}, Files.readAllBytes(b));
    }
    @EnabledOnOs(OS.LINUX)
    @Test void explicitEndpointCapabilitiesPreserveUngrantAndNonregularBoundaries() throws Exception {
        try (var context = nativeContext(Set.of(StandardEndpoint.OUTPUT))) { entered(context, () -> {
            var state = Language.currentState(null); var files = state.getFiles(); var stdio = state.getStdio();
            for (long fd : new long[] {0, 2}) {
                assertThrows(UnsupportedOperationException.class, () -> files.statImage(fd)); assertEquals(-1L, stdio.ready(fd, 0, 0, 0)); assertEquals(StdioHostAbi.load().error(7), stdio.errno());
            }
            var image = files.statImage(1); boolean regular = PosixStat.execute(OriginalStdioOp.IS_REG, ManagedAddress.nullAddress(), field(image, OriginalStdioOp.ST_MODE)) == 1L;
            assertEquals(regular ? 0L : 1L, files.deviceType(1));
            if (regular) {
                assertEquals(1L, files.ready(1, 0, false, null)); assertEquals(0L, files.isTerminal(1, ForeignSafety.UNSAFE));
                assertEquals(-1L, files.setSize(1, field(image, OriginalStdioOp.ST_SIZE) + 1));
                assertEquals(7L, files.errorKind(), "Inherited append status is not guessed for endpoint extension");
            } else {
                // Granted native streams now have an actual poll capability.
                // Host stdout's changing readiness is not a fixture: either
                // observation is valid, but ENOTSUP is not. Exact read/write
                // transitions are checked with private FIFOs in NativeFdWaitTest.
                assertEquals(-1L, stdio.close(-1)); long error = stdio.errno();
                for (long writing : new long[] {0, 1}) { long ready = stdio.ready(1, writing, 0, 0); assertTrue(ready >= 0 && ready <= 1); assertEquals(error, stdio.errno()); }
                long terminal; try (var endpoint = provider().standard(StandardEndpoint.OUTPUT)) { terminal = endpoint.terminalStatus(); }
                assertEquals(terminal, stdio.isTerminal(1), "The granted endpoint and its duplicate use isatty");
                assertEquals(terminal == 0L ? StdioHostAbi.load().notTerminal() : error, stdio.errno());
                assertEquals(-1L, files.size(1)); assertEquals(7L, files.errorKind()); assertEquals(-1L, files.setSize(1, 0)); assertEquals(7L, files.errorKind());
                assertEquals(-1L, stdio.truncate(1, 0)); assertEquals(StdioHostAbi.load().error(5), stdio.errno());
            }
            long duplicate = files.duplicate(1); var identity = identity(files, 1); assertEquals(0L, files.close(1, ForeignSafety.UNSAFE));
            assertEquals(identity, identity(files, duplicate)); assertThrows(IOException.class, () -> files.statImage(1)); assertEquals(0L, files.close(duplicate, ForeignSafety.UNSAFE)); return null;
        }); }
        try (var context = nativeContext(EnumSet.allOf(StandardEndpoint.class))) { entered(context, () -> {
            for (long fd = 0; fd <= 2; fd++) assertTrue(Language.currentState(null).getFiles().statImage(fd).length != 0); return null;
        }); }
    }
    private long matching(Path target) throws IOException {
        long count = 0;
        try (var entries = Files.newDirectoryStream(Path.of("/proc/self/fd"))) {
            for (var entry : entries) try { if (Files.readSymbolicLink(entry).equals(target)) count++; } catch (NoSuchFileException ignored) { }
        }
        return count;
    }
    @EnabledOnOs(OS.LINUX)
    @Test void partialStandardInstallationRollsBackBeforePublishingAuthority() throws Exception {
        try (var context = nativeContext(Set.of(StandardEndpoint.OUTPUT))) { entered(context, () -> {
            var state = Language.currentState(null); var standalone = new ManagedFiles(state.getEnv(), state.getThreads());
            var target = Files.readSymbolicLink(Path.of("/proc/self/fd/1")); long before = matching(target);
            try {
                assertThrows(SecurityException.class, () -> standalone.installNative(provider(), new LinkedHashSet<>(List.of(StandardEndpoint.OUTPUT, StandardEndpoint.ERROR))));
                assertEquals(before, matching(target), "Completed OUTPUT acquisition closes when later ERROR grant fails");
                for (long fd = 0; fd <= 2; fd++) { long descriptor = fd; assertThrows(UnsupportedOperationException.class, () -> standalone.statImage(descriptor)); }
            } finally { standalone.dispose(); }
            return null;
        }); }
    }
    @EnabledOnOs(OS.LINUX)
    @Test void nativeErrorsKeepPrivateCategoriesAndExactOriginalErrno() throws Exception {
        try (var context = nativeContext(Set.of(StandardEndpoint.OUTPUT))) { entered(context, () -> {
            var state = Language.currentState(null); var files = state.getFiles(); var stdio = state.getStdio(); var abi = StdioHostAbi.load();
            assertEquals(-1L, files.open(path(directory.resolve("missing")), 0, ForeignSafety.UNSAFE)); assertEquals(1L, files.errorKind()); assertEquals(abi.error(1), files.nativeErrno());
            assertEquals(-1L, stdio.close(-1)); assertEquals(abi.error(4), stdio.errno()); assertEquals(0L, files.nativeErrno());
            // Compare against the actual same-endpoint native operation, without
            // assuming this test process's stdout is a pipe, terminal or file.
            try (var endpoint = provider().standard(StandardEndpoint.OUTPUT)) {
                NativeFileException expected = null; try { endpoint.position(); } catch (NativeFileException error) { expected = error; }
                long observed = stdio.seek(1, 0, abi.seekConstant(OriginalStdioOp.SEEK_CUR));
                if (expected != null) { assertEquals(-1L, observed); assertEquals((long) expected.getErrno(), stdio.errno()); assertEquals((long) expected.getErrno(), files.nativeErrno()); }
                else assertTrue(observed >= 0);
            }
            long sticky = stdio.errno(), alias = stdio.duplicate(1); assertTrue(alias >= 0); assertEquals(sticky, stdio.errno());
            assertEquals(0L, stdio.close(alias)); assertEquals(sticky, stdio.errno()); assertEquals(-1L, stdio.close(-1));
            assertEquals(abi.error(4), stdio.errno(), "Later managed failure must not reuse stale native errno"); return null;
        }); }
    }
}
