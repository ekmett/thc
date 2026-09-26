// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import thc.Language

/** anyToAddr# is not a GC root. These opaque addresses retain identity, never
 * expose a JVM pointer or invent a process address, and reject stale dereference. */
internal class HeapAddresses {
    private class Key(value: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(value, queue) {
        private val hash = System.identityHashCode(value)
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = this === other ||
            other is Key && get()?.let { it === other.get() } == true
    }
    internal class Handle internal constructor(val owner: HeapAddresses, val id: Long, value: Any) {
        internal val value = WeakReference(value)
    }
    private val queue = ReferenceQueue<Any>()
    private val entries = HashMap<Key, Handle>()
    private var nextId = 1L
    private var closed = false

    @Synchronized @TruffleBoundary
    fun handle(raw: Any?): Handle {
        if (closed || nextId <= 0L) fault("Heap-address context is closed or exhausted")
        val value = completedBoxedIdentity(raw) ?: fault("anyToAddr# requires a guest value")
        if (value is Thunk) fault("anyToAddr# requires an evaluated value, not a thunk")
        while (true) entries.remove(queue.poll() ?: break)
        return try {
            entries[Key(value, null)] ?: Handle(this, nextId++, value).also {
                entries[Key(value, queue)] = it
            }
        } finally { Reference.reachabilityFence(value) }
    }
    fun address(value: Any?): ManagedAddress = ManagedAddress.fromHeapHandle(handle(value))
    @Synchronized fun require(handle: Handle): Handle {
        if (closed || handle.owner !== this) fault("Foreign or closed heap address")
        return handle
    }
    fun require(address: ManagedAddress): Handle = require(address.heapHandle()
        ?: fault("addrToAny# requires an opaque guest heap address"))
    fun dereference(address: ManagedAddress): Any = require(address).value.get()
        ?: fault("Dangling guest heap address")
    @Synchronized fun close() { closed = true; entries.clear() }

    companion object {
        fun current(): HeapAddresses = Language.currentState(null).heapAddresses
    }
}
