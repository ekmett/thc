// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.Node
import java.lang.foreign.MemorySegment
import thc.Language

/** Malloc is explicitly owned until free or context disposal, even if guest
 * references disappear. It is not the immutable-image weak cache. */
internal class ManagedNativeAllocations(private val env: TruffleLanguage.Env) {
    private val live = HashSet<Owner>()
    private val freeing = HashSet<Owner>()
    private var closed = false
    private fun current() {
        if (Language.currentState().nativeAllocations !== this) fault("Native allocation belongs to another context")
        if (!env.isNativeAccessAllowed) fault("Native allocation requires native access")
    }
    internal inner class Owner internal constructor(private val storage: NativeMallocAllocation, val size: Long) {
        fun borrow(): NativeMallocAllocation.Borrow { current(); return storage.borrow() }
        internal fun release() = storage.close()
        internal fun requireFreeable() = storage.requireFreeable()
        @TruffleBoundary fun requireLive() { borrow().use {} }
        @TruffleBoundary fun <T> access(body: (MemorySegment) -> T): T = borrow().use { body(it.segment()) }
    }
    @Synchronized @TruffleBoundary fun malloc(size: Long): ManagedAddress {
        current()
        if (closed) fault("Native allocation registry is closed")
        if (System.getProperty("os.name") != "Linux" || System.getProperty("os.arch") !in setOf("amd64", "x86_64"))
            fault("Native malloc currently requires the verified Linux x86_64 LP64 ABI")
        val threads = Language.currentState().threads
        val previous = threads.enterForeign()
        try {
            val result = NativeMallocAllocation.allocate(size)
            val storage = result.allocation() ?: run {
                Language.currentState().stdio.nativeError(result.errno())
                return ManagedAddress.nullAddress()
            }
            try {
                val owner = Owner(storage, size)
                val address = ManagedAddress.fromNativeAllocation(owner)
                live.add(owner)
                return address
            } catch (failure: Throwable) { storage.close(); throw failure }
        } finally { threads.leaveForeign(previous) }
    }
    @TruffleBoundary fun free(address: ManagedAddress) {
        current()
        val owner = synchronized(this) {
            if (closed) fault("Native allocation registry is closed")
            if (address === ManagedAddress.nullAddress()) return
            val allocation = address.nativeAllocation() ?: fault("Native free requires an owned malloc base")
            if (allocation !in live || allocation in freeing) fault("Native free requires a live allocation from this context")
            if (!address.isNativeBase()) fault("Native free requires the allocation base")
            allocation.requireFreeable()
            freeing.add(allocation)
            allocation
        }
        val threads = Language.currentState().threads
        val previous = threads.enterForeign()
        try {
            owner.release() // Wait for other threads' borrows before consuming ownership.
        } finally {
            synchronized(this) { live.remove(owner); freeing.remove(owner) }
            threads.leaveForeign(previous)
        }
    }
    @TruffleBoundary fun close() {
        val pending = synchronized(this) {
            if (closed) return
            live.forEach(Owner::requireFreeable)
            closed = true
            live.toList()
        }
        // Never hold the registry monitor while waiting for a native borrower:
        // a borrowed call may acquire an unrelated allocation in this context.
        var failed: Throwable? = null
        for (owner in pending) try { owner.release() } catch (failure: Throwable) {
            if (failed == null) failed = failure else failed.addSuppressed(failure)
        }
        synchronized(this) { live.clear(); freeing.clear() }
        failed?.let { throw it }
    }
    @Synchronized internal fun liveCount(): Int = live.size
    companion object {
        @JvmStatic fun current(node: Node?): ManagedNativeAllocations = Language.currentState(node).nativeAllocations
    }
}
