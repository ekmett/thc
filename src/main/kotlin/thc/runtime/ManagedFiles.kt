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
import java.util.concurrent.CountDownLatch

/** Explicit thc_io_v1 service, not a POSIX ABI. Descriptors and reader/writer
 * claims belong to one THC context. Only regular files and the embedding's
 * three streams are supported. Calls are synchronous: this is not a scheduler,
 * general readiness service, or an implementation of interruptible foreign calls. */
internal class ManagedFiles(private val env: TruffleLanguage.Env, private val threads: GuestThreads,
    private val descriptorLimit: Long = Int.MAX_VALUE.toLong() + 1) {
    private enum class Readiness { REGULAR_FILE, UNAVAILABLE }
    private data class FileIdentity(val device: Long, val inode: Long)
    private class OpenClaim(var identity: FileIdentity?, val writable: Boolean) {
        val owner: Thread = Thread.currentThread()
        val finished = CountDownLatch(1)
    }
    private class OpenDescription(
        val input: InputStream? = null,
        val output: OutputStream? = null,
        val channel: SeekableByteChannel? = null,
        val identity: FileIdentity? = null,
        val readable: Boolean = false,
        val writable: Boolean = false,
        val append: Boolean = false,
        val readiness: Readiness = Readiness.UNAVAILABLE
    ) {
        // References are protected by the registry. IO and physical retirement
        // share this monitor; the registry never waits for it while locked.
        var references = 1L
        var closed = false
    }
    private class Descriptor(val owner: OpenDescription) {
        // Protected by the registry, independently of the shared IO lifetime.
        var closed = false
    }
    private data class Failure(val kind: Long, val message: String)
    private class FileFailure(val kind: Long, message: String) : IOException(message)
    private val failure = ThreadLocal.withInitial { Failure(0, "") }
    private val descriptors = linkedMapOf<Long, Descriptor>(
        0L to Descriptor(OpenDescription(input = env.`in`(), readable = true)),
        1L to Descriptor(OpenDescription(output = env.out(), writable = true)),
        2L to Descriptor(OpenDescription(output = env.err(), writable = true)))
    // Includes zero-reference owners until their provider callback has returned.
    // These are THC open-admission claims, not GHC's RTS unlockFile protocol.
    private val owners = descriptors.values.mapTo(linkedSetOf()) { it.owner }
    private val opening = mutableListOf<OpenClaim>()
    private var nextDescriptor = 3L // Preserve the private open API's non-reuse guarantee.
    private var disposed = false

    init { require(descriptorLimit in 3L..(Int.MAX_VALUE.toLong() + 1)) }

    // Portable error categories, deliberately not host errno numbers. A successful
    // operation does not clear the last error; queries cannot destroy the message.
    @TruffleBoundary fun errorKind(): Long = failure.get().kind
    @TruffleBoundary fun errorMessage(): ManagedAddress {
        val bytes = failure.get().message.toByteArray(Charsets.UTF_8)
        return ManagedAddress.fromHex(bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
    }

    private fun fail(kind: Long, message: String): Nothing = throw FileFailure(kind, message)
    private fun descriptor(fd: Long): Descriptor = synchronized(this) {
        descriptors[fd] ?: fail(4, "Closed or unknown THC file descriptor: $fd")
    }

    private inline fun <T> withDescriptor(fd: Long, action: (OpenDescription) -> T): T {
        val entry = descriptor(fd)
        return synchronized(entry.owner) {
            synchronized(this) {
                if (entry.closed || descriptors[fd] !== entry)
                    fail(4, "Closed or unknown THC file descriptor: $fd")
            }
            action(entry.owner)
        }
    }

    // Caller holds the registry. Sparse descriptor maps never allocate a table
    // proportional to dup2's target. This namespace is not the host RLIMIT_NOFILE.
    private fun unusedDescriptor(first: Long): Long {
        var fd = first
        while (fd < descriptorLimit && descriptors.containsKey(fd)) fd++
        if (fd == descriptorLimit) fail(10, "THC file descriptor space exhausted")
        return fd
    }

    private fun retire(owner: OpenDescription) = synchronized(owner) {
        if (!owner.closed) {
            owner.closed = true
            try {
                owner.channel?.close()
                owner.output?.flush() // Embedding streams are never closed.
            } finally { synchronized(this) { owners.remove(owner) } }
        }
    }

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

    private fun requireUnclaimed(identity: FileIdentity, writable: Boolean, name: String, own: OpenClaim? = null) {
        for (other in owners) {
            if (other.identity == identity && (writable || other.writable))
                fail(8, "File already has an incompatible reader/writer in this THC context: $name")
        }
        for (other in opening) {
            if (other !== own && other.identity == identity && (writable || other.writable))
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
    @TruffleBoundary fun open(path: ManagedAddress, mode: Long): Long {
        val name = path.utf8() // Bad managed memory is a runtime fault, not IOException.
        return result {
            if (mode !in 0L..3L) fail(5, "Unknown THC open mode: $mode")
            if (name.isEmpty()) fail(1, "Empty file path")
            synchronized(this) {
                if (disposed) fail(4, "THC file context is closed")
                unusedDescriptor(nextDescriptor)
            }
            val file = env.getPublicTruffleFile(name)
            val writable = mode != 0L
            val before = try { identity(file, name) } catch (_: NoSuchFileException) { null }
            val options = when (mode) {
                0L -> setOf(StandardOpenOption.READ)
                2L -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                3L -> setOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
                else -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            }
            val claim = OpenClaim(before, writable)
            var channel: SeekableByteChannel? = null
            try {
                synchronized(this) {
                    if (disposed) fail(4, "THC file context is closed")
                    unusedDescriptor(nextDescriptor)
                    if (before != null) requireUnclaimed(before, writable, name)
                    opening.add(claim)
                }
                // Providers may block or enter guest code. The pending inode
                // claim protects truncation without holding the registry lock.
                val acquired = file.newByteChannel(options)
                channel = acquired
                val after = identity(file, name)
                if (before != null && before != after) fail(8, "File identity changed while opening: $name")
                synchronized(this) {
                    if (disposed) fail(4, "THC file context is closed")
                    requireUnclaimed(after, writable, name, claim)
                    claim.identity = after
                }
                if (mode == 1L) acquired.truncate(0)
                val fd = synchronized(this) {
                    if (disposed) fail(4, "THC file context is closed")
                    requireUnclaimed(after, writable, name, claim)
                    val allocated = unusedDescriptor(nextDescriptor)
                    nextDescriptor = allocated + 1
                    val owner = OpenDescription(channel = acquired, identity = after,
                        readable = mode == 0L || mode == 3L, writable = writable, append = mode == 2L,
                        readiness = Readiness.REGULAR_FILE)
                    owners.add(owner)
                    descriptors[allocated] = Descriptor(owner)
                    opening.remove(claim)
                    allocated
                }
                channel = null // The descriptor now owns the channel.
                fd
            } catch (error: Throwable) {
                try { channel?.close() } catch (closing: Throwable) { error.addSuppressed(closing) }
                throw error
            } finally {
                synchronized(this) { opening.remove(claim) }
                claim.finished.countDown()
            }
        }
    }

    @TruffleBoundary fun read(fd: Long, address: ManagedAddress, count: Long): Long {
        address.requireRange(0, count, true) // Validate the whole destination before consuming input.
        return result { withDescriptor(fd) { entry ->
            if (!entry.readable) fail(4, "THC file descriptor is not readable: $fd")
            if (count == 0L) 0L else {
                val bytes = address.rawBacking()
                val offset = address.cbitsOffset().toInt()
                val n = entry.input?.read(bytes, offset, count.toInt())
                    ?: entry.channel!!.read(ByteBuffer.wrap(bytes, offset, count.toInt()))
                if (n < 0) 0L else if (n == 0) fail(6, "THC input made no progress") else n.toLong()
            }
        } }
    }

    /** The pinned POSIX fdReady reports any poll event, including POLLNVAL,
     * as ready. Regular files are ready in either direction, even at EOF or
     * with an incompatible open mode: the following transfer reports errors.
     * Do not acquire a descriptor's IO monitor to make this nonblocking probe.
     * Opaque embedding streams have no readiness contract and are not process
     * fd0/fd1/fd2. Negative descriptors are ignored by poll; only their zero
     * timeout probe is supported until a real cancellable wait service exists. */
    @TruffleBoundary fun ready(fd: Long, milliseconds: Long): Long {
        if (fd < 0L) {
            if (milliseconds != 0L) fault("Original fdReady cannot wait on an ignored negative descriptor")
            return 0L
        }
        return result {
            val readiness = synchronized(this) { descriptors[fd]?.owner?.readiness }
            when (readiness) {
                null, Readiness.REGULAR_FILE -> 1L
                Readiness.UNAVAILABLE -> fail(7, "THC stream has no readiness contract: $fd")
            }
        }
    }

    @TruffleBoundary fun write(fd: Long, address: ManagedAddress, count: Long): Long {
        address.requireRange(0, count)
        return result { withDescriptor(fd) { entry ->
            if (!entry.writable) fail(4, "THC file descriptor is not writable: $fd")
            if (count == 0L) 0L else {
                val bytes = address.rawBacking()
                val offset = address.cbitsOffset().toInt()
                if (entry.output != null) {
                    entry.output.write(bytes, offset, count.toInt())
                    count
                } else entry.channel!!.write(ByteBuffer.wrap(bytes, offset, count.toInt())).toLong()
            }
        } }
    }

    /** Logical close consumes the descriptor even if an underlying close fails;
     * callers must not retry it. Embedding streams are flushed, never closed. */
    @TruffleBoundary fun close(fd: Long): Long = result {
        val entry = descriptor(fd)
        synchronized(entry.owner) {
            val last = synchronized(this) {
                if (descriptors[fd] !== entry || entry.closed)
                    fail(4, "Closed or unknown THC file descriptor: $fd")
                descriptors.remove(fd)
                entry.closed = true
                --entry.owner.references == 0L
            }
            if (last) retire(entry.owner)
            0L
        }
    }

    /** Independent descriptor lifetime, shared offset/status and IO monitor.
     * Only this context's CInt namespace is duplicated, never a host descriptor. */
    @TruffleBoundary fun duplicate(fd: Long): Long = result { synchronized(this) {
        val source = descriptors[fd] ?: fail(4, "Closed or unknown THC file descriptor: $fd")
        val target = unusedDescriptor(0)
        source.owner.references++
        descriptors[target] = Descriptor(source.owner)
        target
    } }

    /** Install atomically before retiring the replaced owner. A target close
     * IOException cannot undo replacement or change the successful result. */
    @TruffleBoundary fun duplicateTo(fd: Long, target: Long): Long = result {
        val retired = synchronized(this) {
            val source = descriptors[fd] ?: fail(4, "Closed or unknown THC file descriptor: $fd")
            if (target < 0 || target >= descriptorLimit) fail(4, "THC dup2 target is out of range: $target")
            if (fd == target) return@result target
            source.owner.references++
            val old = descriptors.put(target, Descriptor(source.owner))
            if (old != null) {
                old.closed = true
                if (--old.owner.references == 0L) old.owner else null
            } else null
        }
        if (retired != null) try { retire(retired) } catch (_: IOException) {
            // Like native dup2, target-close failures are deliberately unobservable.
        }
        target
    }

    /** Absolute0/Relative1/End2, with checked signed offsets. */
    @TruffleBoundary fun seek(fd: Long, offset: Long, mode: Long): Long = result { withDescriptor(fd) { entry ->
        val channel = entry.channel ?: fail(7, "THC stream is not seekable: $fd")
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
    } }

    @TruffleBoundary fun size(fd: Long): Long = result { withDescriptor(fd) { entry ->
        (entry.channel ?: fail(7, "THC stream has no file size: $fd")).size()
    } }

    @TruffleBoundary fun setSize(fd: Long, length: Long): Long = resize(fd, length, false)

    /** Original ftruncate reports EINVAL for a known read-only or stream fd.
     * Classify it while holding that descriptor's lock, without probing the
     * provider or racing a concurrent close. The managed API retains EBADF. */
    @TruffleBoundary fun truncateOriginal(fd: Long, length: Long): Long = resize(fd, length, true)

    private fun resize(fd: Long, length: Long, original: Boolean): Long = result { withDescriptor(fd) { entry ->
        val channel = entry.channel ?: fail(if (original) 5 else 7, "Cannot resize a THC stream: $fd")
        if (!entry.writable) fail(if (original) 5 else 4, "THC file descriptor is not writable: $fd")
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
    } }

    // Env exposes byte streams, not terminal handles. Do not infer a terminal
    // from System.console(): it may be unrelated to the embedding's streams.
    @TruffleBoundary fun isTerminal(fd: Long): Long = result { withDescriptor(fd) { 0L } }
    @TruffleBoundary fun deviceType(fd: Long): Long = result { withDescriptor(fd) { entry ->
        if (entry.channel != null) 0L else 1L
    } }

    /** Final disposal cannot run Haskell finalizers or flush GHC's own buffers.
     * Release every owned channel even if one close fails. */
    @TruffleBoundary fun dispose() {
        val (entries, pending) = synchronized(this) {
            if (disposed) return
            disposed = true
            val pending = opening.toList()
            opening.clear()
            val entries = owners.toList()
            descriptors.values.forEach { it.closed = true }
            entries.forEach { it.references = 0L }
            descriptors.clear()
            entries to pending
        }
        var failed: Throwable? = null
        // Context teardown may invoke an embedding stream after this context's
        // guest registry has closed. A callback into another context remains
        // under an opaque Java frame and must retain that async origin.
        val previous = threads.enterForeign()
        try {
            for (entry in entries) try {
                retire(entry)
            } catch (error: Throwable) {
                if (failed == null) failed = error else if (failed !== error) failed.addSuppressed(error)
            }
            // A provider may still be returning a newly acquired channel. Its
            // open observes disposed and closes it before signaling completion.
            // Do not wait for this thread's own reentrant provider callback.
            var interrupted = false
            for (claim in pending) if (claim.owner !== Thread.currentThread()) {
                while (true) try {
                    claim.finished.await()
                    break
                } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        } finally { threads.leaveForeign(previous) }
        failure.remove()
        if (failed is IOException)
            throw RuntimeFault("THC file disposal failed: ${failed.message}").also { it.initCause(failed) }
        if (failed != null) throw failed
    }
}
