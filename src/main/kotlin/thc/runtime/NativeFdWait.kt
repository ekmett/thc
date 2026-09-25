// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.nodes.Node
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.atomic.AtomicBoolean
import thc.NativeIO

/** Private Linux readiness transport. Neither the observed descriptor nor the
 * wake descriptor is guest data. Each wait owns a duplicate of an authenticated
 * lease, so host fd reuse cannot redirect an outstanding poll. Logical guest
 * close is a separate wake event; it never closes an fd underneath poll.
 *
 * poll sleeps until readiness, timeout or an eventfd wake. There is no timer
 * thread, periodic probe, helper executor, or signal-handler installation. All
 * wake/cleanup downcalls work without entering a possibly disposed LLVM context.
 */
internal class NativeFdWait private constructor(private val descriptor: Int, private val wakeFd: Int,
                                               private val arena: Arena) : AutoCloseable,
    TruffleSafepoint.Interrupter {
    private val polls = arena.allocate(16, 4) // Linux: two { int fd; short events, revents; }.
    private val one = arena.allocate(ValueLayout.JAVA_LONG).also { it.set(ValueLayout.JAVA_LONG, 0, 1L) }
    private val drained = arena.allocate(ValueLayout.JAVA_LONG)
    private val wakeErrors = arena.allocate(NativePollApi.capture)
    private val closeErrors = arena.allocate(NativePollApi.capture)
    private val drainErrors = arena.allocate(NativePollApi.capture)
    private val interrupted = AtomicBoolean()
    @Volatile private var descriptorClosed = false
    private var released = false

    init {
        polls.set(ValueLayout.JAVA_INT, 0, descriptor)
        polls.set(ValueLayout.JAVA_INT, 8, wakeFd)
        polls.set(ValueLayout.JAVA_SHORT, 12, 1) // POLLIN for eventfd.
    }

    companion object {
        /** Ownership of the private duplicate transfers even if setup fails. */
        internal fun acquire(lease: NativeFileLease): NativeFdWait {
            val arena = Arena.ofShared()
            var descriptor = -1
            var wake = -1
            try {
                descriptor = lease.duplicateForWait()
                wake = NativePollApi.eventfd()
                return NativeFdWait(descriptor, wake, arena)
            } catch (failure: Throwable) {
                for (fd in listOf(wake, descriptor)) if (fd >= 0) try { NativePollApi.close(fd) }
                    catch (closing: Throwable) { failure.addSuppressed(closing) }
                arena.close()
                throw failure
            }
        }
    }

    // Truffle calls these with internal locks held. Nonblocking eventfd IO only:
    // no guest call, JVM monitor, LLVM context or descriptor lookup.
    override fun interrupt(thread: Thread) {
        interrupted.set(true)
        NativePollApi.signal(wakeFd, one, wakeErrors)
    }

    override fun resetInterrupted() {
        // Verified against Truffle 25.3.4.1 ThreadLocalHandshake:
        // setFastPendingAndInterrupt, takeHandshakes and setBlockedImpl invoke
        // interrupt/reset under the same per-target ReentrantLock. Unregister
        // also holds that lock. Thus no interrupt can land between this drain
        // and flag clear, nor after blocked-state removal and request cleanup.
        // descriptorClosed is independent and sticky, even if its wake is drained.
        NativePollApi.drain(wakeFd, drained, drainErrors)
        interrupted.set(false)
    }

    internal fun descriptorClosed() {
        descriptorClosed = true
        NativePollApi.signal(wakeFd, one, closeErrors)
    }

    /** -2 is a logical close, never native errno or fabricated readiness.
     * The caller decides whether it needs fdReady's POLLNVAL-as-ready behavior
     * or waitRead#/waitWrite#'s original blockedOnBadFD exception payload. */
    internal fun await(node: Node?, writing: Boolean, milliseconds: Long,
                       beforeBlock: (() -> Unit)? = null): Int {
        polls.set(ValueLayout.JAVA_SHORT, 4, if (writing) 4 else 1) // POLLOUT / POLLIN.
        val started = System.nanoTime()
        fun remaining(): Int = if (milliseconds < 0) -1 else
            (milliseconds - (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000)
                .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val action = TruffleSafepoint.InterruptibleFunction<Unit, Int> {
            while (true) {
                if (interrupted.get()) throw InterruptedException()
                if (descriptorClosed) return@InterruptibleFunction -2
                // Only a genuinely blocking operation is an interruptible
                // Haskell cut. A readiness result does not consume guest data.
                val probe = NativePollApi.poll(polls, 0)
                if (interrupted.get()) throw InterruptedException()
                if (descriptorClosed) return@InterruptibleFunction -2
                if (probe > 0 && polls.get(ValueLayout.JAVA_SHORT, 6).toInt() != 0)
                    return@InterruptibleFunction 1
                if (milliseconds == 0L) return@InterruptibleFunction 0
                beforeBlock?.invoke()
                val timeout = remaining()
                val ready = NativePollApi.poll(polls, timeout)
                if (interrupted.get()) throw InterruptedException()
                if (descriptorClosed) return@InterruptibleFunction -2
                if (polls.get(ValueLayout.JAVA_SHORT, 14).toInt() != 0)
                    throw InterruptedException() // Safepoint wake, never target readiness.
                if (ready > 0) return@InterruptibleFunction 1
                if (milliseconds >= 0 && remaining() == 0) return@InterruptibleFunction 0
                // Only a long timeout chunk (> INT_MAX ms) can get here.
            }
            @Suppress("UNREACHABLE_CODE") 0
        }
        return TruffleSafepoint.getCurrent().setBlockedFunction(node, this, action, Unit, null, null)
    }

    /** Called after unregistering from both the descriptor and Truffle's
     * blocked state; no interrupt callback may race arena/fd destruction. */
    override fun close() {
        if (released) return
        released = true
        try { NativePollApi.close(wakeFd) }
        finally { try { NativePollApi.close(descriptor) } finally { arena.close() } }
    }
}

/** Loaded only by the explicitly Linux x86_64 native-file capability. Keeping
 * lookup lazy avoids eventfd linkage (and Linux headers) in macOS builds. The
 * constants and pollfd layout here are the Linux ABI, not portable POSIX values. */
private object NativePollApi {
    private val linker = Linker.nativeLinker()
    val capture: MemoryLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = capture.byteOffset(MemoryLayout.PathElement.groupElement("errno"))
    private fun function(name: String, result: ValueLayout, vararg arguments: MemoryLayout): MethodHandle {
        check(NativeIO.supportedHost()) { "Native readiness currently requires Linux x86_64" }
        return linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(),
            FunctionDescriptor.of(result, *arguments), Linker.Option.captureCallState("errno"))
    }
    private val event = function("eventfd", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
    private val poll = function("poll", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)
    private val read = function("read", ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    private val write = function("write", ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    private val close = function("close", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)

    private fun errno(errors: MemorySegment): Int = errors.get(ValueLayout.JAVA_INT, errnoOffset)
    fun eventfd(): Int = Arena.ofConfined().use { arena ->
        val errors = arena.allocate(capture)
        val fd = event.invokeWithArguments(errors, 0, 0x800 or 0x80000) as Int // NONBLOCK | CLOEXEC.
        if (fd < 0) throw NativeFileException("eventfd", errno(errors))
        fd
    }
    fun poll(descriptors: MemorySegment, timeout: Int): Int = Arena.ofConfined().use { arena ->
        val errors = arena.allocate(capture)
        val result = poll.invokeWithArguments(errors, descriptors, 2L, timeout) as Int
        if (result < 0) {
            val error = errno(errors)
            if (error == 4) throw InterruptedException() // EINTR: retry through a safepoint.
            throw NativeFileException("poll", error)
        }
        result
    }
    fun signal(fd: Int, value: MemorySegment, errors: MemorySegment) {
        while (true) {
            val result = write.invokeWithArguments(errors, fd, value, 8L) as Long
            if (result == 8L) return
            val error = errno(errors)
            // These calls cannot block on readiness. Retry an OS signal before
            // transfer; EAGAIN already guarantees a wake counter is present.
            if (result < 0 && error == 4) continue
            if (result < 0 && error == 11) return
            throw NativeFileException("eventfd write", error)
        }
    }
    fun drain(fd: Int, value: MemorySegment, errors: MemorySegment) {
        while (true) {
            val result = read.invokeWithArguments(errors, fd, value, 8L) as Long
            if (result == 8L) return
            val error = errno(errors)
            if (result < 0 && error == 4) continue
            if (result < 0 && error == 11) return
            throw NativeFileException("eventfd read", error)
        }
    }
    fun close(fd: Int) = Arena.ofConfined().use { arena ->
        val errors = arena.allocate(capture)
        if (close.invokeWithArguments(errors, fd) as Int != 0)
            throw NativeFileException("close readiness descriptor", errno(errors))
    }
}
