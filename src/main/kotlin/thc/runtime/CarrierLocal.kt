// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Share a mutable carrier cell with Truffle's compiled context-thread-local reader. */
internal class CarrierLocal<T>(private val initial: T) : ThreadLocal<T>() {
    internal class Cell<T>(var value: T)
    // A factory can be called for a carrier other than the current Java thread.
    // Neither this registry's keys nor its values keep a dead carrier alive.
    private val cells = WeakHashMap<Thread, WeakReference<Cell<T>>>()
    private val current = ThreadLocal.withInitial { cell(Thread.currentThread()) }

    @TruffleBoundary @Synchronized internal fun cell(thread: Thread): Cell<T> =
        cells[thread]?.get() ?: Cell(initial).also { cells[thread] = WeakReference(it) }

    // These adapters retain the registry/embedding API. Hot guest reads use
    // the same cell through ContextThreadLocal, never these Java map lookups.
    @TruffleBoundary override fun get(): T = current.get().value
    @TruffleBoundary override fun set(value: T) { current.get().value = value }
    @TruffleBoundary override fun remove() {
        synchronized(this) { cells[Thread.currentThread()]?.get()?.value = initial }
        current.remove()
    }
}
