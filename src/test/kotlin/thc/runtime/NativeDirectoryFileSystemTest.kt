// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.Language
import thc.NativeFileSystem
import thc.NativeIO
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.AccessMode
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class NativeDirectoryFileSystemTest {
    @TempDir lateinit var directory: Path

    private fun <T> entered(context: Context, action: () -> T): T {
        context.initialize("thc"); context.enter()
        return try { action() } finally { context.leave() }
    }

    private fun <T> filesystem(action: (NativeFileSystem) -> T): T {
        val filesystem = NativeFileSystem()
        return try { action(filesystem) } finally { filesystem.directoryOwner.close() }
    }

    private fun read(filesystem: NativeFileSystem, path: Path): String =
        filesystem.newByteChannel(path, setOf(READ)).use { channel ->
            val bytes = ByteBuffer.allocate(channel.size().toInt())
            while (bytes.hasRemaining()) assertTrue(channel.read(bytes) > 0)
            String(bytes.array(), Charsets.UTF_8)
        }

    // Private observation of this test's directory descriptors, never guest IDs.
    private fun descriptors(path: Path): Long = Files.list(Path.of("/proc/self/fd")).use { entries ->
        entries.filter { entry ->
            try { Files.readSymbolicLink(entry) == path }
            catch (_: java.nio.file.NoSuchFileException) { false }
        }.count()
    }

    @Test fun envPublicAndInternalFilesShareOneContextDirectoryAndFailedChangesRollBack() {
        val firstDir = Files.createDirectory(directory.resolve("first"))
        val secondDir = Files.createDirectory(directory.resolve("second"))
        Files.writeString(firstDir.resolve("value"), "first")
        Files.writeString(secondDir.resolve("value"), "second")
        val processDirectory = Files.readSymbolicLink(Path.of("/proc/self/cwd"))
        NativeIO.createContext().use { first -> NativeIO.createContext().use { second ->
            entered(first) {
                val env = Language.currentState().env
                env.setCurrentWorkingDirectory(env.getPublicTruffleFile(firstDir.toUri()))
                assertEquals(firstDir.toRealPath(), Path.of(env.currentWorkingDirectory.toUri()))
                assertArrayEquals("first".toByteArray(), env.getPublicTruffleFile("value").readAllBytes())
                assertArrayEquals("first".toByteArray(), env.getInternalTruffleFile("value").readAllBytes())
                assertTrue(env.getPublicTruffleFile(".").isDirectory(NOFOLLOW_LINKS),
                    "Normalized dot must observe the directory, not the private proc fd symlink")
                assertEquals(firstDir.toRealPath(), Path.of(env.getPublicTruffleFile(".").getCanonicalFile(NOFOLLOW_LINKS).toUri()))
                assertThrows(IllegalArgumentException::class.java) {
                    env.setCurrentWorkingDirectory(env.getPublicTruffleFile(firstDir.resolve("missing").toUri()))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    env.setCurrentWorkingDirectory(env.getPublicTruffleFile("."))
                }
                assertArrayEquals("first".toByteArray(), env.getPublicTruffleFile("value").readAllBytes())
            }
            entered(second) {
                val env = Language.currentState().env
                env.setCurrentWorkingDirectory(env.getPublicTruffleFile(secondDir.toUri()))
                assertArrayEquals("second".toByteArray(), env.getInternalTruffleFile("value").readAllBytes())
            }
            entered(first) {
                assertArrayEquals("first".toByteArray(), Language.currentState().env.getPublicTruffleFile("value").readAllBytes())
            }
        } }
        assertEquals(processDirectory, Files.readSymbolicLink(Path.of("/proc/self/cwd")))
    }

    @Test fun builderInitializationIsIdempotentAndDirectSetterFailureKeepsTheOpenedDirectory() = filesystem { fs ->
        val initial = Files.createDirectory(directory.resolve("initial"))
        Files.writeString(initial.resolve("value"), "kept")
        Context.newBuilder("thc").allowNativeAccess(true)
            .allowIO(IOAccess.newBuilder().fileSystem(fs).build()).currentWorkingDirectory(initial).build().use { context ->
                entered(context) {
                    assertArrayEquals("kept".toByteArray(), Language.currentState().env.getPublicTruffleFile("value").readAllBytes())
                }
                fs.setCurrentWorkingDirectory(initial)
                assertThrows(IOException::class.java) { fs.setCurrentWorkingDirectory(initial.resolve("absent")) }
                assertThrows(IOException::class.java) { fs.setCurrentWorkingDirectory(initial.resolve("value")) }
                assertEquals(initial.toRealPath(), fs.toAbsolutePath(Path.of("")))
                assertEquals("kept", read(fs, Path.of("value")))
            }
    }

    @Test fun relativeOperationsKeepDirectoryIdentityAcrossRenameAndReplacement() = filesystem { fs ->
        val original = Files.createDirectory(directory.resolve("original"))
        val moved = directory.resolve("moved")
        Files.writeString(original.resolve("value"), "opened")
        fs.setCurrentWorkingDirectory(original)
        Files.move(original, moved)
        Files.createDirectory(original)
        Files.writeString(original.resolve("value"), "replacement")
        assertEquals("opened", read(fs, Path.of("value")))
        fs.checkAccess(Path.of("value"), setOf(AccessMode.READ))
        assertEquals(6L, fs.readAttributes(Path.of("value"), "basic:size")["size"])
        fs.copy(Path.of("value"), Path.of("copy"))
        fs.move(Path.of("copy"), Path.of("renamed"), ATOMIC_MOVE)
        assertEquals("opened", Files.readString(moved.resolve("renamed")))
        assertFalse(Files.exists(original.resolve("renamed")))
        Files.createLink(moved.resolve("alias"), moved.resolve("value"))
        assertTrue(fs.isSameFile(Path.of("value"), Path.of("alias")))
        fs.createDirectory(Path.of("child"))
        assertTrue(Files.isDirectory(moved.resolve("child")))
        fs.delete(Path.of("renamed")); fs.delete(Path.of("child"))
        assertFalse(Files.exists(moved.resolve("renamed")))
        assertEquals(moved.toRealPath(), fs.toAbsolutePath(Path.of("")))
        assertEquals(moved.resolve("value"), fs.toRealPath(Path.of("value"), NOFOLLOW_LINKS))
        assertEquals("replacement", read(fs, original.resolve("value")), "Absolute paths retain their own root")
    }

    @Test fun deletedDirectoryStillSupportsRelativeMetadataAndStreamsWhileNamingFails() = filesystem { fs ->
        val removed = Files.createDirectory(directory.resolve("removed"))
        fs.setCurrentWorkingDirectory(removed)
        Files.delete(removed)
        assertEquals(true, fs.readAttributes(Path.of("."), "basic:isDirectory")["isDirectory"])
        fs.newDirectoryStream(Path.of(".")) { true }.use { assertFalse(it.iterator().hasNext()) }
        assertThrows(IOException::class.java) { fs.toAbsolutePath(Path.of("")) }
        assertThrows(IOException::class.java) { fs.toRealPath(Path.of("."), NOFOLLOW_LINKS) }
        fs.setCurrentWorkingDirectory(directory)
        assertEquals(directory.toRealPath(), fs.toAbsolutePath(Path.of("")))
    }

    @Test fun directoryStreamsRetainTheSnapshotThroughOwnerChangeAndCloseAndRemapEntries() {
        val original = Files.createDirectory(directory.resolve("stream"))
        val moved = directory.resolve("stream-moved")
        Files.writeString(original.resolve("a"), "a")
        Files.writeString(original.resolve("b"), "b")
        val fs = NativeFileSystem()
        var stream: DirectoryStream<Path>? = null
        try {
            fs.setCurrentWorkingDirectory(original)
            val baseline = descriptors(original)
            assertThrows(IOException::class.java) { fs.newDirectoryStream(Path.of("missing")) { true } }
            assertEquals(baseline, descriptors(original), "Failed stream acquisition releases its borrowed fd")
            val filtered = mutableListOf<Path>()
            val opened = fs.newDirectoryStream(Path.of(".")) { entry ->
                filtered.add(entry)
                assertFalse(entry.isAbsolute)
                assertEquals(Path.of("."), entry.parent)
                true
            }
            stream = opened
            Files.move(original, moved)
            Files.createDirectory(original)
            Files.writeString(original.resolve("replacement"), "x")
            fs.setCurrentWorkingDirectory(directory)
            fs.directoryOwner.close()
            assertEquals(setOf(Path.of("./a"), Path.of("./b")), opened.toSet())
            assertEquals(setOf(Path.of("./a"), Path.of("./b")), filtered.toSet())
            opened.close(); opened.close()
            assertEquals(0L, descriptors(moved), "Stream close releases both stream and directory snapshot")
            assertThrows(IOException::class.java) { fs.readAttributes(Path.of("value"), "basic:size") }
        } finally {
            stream?.close(); fs.directoryOwner.close()
        }
    }

    @Test fun rawPathBytesSurviveResolutionAndDirectoryEntryMapping() = filesystem { fs ->
        val raw = Path.of(URI.create(directory.toUri().toASCIIString() + "byte-%FF"))
        Files.writeString(raw, "raw")
        val relative = directory.relativize(raw)
        fs.setCurrentWorkingDirectory(directory)
        assertEquals("raw", read(fs, relative))
        assertEquals(raw, fs.toAbsolutePath(relative))
        assertEquals(raw, fs.toRealPath(relative, NOFOLLOW_LINKS))
        fs.newDirectoryStream(Path.of("")) { true }.use { entries ->
            assertEquals(setOf(relative), entries.toSet())
        }
    }

    @Test fun borrowedDirectoryOutlivesReplacementAndDisposalWithoutClosingReusedResources() {
        val first = Files.createDirectory(directory.resolve("borrow-first"))
        val second = Files.createDirectory(directory.resolve("borrow-second"))
        Files.writeString(first.resolve("value"), "first")
        Files.writeString(second.resolve("value"), "second")
        val owner = NativeDirectoryOwner(first)
        val borrowed = owner.borrow()
        try {
            owner.change(second)
            owner.close()
            assertThrows(java.nio.channels.ClosedChannelException::class.java) { owner.borrow() }
            assertEquals("first", Files.readString(borrowed.resolve(Path.of("value"))))
            borrowed.close()
            assertThrows(java.nio.channels.ClosedChannelException::class.java) { borrowed.resolve(Path.of("value")) }
            Files.newByteChannel(second.resolve("value"), READ).use { fresh ->
                borrowed.close(); owner.close()
                val bytes = ByteBuffer.allocate(6)
                assertEquals(6, fresh.read(bytes))
                assertArrayEquals("second".toByteArray(), bytes.array())
            }
        } finally { borrowed.close(); owner.close() }
        assertEquals(0L, descriptors(first))
        assertEquals(0L, descriptors(second))
    }

    @Test fun contextDisposalClosesItsDirectoryOwner() {
        val selected = Files.createDirectory(directory.resolve("disposed"))
        Files.writeString(selected.resolve("value"), "value")
        val context = NativeIO.createContext()
        try {
            entered(context) {
                val env = Language.currentState().env
                env.setCurrentWorkingDirectory(env.getPublicTruffleFile(selected.toUri()))
                assertArrayEquals("value".toByteArray(), env.getPublicTruffleFile("value").readAllBytes())
            }
        } finally { context.close() }
        assertEquals(0L, descriptors(selected))
    }
}
