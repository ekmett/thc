// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.FileSystem
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
import thc.NativeIO.StandardEndpoint
import thc.executionContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Provider/ownership proof only, deliberately not original FCall admission. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class NativeFileProviderTest {
    @TempDir lateinit var directory: Path
    private fun nativeContext(endpoints: Set<StandardEndpoint> = emptySet()) = NativeIO.createContext(endpoints)
    private fun <T> entered(context: Context, body: () -> T): T {
        context.initialize("thc"); context.enter()
        return try { body() } finally { context.leave() }
    }
    private fun provider() = NativeFileProvider.current()
    private fun field(image: ByteArray, op: OriginalStdioOp) = PosixStat.execute(op, ManagedAddress.fromByteArray(image), 0)
    private fun identity(file: OpenedNativeFile) = file.statImage().let {
        field(it, OriginalStdioOp.ST_DEV) to field(it, OriginalStdioOp.ST_INO)
    }
    private fun path(path: Path) = ManagedAddress.fromByteArray(path.toString().toByteArray() + byteArrayOf(0))
    private fun identity(files: ManagedFiles, fd: Long) = files.statImage(fd).let {
        field(it, OriginalStdioOp.ST_DEV) to field(it, OriginalStdioOp.ST_INO)
    }
    // Test-only kernel observation; no fd integer enters production or Core.
    private fun nativeDescriptors(path: Path): Long = Files.list(Path.of("/proc/self/fd")).use { entries ->
        entries.filter { entry ->
            try { Files.readSymbolicLink(entry) == path.toAbsolutePath() }
            catch (_: java.nio.file.NoSuchFileException) { false }
        }.count()
    }

    @Test fun nativeAndCommandLineContextsPermitGuestThreads() {
        for (factory in listOf<() -> Context>({ nativeContext() }, { executionContext(fileIO = true) })) factory().use {
            entered(it) {
                assertNotNull(Language.currentState().nativeFiles)
                val ran = CountDownLatch(1)
                val thread = Language.currentState().env.newTruffleThreadBuilder(Runnable { ran.countDown() }).build()
                thread.start()
                assertTrue(ran.await(5, TimeUnit.SECONDS), "Guest thread must run in the native context")
                thread.join(5000)
                assertFalse(thread.isAlive)
            }
        }
        executionContext(fileIO = true).use { context -> entered(context) {
            for (endpoint in StandardEndpoint.entries) provider().standard(endpoint).use {
                assertTrue(it.isOpen, "The command line explicitly grants $endpoint")
            }
            for (fd in 0L..2L) assertTrue(Language.currentState().files.statImage(fd).isNotEmpty())
        } }
    }

    @Test fun metadataAndBytesRetainOneOpenedResourceAcrossRenameUnlinkAndHostChanges() {
        val original = directory.resolve("original")
        val renamed = directory.resolve("renamed")
        Files.writeString(original, "before")
        nativeContext().use { context -> entered(context) {
            provider().open(original.toString(), 3).use { opened ->
                val id = identity(opened)
                assertEquals(6L, opened.size())
                Files.move(original, renamed)
                Files.writeString(original, "replacement")
                Files.setPosixFilePermissions(renamed, PosixFilePermissions.fromString("r--------"))
                val mode = field(opened.statImage(), OriginalStdioOp.ST_MODE)
                assertEquals(0x100L, mode and 0x1ff)
                assertEquals(id, identity(opened))
                assertEquals(5, opened.write(ByteBuffer.wrap("guest".toByteArray())))
                assertEquals("gueste", Files.readString(renamed))
                Files.setPosixFilePermissions(renamed, PosixFilePermissions.fromString("rw-------"))
                Files.newByteChannel(renamed, StandardOpenOption.WRITE).use { it.truncate(2) }
                assertEquals(2L, opened.size())
                Files.delete(renamed)
                assertEquals(id, identity(opened))
                assertEquals(2L, opened.size())
                opened.position(0)
                val bytes = ByteBuffer.allocate(2)
                assertEquals(2, opened.read(bytes))
                assertArrayEquals("gu".toByteArray(), bytes.array())
                val copy = opened.statImage(); copy.fill(0)
                assertEquals(id, identity(opened), "Metadata must not be an exposed mutable cache")
                assertEquals("replacement", Files.readString(original))
            }
        } }
    }

    @Test fun nontruncatingAcquisitionAndChannelPreflightsPreserveBytesAndPosition() {
        val path = directory.resolve("bytes")
        Files.writeString(path, "123456")
        nativeContext().use { context -> entered(context) {
            val provider = provider()
            provider.open(path.toString(), 1).use { assertEquals(6L, it.size()) }
            assertEquals("123456", Files.readString(path))
            provider.open(path.toString(), 0).use { opened ->
                assertThrows(NonWritableChannelException::class.java) { opened.truncate(6) }
                assertThrows(ReadOnlyBufferException::class.java) { opened.read(ByteBuffer.allocate(2).asReadOnlyBuffer()) }
                assertEquals(0L, opened.position())
                val destination = ByteBuffer.wrap(ByteArray(8) { 90 }).also { it.position(2); it.limit(5) }
                assertEquals(3, opened.read(destination))
                assertArrayEquals(byteArrayOf(90, 90, 49, 50, 51, 90, 90, 90), destination.array())
                opened.position(6)
                assertEquals(-1, opened.read(ByteBuffer.allocate(1)))
                assertEquals(0, opened.read(ByteBuffer.allocate(0)))
            }
            provider.open(path.toString(), 3).use { opened ->
                opened.position(6); opened.truncate(2)
                assertEquals(2L, opened.position())
                opened.truncate(20)
                assertEquals(2L, opened.size())
            }
        } }
    }

    @Test fun nativeAndFilesystemAuthorityAreBothRequiredAndErrorsAreReal() {
        Context.newBuilder("thc").allowNativeAccess(true).build().use { context -> entered(context) {
            assertThrows(SecurityException::class.java) { provider() }
        } }
        Context.newBuilder("thc").allowIO(IOAccess.ALL).build().use { context -> entered(context) {
            assertThrows(SecurityException::class.java) { provider() }
        } }
        Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.ALL).build().use { context -> entered(context) {
            assertThrows(SecurityException::class.java) { provider().open(directory.resolve("never").toString(), 3) }
            assertFalse(Files.exists(directory.resolve("never")))
        } }
        Context.newBuilder("thc").allowNativeAccess(true)
            .allowIO(IOAccess.newBuilder().fileSystem(FileSystem.newReadOnlyFileSystem(NativeFileSystem())).build())
            .build().use { context -> entered(context) {
            assertThrows(SecurityException::class.java) { provider().open(directory.resolve("never").toString(), 3) }
            assertFalse(Files.exists(directory.resolve("never")))
        } }
        nativeContext().use { context -> entered(context) {
            val error = assertThrows(NativeFileException::class.java) { provider().open(directory.resolve("absent").toString(), 0) }
            assertEquals(StdioHostAbi.load().error(1), error.errno.toLong())
            assertThrows(SecurityException::class.java) { provider().standard(StandardEndpoint.OUTPUT) }
            assertThrows(UnsupportedOperationException::class.java) { provider().open(directory.toString(), 0) }
            assertEquals(0L, nativeDescriptors(directory), "Rejected opened type must close its native fd")
        } }
    }

    @Test fun explicitStandardResourcesAreOwnedDuplicatesNotEmbeddingStreamMetadata() {
        nativeContext(StandardEndpoint.entries.toSet()).use { context -> entered(context) {
            val provider = provider()
            val first = provider.standard(StandardEndpoint.OUTPUT)
            val id = identity(first)
            first.close(); first.close()
            provider.standard(StandardEndpoint.OUTPUT).use { second ->
                assertEquals(id, identity(second), "Closing an owned duplicate must not close process stdout")
                val line = "native opened-resource provider proof\n".toByteArray()
                assertEquals(line.size, second.write(ByteBuffer.wrap(line)))
            }
            for (endpoint in listOf(StandardEndpoint.INPUT, StandardEndpoint.ERROR)) {
                val original = provider.standard(endpoint)
                val originalIdentity = identity(original)
                original.close(); original.close()
                provider.standard(endpoint).use { alias -> assertEquals(originalIdentity, identity(alias)) }
            }
        } }
        val unrelated = ByteArrayOutputStream()
        Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.ALL).out(unrelated).build().use { context -> entered(context) {
            assertThrows(SecurityException::class.java) { provider().standard(StandardEndpoint.OUTPUT) }
            assertEquals(1L, Language.currentState().files.write(1, ManagedAddress.fromByteArray(byteArrayOf(42)), 1))
            assertArrayEquals(byteArrayOf(42), unrelated.toByteArray(), "Ordinary embedding streams remain unchanged")
        } }
    }

    @Test fun contextDisposalAndRepeatedCloseCannotCloseAReusedResource() {
        val context = nativeContext()
        val pair = entered(context) {
            val provider = provider()
            val old = provider.open(directory.resolve("old").toString(), 3)
            old.close()
            val current = provider.open(directory.resolve("current").toString(), 3)
            old.close()
            assertEquals(0L, nativeDescriptors(directory.resolve("old")))
            assertEquals(1L, nativeDescriptors(directory.resolve("current")))
            assertEquals(1, current.write(ByteBuffer.wrap(byteArrayOf(42))))
            assertEquals(1L, current.size())
            provider to current
        }
        context.close()
        assertEquals(0L, nativeDescriptors(directory.resolve("current")), "Host close must release the real kernel fd after LLVM disposal")
        assertFalse(pair.second.isOpen)
        pair.second.close(); pair.first.close()
    }

    @Test fun completedAcquisitionThenProviderFailureRetainsRollbackOwnership() {
        var acquired: SeekableByteChannel? = null
        val failure = IOException("after completion")
        nativeContext().use { context -> entered(context) {
            val request = NativeOpenRequest(null, emptySet()) { provider().open(it!!.toString(), 3) }
            assertSame(failure, assertThrows(IOException::class.java) {
                request.use {
                    acquired = it.acquire(directory.resolve("created"), emptySet())
                    throw failure
                }
            })
            assertFalse(acquired!!.isOpen, "Completed token must retain cleanup when channel return throws")
            assertEquals(0L, nativeDescriptors(directory.resolve("created")))
        } }
    }

    @Test fun reentrantProviderDisposalAfterAcquisitionCannotPublishAClosedResource() {
        nativeContext().use { context -> entered(context) {
            val selected = provider()
            val request = NativeOpenRequest(null, emptySet()) { selected.open(it!!.toString(), 3) }
            request.use {
                val acquired = it.acquire(directory.resolve("reentrant"), emptySet())
                selected.close()
                assertThrows(ClosedChannelException::class.java) { it.commit(acquired) }
                assertFalse(acquired.isOpen)
            }
            assertEquals(0L, nativeDescriptors(directory.resolve("reentrant")))
        } }
    }

    @Test fun duplicateAndLateCompletionAreRejectedWithoutLeakingChannels() {
        nativeContext().use { context -> entered(context) {
            val selected = provider()
            val request = NativeOpenRequest(null, emptySet()) { selected.open(it!!.toString(), 3) }
            val acquired = request.acquire(directory.resolve("twice"), emptySet())
            assertThrows(IllegalStateException::class.java) { request.acquire(directory.resolve("ignored"), emptySet()) }
            request.close()
            assertFalse(acquired.isOpen)
            assertEquals(0L, nativeDescriptors(directory.resolve("twice")))
            assertThrows(IllegalStateException::class.java) { request.acquire(directory.resolve("ignored"), emptySet()) }
            val once = NativeOpenRequest(null, emptySet()) { selected.open(it!!.toString(), 3) }
            once.commit(once.acquire(directory.resolve("once"), emptySet())).use { opened ->
                assertThrows(IllegalStateException::class.java) { once.acquire(directory.resolve("ignored"), emptySet()) }
                assertEquals(0L, opened.size())
            }
        } }
    }

    @Test fun arbitraryFilesystemCannotAcquireAThenSubstituteChannelB() {
        val native = NativeFileSystem()
        var calls = 0
        val fs = object : FileSystem by native {
            override fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
                calls++
                native.newByteChannel(path, options, *attrs)
                return Files.newByteChannel(directory.resolve("substitute"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            }
        }
        Context.newBuilder("thc").allowNativeAccess(true)
            .allowIO(IOAccess.newBuilder().fileSystem(fs).build()).build().use { context -> entered(context) {
                assertThrows(SecurityException::class.java) { provider().open(directory.resolve("victim").toString(), 3) }
                assertEquals(0, calls, "Reject unauthenticated embedding before native acquisition")
                assertFalse(Files.exists(directory.resolve("victim")))
                assertFalse(Files.exists(directory.resolve("substitute")))
            } }
    }

    @Test fun metadataCannotCrossContextsAndClosedResourcesStayClosed() {
        nativeContext().use { first -> nativeContext().use { second ->
            val opened = entered(first) { provider().open(directory.resolve("private").toString(), 3) }
            entered(second) { assertThrows(RuntimeFault::class.java) { opened.statImage() } }
            entered(first) {
                assertEquals(0L, opened.size())
                opened.close()
                assertThrows(ClosedChannelException::class.java) { opened.statImage() }
            }
        } }
    }

    @Test fun managedAliasesKeepAuthoritativeIdentityAndClaimsAcrossHostMutation() {
        val original = directory.resolve("managed")
        val renamed = directory.resolve("managed-renamed")
        Files.writeString(original, "abcdef")
        nativeContext().use { context -> entered(context) {
            val files = Language.currentState().files
            val first = files.open(path(original), 3)
            assertTrue(first >= 3)
            val alias = files.duplicate(first)
            val id = identity(files, first)
            assertEquals(id, identity(files, alias))
            assertEquals(1L, nativeDescriptors(original), "Managed dup shares one physical resource")
            Files.move(original, renamed)
            Files.writeString(original, "replacement")
            Files.setPosixFilePermissions(renamed, PosixFilePermissions.fromString("r--------"))
            assertEquals(0x100L, field(files.statImage(alias), OriginalStdioOp.ST_MODE) and 0x1ff)
            Files.setPosixFilePermissions(renamed, PosixFilePermissions.fromString("rw-------"))
            assertEquals(-1L, files.open(path(renamed), 1))
            assertEquals(8L, files.errorKind())
            assertEquals("abcdef", Files.readString(renamed), "Claim rejection must precede truncation")
            assertEquals(1L, nativeDescriptors(renamed), "Rejected writer closes only its new acquisition")
            val replacement = files.open(path(original), 3)
            assertTrue(replacement >= 3)
            assertNotEquals(id, identity(files, replacement))
            assertEquals(0L, files.close(replacement))
            val byte = ManagedAddress.fromByteArray(byteArrayOf(0))
            assertEquals(1L, files.read(first, byte, 1)); assertEquals(97L, byte.readWord8(0))
            assertEquals(1L, files.read(alias, byte, 1)); assertEquals(98L, byte.readWord8(0))
            assertEquals(0L, files.close(first))
            assertThrows(IOException::class.java) { files.statImage(first) }
            assertEquals(1L, nativeDescriptors(renamed))
            Files.delete(renamed)
            assertEquals(id, identity(files, alias))
            assertEquals(1L, files.write(alias, ManagedAddress.fromByteArray(byteArrayOf(90)), 1))
            assertEquals(3L, files.seek(alias, 3, 0))
            assertEquals(1L, files.read(alias, byte, 1)); assertEquals(100L, byte.readWord8(0))
            assertEquals(0L, files.close(alias))
            assertEquals("replacement", Files.readString(original))
        } }
    }

    @Test fun managedReplacementMovesCapabilityAndLastOwnerClosesExactlyOnce() {
        val a = directory.resolve("owner-a"); val b = directory.resolve("owner-b")
        val context = nativeContext()
        lateinit var files: ManagedFiles
        entered(context) {
            files = Language.currentState().files
            val one = files.open(path(a), 3); val two = files.open(path(b), 3)
            val alias = files.duplicate(one)
            val oldIdentity = identity(files, one)
            val newIdentity = identity(files, two)
            assertNotEquals(oldIdentity, newIdentity)
            assertEquals(one, files.duplicateTo(two, one))
            assertEquals(newIdentity, identity(files, one))
            assertEquals(oldIdentity, identity(files, alias))
            assertEquals(1L, nativeDescriptors(a)); assertEquals(1L, nativeDescriptors(b))
            assertEquals(0L, files.close(alias))
            assertEquals(0L, nativeDescriptors(a))
            assertEquals(one, files.duplicateTo(two, one), "Replacing same-owner alias must not close it")
            assertEquals(0L, files.close(two))
            assertEquals(1L, nativeDescriptors(b))
            assertEquals(1L, files.write(one, ManagedAddress.fromByteArray(byteArrayOf(42)), 1))
        }
        context.close()
        assertEquals(0L, nativeDescriptors(b), "Context disposal closes the final alias physically")
        files.dispose()
        assertEquals(0L, nativeDescriptors(b))
        assertArrayEquals(byteArrayOf(42), Files.readAllBytes(b))
    }

    @Test fun explicitEndpointCapabilitiesPreserveUngrantAndNonregularBoundaries() {
        nativeContext(setOf(StandardEndpoint.OUTPUT)).use { context -> entered(context) {
            val state = Language.currentState(); val files = state.files; val stdio = state.stdio
            for (fd in listOf(0L, 2L))
                assertThrows(UnsupportedOperationException::class.java) { files.statImage(fd) }
            val image = files.statImage(1)
            val regular = PosixStat.execute(OriginalStdioOp.IS_REG, ManagedAddress.nullAddress(),
                field(image, OriginalStdioOp.ST_MODE)) == 1L
            assertEquals(if (regular) 0L else 1L, files.deviceType(1))
            if (regular) {
                assertEquals(1L, files.ready(1, 0)); assertEquals(0L, files.isTerminal(1))
                assertEquals(-1L, files.setSize(1, field(image, OriginalStdioOp.ST_SIZE) + 1))
                assertEquals(7L, files.errorKind(), "Inherited append status is not guessed for endpoint extension")
            } else {
                assertEquals(-1L, files.ready(1, 0)); assertEquals(7L, files.errorKind())
                assertEquals(0L, stdio.isTerminal(1)); assertEquals(StdioHostAbi.load().error(7), stdio.errno())
                assertEquals(-1L, files.size(1)); assertEquals(7L, files.errorKind())
                assertEquals(-1L, files.setSize(1, 0)); assertEquals(7L, files.errorKind())
                assertEquals(-1L, stdio.truncate(1, 0)); assertEquals(StdioHostAbi.load().error(5), stdio.errno())
            }
            val duplicate = files.duplicate(1)
            val identity = identity(files, 1)
            assertEquals(0L, files.close(1))
            assertEquals(identity, identity(files, duplicate))
            assertThrows(IOException::class.java) { files.statImage(1) }
            assertEquals(0L, files.close(duplicate))
        } }
        nativeContext(StandardEndpoint.entries.toSet()).use { context -> entered(context) {
            for (fd in 0L..2L) assertTrue(Language.currentState().files.statImage(fd).isNotEmpty())
        } }
    }

    @Test fun partialStandardInstallationRollsBackBeforePublishingAuthority() {
        nativeContext(setOf(StandardEndpoint.OUTPUT)).use { context -> entered(context) {
            val state = Language.currentState()
            val standalone = ManagedFiles(state.env, state.threads)
            val target = Files.readSymbolicLink(Path.of("/proc/self/fd/1"))
            fun matching() = Files.list(Path.of("/proc/self/fd")).use { entries -> entries.filter {
                try { Files.readSymbolicLink(it) == target } catch (_: java.nio.file.NoSuchFileException) { false }
            }.count() }
            val before = matching()
            try {
                assertThrows(SecurityException::class.java) {
                    standalone.installNative(provider(), linkedSetOf(StandardEndpoint.OUTPUT, StandardEndpoint.ERROR))
                }
                assertEquals(before, matching(), "Completed OUTPUT acquisition closes when later ERROR grant fails")
                for (fd in 0L..2L)
                    assertThrows(UnsupportedOperationException::class.java) { standalone.statImage(fd) }
            } finally { standalone.dispose() }
        } }
    }

    @Test fun nativeErrorsKeepPrivateCategoriesAndExactOriginalErrno() {
        nativeContext(setOf(StandardEndpoint.OUTPUT)).use { context -> entered(context) {
            val state = Language.currentState(); val files = state.files; val stdio = state.stdio
            val abi = StdioHostAbi.load()
            assertEquals(-1L, files.open(path(directory.resolve("missing")), 0))
            assertEquals(1L, files.errorKind()); assertEquals(abi.error(1), files.nativeErrno())
            assertEquals(-1L, stdio.close(-1))
            assertEquals(abi.error(4), stdio.errno()); assertEquals(0L, files.nativeErrno())
            // Compare against the actual same-endpoint native operation, without
            // assuming this test process's stdout is a pipe, terminal or file.
            provider().standard(StandardEndpoint.OUTPUT).use { endpoint ->
                val expected = try { endpoint.position(); null } catch (error: NativeFileException) { error }
                val observed = stdio.seek(1, 0, abi.seekConstant(OriginalStdioOp.SEEK_CUR))
                if (expected != null) {
                    assertEquals(-1L, observed)
                    assertEquals(expected.errno.toLong(), stdio.errno())
                    assertEquals(expected.errno.toLong(), files.nativeErrno())
                } else assertTrue(observed >= 0)
            }
            val sticky = stdio.errno()
            val alias = stdio.duplicate(1)
            assertTrue(alias >= 0); assertEquals(sticky, stdio.errno())
            assertEquals(0L, stdio.close(alias)); assertEquals(sticky, stdio.errno())
            assertEquals(-1L, stdio.close(-1))
            assertEquals(abi.error(4), stdio.errno(), "Later managed failure must not reuse stale native errno")
        } }
    }
}
