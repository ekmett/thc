// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** StablePtr# roots belong to a context; their AddrRep carrier is opaque. */
internal class StablePointers {
    internal class Handle(val owner: StablePointers, val id: Long)
    private class Entry(val handle: Handle, val value: Any?)
    private val entries = HashMap<Long, Entry>()
    private var nextId = 1L
    private var disposed = false

    @Synchronized @TruffleBoundary
    fun make(value: Any?): ManagedAddress {
        if (disposed || nextId <= 0L) fault("StablePtr context is closed or exhausted")
        if (value == null) fault("StablePtr# requires a lifted referent")
        val handle = Handle(this, nextId++)
        entries[handle.id] = Entry(handle, value)
        return ManagedAddress.fromStableHandle(handle)
    }

    private fun entry(address: ManagedAddress): Entry {
        val handle = address.stableHandle() ?: fault("Expected an opaque StablePtr#")
        if (disposed || handle.owner !== this || entries[handle.id]?.handle !== handle)
            fault("Stale or foreign StablePtr#")
        return entries.getValue(handle.id)
    }

    @Synchronized @TruffleBoundary
    fun dereference(address: ManagedAddress): Any? = entry(address).value

    @Synchronized @TruffleBoundary
    fun validate(address: ManagedAddress) { entry(address) }

    @Synchronized @TruffleBoundary
    fun equal(left: ManagedAddress, right: ManagedAddress): Boolean {
        val first = entry(left)
        val second = entry(right)
        return first.handle === second.handle
    }

    @Synchronized @TruffleBoundary
    fun free(address: ManagedAddress) {
        val handle = entry(address).handle
        entries.remove(handle.id)
    }

    @Synchronized fun close() { disposed = true; entries.clear() }

    companion object {
        @JvmStatic fun current(node: Node?): StablePointers = Language.currentState(node).stablePointers
    }
}
