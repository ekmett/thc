// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.io.Closeable
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.net.URI
import java.nio.channels.ClosedChannelException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** One factory-owned context directory. Replacement publishes a fully checked
 * handle, never a pathname cache. Each operation owns a duplicate until its
 * finally/stream-close; replacement and disposal cannot reuse that descriptor.
 * This object never changes the process working directory. */
internal class NativeDirectoryOwner(initial: Path) : Closeable {
    private var current: NativeFileLease? = acquire(-1, pathBytes(initial))

    @Synchronized fun borrow(): Borrow {
        val source = current ?: throw ClosedChannelException()
        val copy = NativeFileLease()
        try {
            val fd = source.duplicateForWait()
            copy.openSlot().set(ValueLayout.JAVA_INT, 0, fd)
            return Borrow(copy, fd)
        } catch (failure: Throwable) {
            try { copy.closeDirectory() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    fun change(path: Path) = change(pathBytes(path))

    @Synchronized fun change(path: ByteArray) {
        // Keep acquisition and publication in one context transaction. No Env
        // or filesystem setter follows this commit; they read this same owner.
        borrow().use { anchor ->
            val replacement = acquire(anchor.descriptor, path)
            val previous = current!!
            current = replacement
            previous.closeDirectory()
        }
    }

    fun currentPath(): Path = borrow().use { it.currentPath() }
    fun name(capacity: Int): ByteArray = borrow().use { it.name(capacity) }

    @Synchronized override fun close() {
        val previous = current ?: return
        current = null
        previous.closeDirectory()
        // Borrowers own different descriptors and close after their operation.
        // In particular a native open worker must join before its borrow closes.
    }

    internal class Borrow internal constructor(internal val lease: NativeFileLease,
                                               internal val descriptor: Int) : Closeable {
        fun resolve(path: Path): Path {
            lease.requireOpen()
            return if (path.isAbsolute) path else Path.of("/proc/self/fd/$descriptor")
                .resolve(if (path == Path.of("")) Path.of(".") else path)
        }
        fun name(capacity: Int): ByteArray {
            lease.requireOpen()
            return NativeDirectoryApi.name(descriptor, capacity)
        }
        fun currentPath(): Path {
            var capacity = 4096
            while (true) {
                try { return bytesPath(name(capacity)) }
                catch (failure: NativeFileException) {
                    if (failure.errno != 34 || capacity > Int.MAX_VALUE / 2) throw failure
                    capacity *= 2
                }
            }
        }
        override fun close() = lease.closeDirectory()
    }

    companion object {
        private fun acquire(at: Int, path: ByteArray): NativeFileLease {
            val lease = NativeFileLease()
            try {
                NativeDirectoryApi.open(at, path, lease.openSlot())
                lease.requireOpen()
                return lease
            } catch (failure: Throwable) {
                try { lease.closeDirectory() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                throw failure
            }
        }

        /** The Unix Path URI preserves raw bytes, including invalid UTF-8.
         * Relativize under a fixed root first; never use the process CWD. */
        internal fun pathBytes(path: Path): ByteArray {
            val absolute = path.isAbsolute
            val uri = (if (absolute) path else Path.of("/").resolve(path)).toUri()
            check(uri.scheme == "file" && uri.rawAuthority.isNullOrEmpty())
            // UnixUriUtils appends '/' after observing a directory. Resolving a
            // relative path below '/' must not let that unrelated observation
            // turn context-relative regular-file "tmp" into "tmp/".
            val raw = if (path == path.root) uri.rawPath else uri.rawPath.removeSuffix("/")
            val bytes = java.io.ByteArrayOutputStream()
            var index = if (absolute) 0 else 1
            while (index < raw.length) {
                val char = raw[index++]
                if (char == '%') {
                    check(index + 1 < raw.length)
                    val high = raw[index++].digitToInt(16)
                    val low = raw[index++].digitToInt(16)
                    bytes.write((high shl 4) or low)
                } else {
                    check(char.code in 1..127)
                    bytes.write(char.code)
                }
            }
            bytes.write(0)
            return bytes.toByteArray()
        }

        internal fun bytesPath(bytes: ByteArray): Path {
            check(bytes.isNotEmpty() && bytes[0] == '/'.code.toByte() && bytes.none { it == 0.toByte() })
            val text = StringBuilder("file://")
            for (byte in bytes) {
                val value = byte.toInt() and 255
                if (value == '/'.code) text.append('/')
                else text.append('%').append("0123456789ABCDEF"[value ushr 4]).append("0123456789ABCDEF"[value and 15])
            }
            return Path.of(URI.create(text.toString()))
        }
    }
}

/** FileSystem callbacks may run outside an entered guest context. This small
 * native bridge therefore uses FFM, not LLVM or a guest-context callback. */
internal object NativeDirectoryApi {
    private val library: SymbolLookup = run {
        val file = Files.createTempFile("thc-directory-", ".so")
        try {
            NativeDirectoryOwner::class.java.getResourceAsStream("/thc/native/native-directory-api.so").use { input ->
                checkNotNull(input) { "Missing native directory bridge" }
                Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING)
            }
            SymbolLookup.libraryLookup(file, Arena.global())
        } finally { Files.deleteIfExists(file) }
    }
    private val linker = Linker.nativeLinker()
    private val open = linker.downcallHandle(library.find("thc_directory_open").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    private val name = linker.downcallHandle(library.find("thc_directory_name").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
    private val streamOpen = linker.downcallHandle(library.find("thc_directory_stream_open").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    private val streamFdOpen = linker.downcallHandle(library.find("thc_directory_stream_fdopen").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    private val streamRead = linker.downcallHandle(library.find("thc_directory_stream_read").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    private val streamClose = linker.downcallHandle(library.find("thc_directory_stream_close").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))

    fun openStream(at: Int, path: ByteArray, slot: MemorySegment) = Arena.ofConfined().use { arena ->
        val bytes = arena.allocate(path.size.toLong()).also { it.copyFrom(MemorySegment.ofArray(path)) }
        val error = streamOpen.invokeExact(at, bytes, slot) as Int
        if (error != 0) throw NativeFileException("opendir", error)
    }
    fun fdOpenStream(lease: MemorySegment, slot: MemorySegment) {
        val error = streamFdOpen.invokeExact(lease, slot) as Int
        if (error != 0) throw NativeFileException("fdopendir", error)
    }
    fun readStream(stream: MemorySegment, errno: Int, receive: (Int, Int, ByteArray?) -> Unit) =
        Arena.ofConfined().use { arena ->
            val name = arena.allocate(ValueLayout.ADDRESS)
            val size = arena.allocate(ValueLayout.JAVA_LONG)
            val error = arena.allocate(ValueLayout.JAVA_INT).also { it.set(ValueLayout.JAVA_INT, 0, errno) }
            val result = streamRead.invokeExact(stream, name, size, error) as Int
            val count = size.get(ValueLayout.JAVA_LONG, 0)
            check(result == 0 || result == -1)
            check(count in 0L until Int.MAX_VALUE.toLong())
            val pointer = name.get(ValueLayout.ADDRESS, 0)
            check((result == -1) == (pointer.address() == 0L))
            val bytes = if (result == -1) null else pointer.reinterpret(count + 1).toArray(ValueLayout.JAVA_BYTE)
            check(bytes == null || bytes.last() == 0.toByte())
            receive(result, error.get(ValueLayout.JAVA_INT, 0), bytes)
        }
    fun closeStream(slot: MemorySegment): Int = streamClose.invokeExact(slot) as Int

    fun open(at: Int, path: ByteArray, slot: MemorySegment) = Arena.ofConfined().use { arena ->
        check(path.isNotEmpty() && path.last() == 0.toByte() && path.dropLast(1).none { it == 0.toByte() })
        val bytes = arena.allocate(path.size.toLong()).also { it.copyFrom(MemorySegment.ofArray(path)) }
        val error = open.invokeExact(at, bytes, slot) as Int
        if (error != 0) throw NativeFileException("chdir", error)
    }

    fun name(fd: Int, capacity: Int): ByteArray = Arena.ofConfined().use { arena ->
        require(capacity >= 0)
        val output = arena.allocate(maxOf(1, capacity).toLong())
        val result = name.invokeExact(fd, output, capacity.toLong()) as Long
        if (result < 0) throw NativeFileException("getcwd", (-result).toInt())
        check(result < capacity && output.get(ValueLayout.JAVA_BYTE, result) == 0.toByte())
        ByteArray(result.toInt()).also { MemorySegment.copy(output, 0, MemorySegment.ofArray(it), 0, result) }
    }
}
