// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.nodes.Node
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicBoolean

/** A poll snapshot owns all its native duplicates. A separate eventfd wakes
 * cancellation or logical close without closing an fd beneath the syscall. */
internal class NativeEventWait private constructor(private val descriptors: IntArray,
    events: ShortArray, invalid: BooleanArray, private val wake: Int, private val arena: Arena) :
    AutoCloseable, TruffleSafepoint.Interrupter {
    private val polls = arena.allocate((descriptors.size.toLong() + 1) * 8, 4)
    private val one = arena.allocate(ValueLayout.JAVA_LONG).also { it.set(ValueLayout.JAVA_LONG, 0, 1L) }
    private val drained = arena.allocate(ValueLayout.JAVA_LONG)
    private val interruptErrors = arena.allocate(NativePollApi.capture)
    private val drainErrors = arena.allocate(NativePollApi.capture)
    private val interrupted = AtomicBoolean()
    private var released = false

    /** Registry callbacks only store a flag and perform nonblocking eventfd IO.
     * Each callback has its own errno slot; unrelated descriptor closes may run
     * concurrently with Truffle's serialized interrupt/reset callbacks. */
    inner class Watch internal constructor(index: Int, invalid: Boolean) {
        val index = index
        val closed = AtomicBoolean(invalid)
        private val errors = arena.allocate(NativePollApi.capture)
        fun descriptorClosed() {
            closed.set(true)
            NativePollApi.signal(wake, one, errors)
        }
    }
    val watches = Array(descriptors.size) { Watch(it, invalid[it]) }

    init {
        for (index in descriptors.indices) {
            polls.set(ValueLayout.JAVA_INT, index * 8L, descriptors[index])
            polls.set(ValueLayout.JAVA_SHORT, index * 8L + 4, events[index])
        }
        polls.set(ValueLayout.JAVA_INT, descriptors.size * 8L, wake)
        polls.set(ValueLayout.JAVA_SHORT, descriptors.size * 8L + 4, 1)
    }

    companion object {
        /** Takes every supplied duplicate, including on failed wake setup. */
        fun acquire(descriptors: IntArray, events: ShortArray, invalid: BooleanArray): NativeEventWait {
            val arena = Arena.ofShared()
            var wake = -1
            try {
                wake = NativePollApi.eventfd()
                return NativeEventWait(descriptors, events, invalid, wake, arena)
            } catch (failure: Throwable) {
                for (fd in descriptors.toList() + wake) if (fd >= 0) try { NativePollApi.close(fd) }
                    catch (closing: Throwable) { failure.addSuppressed(closing) }
                arena.close()
                throw failure
            }
        }
    }

    override fun interrupt(thread: Thread) {
        interrupted.set(true)
        NativePollApi.signal(wake, one, interruptErrors)
    }
    override fun resetInterrupted() {
        NativePollApi.drain(wake, drained, drainErrors)
        interrupted.set(false)
    }

    fun await(node: Node?, timeout: Int, beforeBlock: Runnable? = null): ShortArray {
        val started = System.nanoTime()
        fun remaining(): Int = if (timeout < 0) -1 else
            (timeout.toLong() - (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000)
                .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        fun results(): ShortArray = ShortArray(descriptors.size) { index ->
            if (watches[index].closed.get()) 32 // Linux POLLNVAL.
            else polls.get(ValueLayout.JAVA_SHORT, index * 8L + 6)
        }
        val action = TruffleSafepoint.InterruptibleFunction<Unit, ShortArray> {
            while (true) {
                if (interrupted.get()) throw InterruptedException()
                NativePollApi.poll(polls, 0, descriptors.size.toLong() + 1)
                if (interrupted.get()) throw InterruptedException()
                val immediate = results()
                if (immediate.any { it.toInt() != 0 } || timeout == 0) return@InterruptibleFunction immediate
                // Re-run the observation after a Truffle wake, before entering
                // the next blocking syscall. Never claim guest delivery here.
                beforeBlock?.run()
                NativePollApi.poll(polls, remaining(), descriptors.size.toLong() + 1)
                if (interrupted.get()) throw InterruptedException()
                val ready = results()
                if (ready.any { it.toInt() != 0 } || timeout >= 0 && remaining() == 0)
                    return@InterruptibleFunction ready
                if (polls.get(ValueLayout.JAVA_SHORT, descriptors.size * 8L + 6).toInt() != 0)
                    throw InterruptedException()
            }
            @Suppress("UNREACHABLE_CODE") shortArrayOf()
        }
        return TruffleSafepoint.getCurrent().setBlockedFunction(node, this, action, Unit, null, null)
    }

    /** Unregister every Watch and restore Truffle blocked state before close. */
    override fun close() {
        if (released) return
        released = true
        var failure: Throwable? = null
        try {
            for (fd in descriptors.toList() + wake) if (fd >= 0) try { NativePollApi.close(fd) }
                catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
        } finally { arena.close() }
        failure?.let { throw it }
    }
}
