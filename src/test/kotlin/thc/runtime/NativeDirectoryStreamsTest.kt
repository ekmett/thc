// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.Language
import thc.NativeIO
import java.nio.file.Files
import java.nio.file.Path

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class NativeDirectoryStreamsTest {
    @TempDir lateinit var root: Path
    private fun address(bytes: ByteArray) = ManagedAddress.fromByteArray(bytes + 0.toByte())
    private fun path(path: Path) = ManagedAddress.fromByteArray(NativeDirectoryOwner.pathBytes(path))
    private fun cell() = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8))
    private fun <T> entered(context: Context, action: () -> T): T {
        context.enter()
        return try { action() } finally { context.leave() }
    }
    private fun bytes(address: ManagedAddress): List<Int> =
        (0 until address.cStringLength()).map { address.readWord8(it).toInt() }
    private fun contents(stream: ManagedAddress): Set<List<Int>> {
        val service = NativeFileProvider.current().directoryStreams
        val output = cell()
        val names = mutableSetOf<List<Int>>()
        while (true) {
            Language.currentState().stdio.setErrno(0)
            val status = service.read(stream, output)
            if (status == -1L) {
                assertSame(ManagedAddress.nullAddress(), output.readAddressElementIndex(0))
                assertEquals(0L, Language.currentState().stdio.errno())
                return names
            }
            assertEquals(0L, status)
            val entry = output.readAddressElementIndex(0)
            names.add(bytes(service.name(entry)))
            service.freeEntry(entry)
        }
    }

    @Test fun rawNamesEofAndEntryLifetimesFollowTheOriginalGlibcProtocol() {
        Files.writeString(root.resolve("file"), "unchanged")
        val raw = byteArrayOf(-1, 110)
        Files.write(NativeDirectoryOwner.bytesPath(root.toString().toByteArray() + byteArrayOf(47) + raw), byteArrayOf(7))
        val renamed = root.resolveSibling(root.fileName.toString() + "-renamed")
        NativeIO.createContext().use { context -> entered(context) {
            val stdio = Language.currentState().stdio
            val service = NativeFileProvider.current().directoryStreams
            val stream = stdio.openDirectory(path(root))
            assertNotSame(ManagedAddress.nullAddress(), stream)
            val output = cell()
            stdio.setErrno(9)
            assertEquals(0L, service.read(stream, output))
            val entry = output.readAddressElementIndex(0)
            val name = service.name(entry)
            val first = bytes(name)
            service.freeEntry(entry)
            assertEquals(first, bytes(name), "free_dirent is a native no-op on this glibc contract")
            assertEquals(9L, stdio.errno())
            Files.move(root, renamed)
            try {
                val names = contents(stream) + setOf(first)
                assertEquals(setOf(listOf(46), listOf(46,46), "file".map { it.code }, listOf(255,110)), names)
                assertThrows(RuntimeFault::class.java) { bytes(name) }
                assertThrows(RuntimeFault::class.java) { service.name(entry) }
                stdio.setErrno(9)
                assertEquals(-1L, service.read(stream, output))
                assertEquals(9L, stdio.errno(), "EOF preserves the guest errno seed")
                assertEquals(0L, service.closeStream(stream))
                assertThrows(RuntimeFault::class.java) { service.closeStream(stream) }
            } finally { Files.move(renamed, root) }
        } }
    }

    @Test fun fdopendirConsumesOnlyTheSuccessfulGuestFdAndPreservesAliases() {
        Files.writeString(root.resolve("file"), "unchanged")
        NativeIO.createContext().use { context -> entered(context) {
            val stdio = Language.currentState().stdio
            val service = NativeFileProvider.current().directoryStreams
            val flags = stdio.flagConstant(OriginalStdioOp.O_RDONLY)
            val fd = stdio.open(path(root), flags, 0)
            assertTrue(fd >= 0)
            val alias = stdio.duplicate(fd)
            val stream = stdio.openDirectoryFd(fd)
            assertNotSame(ManagedAddress.nullAddress(), stream)
            assertEquals(-1L, stdio.close(fd))
            assertEquals(0L, stdio.close(alias), "The surviving guest alias remains independently closeable")
            assertEquals(setOf(listOf(46), listOf(46,46), "file".map { it.code }), contents(stream))
            assertEquals(0L, service.closeStream(stream))
            val regular = stdio.open(path(root.resolve("file")), flags, 0)
            assertSame(ManagedAddress.nullAddress(), stdio.openDirectoryFd(regular))
            assertEquals(20L, stdio.errno())
            assertEquals(0L, stdio.close(regular), "Failed fdopendir leaves its input fd owned by the caller")
            assertSame(ManagedAddress.nullAddress(), stdio.openDirectoryFd(regular))
            assertEquals(9L, stdio.errno())
            assertEquals(0, service.liveCount())
        } }
    }

    @Test fun outputValidationPrecedesReadAndDisposalRetiresAllViews() {
        val first = NativeIO.createContext()
        val second = NativeIO.createContext()
        var staleName: ManagedAddress? = null
        lateinit var service: NativeDirectoryStreams
        try {
            val stream = entered(first) {
                service = NativeFileProvider.current().directoryStreams
                Language.currentState().stdio.openDirectory(path(root))
            }
            entered(second) {
                assertThrows(RuntimeFault::class.java) { NativeFileProvider.current().directoryStreams.read(stream, cell()) }
            }
            entered(first) {
                val reference = Language.currentState().stdio.openDirectory(path(root))
                val expected = cell()
                assertEquals(0L, service.read(reference, expected))
                val expectedName = bytes(service.name(expected.readAddressElementIndex(0)))
                service.closeStream(reference)
                val raw = ManagedAllocation.mutable(8, 8).also { it.rawBytesIfPointerFree() }
                for (invalid in listOf(ManagedAddress.nullAddress(), ManagedAddress.fromAllocation(raw),
                    ManagedAddress.fromAllocation(ManagedAllocation.mutable(16,8)).plus(1),
                    ManagedAddress.fromAllocation(ManagedAllocation.immutable(ByteArray(8),8))))
                    assertThrows(RuntimeFault::class.java) { service.read(stream, invalid) }
                val output = cell()
                assertEquals(0L, service.read(stream, output))
                staleName = service.name(output.readAddressElementIndex(0))
                assertEquals(expectedName, bytes(staleName!!), "Rejected storage must not consume the first entry")
                assertThrows(RuntimeFault::class.java) { service.read(stream.plus(1), output) }
                assertThrows(RuntimeFault::class.java) { service.read(address(byteArrayOf()), output) }
            }
        } finally { first.close(); second.close() }
        assertEquals(0, service.liveCount())
        assertThrows(RuntimeFault::class.java) { bytes(staleName!!) }
    }

    @Test fun activeNameCannotBeUsedAsTheNextOutputCell() {
        Files.writeString(root.resolve("long-directory-entry"), "unchanged")
        NativeIO.createContext().use { context -> entered(context) {
            val stdio = Language.currentState().stdio
            val service = NativeFileProvider.current().directoryStreams
            val stream = stdio.openDirectory(path(root))
            val reference = stdio.openDirectory(path(root))
            val output = cell(); val comparison = cell()
            repeat(3) {
                assertEquals(service.read(reference, comparison), service.read(stream, output))
                val name = service.name(output.readAddressElementIndex(0))
                assertEquals(bytes(service.name(comparison.readAddressElementIndex(0))), bytes(name))
                if (name.cStringLength() >= 8) {
                    val original = bytes(name)
                    assertThrows(RuntimeFault::class.java) { service.read(stream, name) }
                    assertEquals(original, bytes(name), "Rejected alias cannot retire or shrink the current name")
                }
            }
            stdio.setErrno(0)
            assertEquals(-1L, service.read(stream, output))
            assertEquals(0L, stdio.errno())
            assertEquals(0L, service.closeStream(stream)); assertEquals(0L, service.closeStream(reference))
        } }
    }

    @Test fun nativeOutputCellsRetainPointerIdentityAndRejectReleasedStorageBeforeRead() {
        NativeIO.createContext().use { context -> entered(context) {
            val state = Language.currentState()
            val service = NativeFileProvider.current().directoryStreams
            val stream = state.stdio.openDirectory(path(root))
            val reference = state.stdio.openDirectory(path(root))
            val native = state.nativeAllocations.malloc(24)
            val output = native.plus(8)
            val comparison = cell()
            try {
                native.fill(24, 165)
                assertEquals(0L, service.read(stream, output))
                assertEquals(0L, service.read(reference, comparison))
                val entry = output.readAddressElementIndex(0)
                assertEquals(bytes(service.name(comparison.readAddressElementIndex(0))), bytes(service.name(entry)))
                service.freeEntry(entry)
                for (offset in 0L..7L) {
                    assertEquals(165L, native.readWord8(offset))
                    assertEquals(165L, native.readWord8(offset + 16))
                }
            } finally { state.nativeAllocations.free(native) }
            assertThrows(RuntimeFault::class.java) { service.read(stream, output) }
            val next = cell()
            assertEquals(service.read(reference, comparison), service.read(stream, next))
            assertEquals(bytes(service.name(comparison.readAddressElementIndex(0))), bytes(service.name(next.readAddressElementIndex(0))))
            assertEquals(0L, service.closeStream(stream)); assertEquals(0L, service.closeStream(reference))
        } }
    }
}
