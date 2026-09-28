// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import thc.NativeFileSystem;
import thc.NativeIO;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.ClosedChannelException;
import java.util.*;
import java.util.concurrent.Callable;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.READ;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class NativeDirectoryFileSystemTest {
    @TempDir Path directory;
    private <T> T entered(Context context, Callable<T> action) throws Exception {
        context.initialize("thc"); context.enter(); try { return action.call(); } finally { context.leave(); }
    }
    private String read(NativeFileSystem filesystem, Path path) throws IOException {
        try (var channel = filesystem.newByteChannel(path, Set.of(READ))) {
            var bytes = ByteBuffer.allocate((int) channel.size());
            while (bytes.hasRemaining()) assertTrue(channel.read(bytes) > 0);
            return new String(bytes.array(), StandardCharsets.UTF_8);
        }
    }
    // Private observation of this test's directory descriptors, never guest IDs.
    private long descriptors(Path path) throws IOException {
        long count = 0;
        try (var entries = Files.newDirectoryStream(Path.of("/proc/self/fd"))) {
            for (var entry : entries) try { if (Files.readSymbolicLink(entry).equals(path)) count++; }
            catch (NoSuchFileException ignored) { }
        }
        return count;
    }
    @Test void envPublicAndInternalFilesShareOneContextDirectoryAndFailedChangesRollBack() throws Exception {
        var firstDir = Files.createDirectory(directory.resolve("first")); var secondDir = Files.createDirectory(directory.resolve("second"));
        Files.writeString(firstDir.resolve("value"), "first"); Files.writeString(secondDir.resolve("value"), "second");
        var processDirectory = Files.readSymbolicLink(Path.of("/proc/self/cwd"));
        try (var first = NativeIO.createContext(Set.of()); var second = NativeIO.createContext(Set.of())) {
            entered(first, () -> {
                var env = Language.currentState(null).getEnv(); env.setCurrentWorkingDirectory(env.getPublicTruffleFile(firstDir.toUri()));
                assertEquals(firstDir.toRealPath(), Path.of(env.getCurrentWorkingDirectory().toUri()));
                assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), env.getPublicTruffleFile("value").readAllBytes());
                assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), env.getInternalTruffleFile("value").readAllBytes());
                assertTrue(env.getPublicTruffleFile(".").isDirectory(NOFOLLOW_LINKS), "Normalized dot must observe the directory, not the private proc fd symlink");
                assertEquals(firstDir.toRealPath(), Path.of(env.getPublicTruffleFile(".").getCanonicalFile(NOFOLLOW_LINKS).toUri()));
                assertThrows(IllegalArgumentException.class, () -> env.setCurrentWorkingDirectory(env.getPublicTruffleFile(firstDir.resolve("missing").toUri())));
                assertThrows(IllegalArgumentException.class, () -> env.setCurrentWorkingDirectory(env.getPublicTruffleFile(".")));
                assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), env.getPublicTruffleFile("value").readAllBytes()); return null;
            });
            entered(second, () -> {
                var env = Language.currentState(null).getEnv(); env.setCurrentWorkingDirectory(env.getPublicTruffleFile(secondDir.toUri()));
                assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), env.getInternalTruffleFile("value").readAllBytes()); return null;
            });
            entered(first, () -> { assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), Language.currentState(null).getEnv().getPublicTruffleFile("value").readAllBytes()); return null; });
        }
        assertEquals(processDirectory, Files.readSymbolicLink(Path.of("/proc/self/cwd")));
    }
    @Test void builderInitializationIsIdempotentAndDirectSetterFailureKeepsTheOpenedDirectory() throws Exception {
        var fs = new NativeFileSystem();
        try {
            var initial = Files.createDirectory(directory.resolve("initial")); Files.writeString(initial.resolve("value"), "kept");
            try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.newBuilder().fileSystem(fs).build()).currentWorkingDirectory(initial).build()) {
                entered(context, () -> { assertArrayEquals("kept".getBytes(StandardCharsets.UTF_8), Language.currentState(null).getEnv().getPublicTruffleFile("value").readAllBytes()); return null; });
                fs.setCurrentWorkingDirectory(initial);
                assertThrows(IOException.class, () -> fs.setCurrentWorkingDirectory(initial.resolve("absent")));
                assertThrows(IOException.class, () -> fs.setCurrentWorkingDirectory(initial.resolve("value")));
                assertEquals(initial.toRealPath(), fs.toAbsolutePath(Path.of(""))); assertEquals("kept", read(fs, Path.of("value")));
            }
        } finally { fs.getDirectoryOwner().close(); }
    }
    @Test void relativeOperationsKeepDirectoryIdentityAcrossRenameAndReplacement() throws Exception {
        var fs = new NativeFileSystem();
        try {
            var original = Files.createDirectory(directory.resolve("original")); var moved = directory.resolve("moved");
            Files.writeString(original.resolve("value"), "opened"); fs.setCurrentWorkingDirectory(original);
            Files.move(original, moved); Files.createDirectory(original); Files.writeString(original.resolve("value"), "replacement");
            assertEquals("opened", read(fs, Path.of("value"))); fs.checkAccess(Path.of("value"), Set.of(AccessMode.READ));
            assertEquals(6L, fs.readAttributes(Path.of("value"), "basic:size").get("size"));
            fs.copy(Path.of("value"), Path.of("copy")); fs.move(Path.of("copy"), Path.of("renamed"), ATOMIC_MOVE);
            assertEquals("opened", Files.readString(moved.resolve("renamed"))); assertFalse(Files.exists(original.resolve("renamed")));
            Files.createLink(moved.resolve("alias"), moved.resolve("value")); assertTrue(fs.isSameFile(Path.of("value"), Path.of("alias")));
            fs.createDirectory(Path.of("child")); assertTrue(Files.isDirectory(moved.resolve("child")));
            fs.delete(Path.of("renamed")); fs.delete(Path.of("child")); assertFalse(Files.exists(moved.resolve("renamed")));
            assertEquals(moved.toRealPath(), fs.toAbsolutePath(Path.of(""))); assertEquals(moved.resolve("value"), fs.toRealPath(Path.of("value"), NOFOLLOW_LINKS));
            assertEquals("replacement", read(fs, original.resolve("value")), "Absolute paths retain their own root");
        } finally { fs.getDirectoryOwner().close(); }
    }
    @Test void deletedDirectoryStillSupportsRelativeMetadataAndStreamsWhileNamingFails() throws Exception {
        var fs = new NativeFileSystem();
        try {
            var removed = Files.createDirectory(directory.resolve("removed")); fs.setCurrentWorkingDirectory(removed); Files.delete(removed);
            assertEquals(true, fs.readAttributes(Path.of("."), "basic:isDirectory").get("isDirectory"));
            try (var entries = fs.newDirectoryStream(Path.of("."), ignored -> true)) { assertFalse(entries.iterator().hasNext()); }
            assertThrows(IOException.class, () -> fs.toAbsolutePath(Path.of("")));
            assertThrows(IOException.class, () -> fs.toRealPath(Path.of("."), NOFOLLOW_LINKS));
            fs.setCurrentWorkingDirectory(directory); assertEquals(directory.toRealPath(), fs.toAbsolutePath(Path.of("")));
        } finally { fs.getDirectoryOwner().close(); }
    }
    @Test void directoryStreamsRetainTheSnapshotThroughOwnerChangeAndCloseAndRemapEntries() throws Exception {
        var original = Files.createDirectory(directory.resolve("stream")); var moved = directory.resolve("stream-moved");
        Files.writeString(original.resolve("a"), "a"); Files.writeString(original.resolve("b"), "b");
        var fs = new NativeFileSystem(); DirectoryStream<Path> stream = null;
        try {
            fs.setCurrentWorkingDirectory(original); long baseline = descriptors(original);
            assertThrows(IOException.class, () -> fs.newDirectoryStream(Path.of("missing"), ignored -> true));
            assertEquals(baseline, descriptors(original), "Failed stream acquisition releases its borrowed fd");
            var filtered = new ArrayList<Path>();
            var opened = fs.newDirectoryStream(Path.of("."), entry -> {
                filtered.add(entry); assertFalse(entry.isAbsolute()); assertEquals(Path.of("."), entry.getParent()); return true;
            });
            stream = opened; Files.move(original, moved); Files.createDirectory(original); Files.writeString(original.resolve("replacement"), "x");
            fs.setCurrentWorkingDirectory(directory); fs.getDirectoryOwner().close();
            var entries = new HashSet<Path>(); for (var entry : opened) entries.add(entry);
            assertEquals(Set.of(Path.of("./a"), Path.of("./b")), entries); assertEquals(Set.of(Path.of("./a"), Path.of("./b")), new HashSet<>(filtered));
            opened.close(); opened.close(); assertEquals(0L, descriptors(moved), "Stream close releases both stream and directory snapshot");
            assertThrows(IOException.class, () -> fs.readAttributes(Path.of("value"), "basic:size"));
        } finally { if (stream != null) stream.close(); fs.getDirectoryOwner().close(); }
    }
    @Test void rawPathBytesSurviveResolutionAndDirectoryEntryMapping() throws Exception {
        var fs = new NativeFileSystem();
        try {
            var raw = Path.of(URI.create(directory.toUri().toASCIIString() + "byte-%FF")); Files.writeString(raw, "raw");
            var relative = directory.relativize(raw); fs.setCurrentWorkingDirectory(directory);
            assertEquals("raw", read(fs, relative)); assertEquals(raw, fs.toAbsolutePath(relative)); assertEquals(raw, fs.toRealPath(relative, NOFOLLOW_LINKS));
            try (var entries = fs.newDirectoryStream(Path.of(""), ignored -> true)) {
                var actual = new HashSet<Path>(); for (var entry : entries) actual.add(entry); assertEquals(Set.of(relative), actual);
            }
        } finally { fs.getDirectoryOwner().close(); }
    }
    @Test void borrowedDirectoryOutlivesReplacementAndDisposalWithoutClosingReusedResources() throws Exception {
        var first = Files.createDirectory(directory.resolve("borrow-first")); var second = Files.createDirectory(directory.resolve("borrow-second"));
        Files.writeString(first.resolve("value"), "first"); Files.writeString(second.resolve("value"), "second");
        var owner = new NativeDirectoryOwner(first); var borrowed = owner.borrow();
        try {
            owner.change(second); owner.close(); assertThrows(ClosedChannelException.class, owner::borrow);
            assertEquals("first", Files.readString(borrowed.resolve(Path.of("value")))); borrowed.close();
            assertThrows(ClosedChannelException.class, () -> borrowed.resolve(Path.of("value")));
            try (var fresh = Files.newByteChannel(second.resolve("value"), READ)) {
                borrowed.close(); owner.close(); var bytes = ByteBuffer.allocate(6);
                assertEquals(6, fresh.read(bytes)); assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), bytes.array());
            }
        } finally { borrowed.close(); owner.close(); }
        assertEquals(0L, descriptors(first)); assertEquals(0L, descriptors(second));
    }
    @Test void contextDisposalClosesItsDirectoryOwner() throws Exception {
        var selected = Files.createDirectory(directory.resolve("disposed")); Files.writeString(selected.resolve("value"), "value");
        var context = NativeIO.createContext(Set.of());
        try { entered(context, () -> {
            var env = Language.currentState(null).getEnv(); env.setCurrentWorkingDirectory(env.getPublicTruffleFile(selected.toUri()));
            assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), env.getPublicTruffleFile("value").readAllBytes()); return null;
        }); } finally { context.close(); }
        assertEquals(0L, descriptors(selected));
    }
}
