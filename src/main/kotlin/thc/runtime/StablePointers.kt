// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnsupportedMessageException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import thc.Language
import java.lang.foreign.Arena

/** StablePtr# roots belong to a context; their AddrRep carrier is opaque. */
internal class StablePointers {
    internal class Handle(val owner: StablePointers, val id: Long)
    private class Entry(val handle: Handle, val value: Any?) {
        var token: StablePointerToken? = null
    }
    private val entries = HashMap<Long, Entry>()
    private val tokens = HashMap<Long, Handle>()
    private var eventManagerStore: Handle? = null
    private var signalHandlerStore: Handle? = null
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

    @TruffleBoundary internal fun requireNativeAccess() {
        val state = Language.currentState()
        if (state.stablePointers !== this) fault("StablePtr belongs to another context")
        if (!state.env.isNativeAccessAllowed) fault("StablePtr C transport requires native access")
    }

    /** Materialize only when a StablePtr crosses C FFI or exposes native bits. */
    @Synchronized @TruffleBoundary
    fun nativeTransport(address: ManagedAddress): StablePointerToken {
        requireNativeAccess()
        val entry = entry(address)
        entry.token?.let { return it }
        val token = StablePointerToken(this)
        try {
            tokens[token.bits] = entry.handle
            entry.token = token
            return token
        } catch (failure: Throwable) { token.close(); throw failure }
    }

    fun nativeToken(address: ManagedAddress): Long = nativeTransport(address).bits

    /** Only this context's exact live identities regain StablePtr authority. */
    @Synchronized @TruffleBoundary
    fun recoverToken(bits: Long): ManagedAddress? {
        if (disposed) fault("StablePtr context is closed")
        return tokens[bits]?.let(ManagedAddress::fromStableHandle)
    }

    @Synchronized @TruffleBoundary
    fun equal(left: ManagedAddress, right: ManagedAddress): Boolean {
        val first = entry(left)
        val second = entry(right)
        return first.handle === second.handle
    }

    @Synchronized @TruffleBoundary
    fun free(address: ManagedAddress) {
        val entry = entry(address)
        val handle = entry.handle
        if (handle === eventManagerStore || handle === signalHandlerStore)
            fault("RTS shared CAF StablePtr# remains owned until context disposal")
        entries.remove(handle.id)
        entry.token?.let { tokens.remove(it.bits); it.close() }
    }

    @Synchronized @TruffleBoundary
    fun getOrSetSharedCAF(store: SharedCAFStore, candidate: ManagedAddress): ManagedAddress {
        if (disposed) fault("StablePtr context is closed")
        val supplied = if (candidate === ManagedAddress.nullAddress()) null else entry(candidate).handle
        val current = when (store) {
            SharedCAFStore.EVENT_MANAGER -> eventManagerStore
            SharedCAFStore.SIGNAL_HANDLER -> signalHandlerStore
        }
        if (current != null) return ManagedAddress.fromStableHandle(current)
        if (supplied == null) return ManagedAddress.nullAddress()
        when (store) {
            SharedCAFStore.EVENT_MANAGER -> eventManagerStore = supplied
            SharedCAFStore.SIGNAL_HANDLER -> signalHandlerStore = supplied
        }
        return candidate
    }

    @Synchronized fun close() {
        disposed = true
        eventManagerStore = null
        signalHandlerStore = null
        tokens.clear()
        entries.values.forEach { it.token?.close() }
        entries.clear()
    }

    companion object {
        @JvmStatic fun current(node: Node?): StablePointers = Language.currentState(node).stablePointers
    }
}

/** Persistent opaque identity, not guest byte storage. A genuine native address
 * also works when host C retains the token. Simultaneously live contexts cannot
 * collide as they could with Sulong's context-local numeric handle slots. */
@ExportLibrary(InteropLibrary::class)
internal class StablePointerToken(private val owner: StablePointers) : TruffleObject, AutoCloseable {
    private val arena = Arena.ofShared()
    val bits = try { arena.allocate(1).address() }
        catch (failure: Throwable) { arena.close(); throw failure }
    @Volatile private var closed = false
    @ExportMessage fun isPointer(): Boolean = !closed
    @ExportMessage fun asPointer(): Long {
        owner.requireNativeAccess()
        if (closed) throw UnsupportedMessageException.create()
        return bits
    }
    @Synchronized override fun close() {
        if (!closed) { closed = true; arena.close() }
    }
}
