// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnsupportedMessageException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.io.ByteSequence
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.Context
import java.io.Closeable
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.nio.file.OpenOption
import thc.Language
import thc.NativeIO.StandardEndpoint
import thc.NativeFileSystem
import thc.NativeIO
import thc.ContextProfile
import thc.FfiMode
import thc.withContextProfile
import thc.withFfiMode

/** Linux provider proof only. Acquisition is reachable solely through the
 * explicitly configured NativeFileSystem request, never from a guest fd.
 * No original FCall recognition or automatic replacement of Env streams. */
internal class NativeFileProvider private constructor(private val env: TruffleLanguage.Env, private val threads: GuestThreads, private val directory: NativeDirectoryOwner) : Closeable {
    companion object {
        /** No arbitrary Builder, FileSystem, provider attachment, or global map.
         * The factory knows the exact final provider whose channel it authenticates. */
        internal fun createContext(endpoints: Set<StandardEndpoint>,
                                   profile: ContextProfile = ContextProfile.NATIVE,
                                   ffiMode: FfiMode = FfiMode.NATIVE,
                                   allowProcesses: Boolean = false): Context {
            if (!NativeIO.supportedPosixHost())
                throw UnsupportedOperationException("Native files are currently verified only on Linux x86_64")
            val filesystem = NativeFileSystem(endpoints)
            val context = try {
                Context.newBuilder("thc").allowNativeAccess(true).allowCreateProcess(allowProcesses)
                    .allowIO(IOAccess.newBuilder().fileSystem(filesystem).build())
                    .withContextProfile(profile).withFfiMode(ffiMode).build()
            } catch (failure: Throwable) {
                try { filesystem.directoryOwner.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                throw failure
            }
            try {
                context.initialize("thc"); context.enter()
                try {
                    val state = Language.currentState()
                    check(state.nativeFiles == null)
                    val provider = NativeFileProvider(state.env, state.threads, filesystem.directoryOwner)
                    state.files.installNative(provider, endpoints)
                    state.nativeFiles = provider
                    if (profile == ContextProfile.LAUNCHER) state.signals.authorizeLauncher()
                } finally { context.leave() }
                return context
            } catch (failure: Throwable) {
                try { context.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                try { filesystem.directoryOwner.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                throw failure
            }
        }
        internal fun current(): NativeFileProvider = Language.currentState().let { state ->
            state.nativeFiles?.takeIf { it.env === state.env }
                ?: throw SecurityException("Native files require the explicit fixed-filesystem NativeIO context")
        }
    }
    private val interop = InteropLibrary.getUncached()
    private val library: Any
    private val leases = linkedSetOf<NativeFileLease>()
    private var disposed = false
    internal val directoryStreams = NativeDirectoryStreams(directory)
    private var processService: ManagedProcesses? = null
    internal val processes: ManagedProcesses
        @Synchronized get() {
            current()
            if (disposed) throw ClosedChannelException()
            return processService ?: ManagedProcesses(directory).also { processService = it }
        }
    private val statSize: Int
    private val termiosSize: Int

    init {
        if (!env.isNativeAccessAllowed || !env.isFileIOAllowed)
            throw SecurityException("Native files require explicit file IO and native access")
        if (!NativeIO.supportedPosixHost())
            throw UnsupportedOperationException("Native files are currently verified only on Linux x86_64")
        val bytes = javaClass.getResourceAsStream("/thc/native/native-file-api.so")?.use { it.readBytes() }
            ?: fault("Missing native file provider bridge")
        library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(bytes), "native-file-api.so").build()).call()
        statSize = interop.asLong(interop.execute(interop.readMember(library, "thc_file_stat_size"))).toInt()
        val expected = PosixStat.execute(OriginalStdioOp.SIZEOF_STAT, ManagedAddress.nullAddress(), 0)
        if (statSize.toLong() != expected) fault("Native file provider/stat image ABI mismatch")
        termiosSize = interop.asLong(interop.execute(interop.readMember(library, "thc_file_termios_size"))).toInt()
        if (termiosSize.toLong() != TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS, ManagedAddress.nullAddress(), 0))
            fault("Native file provider/termios image ABI mismatch")
        env.registerOnDispose(this)
    }

    private fun current() {
        if (Language.currentState().env !== env) fault("Native file resource belongs to another context")
    }

    private fun result(name: String, vararg arguments: Any): Long {
        current()
        val previous = threads.enterForeign()
        try {
            NativeLimbScope().use { scope ->
                val error = scope.allocate(8)
                val value = interop.asLong(interop.execute(interop.readMember(library, "thc_file_$name"), *arguments, error))
                val errno = error.readWord(0)
                if (errno != 0L) {
                    if (errno !in 1L..Int.MAX_VALUE.toLong() || value != -1L) fault("Invalid native file result")
                    throw NativeFileException(name, errno.toInt())
                }
                if (name == "open" && value == -2L)
                    throw UnsupportedOperationException("Native file acquisition currently supports regular files only")
                if (value < 0) fault("Negative native file success")
                return value
            }
        } finally { threads.leaveForeign(previous) }
    }

    private fun acquire(readable: Boolean, writable: Boolean, action: (NativeFileLease) -> Unit): NativeResource {
        current()
        val lease = NativeFileLease()
        try {
            synchronized(this) {
                if (disposed) throw ClosedChannelException()
                leases.add(lease)
            }
            synchronized(lease) { action(lease); lease.requireOpen() }
            synchronized(this) { if (disposed) throw ClosedChannelException() }
            return NativeResource(lease, readable, writable)
        } catch (failure: Throwable) {
            try { retire(lease) } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    /** Only the configured public FileSystem can complete an acquisition. */
    fun open(path: String, mode: Int): OpenedNativeFile {
        val options: Set<OpenOption> = when (mode) {
            0 -> setOf(StandardOpenOption.READ)
            1 -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            2 -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            3 -> setOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            else -> throw IllegalArgumentException("Invalid native open mode")
        }
        return opened(path, NativeOpenRequest(null, options) { pathValue, anchor -> acquireFile(pathValue!!, mode, anchor!!) }, options)
    }

    /** The public path is only the fixed provider's dispatch/CWD anchor. Guest
     * pathname bytes never pass through String or a charset decoder. */
    fun openRaw(path: ByteArray, flags: Int, mode: Long, operation: OriginalStdioOp = OriginalStdioOp.OPEN,
                node: Node? = null): OpenedNativeFile {
        check(operation.opening)
        val abi = StdioHostAbi.load()
        val readable = abi.openReadable(flags.toLong())
        val writable = abi.openWritable(flags.toLong())
        val options: Set<OpenOption> = emptySet()
        return opened(".", NativeOpenRequest(null, options) { _, anchor ->
            val directoryBorrow = checkNotNull(anchor)
            val bytes = path
            acquire(readable, writable) { lease ->
                if (operation == OriginalStdioOp.OPEN) NativeLimbScope().use { scope ->
                    val name = scope.allocate((bytes.size.toLong() + 7) and -8L)
                    name.copyFrom(bytes, 0, bytes.size)
                    result("open_raw", lease, directoryBorrow.lease, name, flags, mode.toInt())
                } else NativeOpenOperation(bytes, flags, mode.toInt(), directoryBorrow.descriptor).use { request ->
                    request.await(node, threads, operation == OriginalStdioOp.OPEN_INTERRUPTIBLE, lease)
                }
            }
        }, options)
    }

    /** This provider exists only in the factory-owned full-host filesystem.
     * Resolve its context CWD, preserving the guest's raw bytes and ./.. path
     * components. No preliminary stat/access check races with the native unlink. */
    @Synchronized fun unlinkRaw(path: ByteArray): Long {
        current()
        if (disposed) throw ClosedChannelException()
        val bytes = path
        return directory.borrow().use { anchor -> NativeLimbScope().use { scope ->
            val name = scope.allocate((bytes.size.toLong() + 7) and -8L)
            name.copyFrom(bytes, 0, bytes.size)
            result("unlink", anchor.lease, name)
        }
        }
    }

    /** Only the link pathname is anchored. Relative/absolute target bytes are
     * data for symlink itself and must remain exactly as supplied. */
    @Synchronized fun symlinkRaw(target: ByteArray, path: ByteArray): Long {
        current()
        if (disposed) throw ClosedChannelException()
        val bytes = path
        return directory.borrow().use { anchor -> NativeLimbScope().use { scope ->
            val targetName = scope.allocate((target.size.toLong() + 7) and -8L)
            targetName.copyFrom(target, 0, target.size)
            val name = scope.allocate((bytes.size.toLong() + 7) and -8L)
            name.copyFrom(bytes, 0, bytes.size)
            result("symlink", anchor.lease, targetName, name)
        }
        }
    }

    /** Stage output and expose exactly the returned prefix, without a terminator. */
    @Synchronized fun readlinkRaw(path: ByteArray, capacity: Int): ByteArray {
        current()
        if (disposed) throw ClosedChannelException()
        val bytes = path
        return directory.borrow().use { anchor -> NativeLimbScope().use { scope ->
            val name = scope.allocate((bytes.size.toLong() + 7) and -8L)
            name.copyFrom(bytes, 0, bytes.size)
            val output = scope.allocate((capacity.toLong() + 7) and -8L)
            val count = result("readlink", anchor.lease, name, output, capacity.toLong())
            if (count !in 0L..capacity.toLong()) fault("Invalid native readlink result")
            ByteArray(count.toInt()).also { output.copyTo(it, 0, it.size) }
        }
        }
    }

    /** Native pathname errors precede bad-dirfd errors. Use a fixed invalid
     * descriptor, never the untrusted guest integer or an absolute pathname. */
    @Synchronized fun unlinkAtInvalidRaw(path: ByteArray, flags: Int): Long {
        current()
        if (disposed) throw ClosedChannelException()
        check(path.isNotEmpty() && path.last() == 0.toByte() && path[0] != '/'.code.toByte())
        return NativeLimbScope().use { scope ->
            val name = scope.allocate((path.size.toLong() + 7) and -8L)
            name.copyFrom(path, 0, path.size)
            result("unlinkat_invalid", name, flags)
        }
    }

    private fun statAtImage(name: String, path: ByteArray, flags: Int, lease: NativeFileLease? = null): ByteArray =
        NativeLimbScope().use { scope ->
            val bytes = scope.allocate((path.size.toLong() + 7) and -8L)
            bytes.copyFrom(path, 0, path.size)
            val image = scope.allocate(statSize.toLong())
            if (lease == null) result(name, bytes, image, flags) else result(name, lease, bytes, image, flags)
            ByteArray(statSize).also { image.copyTo(it, 0, it.size) }
        }

    @Synchronized fun statAtInvalidRaw(path: ByteArray, flags: Int): ByteArray {
        current()
        if (disposed) throw ClosedChannelException()
        check(path.isNotEmpty() && path.last() == 0.toByte() && path[0] != '/'.code.toByte())
        return statAtImage("fstatat_invalid", path, flags)
    }

    /** Empty+AT_EMPTY_PATH names the retained context-directory identity. */
    @Synchronized fun statAtRaw(path: ByteArray, flags: Int): ByteArray {
        current()
        if (disposed) throw ClosedChannelException()
        return directory.borrow().use { statAtImage("fstatat", path, flags, it.lease) }
    }

    @Synchronized fun changeDirectory(path: ByteArray): Long {
        current()
        if (disposed) throw ClosedChannelException()
        directory.change(path)
        return 0L
    }

    @Synchronized fun currentDirectory(capacity: Int): ByteArray {
        current()
        if (disposed) throw ClosedChannelException()
        return directory.name(capacity)
    }

    /** AT_FDCWD and absolute paths use the fixed context filesystem's anchor. */
    @Synchronized fun unlinkAtRaw(path: ByteArray, flags: Int): Long {
        current()
        if (disposed) throw ClosedChannelException()
        val bytes = path
        return directory.borrow().use { anchor -> NativeLimbScope().use { scope ->
            val name = scope.allocate((bytes.size.toLong() + 7) and -8L)
            name.copyFrom(bytes, 0, bytes.size)
            result("unlinkat", anchor.lease, name, flags)
        }
        }
    }

    /** access uses libc's real-ID permission semantics, including its invalid-mode
     * errors. Do not approximate it with Java readable/writable predicates. */
    @Synchronized fun accessRaw(path: ByteArray, mode: Int): Long {
        current()
        if (disposed) throw ClosedChannelException()
        val bytes = path
        return directory.borrow().use { anchor -> NativeLimbScope().use { scope ->
            val name = scope.allocate((bytes.size.toLong() + 7) and -8L)
            name.copyFrom(bytes, 0, bytes.size)
            result("access", anchor.lease, name, mode)
        }
        }
    }

    /** Preserve native mode and umask semantics; never change the process umask. */
    @Synchronized fun pathModeRaw(path: ByteArray, mode: Long, createDirectory: Boolean): Long {
        current()
        if (disposed) throw ClosedChannelException()
        val bytes = path
        return directory.borrow().use { anchor -> NativeLimbScope().use { scope ->
            val name = scope.allocate((bytes.size.toLong() + 7) and -8L)
            name.copyFrom(bytes, 0, bytes.size)
            result(if (createDirectory) "mkdir" else "chmod", anchor.lease, name, mode.toInt())
        }
        }
    }

    /** Preserve the fixed filesystem's CWD and raw pathname bytes. The staging
     * image is published only after the selected native stat call succeeds. */
    @Synchronized fun statRaw(path: ByteArray, followLinks: Boolean): ByteArray {
        current()
        if (disposed) throw ClosedChannelException()
        val bytes = path
        return directory.borrow().use { anchor -> NativeLimbScope().use { scope ->
            val name = scope.allocate((bytes.size.toLong() + 7) and -8L)
            name.copyFrom(bytes, 0, bytes.size)
            val image = scope.allocate(statSize.toLong())
            result("path_stat", anchor.lease, name, if (followLinks) 1 else 0, image)
            ByteArray(statSize).also { image.copyTo(it, 0, it.size) }
        }
        }
    }

    /** Explicit endpoint grants duplicate process endpoints into owned resources.
     * This does not associate them with arbitrary Env.in/out/err streams. */
    fun standard(endpoint: StandardEndpoint): OpenedNativeFile {
        val options: Set<OpenOption> = setOf(if (endpoint == StandardEndpoint.INPUT)
            StandardOpenOption.READ else StandardOpenOption.WRITE)
        return opened(".", NativeOpenRequest(endpoint, options) { _, _ -> acquireStandard(endpoint.ordinal) }, options)
    }

    private fun opened(path: String, request: NativeOpenRequest, options: Set<OpenOption>): OpenedNativeFile {
        current()
        var channel: SeekableByteChannel? = null
        val previous = threads.enterForeign()
        try {
            channel = env.getPublicTruffleFile(path).newByteChannel(options + request)
            return request.commit(channel)
        } catch (failure: Throwable) {
            try { channel?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            try { request.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        } finally { threads.leaveForeign(previous) }
    }

    // Only a consumed, configured FileSystem acquisition request calls these.
    private fun acquireFile(path: java.nio.file.Path, mode: Int, anchor: NativeDirectoryOwner.Borrow): NativeResource {
        // Truffle normalizes a nonempty "." path before the SPI. Literal empty
        // TruffleFile IO is rejected by Truffle before this acquisition.
        val bytes = NativeDirectoryOwner.pathBytes(if (path == java.nio.file.Path.of("")) java.nio.file.Path.of(".") else path)
        return acquire(mode == 0 || mode == 3, mode != 0) { lease -> NativeLimbScope().use { scope ->
            val name = scope.allocate((bytes.size.toLong() + 7) and -8L)
            name.copyFrom(bytes, 0, bytes.size)
            result("open", lease, anchor.lease, name, mode)
        } }
    }

    private fun acquireStandard(endpoint: Int): NativeResource = acquire(endpoint == 0, endpoint != 0) { lease ->
        require(endpoint in 0..2)
        result("standard", lease, endpoint)
    }

    /** These anonymous kernel resources need no pathname or filesystem lookup.
     * They remain available only through this explicitly authorized provider. */
    internal fun eventfd(initial: Int, flags: Int): NativeFileResource = acquire(true, true) { lease ->
        result("eventfd", lease, initial, flags)
    }

    internal fun epoll(size: Int): NativeFileResource = acquire(false, false) { lease ->
        result("epoll_create", lease, size)
    }

    internal fun pipe(): Pair<NativeFileResource, NativeFileResource> {
        var writer: NativeFileResource? = null
        try {
            val reader = acquire(true, false) { readLease ->
                writer = acquire(false, true) { writeLease -> result("pipe", readLease, writeLease) }
            }
            return reader to checkNotNull(writer)
        } catch (failure: Throwable) {
            try { writer?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    /** Adopts only a capability issued by our context's process registry; no
     * guest-supplied native descriptor enters the provider. */
    internal fun adoptProcessPipe(pipe: ManagedProcesses.Pipe, readable: Boolean, writable: Boolean): NativeFileResource {
        current()
        val lease = synchronized(this) {
            if (disposed) throw ClosedChannelException()
            pipe.takeLease()
        }
        try {
            synchronized(this) {
                if (disposed) throw ClosedChannelException()
                leases.add(lease)
            }
            lease.requireOpen()
            synchronized(this) { if (disposed) throw ClosedChannelException() }
            return NativeResource(lease, readable, writable)
        } catch (failure: Throwable) {
            try { retire(lease) } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    private fun retire(lease: NativeFileLease) {
        try { lease.close() } finally { synchronized(this) { leases.remove(lease) } }
    }

    override fun close() {
        val pending = synchronized(this) {
            if (disposed) return
            disposed = true
            leases.toList()
        }
        var failed: Throwable? = null
        try { processService?.close() } catch (failure: Throwable) { failed = failure }
        try { directoryStreams.close() } catch (failure: Throwable) {
            if (failed == null) failed = failure else if (failed !== failure) failed.addSuppressed(failure)
        }
        for (lease in pending) try { retire(lease) } catch (failure: Throwable) {
            if (failed == null) failed = failure else if (failed !== failure) failed.addSuppressed(failure)
        }
        try { directory.close() } catch (failure: Throwable) {
            if (failed == null) failed = failure else if (failed !== failure) failed.addSuppressed(failure)
        }
        if (failed != null) throw failed
    }

    /** Bytes and metadata use the very same lease; there is no pathname here.
     * The descriptor owner will eventually share this object among guest dup
     * aliases. Closing is idempotent and never re-enters the LLVM context. */
    private inner class NativeResource(private val lease: NativeFileLease,
        private val readable: Boolean, private val writable: Boolean) : NativeFileResource {
        override fun readinessWait(): NativeFdWait {
            current()
            synchronized(this@NativeFileProvider) { if (disposed) throw ClosedChannelException() }
            // Do not take the lease's IO monitor: another read may be blocked.
            // The short native lifetime lock protects only duplication/close.
            return NativeFdWait.acquire(lease)
        }
        private inline fun <T> live(action: () -> T): T = synchronized(lease) {
            current(); lease.requireOpen(); action()
        }
        override fun requireLive() = live {
            synchronized(this@NativeFileProvider) { if (disposed) throw ClosedChannelException() }
        }
        override fun unlinkAt(path: ByteArray, flags: Int): Long = live {
            NativeLimbScope().use { scope ->
                val name = scope.allocate((path.size.toLong() + 7) and -8L)
                name.copyFrom(path, 0, path.size)
                result("unlinkat", lease, name, flags)
            }
        }
        override fun statAt(path: ByteArray, flags: Int): ByteArray = live {
            statAtImage("fstatat", path, flags, lease)
        }
        override fun statImage(): ByteArray = live {
            NativeLimbScope().use { scope ->
                val image = scope.allocate(statSize.toLong())
                result("stat", lease, image)
                ByteArray(statSize).also { image.copyTo(it, 0, it.size) }
            }
        }
        override fun readTermios(image: ByteArray): Unit = live {
            if (image.size != termiosSize) fault("Native termios image has the wrong size")
            NativeLimbScope().use { scope ->
                val bytes = scope.allocate((termiosSize.toLong() + 7) and -8L)
                bytes.copyFrom(image, 0, image.size)
                try { result("tcgetattr", lease, bytes) }
                finally { bytes.copyTo(image, 0, image.size) }
            }
        }
        override fun terminalStatus(): Long = live {
            try { result("isatty", lease) }
            catch (error: NativeFileException) {
                if (error.errno == StdioHostAbi.load().notTerminal().toInt()) 0L else throw error
            }
        }
        override fun writeTermios(action: Int, image: ByteArray): Unit = live {
            if (image.size != termiosSize) fault("Native termios image has the wrong size")
            NativeLimbScope().use { scope ->
                val bytes = scope.allocate((termiosSize.toLong() + 7) and -8L)
                bytes.copyFrom(image, 0, image.size)
                result("tcsetattr", lease, action, bytes)
            }
        }
        override fun statusFlags(): Long = live { result("getfl", lease) }
        override fun setStatusFlags(flags: Long): Long = live { result("setfl", lease, flags) }
        override fun setDescriptorFlags(flags: Long): Long = live { result("setfd", lease, flags) }
        override fun writeEvent(value: Long): Long = live {
            if (!writable) throw NonWritableChannelException()
            result("eventfd_write", lease, value)
        }
        override fun duplicateDescriptor(): Int = lease.duplicateForWait()
        override fun read(destination: ByteBuffer): Int = live {
            if (!readable) throw NonReadableChannelException()
            if (destination.isReadOnly) throw ReadOnlyBufferException()
            val count = minOf(destination.remaining(), 1024 * 1024)
            if (count == 0) return@live 0
            NativeLimbScope().use { scope ->
                val bytes = scope.allocate((count.toLong() + 7) and -8L)
                val received = result("read", lease, bytes, count.toLong())
                if (received > count) fault("Native read exceeded capacity")
                if (received == 0L) -1 else {
                    val copy = ByteArray(received.toInt())
                    bytes.copyTo(copy, 0, copy.size)
                    destination.put(copy)
                    copy.size
                }
            }
        }
        override fun write(source: ByteBuffer): Int = live {
            if (!writable) throw NonWritableChannelException()
            val count = minOf(source.remaining(), 1024 * 1024)
            if (count == 0) return@live 0
            val copy = ByteArray(count).also { source.duplicate().get(it) }
            NativeLimbScope().use { scope ->
                val bytes = scope.allocate((count.toLong() + 7) and -8L)
                bytes.copyFrom(copy, 0, copy.size)
                val written = result("write", lease, bytes, count.toLong())
                if (written > count) fault("Native write exceeded capacity")
                source.position(source.position() + written.toInt())
                written.toInt()
            }
        }
        override fun position(): Long = live { result("seek", lease, 0L, 1) }
        override fun position(position: Long): SeekableByteChannel = live {
            require(position >= 0)
            result("seek", lease, position, 0)
            this
        }
        override fun size(): Long = live {
            PosixStat.execute(OriginalStdioOp.ST_SIZE, ManagedAddress.fromByteArray(statImage()), 0)
        }
        override fun truncate(size: Long): SeekableByteChannel = live {
            require(size >= 0)
            if (!writable) throw NonWritableChannelException()
            // SeekableByteChannel.truncate must not grow a shorter file.
            if (size < size()) {
                result("truncate", lease, size)
                if (position() > size) position(size)
            }
            this
        }
        override fun isOpen(): Boolean = lease.isOpen
        override fun close() = retire(lease)
    }
}

/** Private native-provider ownership; neither fd nor pointer becomes guest data.
 * Cleanup is a host downcall and remains valid after LLVM disposal. Acquisition
 * publishes only after its worker joins. Linux consumes close even on EINTR. */
@ExportLibrary(InteropLibrary::class)
internal class NativeFileLease : AutoCloseable, TruffleObject {
    // Resolve host bindings when a lease is created, not when Native Image
    // prepares this interop receiver's class for guest compilation.
    init { NativeCalls.closeFd }
    private val arena = Arena.ofShared()
    private val slot = arena.allocate(ValueLayout.JAVA_INT)
    // IO owns this lease's monitor. Readiness never waits on a blocking read:
    // only physical close and duplicate share this short lifetime lock.
    private val lifetime = Any()
    private var closed = false
    init { slot.set(ValueLayout.JAVA_INT, 0, -1) }

    @Synchronized fun openSlot(): MemorySegment {
        if (closed) throw ClosedChannelException()
        check(slot.get(ValueLayout.JAVA_INT, 0) == -1) { "Open lease already populated" }
        return slot
    }
    @Synchronized fun requireOpen() {
        if (closed || slot.get(ValueLayout.JAVA_INT, 0) < 0) throw ClosedChannelException()
    }
    val isOpen: Boolean
        @Synchronized get() = !closed && slot.get(ValueLayout.JAVA_INT, 0) >= 0

    fun duplicateForWait(): Int = synchronized(lifetime) {
        if (closed || slot.get(ValueLayout.JAVA_INT, 0) < 0) throw ClosedChannelException()
        try {
            Arena.ofConfined().use { call ->
                val errors = call.allocate(NativeCalls.capture)
                val result = WaitDuplicate.fcntl.invokeExact(errors,
                    slot.get(ValueLayout.JAVA_INT, 0), 1030, 0) as Int // Linux F_DUPFD_CLOEXEC.
                if (result < 0) throw NativeFileException("duplicate readiness lease", errors.get(ValueLayout.JAVA_INT, NativeCalls.errno))
                result
            }
        } catch (failure: Throwable) { failed("Native readiness duplication failed", failure) }
    }
    @Synchronized override fun close() = closeOwned(reportError = true)

    /** O_PATH directory borrows carry no buffered writes. Linux consumes their
     * fd even when close reports EINTR; never retry or report a successful CWD
     * commit/native effect as failed because its private anchor was retired. */
    @Synchronized internal fun closeDirectory() = closeOwned(reportError = false)

    private fun closeOwned(reportError: Boolean) {
        synchronized(lifetime) {
            if (closed) return
            closed = true
            try {
                val fd = slot.get(ValueLayout.JAVA_INT, 0)
                slot.set(ValueLayout.JAVA_INT, 0, -1)
                if (fd >= 0) try {
                    Arena.ofConfined().use { call ->
                        val errors = call.allocate(NativeCalls.capture)
                        val result = NativeCalls.closeFd.invokeExact(errors, fd) as Int
                        if (result != 0 && reportError) throw NativeFileException("close", errors.get(ValueLayout.JAVA_INT, NativeCalls.errno))
                    }
                } catch (failure: Throwable) { failed("Native close invocation failed", failure) }
            } finally { arena.close() }
        }
    }
    @ExportMessage @Synchronized fun isPointer(): Boolean = !closed
    @ExportMessage @Synchronized fun asPointer(): Long {
        if (closed) throw UnsupportedMessageException.create()
        return slot.address()
    }
    companion object {
        private fun failed(message: String, failure: Throwable): Nothing {
            if (failure is IOException || failure is RuntimeException || failure is Error) throw failure
            throw IOException(message, failure)
        }
    }
    private object NativeCalls {
        val capture = Linker.Option.captureStateLayout()
        val errno = capture.byteOffset(MemoryLayout.PathElement.groupElement("errno"))
        val closeFd = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"))
    }
    private object WaitDuplicate {
        val fcntl = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("fcntl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            Linker.Option.firstVariadicArg(2), Linker.Option.captureCallState("errno"))
    }
}
