// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.Node
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.locks.ReentrantReadWriteLock
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
    internal inner class Owner internal constructor(private val pointer: MemorySegment, val size: Long) {
        private val lifetime = ReentrantReadWriteLock(true)
        private val arena = Arena.ofShared()
        private val segment = try { pointer.reinterpret(size, arena, null) }
            catch (failure: Throwable) { arena.close(); throw failure }
        private var closed = false

        @TruffleBoundary fun borrow(): Borrow {
            current()
            lifetime.readLock().lock()
            try {
                if (closed) fault("Native allocation is freed")
                return Borrow()
            } catch (failure: Throwable) { lifetime.readLock().unlock(); throw failure }
        }
        inner class Borrow : AutoCloseable {
            private var released = false
            private val thread = Thread.currentThread()
            fun segment(): MemorySegment {
                if (released || thread !== Thread.currentThread()) fault("Invalid native allocation borrow")
                return segment
            }
            @TruffleBoundary override fun close() {
                if (thread !== Thread.currentThread()) fault("Native allocation borrow belongs to another thread")
                if (!released) { released = true; lifetime.readLock().unlock() }
            }
        }
        internal fun requireFreeable() {
            // A synchronous native callback must not upgrade its own live borrow.
            if (lifetime.readHoldCount != 0) fault("Cannot free an allocation borrowed by this thread")
        }
        @TruffleBoundary internal fun release() {
            requireFreeable()
            lifetime.writeLock().lock()
            try {
                if (closed) return
                closed = true
                try { arena.close() } finally { releaseNative(pointer) }
            } finally { lifetime.writeLock().unlock() }
        }
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
            if (size < 0) fault("Native malloc size exceeds the signed Long segment domain")
            var errno = 0L
            val owner = try {
                Arena.ofConfined().use { call ->
                    val errors = call.allocate(Libc.capture)
                    val pointer = Libc.malloc.invokeExact(errors, size) as MemorySegment
                    if (pointer.address() == 0L) {
                        errno = errors.get(ValueLayout.JAVA_INT, Libc.errno).toLong()
                        null
                    } else try { Owner(pointer, size) }
                        catch (failure: Throwable) { releaseNative(pointer); throw failure }
                }
            } catch (failure: Throwable) { nativeFailure("Native malloc invocation failed", failure) }
            if (owner == null) {
                Language.currentState().stdio.nativeError(errno)
                return ManagedAddress.nullAddress()
            }
            try {
                val address = ManagedAddress.fromNativeAllocation(owner)
                live.add(owner)
                return address
            } catch (failure: Throwable) { owner.release(); throw failure }
        } finally { threads.leaveForeign(previous) }
    }
    @TruffleBoundary fun free(address: ManagedAddress) {
        current()
        val owner = synchronized(this) {
            val allocation = freeableOwner(address) ?: return
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
    /** Allocate/copy/retire is a valid realloc implementation: successful calls
     * invalidate every old alias, and allocation failure leaves the old owner
     * untouched. Reserve ownership without holding the registry across borrows. */
    @TruffleBoundary fun realloc(address: ManagedAddress, size: Long): ManagedAddress {
        current()
        if (size < 0) fault("Native realloc size exceeds the signed Long segment domain")
        if (address === ManagedAddress.nullAddress()) return malloc(size)
        val owner = synchronized(this) {
            val allocation = freeableOwner(address)!!
            freeing.add(allocation)
            allocation
        }
        var replacement = ManagedAddress.nullAddress()
        var retired = false
        try {
            // This is the selected Linux libc contract for realloc(p, 0).
            if (size != 0L) {
                replacement = malloc(size)
                if (replacement === ManagedAddress.nullAddress()) return replacement
                owner.access { source -> replacement.nativeAllocation()!!.access { destination ->
                    destination.asSlice(0, minOf(owner.size, size)).copyFrom(source.asSlice(0, minOf(owner.size, size)))
                } }
            }
            owner.release()
            retired = true
            return replacement
        } catch (failure: Throwable) {
            if (replacement !== ManagedAddress.nullAddress()) try { free(replacement) }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        } finally {
            synchronized(this) {
                if (retired) live.remove(owner)
                freeing.remove(owner)
            }
        }
    }
    /** Check the same ownership contract when installing an &free callback. */
    @Synchronized @TruffleBoundary fun requireFreeTarget(address: ManagedAddress) {
        current()
        freeableOwner(address)
    }
    private fun freeableOwner(address: ManagedAddress): Owner? {
        if (closed) fault("Native allocation registry is closed")
        if (address === ManagedAddress.nullAddress()) return null // libc free(NULL) is valid.
        val owner = address.nativeAllocation() ?: fault("Native free requires an owned malloc base")
        if (owner !in live || owner in freeing) fault("Native free requires a live allocation from this context")
        if (!address.isNativeBase()) fault("Native free requires the allocation base")
        owner.requireFreeable()
        return owner
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

    /** Requested bytes still owned by this registry, including an allocation
     * whose free is waiting for active borrows. Excludes allocator overhead and
     * every other native allocation service. Overflow must not look like bytes. */
    @Synchronized @TruffleBoundary internal fun liveBytes(): Long {
        var bytes = 0L
        for (owner in live) bytes = Math.addExact(bytes, owner.size)
        return bytes
    }

    /** Pointer atomic results recover only existing context-owned allocations.
     * Unknown bits remain non-dereferenceable in NativeAddresses. */
    @Synchronized @TruffleBoundary internal fun recoverAddress(bits: Long): ManagedAddress? {
        current()
        if (closed) fault("Native allocation registry is closed")
        for (owner in live) if (owner !in freeing) {
            val displacement = owner.access { bits - it.address() }
            if (java.lang.Long.compareUnsigned(displacement, owner.size) <= 0)
                return ManagedAddress.fromNativeAllocation(owner).plus(displacement)
        }
        return null
    }
    companion object {
        @JvmStatic fun current(node: Node?): ManagedNativeAllocations = Language.currentState(node).nativeAllocations
        private fun releaseNative(pointer: MemorySegment) {
            try { Libc.free.invokeExact(pointer) }
            catch (failure: Throwable) { nativeFailure("Native free invocation failed", failure) }
        }
        private fun nativeFailure(message: String, failure: Throwable): Nothing {
            if (failure is RuntimeException || failure is Error) throw failure
            throw RuntimeFault(message).also { it.initCause(failure) }
        }
    }
    // Initialize host downcalls only when the authorized native path is used.
    private object Libc {
        private val linker = Linker.nativeLinker()
        val capture = Linker.Option.captureStateLayout()
        val errno = capture.byteOffset(MemoryLayout.PathElement.groupElement("errno"))
        val malloc = linker.downcallHandle(linker.defaultLookup().find("malloc").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), Linker.Option.captureCallState("errno"))
        val free = linker.downcallHandle(linker.defaultLookup().find("free").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    }
}
