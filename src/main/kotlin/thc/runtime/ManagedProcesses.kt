// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node
import java.io.Closeable
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.channels.ClosedChannelException
import java.util.IdentityHashMap
import thc.Language
import thc.NativeIO

/** Context-owned process lifecycle. This isolated service does not admit foreign
 * declarations or change their safety. The original Haskell ProcessHandle owns
 * exit-code caching; repeated raw C calls retain their original ECHILD behavior.
 *
 * Spawn and native status publication are single effects. Waiting only blocks
 * on a duplicated pidfd, so cancellation cannot abandon an in-flight reap or
 * execute a second spawn. Closing the context kills/reaps only its own children.
 */
internal class ManagedProcesses(private val directory: NativeDirectoryOwner) : Closeable {
    private val context = Language.currentState()
    private val children = IdentityHashMap<Handle, Child>()
    private val processIds = mutableMapOf<Int, Handle>()
    private var disposed = false

    /** Identity capability, never a guest-supplied host PID. Numeric FFI handle
     * mapping belongs to the eventual original-declaration adapter. */
    class Handle internal constructor()
    sealed interface Stream {
        data object Pipe : Stream
        data object Closed : Stream
        class Descriptor(val resource: NativeFileResource) : Stream
    }
    class Launch internal constructor(val handle: Handle, val input: Pipe?, val output: Pipe?, val error: Pipe?)

    /** Until transferred to ManagedFiles, the context retains cleanup ownership.
     * A transfer is one-way; the receiver must install/close the returned lease. */
    class Pipe internal constructor() : Closeable {
        private val context = Language.currentState()
        private var owned: NativeFileLease? = NativeFileLease()
        internal fun slot() = owned!!.openSlot()
        @Synchronized fun takeLease(): NativeFileLease {
            if (Language.currentState() !== context) fault("Process pipe belongs to another context")
            return (owned ?: throw ClosedChannelException()).also { owned = null }
        }
        @Synchronized fun duplicate(): Int = (owned ?: throw ClosedChannelException()).duplicateForWait()
        @Synchronized override fun close() {
            val lease = owned ?: return
            owned = null
            lease.close()
        }
    }
    private class Child(val pidfd: NativeFileLease, val pipes: List<Pipe>) {
        var pid = -1
        var descriptor = -1
        val waiters = linkedSetOf<NativeEventWait.Watch>()
        var reaped = false
        var closed = false
    }

    init {
        if (!NativeIO.supportedPosixHost()) throw UnsupportedOperationException("Native processes require Linux x86_64")
        if (!context.env.isNativeAccessAllowed || !context.env.isCreateProcessAllowed)
            throw SecurityException("Native processes require explicit native access and process creation permission")
        context.env.registerOnDispose(this)
    }

    private fun current() {
        if (Language.currentState() !== context) fault("Process registry belongs to another context")
        if (disposed) throw ClosedChannelException()
    }
    @Synchronized private fun child(handle: Handle): Child {
        current()
        return children[handle] ?: fault("Unknown or cross-context process handle")
    }

    /** Strings contain raw bytes without NUL; env is an explicit complete
     * snapshot, never libc environ. All inherited endpoints must be authenticated
     * provider resources. Credential changes and platform console flags reject.
     * Directory actions preserve rename-stable context CWD and raw relative cwd.
     * searchPath is the parent context's PATH, independent of a child env override;
     * null means an absent parent PATH and selects libc's _CS_PATH default. */
    @Synchronized @TruffleBoundary
    fun spawn(arguments: List<ByteArray>, environment: List<ByteArray>, cwd: ByteArray? = null,
              input: Stream = Stream.Closed, output: Stream = Stream.Closed, error: Stream = Stream.Closed,
              flags: Int = 0, childGroup: Long? = null, childUser: Long? = null, searchPath: ByteArray? = null): Launch {
        current()
        require(arguments.isNotEmpty() && arguments[0].isNotEmpty()) { "Empty process command" }
        require((arguments + environment + listOfNotNull(cwd, searchPath)).none { bytes -> bytes.any { it == 0.toByte() } })
        if (childGroup != null || childUser != null || flags and (0x1 or 0x2 or 0x8 or 0x20).inv() != 0)
            throw UnsupportedOperationException("Process credentials or console flags are unavailable")
        // Complete allocation and validation before the native spawn effect.
        val streams = listOf(input, output, error)
        val pipes = streams.map { if (it == Stream.Pipe) Pipe() else null }
        val pidfd = NativeFileLease()
        val result = Launch(Handle(), pipes[0], pipes[1], pipes[2])
        val child = Child(pidfd, pipes.filterNotNull())
        val duplicates = IntArray(3) { -1 }
        var published = false
        var failure: Throwable? = null
        try {
            val pidSlot = pidfd.openSlot()
            val pipeSlots = pipes.map { it?.slot() }
            val descriptors = streams.mapIndexed { index, stream -> when (stream) {
                Stream.Pipe -> -1
                Stream.Closed -> -2
                is Stream.Descriptor -> {
                    stream.resource.requireLive() // Authenticates the provider's context before duplication.
                    stream.resource.duplicateDescriptor().also { duplicates[index] = it }
                }
            } }.toIntArray()
            directory.borrow().use { anchor -> Arena.ofConfined().use { arena ->
                val slots = arena.allocate(24, 4)
                val errno = NativeProcessApi.spawn(arguments, environment, anchor.descriptor, cwd, descriptors, flags, searchPath, slots)
                if (errno != 0) {
                    val stage = ProcessFailureStage.entries.getOrNull(slots.get(ValueLayout.JAVA_INT, 20))
                        ?: fault("Invalid native process failure stage")
                    if (stage == ProcessFailureStage.NONE) fault("Missing native process failure stage")
                    throw ProcessSpawnException(errno, stage)
                }
                child.pid = slots.get(ValueLayout.JAVA_INT, 0)
                child.descriptor = slots.get(ValueLayout.JAVA_INT, 4)
                pidSlot.set(ValueLayout.JAVA_INT, 0, child.descriptor)
                for (index in pipeSlots.indices)
                    pipeSlots[index]?.set(ValueLayout.JAVA_INT, 0, slots.get(ValueLayout.JAVA_INT, (index + 2) * 4L))
            } }
            children[result.handle] = child
            published = true
            return result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            } }
            for (fd in duplicates) if (fd >= 0) cleanup { NativePollApi.close(fd) }
            // A failure before returning ownership retires the child as well.
            if (!published || cleanupFailure != null) {
                children.remove(result.handle)
                cleanup { dispose(child) }
            }
            cleanupFailure?.let { if (failure == null) throw it else failure.addSuppressed(it) }
        }
    }

    /** Original getPid may observe this only after authenticating the handle. */
    @TruffleBoundary fun processId(handle: Handle): Int = child(handle).let { child -> synchronized(child) {
        if (child.closed) throw ClosedChannelException()
        child.pid
    } }

    /** Reserve the real CPid for the original ABI. Retired identities are never
     * replaced: a reused PID rejects and cleans only the newly launched child. */
    @Synchronized @TruffleBoundary fun publishProcessId(handle: Handle): Int {
        val pid = processId(handle)
        val previous = processIds[pid]
        if (previous != null && previous !== handle) {
            val failure = NativeFileException("process PID identity collision", 11)
            try { abortUnpublished(handle) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        processIds[pid] = handle
        return pid
    }

    @Synchronized @TruffleBoundary fun fromProcessId(pid: Int): Handle {
        current()
        return processIds[pid] ?: fault("Unknown or cross-context process ID")
    }

    /** Roll back a launch not yet returned to the guest. Transferred pipes are
     * the receiver's responsibility; all remaining owners are retired here.
     * A registered numeric identity stays reserved even after rollback. */
    @Synchronized @TruffleBoundary fun abortUnpublished(handle: Handle) {
        val child = child(handle)
        dispose(child)
    }

    private fun query(child: Child): ProcessResult {
        if (child.closed) throw ClosedChannelException()
        if (child.reaped) return ProcessResult(-1, 0, 10)
        val result = NativeProcessApi.poll(child.descriptor)
        if (result.status == 1 || result.errno == 10) {
            child.reaped = true
            child.descriptor = -1
            // Like directory anchors, pidfds have no buffered writes. Linux
            // consumes close even on EINTR; preserve the completed reap result.
            child.pidfd.closeDirectory()
        }
        return result
    }

    /** Original getProcessExitCode initializes output to zero, and maps ECHILD
     * to success with zero while leaving ECHILD observable through get_errno. */
    @TruffleBoundary fun poll(handle: Handle): ProcessResult = child(handle).let { child -> synchronized(child) {
        val result = query(child)
        if (result.errno == 10) ProcessResult(1, 0, 10) else result
    } }

    /** Raw original terminateProcess is 1 on success and 0 on failure. It does
     * not reap or close the handle; Haskell's wrapper handles ClosedHandle. */
    @TruffleBoundary fun terminate(handle: Handle): ProcessResult = child(handle).let { child -> synchronized(child) {
        if (child.closed) throw ClosedChannelException()
        if (child.reaped) ProcessResult(0, null, 3) else NativeProcessApi.terminate(child.descriptor)
    } }

    /** beforeBlock is the caller's existing interruptible-operation cut. No
     * GuestThreads activation/reentry policy is invented by this transport. */
    @TruffleBoundary fun waitFor(handle: Handle, node: Node? = null, beforeBlock: (() -> Unit)? = null): ProcessResult {
        val child = child(handle)
        val wait = synchronized(child) {
            val immediate = query(child)
            if (immediate.status != 0) return waitResult(immediate)
            NativeEventWait.acquire(intArrayOf(child.pidfd.duplicateForWait()), shortArrayOf(1), booleanArrayOf(false))
                .also { child.waiters.add(it.watches[0]) }
        }
        try {
            while (true) {
                beforeBlock?.invoke()
                val readiness = wait.await(node, -1)[0].toInt()
                synchronized(child) {
                    if (child.closed || readiness and 32 != 0) throw ClosedChannelException()
                    val result = query(child)
                    if (result.status != 0) return waitResult(result)
                }
            }
        } finally {
            synchronized(child) { child.waiters.remove(wait.watches[0]) }
            wait.close()
        }
    }
    private fun waitResult(result: ProcessResult) =
        if (result.status == 1) ProcessResult(0, result.exitCode, result.errno)
        else ProcessResult(-1, null, result.errno)

    private fun dispose(child: Child) = synchronized(child) {
        if (child.closed) return@synchronized
        child.closed = true
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        } }
        for (waiter in child.waiters) cleanup { waiter.descriptorClosed() }
        if (!child.reaped && child.pidfd.isOpen) cleanup { NativeProcessApi.dispose(child.descriptor) }
        for (pipe in child.pipes) cleanup { pipe.close() }
        cleanup { child.pidfd.close() }
        failure?.let { throw it }
    }

    @Synchronized @TruffleBoundary override fun close() {
        if (disposed) return
        disposed = true
        var failure: Throwable? = null
        for (child in children.values) try { dispose(child) } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        children.clear()
        processIds.clear()
        failure?.let { throw it }
    }
}
