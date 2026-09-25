// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleFile
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.Node
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.channels.ClosedChannelException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import thc.Language
import thc.NativeIO.StandardEndpoint

/** Explicit thc_io_v1 service, not a POSIX ABI. Descriptors and reader/writer
 * claims belong to one THC context. Regular files and the embedding's streams
 * are supported, with native resources only in an explicit NativeIO context.
 * Transfers are synchronous. Native readiness has a separate cancellable wait
 * capability; this is not a scheduler or interruptible foreign byte transport. */
internal class ManagedFiles(private val env: TruffleLanguage.Env, private val threads: GuestThreads,
    private val descriptorLimit: Long = Int.MAX_VALUE.toLong() + 1,
    // Internal protocol-test seam; the production notifier is only eventfd IO.
    private val signalReadinessClose: (NativeFdWait) -> Unit = { it.descriptorClosed() }) {
    private enum class Readiness { REGULAR_FILE, UNAVAILABLE, NATIVE_UNCLASSIFIED }
    private data class FileIdentity(val device: Long, val inode: Long)
    private class OpenClaim(var identity: FileIdentity?, val writable: Boolean, val reserved: Long? = null) {
        val owner: Thread = Thread.currentThread()
        val finished = CountDownLatch(1)
    }
    private class OpenDescription(
        val input: InputStream? = null,
        val output: OutputStream? = null,
        val channel: SeekableByteChannel? = null,
        val native: NativeFileResource? = null,
        val identity: FileIdentity? = null,
        val readable: Boolean = false,
        val writable: Boolean = false,
        val append: Boolean = false,
        val canExtend: Boolean = true,
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
        val readinessWaits = linkedSetOf<NativeFdWait>()
    }
    private data class Failure(val kind: Long, val message: String, val nativeErrno: Long = 0)
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
    private var nativeProvider: NativeFileProvider? = null
    private val nativeAbi by lazy { StdioHostAbi.load() }

    init { require(descriptorLimit in 3L..(Int.MAX_VALUE.toLong() + 1)) }

    // Portable error categories, deliberately not host errno numbers. A successful
    // operation does not clear the last error; queries cannot destroy the message.
    @TruffleBoundary fun errorKind(): Long = failure.get().kind
    @TruffleBoundary internal fun nativeErrno(): Long = failure.get().nativeErrno
    @TruffleBoundary fun errorMessage(): ManagedAddress {
        val bytes = failure.get().message.toByteArray(Charsets.UTF_8)
        return ManagedAddress.fromHex(bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
    }

    private fun fail(kind: Long, message: String): Nothing = throw FileFailure(kind, message)
    private fun descriptor(fd: Long): Descriptor = synchronized(this) {
        descriptors[fd] ?: fail(4, "Closed or unknown THC file descriptor: $fd")
    }

    // Registry held. Wake the original descriptor's waiters before retiring its
    // owner or publishing a reused number; aliases have distinct wait sets.
    private fun invalidate(entry: Descriptor): Throwable? {
        entry.closed = true
        var failure: Throwable? = null
        for (request in entry.readinessWaits) try { signalReadinessClose(request) }
            catch (error: Throwable) { failure = combineFailures(failure, error) }
        // A wake failure must not interrupt registry/refcount mutation or skip
        // other waiters. Callers finish retirement before surfacing this error.
        return failure
    }

    private fun combineFailures(first: Throwable?, next: Throwable?): Throwable? {
        if (first == null) return next
        if (next != null && next !== first) first.addSuppressed(next)
        return first
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
        while (fd < descriptorLimit && (descriptors.containsKey(fd) || opening.any { it.reserved == fd })) fd++
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

    private fun nativeDescription(resource: NativeFileResource, readable: Boolean,
        writable: Boolean, append: Boolean = false, canExtend: Boolean = true): OpenDescription {
        val image = ManagedAddress.fromByteArray(resource.statImage())
        val mode = PosixStat.execute(OriginalStdioOp.ST_MODE, image, 0)
        val regular = PosixStat.execute(OriginalStdioOp.IS_REG, ManagedAddress.nullAddress(), mode) == 1L
        val identity = if (regular) FileIdentity(PosixStat.execute(OriginalStdioOp.ST_DEV, image, 0),
            PosixStat.execute(OriginalStdioOp.ST_INO, image, 0)) else null
        return OpenDescription(channel = resource, native = resource, identity = identity,
            readable = readable, writable = writable, append = append, canExtend = canExtend,
            readiness = if (regular) Readiness.REGULAR_FILE else Readiness.UNAVAILABLE)
    }

    /** Only the fixed factory calls this before publishing its Context. All
     * grants are acquired before replacing any descriptor; failure rolls back
     * the acquisitions, and factory failure disposes the unpublished context. */
    internal fun installNative(provider: NativeFileProvider, endpoints: Set<StandardEndpoint>) {
        val claim = OpenClaim(null, false)
        val acquired = linkedMapOf<Long, OpenDescription>()
        val retired = mutableListOf<OpenDescription>()
        var published = false
        val previous = threads.enterForeign()
        try {
            synchronized(this) {
                check(!disposed && nativeProvider == null && nextDescriptor == 3L && opening.isEmpty())
                check(descriptors.keys == setOf(0L, 1L, 2L) && descriptors.values.all { it.owner.references == 1L })
                opening.add(claim)
            }
            for (endpoint in endpoints) {
                val resource = provider.standard(endpoint)
                try {
                    acquired[endpoint.ordinal.toLong()] = nativeDescription(resource,
                        endpoint == StandardEndpoint.INPUT, endpoint != StandardEndpoint.INPUT, canExtend = false)
                } catch (failure: Throwable) {
                    try { resource.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                    throw failure
                }
            }
            synchronized(this) {
                if (disposed) fail(4, "THC file context is closed")
                for ((fd, owner) in acquired) {
                    val old = descriptors.getValue(fd)
                    old.closed = true
                    old.owner.references = 0
                    retired.add(old.owner)
                    owners.add(owner)
                    descriptors[fd] = Descriptor(owner)
                }
                nativeProvider = provider
                published = true
            }
            // No registry lock spans a flush callback. Any failure here makes
            // the factory close the context, including the newly installed set.
            for (owner in retired) retire(owner)
            for (owner in acquired.values) owner.native!!.requireLive()
            synchronized(this) {
                if (disposed) fail(4, "THC file context was disposed during native installation")
                check(nativeProvider === provider)
            }
        } catch (failure: Throwable) {
            if (!published) for (owner in acquired.values) try { retire(owner) }
                catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        } finally {
            synchronized(this) { opening.remove(claim) }
            claim.finished.countDown()
            threads.leaveForeign(previous)
        }
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
        } catch (error: NativeFileException) {
            failure.set(Failure(nativeAbi.privateErrorKind(error.errno.toLong()),
                error.message ?: "Native file operation failed", error.errno.toLong())); -1L
        } catch (error: IOException) {
            failure.set(Failure(6, error.message ?: "File operation failed")); -1L
        } finally {
            threads.leaveForeign(previous)
        }
    }

    /** Read0/Write1/Append2/ReadWrite3. Write truncates only after the context's
     * Haskell single-writer/multiple-reader check, never while acquiring a channel.
     * Claims use provider-supplied Unix device/inode identities, not retained paths.
     * The explicit native provider instead uses authoritative opened identity.
     * Legacy pre/post-open checks detect simple replacement but cannot provide atomic
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
            val native = synchronized(this) { nativeProvider }
            if (native != null) return@result openNative(native, name, mode)
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

    private fun openNative(provider: NativeFileProvider, name: String, mode: Long): Long {
        val claim = OpenClaim(null, mode != 0L)
        var resource: OpenedNativeFile? = null
        try {
            synchronized(this) {
                if (disposed) fail(4, "THC file context is closed")
                unusedDescriptor(nextDescriptor)
                opening.add(claim)
            }
            val acquired = provider.open(name, mode.toInt())
            resource = acquired
            val owner = nativeDescription(acquired, mode == 0L || mode == 3L, mode != 0L, mode == 2L)
            val identity = owner.identity ?: fail(7, "Native acquisition is not a regular file: $name")
            synchronized(this) {
                if (disposed) fail(4, "THC file context is closed")
                requireUnclaimed(identity, owner.writable, name, claim)
                claim.identity = identity
            }
            if (mode == 1L) acquired.truncate(0)
            val fd = synchronized(this) {
                if (disposed) fail(4, "THC file context is closed")
                requireUnclaimed(identity, owner.writable, name, claim)
                val allocated = unusedDescriptor(nextDescriptor)
                nextDescriptor = allocated + 1
                owners.add(owner)
                descriptors[allocated] = Descriptor(owner)
                opening.remove(claim)
                allocated
            }
            resource = null // All dup aliases share this owner and capability.
            return fd
        } catch (failure: Throwable) {
            try { resource?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        } finally {
            synchronized(this) { opening.remove(claim) }
            claim.finished.countDown()
        }
    }

    /** Original unsafe open has no private admission claim or RTS lock. Reserve
     * the lowest descriptor before creation/truncation, but hold no registry
     * monitor over native acquisition. A completed result is never polled here. */
    @TruffleBoundary internal fun openOriginal(path: ManagedAddress, flags: Long, mode: Long): Long {
        nativeAbi.requireOpenAbi()
        if (flags != flags.toInt().toLong() || mode !in 0L..0xffffffffL)
            fault("Original open requires canonical CInt flags and Word32 mode")
        fun snapshot(): ByteArray {
            var length = 0L
            while (true) {
                path.requireByteRegion(length + 1)
                if (path.readWord8(length++) == 0L) break
            }
            return ByteArray(length.toInt()) { path.readWord8(it.toLong()).toByte() }
        }
        val allocation = path.cbitsOwner()
        val bytes = if (allocation == null) snapshot() else synchronized(allocation) { snapshot() }
        return result {
            val (provider, claim) = synchronized(this) {
                if (disposed) fail(4, "THC file context is closed")
                val provider = nativeProvider ?: fail(7, "Original open requires the explicit native filesystem")
                val claim = OpenClaim(null, nativeAbi.openWritable(flags), unusedDescriptor(0))
                opening.add(claim)
                provider to claim
            }
            var resource: OpenedNativeFile? = null
            try {
                val acquired = provider.openRaw(bytes, flags.toInt(), mode)
                resource = acquired
                val owner = OpenDescription(channel = acquired, native = acquired,
                    readable = nativeAbi.openReadable(flags), writable = nativeAbi.openWritable(flags),
                    append = nativeAbi.openAppend(flags), readiness = Readiness.NATIVE_UNCLASSIFIED)
                val fd = synchronized(this) {
                    if (disposed) fail(4, "THC file context is closed")
                    val fd = claim.reserved!!
                    check(claim in opening && fd !in descriptors)
                    owners.add(owner)
                    descriptors[fd] = Descriptor(owner)
                    opening.remove(claim)
                    fd
                }
                resource = null
                fd
            } catch (failure: Throwable) {
                try { resource?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                throw failure
            } finally {
                synchronized(this) { opening.remove(claim) }
                claim.finished.countDown()
            }
        }
    }

    // Owner monitor is held. Raw acquisition must not become a failed open
    // merely because a subsequent metadata observation fails.
    private fun regular(entry: OpenDescription): Boolean = when (entry.readiness) {
        Readiness.REGULAR_FILE -> true
        Readiness.UNAVAILABLE -> false
        Readiness.NATIVE_UNCLASSIFIED -> {
            val image = ManagedAddress.fromByteArray(entry.native!!.statImage())
            PosixStat.execute(OriginalStdioOp.IS_REG, ManagedAddress.nullAddress(),
                PosixStat.execute(OriginalStdioOp.ST_MODE, image, 0)) == 1L
        }
    }

    /** Internal opened-resource capability, not original fstat admission. The
     * descriptor identity is revalidated under its shared owner before access. */
    @TruffleBoundary internal fun statImage(fd: Long): ByteArray {
        val previous = threads.enterForeign()
        return try { withDescriptor(fd) { entry ->
            (entry.native ?: throw UnsupportedOperationException("THC descriptor has no opened-resource metadata: $fd")).statImage()
        } } finally { threads.leaveForeign(previous) }
    }

    /** Complete destination validation precedes native observation. Match the
     * existing IO lock order: descriptor owner, then mutable allocation. Holding
     * the latter across snapshot/copy prevents shrink or pointer-cell races. */
    @TruffleBoundary internal fun fstat(fd: Long, destination: ManagedAddress): Long {
        val size = PosixStat.execute(OriginalStdioOp.SIZEOF_STAT, ManagedAddress.nullAddress(), 0)
        destination.requireByteRegion(size, writable = true)
        return result { withDescriptor(fd) { entry ->
            fun copyImage(): Long {
                destination.requireByteRegion(size, writable = true)
                val resource = entry.native ?: fail(7, "THC descriptor has no opened-resource metadata: $fd")
                val image = resource.statImage()
                if (image.size.toLong() != size) fault("Native stat image has the wrong size")
                ManagedAddress.fromByteArray(image).copyNonOverlappingTo(destination, size)
                return 0L
            }
            val allocation = destination.cbitsOwner()
            if (allocation == null) copyImage() else synchronized(allocation) { copyImage() }
        } }
    }

    /** Same owner/allocation lock order as fstat. The staging bytes start with
     * the original image and copy back even on a returned libc error. Neither
     * padding nor the failed-call buffer is synthesized by the adapter. */
    @TruffleBoundary internal fun tcgetattr(fd: Long, destination: ManagedAddress): Long {
        val size = TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS, ManagedAddress.nullAddress(), 0)
        destination.requireByteRegion(size, writable = true)
        return result { withDescriptor(fd) { entry ->
            val resource = entry.native ?: fail(7, "THC descriptor has no native terminal capability: $fd")
            TermiosImage.transfer(destination, copyBack = true) { image ->
                resource.readTermios(image)
                0L
            }
        } }
    }

    /** Snapshot the full const input under the same owner/allocation order as
     * tcgetattr. No write permission or copyback: libc only reads this image. */
    @TruffleBoundary internal fun tcsetattr(fd: Long, action: Int, source: ManagedAddress): Long {
        val size = TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS, ManagedAddress.nullAddress(), 0)
        source.requireByteRegion(size, writable = false)
        return result { withDescriptor(fd) { entry ->
            val resource = entry.native ?: fail(7, "THC descriptor has no native terminal capability: $fd")
            TermiosImage.transfer(source, copyBack = false) { image ->
                resource.writeTermios(action, image)
                0L
            }
        } }
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

    /** Stable logical identity for a future resumable wait primop. The token
     * holds no native fd between attempts: async unwinding unregisters and closes
     * the current native request. Retrying this token never resolves fd again.
     * Original blockedOnBadFD payload linkage is a separate admission obligation;
     * this internal substrate does not substitute a synthetic Haskell exception. */
    internal inner class WaitToken internal constructor(private val fd: Long, private val writing: Boolean) {
        private val entry = descriptor(fd)

        @TruffleBoundary internal fun await(node: Node, async: Boolean) {
            if (Language.currentState(node).env !== env) fault("Descriptor wait belongs to another context")
            val outcome = awaitReady(entry, fd, writing, -1, node) {
                if (async) threads.poll(node, interruptible = true)?.let { throw AsyncBlocked(it, node) }
            }
            if (outcome == -2) throw ClosedChannelException()
        }
    }

    @TruffleBoundary internal fun waitToken(fd: Long, writing: Boolean): WaitToken {
        if (fd != fd.toInt().toLong()) fault("Descriptor wait requires a signed CInt descriptor")
        return WaitToken(fd, writing)
    }

    private fun awaitReady(entry: Descriptor, fd: Long, writing: Boolean, milliseconds: Long,
                           node: Node?, beforeBlock: (() -> Unit)? = null): Int {
        val native = synchronized(this) {
            if (disposed || entry.closed || descriptors[fd] !== entry) return -2
            if (entry.owner.readiness == Readiness.REGULAR_FILE) return 1
            entry.owner.native ?: fail(7, "THC stream has no readiness contract: $fd")
        }
        val request = try { native.readinessWait() }
            catch (_: ClosedChannelException) { return -2 }
        try {
            synchronized(this) {
                if (disposed || entry.closed || descriptors[fd] !== entry) return -2
                entry.readinessWaits.add(request)
            }
            // Original safe/unsafe fdReady remains an opaque foreign extent.
            // Only the explicit managed wait token reports RTS read/write wait.
            if (beforeBlock == null) return request.await(node, writing, milliseconds)
            return GuestThreads.blocking(if (writing) GuestThreadStatus.WRITE else GuestThreadStatus.READ)
                .use { request.await(node, writing, milliseconds, beforeBlock) }
        } finally {
            // Unregister before freeing eventfd: close/dup2/dispose signal under
            // this same registry lock, and Truffle has restored its blocked state.
            synchronized(this) { entry.readinessWaits.remove(request) }
            request.close()
        }
    }

    /** Test observer only; guest execution does not wait by polling this count. */
    @Synchronized internal fun pendingReadiness(fd: Long): Int = descriptors[fd]?.readinessWaits?.size ?: 0

    /** The pinned POSIX fdReady reports any poll event, including POLLNVAL,
     * as ready. Regular files are ready in either direction, even at EOF or
     * with an incompatible open mode: the following transfer reports errors.
     * Do not acquire a descriptor's IO monitor to make this nonblocking probe.
     * Opaque embedding streams have no readiness contract and are not process
     * fd0/fd1/fd2. Negative descriptors are ignored by poll; only their zero
     * timeout probe is currently admitted. Native waits observe the opened
     * resource, with a separate cancellation wake; opaque streams stay denied. */
    @TruffleBoundary fun ready(fd: Long, milliseconds: Long, writing: Boolean = false, node: Node? = null): Long {
        if (fd < 0L) {
            if (milliseconds != 0L) fault("Original fdReady cannot wait on an ignored negative descriptor")
            return 0L
        }
        return result {
            val entry = synchronized(this) { descriptors[fd] } ?: return@result 1L
            val ready = awaitReady(entry, fd, writing, milliseconds, node)
            if (ready == -2) 1L else ready.toLong()
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
        var failure: Throwable? = null
        val last = synchronized(this) {
            if (descriptors[fd] !== entry || entry.closed)
                fail(4, "Closed or unknown THC file descriptor: $fd")
            descriptors.remove(fd)
            failure = invalidate(entry)
            --entry.owner.references == 0L
        }
        // A blocked byte transfer may delay physical retirement, but must not
        // prevent waiters from observing logical close and releasing their pins.
        if (last) try { retire(entry.owner) }
            catch (error: Throwable) { failure = combineFailures(failure, error) }
        failure?.let { throw it }
        0L
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
    @TruffleBoundary fun duplicateTo(fd: Long, target: Long): Long {
        var retired: OpenDescription? = null
        var wakeFailure: Throwable? = null
        val installed = result {
            retired = synchronized(this) {
                val source = descriptors[fd] ?: fail(4, "Closed or unknown THC file descriptor: $fd")
                if (target < 0 || target >= descriptorLimit) fail(4, "THC dup2 target is out of range: $target")
                if (fd == target) return@result target
                if (opening.any { it.reserved == target }) fail(8, "THC dup2 target is being acquired: $target")
                source.owner.references++
                val old = descriptors.put(target, Descriptor(source.owner))
                if (old != null) {
                    wakeFailure = invalidate(old)
                    if (--old.owner.references == 0L) old.owner else null
                } else null
            }
            target
        }
        val owner = retired
        if (owner != null) {
            // The replacement is committed. An unchecked provider failure must
            // escape, not become -1/errno implying an unchanged target. Keep the
            // opaque callback foreign, outside result's exception conversion.
            val previous = threads.enterForeign()
            try { retire(owner) } catch (_: IOException) {
                // Like native dup2, target-close IO failures are unobservable.
            } catch (error: Throwable) {
                wakeFailure = combineFailures(wakeFailure, error)
            } finally { threads.leaveForeign(previous) }
        }
        // Outside result: a post-commit wake/provider failure cannot masquerade
        // as -1/errno meaning that the target descriptor was left unchanged.
        wakeFailure?.let { throw it }
        return installed
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
        if (!regular(entry)) fail(7, "THC stream has no file size: $fd")
        (entry.channel ?: fail(7, "THC stream has no file size: $fd")).size()
    } }

    @TruffleBoundary fun setSize(fd: Long, length: Long): Long = resize(fd, length, false)

    /** Original ftruncate reports EINVAL for a known read-only or stream fd.
     * Classify it while holding that descriptor's lock, without probing the
     * provider or racing a concurrent close. The managed API retains EBADF. */
    @TruffleBoundary fun truncateOriginal(fd: Long, length: Long): Long = resize(fd, length, true)

    private fun resize(fd: Long, length: Long, original: Boolean): Long = result { withDescriptor(fd) { entry ->
        if (!regular(entry))
            fail(if (original) 5 else 7, "Cannot resize a THC stream: $fd")
        val channel = entry.channel ?: fail(if (original) 5 else 7, "Cannot resize a THC stream: $fd")
        if (!entry.writable) fail(if (original) 5 else 4, "THC file descriptor is not writable: $fd")
        if (length < 0) fail(5, "Negative THC file size")
        val oldSize = channel.size()
        if (length > oldSize && (entry.append || !entry.canExtend))
            fail(7, "Extending an append-mode file or native standard endpoint is not supported")
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
    @TruffleBoundary fun isTerminal(fd: Long): Long = result { withDescriptor(fd) { entry ->
        if (entry.native != null && !regular(entry))
            fail(7, "Native endpoint terminal status requires an actual terminal query: $fd")
        0L
    } }
    @TruffleBoundary fun deviceType(fd: Long): Long = result { withDescriptor(fd) { entry ->
        if (regular(entry)) 0L else 1L
    } }

    /** Final disposal cannot run Haskell finalizers or flush GHC's own buffers.
     * Release every owned channel even if one close fails. */
    @TruffleBoundary fun dispose() {
        var failed: Throwable? = null
        val (entries, pending) = synchronized(this) {
            if (disposed) return
            disposed = true
            val pending = opening.toList()
            opening.clear()
            val entries = owners.toList()
            descriptors.values.forEach { failed = combineFailures(failed, invalidate(it)) }
            entries.forEach { it.references = 0L }
            descriptors.clear()
            entries to pending
        }
        // Context teardown may invoke an embedding stream after this context's
        // guest registry has closed. A callback into another context remains
        // under an opaque Java frame and must retain that async origin.
        val previous = threads.enterForeign()
        try {
            for (entry in entries) try {
                retire(entry)
            } catch (error: Throwable) {
                failed = combineFailures(failed, error)
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
        val disposalFailure = failed
        if (disposalFailure is IOException)
            throw RuntimeFault("THC file disposal failed: ${disposalFailure.message}").also { it.initCause(disposalFailure) }
        if (disposalFailure != null) throw disposalFailure
    }
}
