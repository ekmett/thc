// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.nodes.Node
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.FileSystem
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.Language
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Managed service contract tests, not a claim of ordinary GHC Handle execution. */
class ManagedFilesTest {
    @TempDir lateinit var directory: Path
    private fun text(value: String) = ManagedAddress.fromByteArray(value.toByteArray(Charsets.UTF_8) + byteArrayOf(0))
    private fun bytes(value: String) = ManagedAddress.fromByteArray(value.toByteArray(Charsets.UTF_8))
    private fun builder() = Context.newBuilder("thc").allowIO(IOAccess.ALL)
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
    private fun <T> entered(context: Context, action: (ManagedFiles) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(Language.currentState().files) } finally { context.leave() }
    }

    private open class TrackingFileSystem(private val delegate: FileSystem = FileSystem.newDefaultFileSystem()) : FileSystem by delegate {
        val channels = mutableListOf<SeekableByteChannel>()
        override fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): SeekableByteChannel =
            delegate.newByteChannel(path, options, *attributes).also { channels.add(it) }
    }

    @Test fun standardStreamsAreContextOwnedAndClosingThemDoesNotCloseEmbeddingStreams() {
        class Input : ByteArrayInputStream(byteArrayOf(0, 127, -1)) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        class Output : ByteArrayOutputStream() {
            var closed = false
            var flushes = 0
            override fun close() { closed = true; super.close() }
            override fun flush() { flushes++; super.flush() }
        }
        val input = Input(); val output = Output(); val errors = Output()
        builder().`in`(input).out(output).err(errors).build().use { context -> entered(context) { files ->
            val target = ByteArray(6) { 42 }
            assertEquals(2L, files.read(0, ManagedAddress.fromByteArray(target).plus(1), 2))
            assertArrayEquals(byteArrayOf(42, 0, 127, 42, 42, 42), target)
            assertEquals(1L, files.read(0, ManagedAddress.fromByteArray(target).plus(3), 3))
            assertEquals(0L, files.read(0, ManagedAddress.fromByteArray(target), 1))
            assertEquals(2L, files.write(1, ManagedAddress.fromByteArray(target).plus(2), 2))
            assertEquals(3L, files.write(2, bytes("err"), 3))
            assertArrayEquals(byteArrayOf(127, -1), output.toByteArray())
            assertEquals("err", errors.toString(Charsets.UTF_8))
            assertEquals(1L, files.deviceType(0)); assertEquals(0L, files.isTerminal(1))
            val outputFlushes = output.flushes; val errorFlushes = errors.flushes
            for (fd in 0L..2L) assertEquals(0L, files.close(fd))
            assertEquals(outputFlushes + 1, output.flushes); assertEquals(errorFlushes + 1, errors.flushes)
            assertEquals(-1L, files.write(1, bytes("bad"), 3))
            assertEquals(4L, files.errorKind())
        } }
        assertFalse(input.closed); assertFalse(output.closed); assertFalse(errors.closed)
        // Polyglot itself may flush the embedding streams again on Context.close.
    }

    @Test fun embeddingStreamCallDefersDeliveryUntilAnExplicitGuestCallback() {
        val node = object : Node() {}
        lateinit var threads: GuestThreads
        var id = -1L
        lateinit var request: AsyncRequest
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                assertNull(threads.poll(node), "Embedding stream code is foreign execution")
                assertEquals(id, threads.enterCurrent())
                try {
                    assertSame(request, threads.poll(node), "Reentrant guest entry has its own cut")
                    request.acknowledge()
                } finally { threads.leaveCurrent() }
                assertNull(threads.poll(node), "Stream execution resumes as foreign")
                super.write(bytes, offset, length)
            }
        }
        builder().out(output).build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val state = Language.currentState()
                threads = state.threads
                id = threads.enterCurrent()
                try {
                    request = threads.send(id, "stream callback")
                    assertEquals(3L, state.files.write(1, bytes("abc"), 3))
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
                    assertEquals("abc", output.toString(Charsets.UTF_8))
                    val after = threads.send(id, "after stream")
                    assertSame(after, threads.poll(node))
                    after.acknowledge()
                } finally { threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun teardownFlushRetainsForeignOriginForAnotherContextCallback() {
        val node = object : Node() {}
        val other = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        val armed = AtomicBoolean()
        val observed = AtomicReference<ForeignCallbackAsyncFailure>()
        val output = object : ByteArrayOutputStream() {
            override fun flush() {
                if (armed.compareAndSet(true, false)) {
                    val id = other.enterCurrent()
                    try {
                        val request = other.send(id, "teardown callback")
                        val failure = assertThrows(ForeignCallbackAsyncFailure::class.java) {
                            AsyncContinuations.uncaught(other.poll(node)!!, node)
                        }
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
                        observed.set(failure)
                    } finally { other.leaveCurrent() }
                }
                super.flush()
            }
        }
        val context = builder().out(output).build()
        context.initialize("thc")
        armed.set(true)
        context.close()
        assertEquals("teardown callback", observed.get().payload)
    }

    @Test fun realFileRoundTripSupportsOffsetsUnicodeNamesEofAndNonReusedDescriptors() {
        val path = directory.resolve("café-λ.bin")
        builder().build().use { context -> entered(context) { files ->
            val fd = files.open(text(path.toString()), 1)
            assertTrue(fd >= 3)
            val data = byteArrayOf(99, 0, 127, -128, -1, 98)
            assertEquals(4L, files.write(fd, ManagedAddress.fromByteArray(data).plus(1), 4))
            assertEquals(4L, files.size(fd)); assertEquals(0L, files.deviceType(fd))
            assertEquals(0L, files.close(fd))
            assertArrayEquals(data.copyOfRange(1, 5), Files.readAllBytes(path))
            val reader = files.open(text(path.toString()), 0)
            assertTrue(reader > fd)
            val target = ByteArray(8) { 33 }
            assertEquals(4L, files.read(reader, ManagedAddress.fromByteArray(target).plus(2), 6))
            assertArrayEquals(byteArrayOf(33, 33, 0, 127, -128, -1, 33, 33), target)
            assertEquals(0L, files.read(reader, ManagedAddress.fromByteArray(target), 8))
            assertEquals(-1L, files.read(fd, ManagedAddress.fromByteArray(target), 1))
            assertEquals(4L, files.errorKind())
        } }
    }

    @Test fun incompatibleOpenDoesNotTruncateAndReadClaimsPermitOtherReaders() {
        val path = directory.resolve("claimed.bin"); Files.writeString(path, "keep")
        builder().build().use { context -> entered(context) { files ->
            val one = files.open(text(path.toString()), 0)
            val two = files.open(text(path.toString()), 0)
            assertTrue(one >= 3); assertTrue(two > one)
            assertEquals(-1L, files.open(text(path.toString()), 1))
            assertEquals(8L, files.errorKind()); assertEquals("keep", Files.readString(path))
            files.close(one); files.close(two)
            val writer = files.open(text(path.toString()), 1)
            assertTrue(writer >= 3); assertEquals(0L, files.size(writer))
            assertEquals(-1L, files.open(text(path.toString()), 0))
            assertEquals(8L, files.errorKind())
            files.close(writer)
            assertTrue(files.open(text(path.toString()), 0) >= 3)
        } }
    }

    @Test fun aliasesShareTheReaderWriterClaim() {
        val path = directory.resolve("original.bin"); Files.writeString(path, "unchanged")
        val hard = directory.resolve("hard.bin"); Files.createLink(hard, path)
        val symbolic = directory.resolve("symbolic.bin"); Files.createSymbolicLink(symbolic, path.fileName)
        builder().build().use { context -> entered(context) { files ->
            val reader = files.open(text(path.toString()), 0)
            for (alias in listOf(hard, symbolic)) {
                assertEquals(-1L, files.open(text(alias.toString()), 1))
                assertEquals(8L, files.errorKind()); assertEquals("unchanged", Files.readString(path))
            }
            files.close(reader)
            assertTrue(files.open(text(hard.toString()), 1) >= 3)
            assertEquals("", Files.readString(path))
        } }
    }

    @Test fun readerClaimFollowsFileIdentityAcrossRenameAndReplacement() {
        val original = directory.resolve("original.bin"); Files.writeString(original, "keep")
        val renamed = directory.resolve("renamed.bin")
        builder().build().use { context -> entered(context) { files ->
            val reader = files.open(text(original.toString()), 0)
            assertTrue(reader >= 3)
            Files.move(original, renamed)
            Files.writeString(original, "replacement")
            assertEquals(-1L, files.open(text(renamed.toString()), 1))
            assertEquals(8L, files.errorKind()); assertEquals("keep", Files.readString(renamed))
            val replacement = files.open(text(original.toString()), 1)
            assertTrue(replacement >= 3); assertEquals("", Files.readString(original))
            val content = ByteArray(4)
            assertEquals(4L, files.read(reader, ManagedAddress.fromByteArray(content), 4))
            assertEquals("keep", String(content))
            files.close(reader)
            assertTrue(files.open(text(renamed.toString()), 1) >= 3)
        } }
    }

    @Test fun unlinkingAnOpenFileDoesNotPreventUnrelatedOpens() {
        val original = directory.resolve("unlinked.bin"); Files.writeString(original, "keep")
        val unrelated = directory.resolve("unrelated.bin"); Files.writeString(unrelated, "replace")
        builder().build().use { context -> entered(context) { files ->
            val reader = files.open(text(original.toString()), 0)
            assertTrue(reader >= 3)
            Files.delete(original)
            assertTrue(files.open(text(unrelated.toString()), 1) >= 3)
            val content = ByteArray(4)
            assertEquals(4L, files.read(reader, ManagedAddress.fromByteArray(content), 4))
            assertEquals("keep", String(content))
        } }
    }

    @Test fun unavailableStableIdentityFailsBeforeOpeningOrTruncatingExistingFile() {
        val path = directory.resolve("no-identity.bin"); Files.writeString(path, "keep")
        val backing = FileSystem.newDefaultFileSystem()
        val fs = object : TrackingFileSystem(backing) {
            override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any> =
                backing.readAttributes(path, attributes, *options).toMutableMap().also { it.remove("dev") }
        }
        builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build().use { context -> entered(context) { files ->
            assertEquals(-1L, files.open(text(path.toString()), 1))
            assertEquals(7L, files.errorKind()); assertEquals("keep", Files.readString(path))
            assertTrue(fs.channels.isEmpty())
        } }
    }

    @Test fun detectedIdentityChangeClosesTheAcquiredChannelWithoutTruncation() {
        val path = directory.resolve("changing.bin"); Files.writeString(path, "original")
        val moved = directory.resolve("moved.bin")
        val fs = object : TrackingFileSystem() {
            override fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): SeekableByteChannel {
                val channel = super.newByteChannel(path, options, *attributes)
                Files.move(path, moved)
                Files.writeString(path, "replacement")
                return channel
            }
        }
        builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build().use { context -> entered(context) { files ->
            assertEquals(-1L, files.open(text(path.toString()), 1))
            assertEquals(8L, files.errorKind())
            assertEquals("original", Files.readString(moved)); assertEquals("replacement", Files.readString(path))
            assertEquals(1, fs.channels.size); assertFalse(fs.channels.single().isOpen)
        } }
    }

    @Test fun seekReadWriteAndResizePreservePositionAndValidateOffsets() {
        val path = directory.resolve("update.bin")
        builder().build().use { context -> entered(context) { files ->
            val fd = files.open(text(path.toString()), 3)
            assertEquals(6L, files.write(fd, bytes("abcdef"), 6))
            assertEquals(2L, files.seek(fd, 2, 0))
            val read = ByteArray(2)
            assertEquals(2L, files.read(fd, ManagedAddress.fromByteArray(read), 2)); assertEquals("cd", String(read))
            assertEquals(1L, files.seek(fd, -3, 1))
            assertEquals(1L, files.write(fd, bytes("X"), 1))
            assertEquals("aXcdef", Files.readString(path))
            assertEquals(5L, files.seek(fd, -1, 2))
            assertEquals(0L, files.setSize(fd, 3))
            assertEquals(5L, files.seek(fd, 0, 1)); assertEquals(3L, files.size(fd))
            assertEquals(0L, files.setSize(fd, 7))
            assertEquals(5L, files.seek(fd, 0, 1))
            assertArrayEquals(byteArrayOf(97, 88, 99, 0, 0, 0, 0), Files.readAllBytes(path))
            assertEquals(-1L, files.seek(fd, Long.MAX_VALUE, 2)); assertEquals(5L, files.errorKind())
            assertEquals(-1L, files.seek(fd, -1, 0)); assertEquals(5L, files.errorKind())
            assertEquals(-1L, files.seek(fd, 0, 3)); assertEquals(5L, files.errorKind())
            assertEquals(5L, files.seek(fd, 0, 1))
            assertEquals(-1L, files.setSize(fd, -1)); assertEquals(7L, files.size(fd))
        } }
    }

    @Test fun appendWritesAtEndEvenAfterSeek() {
        val path = directory.resolve("append.bin"); Files.writeString(path, "abc")
        builder().build().use { context -> entered(context) { files ->
            val fd = files.open(text(path.toString()), 2)
            assertEquals(0L, files.seek(fd, 0, 0))
            assertEquals(2L, files.write(fd, bytes("de"), 2))
            assertEquals("abcde", Files.readString(path))
            assertEquals(-1L, files.setSize(fd, 8)); assertEquals(7L, files.errorKind())
            assertEquals(5L, files.size(fd))
        } }
    }

    @Test fun expectedFailuresBecomePortableErrorsAndDoNotClearOnSuccess() {
        builder().build().use { context -> entered(context) { files ->
            assertEquals(-1L, files.open(text(directory.resolve("absent").toString()), 0))
            assertEquals(1L, files.errorKind())
            val message = files.errorMessage(); assertTrue(message.utf8().contains("absent"))
            assertEquals(1L, files.deviceType(1)); assertEquals(1L, files.errorKind())
            assertEquals(-1L, files.open(text(directory.toString()), 0)); assertEquals(9L, files.errorKind())
            assertEquals(-1L, files.open(text(""), 0)); assertEquals(1L, files.errorKind())
            assertEquals(-1L, files.open(text(directory.resolve("new").toString()), 8)); assertEquals(5L, files.errorKind())
            assertFalse(Files.exists(directory.resolve("new")))
            assertEquals(-1L, files.seek(1, 0, 0)); assertEquals(7L, files.errorKind())
            assertEquals(-1L, files.size(1)); assertEquals(7L, files.errorKind())
            assertEquals(-1L, files.close(-1)); assertEquals(4L, files.errorKind())
            assertTrue(message.utf8().contains("absent")) // Detached from later failures.
        } }
    }

    @Test fun embeddingFilePolicyIsHonoredWithoutNativeAccess() {
        val path = directory.resolve("denied.bin")
        Context.newBuilder("thc").allowIO(IOAccess.NONE).build().use { context -> entered(context) { files ->
            assertEquals(-1L, files.open(text(path.toString()), 1))
            assertEquals(2L, files.errorKind())
            assertFalse(Files.exists(path))
        } }
    }

    @Test fun invalidManagedMemoryCannotConsumeInputOrWriteAnything() {
        val input = ByteArrayInputStream(byteArrayOf(7, 8)); val output = ByteArrayOutputStream()
        builder().`in`(input).out(output).build().use { context -> entered(context) { files ->
            val data = byteArrayOf(42)
            for (length in listOf(-1L, 2L, Long.MAX_VALUE)) {
                assertThrows(RuntimeFault::class.java) { files.read(0, ManagedAddress.fromByteArray(data), length) }
                assertThrows(RuntimeFault::class.java) { files.write(1, ManagedAddress.fromByteArray(data), length) }
            }
            assertThrows(RuntimeFault::class.java) { files.read(0, ManagedAddress.fromHex("00"), 1) }
            assertEquals(2, input.available()); assertEquals(0, output.size()); assertArrayEquals(byteArrayOf(42), data)
            val onePast = ManagedAddress.fromByteArray(data).plus(1)
            assertEquals(0L, files.read(0, onePast, 0)); assertEquals(0L, files.write(1, onePast, 0))
            assertEquals(-1L, files.read(1, onePast, 0)); assertEquals(4L, files.errorKind())
            assertThrows(RuntimeFault::class.java) { files.open(ManagedAddress.fromByteArray(byteArrayOf(-1, 0)), 1) }
            assertThrows(RuntimeFault::class.java) { files.open(bytes("unterminated"), 1) }
        } }
    }

    @Test fun contextDisposalReleasesOwnedFilesAndContextStateIsIsolated() {
        val path = directory.resolve("dispose.bin")
        var retained: ManagedFiles? = null; var fd = -1L
        builder().build().use { context -> entered(context) { files ->
            retained = files; fd = files.open(text(path.toString()), 1)
            assertTrue(fd >= 3); assertEquals(-1L, files.close(99)); assertEquals(4L, files.errorKind())
        } }
        assertEquals(-1L, retained!!.size(fd)); assertEquals(4L, retained!!.errorKind())
        builder().build().use { context -> entered(context) { files ->
            assertEquals(0L, files.errorKind()); assertEquals(1L, files.deviceType(1))
            assertTrue(files.open(text(path.toString()), 1) >= 3)
        } }
    }

    @Test fun disposalClosesOwnedChannelsAfterCheckedAndUncheckedEmbeddingFailures() {
        for (first in listOf(IOException("checked flush"), IllegalStateException("unchecked flush"))) {
            val second = IllegalArgumentException("second flush")
            var failing = false
            val output = object : ByteArrayOutputStream() {
                override fun flush() { if (failing) throw first }
            }
            val errors = object : ByteArrayOutputStream() {
                override fun flush() { if (failing) throw second }
            }
            val fs = TrackingFileSystem()
            builder().allowIO(IOAccess.newBuilder().fileSystem(fs).build()).out(output).err(errors).build().use { context ->
                entered(context) { files ->
                    val path = directory.resolve("cleanup-${first.javaClass.simpleName}.bin")
                    assertTrue(files.open(text(path.toString()), 1) >= 3)
                    assertEquals(1, fs.channels.size); assertTrue(fs.channels.single().isOpen)
                    failing = true
                    try {
                        val thrown = assertThrows(RuntimeException::class.java) { files.dispose() }
                        val cause = if (first is IOException) thrown.cause else thrown
                        assertSame(first, cause)
                        assertArrayEquals(arrayOf(second), first.suppressed)
                        assertFalse(fs.channels.single().isOpen)
                        assertDoesNotThrow { files.dispose() }
                    } finally { failing = false }
                }
            }
        }
    }

    @Test fun streamIoFailureIsNotSuccessfulOutputAndDoesNotCatchRuntimeFaults() {
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { throw IOException("write failed") }
        }
        builder().out(output).build().use { context -> entered(context) { files ->
            assertEquals(-1L, files.write(1, bytes("hello"), 5))
            assertEquals(6L, files.errorKind()); assertTrue(files.errorMessage().utf8().contains("write failed"))
        } }
    }
}
