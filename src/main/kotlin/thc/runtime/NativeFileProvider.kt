// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.io.ByteSequence
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.Context
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.InvalidPathException
import java.nio.file.StandardOpenOption
import java.nio.file.OpenOption
import thc.Language
import thc.NativeIO.StandardEndpoint
import thc.NativeFileSystem

/** Linux provider proof only. Acquisition is reachable solely through the
 * explicitly configured NativeFileSystem request, never from a guest fd.
 * No original FCall recognition or automatic replacement of Env streams. */
internal class NativeFileProvider private constructor(private val env: TruffleLanguage.Env, private val threads: GuestThreads) : Closeable {
    companion object {
        /** No arbitrary Builder, FileSystem, provider attachment, or global map.
         * The factory knows the exact final provider whose channel it authenticates. */
        internal fun createContext(endpoints: Set<StandardEndpoint>): Context {
            val context = Context.newBuilder("thc").allowNativeAccess(true)
                .allowIO(IOAccess.newBuilder().fileSystem(NativeFileSystem(endpoints)).build()).build()
            try {
                context.initialize("thc"); context.enter()
                try {
                    val state = Language.currentState()
                    check(state.nativeFiles == null)
                    val provider = NativeFileProvider(state.env, state.threads)
                    state.files.installNative(provider, endpoints)
                    state.nativeFiles = provider
                } finally { context.leave() }
                return context
            } catch (failure: Throwable) {
                try { context.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
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
    private val statSize: Int

    init {
        if (!env.isNativeAccessAllowed || !env.isFileIOAllowed)
            throw SecurityException("Native files require explicit file IO and native access")
        if (System.getProperty("os.name") != "Linux" || System.getProperty("os.arch") !in setOf("amd64", "x86_64"))
            throw UnsupportedOperationException("Native files are currently verified only on Linux x86_64")
        val bytes = javaClass.getResourceAsStream("/thc/native/native-file-api.so")?.use { it.readBytes() }
            ?: fault("Missing native file provider bridge")
        library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(bytes), "native-file-api.so").build()).call()
        statSize = interop.asLong(interop.execute(interop.readMember(library, "thc_file_stat_size"))).toInt()
        val expected = PosixStat.execute(OriginalStdioOp.SIZEOF_STAT, ManagedAddress.nullAddress(), 0)
        if (statSize.toLong() != expected) fault("Native file provider/stat image ABI mismatch")
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
        return opened(path, NativeOpenRequest(null, options) { acquireFile(it!!, mode) }, options)
    }

    /** Explicit endpoint grants duplicate process endpoints into owned resources.
     * This does not associate them with arbitrary Env.in/out/err streams. */
    fun standard(endpoint: StandardEndpoint): OpenedNativeFile {
        val options: Set<OpenOption> = setOf(if (endpoint == StandardEndpoint.INPUT)
            StandardOpenOption.READ else StandardOpenOption.WRITE)
        return opened(".", NativeOpenRequest(endpoint, options) { acquireStandard(endpoint.ordinal) }, options)
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
    private fun acquireFile(path: String, mode: Int): NativeResource {
        if ('\u0000' in path) throw InvalidPathException(path, "NUL in native path")
        val bytes = path.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        return acquire(mode == 0 || mode == 3, mode != 0) { lease -> NativeLimbScope().use { scope ->
            val name = scope.allocate((bytes.size.toLong() + 7) and -8L)
            name.copyFrom(bytes, 0, bytes.size)
            result("open", lease, name, mode)
        } }
    }

    private fun acquireStandard(endpoint: Int): NativeResource = acquire(endpoint == 0, endpoint != 0) { lease ->
        require(endpoint in 0..2)
        result("standard", lease, endpoint)
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
        for (lease in pending) try { retire(lease) } catch (failure: Throwable) {
            if (failed == null) failed = failure else if (failed !== failure) failed.addSuppressed(failure)
        }
        if (failed != null) throw failed
    }

    /** Bytes and metadata use the very same lease; there is no pathname here.
     * The descriptor owner will eventually share this object among guest dup
     * aliases. Closing is idempotent and never re-enters the LLVM context. */
    private inner class NativeResource(private val lease: NativeFileLease,
        private val readable: Boolean, private val writable: Boolean) : NativeFileResource {
        private inline fun <T> live(action: () -> T): T = synchronized(lease) {
            current(); lease.requireOpen(); action()
        }
        override fun requireLive() = live {
            synchronized(this@NativeFileProvider) { if (disposed) throw ClosedChannelException() }
        }
        override fun statImage(): ByteArray = live {
            NativeLimbScope().use { scope ->
                val image = scope.allocate(statSize.toLong())
                result("stat", lease, image)
                ByteArray(statSize).also { image.copyTo(it, 0, it.size) }
            }
        }
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
