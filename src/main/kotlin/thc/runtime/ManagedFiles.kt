// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleFile
import com.oracle.truffle.api.TruffleLanguage
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.StandardOpenOption

/** Explicit thc_io_v1 service, not a POSIX ABI. Descriptors and reader/writer
 * claims belong to one THC context. Only regular files and the embedding's
 * three streams are supported. Calls are synchronous: this is not a scheduler,
 * readiness service, or an implementation of interruptible foreign calls. */
internal class ManagedFiles(private val env: TruffleLanguage.Env, private val threads: GuestThreads) {
    private data class FileIdentity(val device: Long, val inode: Long)
    private class Descriptor(
        val input: InputStream? = null,
        val output: OutputStream? = null,
        val channel: SeekableByteChannel? = null,
        val identity: FileIdentity? = null,
        val readable: Boolean = false,
        val writable: Boolean = false,
        val append: Boolean = false
    )
    private data class Failure(val kind: Long, val message: String)
    private class FileFailure(val kind: Long, message: String) : IOException(message)
    private val failure = ThreadLocal.withInitial { Failure(0, "") }
    private val descriptors = linkedMapOf<Long, Descriptor>(
        0L to Descriptor(input = env.`in`(), readable = true),
        1L to Descriptor(output = env.out(), writable = true),
        2L to Descriptor(output = env.err(), writable = true))
    private var nextDescriptor = 3L
    private var disposed = false

    // Portable error categories, deliberately not host errno numbers. A successful
    // operation does not clear the last error; queries cannot destroy the message.
    @TruffleBoundary fun errorKind(): Long = failure.get().kind
    @TruffleBoundary fun errorMessage(): ManagedAddress {
        val bytes = failure.get().message.toByteArray(Charsets.UTF_8)
        return ManagedAddress.fromHex(bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
    }

    private fun fail(kind: Long, message: String): Nothing = throw FileFailure(kind, message)
    private fun descriptor(fd: Long): Descriptor = descriptors[fd]
        ?: fail(4, "Closed or unknown THC file descriptor: $fd")

    private fun identity(file: TruffleFile, name: String): FileIdentity {
        val attributes = file.getAttributes(listOf(TruffleFile.IS_REGULAR_FILE, TruffleFile.IS_DIRECTORY,
            TruffleFile.UNIX_DEV, TruffleFile.UNIX_INODE))
        if (attributes.get(TruffleFile.IS_DIRECTORY) == true) fail(9, "Cannot open a directory: $name")
        if (attributes.get(TruffleFile.IS_REGULAR_FILE) != true) fail(7, "Only regular files are supported: $name")
        val device: Long? = attributes.get(TruffleFile.UNIX_DEV)
        val inode: Long? = attributes.get(TruffleFile.UNIX_INODE)
        if (device == null || inode == null) fail(7, "File provider does not expose stable Unix identity: $name")
        return FileIdentity(device, inode)
    }

    private fun requireUnclaimed(identity: FileIdentity, writable: Boolean, name: String) {
        for (other in descriptors.values) {
            if (other.identity == identity && (writable || other.writable))
                fail(8, "File already has an incompatible reader/writer in this THC context: $name")
        }
    }

    private inline fun result(action: () -> Long): Long {
        // Embedding streams and TruffleFile providers are opaque Java calls.
        // A reentrant guest entry opens its own cut; completion never polls here.
        val previous = threads.enterForeign()
        return try {
            action()
        } catch (error: FileFailure) {
            failure.set(Failure(error.kind, error.message ?: "File operation failed")); -1L
        } catch (error: NoSuchFileException) {
            failure.set(Failure(1, error.message ?: "File does not exist")); -1L
        } catch (error: NotDirectoryException) {
            failure.set(Failure(1, error.message ?: "A path component is not a directory")); -1L
        } catch (error: AccessDeniedException) {
            failure.set(Failure(2, error.message ?: "File access denied")); -1L
        } catch (error: SecurityException) {
            failure.set(Failure(2, error.message ?: "File access denied by the embedding context")); -1L
        } catch (error: FileAlreadyExistsException) {
            failure.set(Failure(3, error.message ?: "File already exists")); -1L
        } catch (error: InvalidPathException) {
            failure.set(Failure(5, error.message ?: "Invalid file path")); -1L
        } catch (error: UnsupportedOperationException) {
            failure.set(Failure(7, error.message ?: "File operation is not supported")); -1L
        } catch (error: IOException) {
            failure.set(Failure(6, error.message ?: "File operation failed")); -1L
        } finally {
            threads.leaveForeign(previous)
        }
    }

    /** Read0/Write1/Append2/ReadWrite3. Write truncates only after the context's
     * Haskell single-writer/multiple-reader check, never while acquiring a channel.
     * Claims use provider-supplied Unix device/inode identities, not retained paths.
     * Pre/post-open checks detect simple replacement but cannot provide atomic
     * opened-channel identity against concurrent host mutation (including ABA).
     * This does not coordinate external processes or replace native file locks. */
    @Synchronized @TruffleBoundary fun open(path: ManagedAddress, mode: Long): Long {
        val name = path.utf8() // Bad managed memory is a runtime fault, not IOException.
        return result {
            if (disposed) fail(4, "THC file context is closed")
            if (mode !in 0L..3L) fail(5, "Unknown THC open mode: $mode")
            if (name.isEmpty()) fail(1, "Empty file path")
            if (nextDescriptor > Int.MAX_VALUE) fail(6, "THC file descriptor space exhausted")
            val file = env.getPublicTruffleFile(name)
            val writable = mode != 0L
            val before = try { identity(file, name) } catch (_: NoSuchFileException) { null }
            if (before != null) requireUnclaimed(before, writable, name)
            val options = when (mode) {
                0L -> setOf(StandardOpenOption.READ)
                2L -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                3L -> setOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
                else -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            }
            val channel = file.newByteChannel(options)
            try {
                val after = identity(file, name)
                if (before != null && before != after) fail(8, "File identity changed while opening: $name")
                requireUnclaimed(after, writable, name)
                if (mode == 1L) channel.truncate(0)
                val fd = nextDescriptor++
                descriptors[fd] = Descriptor(channel = channel, identity = after,
                    readable = mode == 0L || mode == 3L, writable = writable, append = mode == 2L)
                fd
            } catch (error: Throwable) {
                try { channel.close() } catch (closing: Throwable) { error.addSuppressed(closing) }
                throw error
            }
        }
    }

    @Synchronized @TruffleBoundary fun read(fd: Long, address: ManagedAddress, count: Long): Long {
        address.requireRange(0, count, true) // Validate the whole destination before consuming input.
        return result {
            val entry = descriptor(fd)
            if (!entry.readable) fail(4, "THC file descriptor is not readable: $fd")
            if (count == 0L) 0L else {
                val bytes = address.rawBacking()
                val offset = address.cbitsOffset().toInt()
                val n = entry.input?.read(bytes, offset, count.toInt())
                    ?: entry.channel!!.read(ByteBuffer.wrap(bytes, offset, count.toInt()))
                if (n < 0) 0L else if (n == 0) fail(6, "THC input made no progress") else n.toLong()
            }
        }
    }

    @Synchronized @TruffleBoundary fun write(fd: Long, address: ManagedAddress, count: Long): Long {
        address.requireRange(0, count)
        return result {
            val entry = descriptor(fd)
            if (!entry.writable) fail(4, "THC file descriptor is not writable: $fd")
            if (count == 0L) 0L else {
                val bytes = address.rawBacking()
                val offset = address.cbitsOffset().toInt()
                if (entry.output != null) {
                    entry.output.write(bytes, offset, count.toInt())
                    count
                } else entry.channel!!.write(ByteBuffer.wrap(bytes, offset, count.toInt())).toLong()
            }
        }
    }

    /** Logical close consumes the descriptor even if an underlying close fails;
     * callers must not retry it. Embedding streams are flushed, never closed. */
    @Synchronized @TruffleBoundary fun close(fd: Long): Long = result {
        val entry = descriptors.remove(fd) ?: fail(4, "Closed or unknown THC file descriptor: $fd")
        entry.channel?.close()
        entry.output?.flush()
        0L
    }

    /** Absolute0/Relative1/End2, with checked signed offsets. */
    @Synchronized @TruffleBoundary fun seek(fd: Long, offset: Long, mode: Long): Long = result {
        val channel = descriptor(fd).channel ?: fail(7, "THC stream is not seekable: $fd")
        val base = when (mode) {
            0L -> 0L
            1L -> channel.position()
            2L -> channel.size()
            else -> fail(5, "Unknown THC seek mode: $mode")
        }
        val position = try { Math.addExact(base, offset) }
            catch (_: ArithmeticException) { fail(5, "THC file offset overflow") }
        if (position < 0) fail(5, "Negative THC file offset")
        channel.position(position)
        position
    }

    @Synchronized @TruffleBoundary fun size(fd: Long): Long = result {
        (descriptor(fd).channel ?: fail(7, "THC stream has no file size: $fd")).size()
    }

    @Synchronized @TruffleBoundary fun setSize(fd: Long, length: Long): Long = result {
        val entry = descriptor(fd)
        val channel = entry.channel ?: fail(7, "Cannot resize a THC stream: $fd")
        if (!entry.writable) fail(4, "THC file descriptor is not writable: $fd")
        if (length < 0) fail(5, "Negative THC file size")
        val oldSize = channel.size()
        if (length > oldSize && entry.append) fail(7, "Extending an append-mode file is not supported")
        val position = channel.position()
        try {
            if (length <= oldSize) channel.truncate(length) else {
                channel.position(length - 1)
                if (channel.write(ByteBuffer.wrap(byteArrayOf(0))) != 1) fail(6, "Unable to extend THC file")
            }
        } finally { channel.position(position) }
        0L
    }

    // Env exposes byte streams, not terminal handles. Do not infer a terminal
    // from System.console(): it may be unrelated to the embedding's streams.
    @Synchronized @TruffleBoundary fun isTerminal(fd: Long): Long = result { descriptor(fd); 0L }
    @Synchronized @TruffleBoundary fun deviceType(fd: Long): Long = result {
        if (descriptor(fd).channel != null) 0L else 1L
    }

    /** Final disposal cannot run Haskell finalizers or flush GHC's own buffers.
     * Release every owned channel even if one close fails. */
    @Synchronized @TruffleBoundary fun dispose() {
        if (disposed) return
        disposed = true
        val entries = descriptors.values.toList()
        descriptors.clear()
        var failed: Throwable? = null
        // Context teardown may invoke an embedding stream after this context's
        // guest registry has closed. A callback into another context remains
        // under an opaque Java frame and must retain that async origin.
        val previous = threads.enterForeign()
        try {
            for (entry in entries) try {
                entry.channel?.close()
                entry.output?.flush()
            } catch (error: Throwable) {
                if (failed == null) failed = error else if (failed !== error) failed.addSuppressed(error)
            }
        } finally { threads.leaveForeign(previous) }
        failure.remove()
        if (failed is IOException)
            throw RuntimeFault("THC file disposal failed: ${failed.message}").also { it.initCause(failed) }
        if (failed != null) throw failed
    }
}
