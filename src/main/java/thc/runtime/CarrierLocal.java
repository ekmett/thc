// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Share mutable cells with the compiled context-thread-local reader. */
public final class CarrierLocal<T> extends ThreadLocal<T> {
    public static final class Cell<T> {
        private T value;
        public Cell(T value) { this.value = value; }
        public T getValue() { return value; }
        public void setValue(T value) { this.value = value; }
    }
    private final T initial;
    private final WeakHashMap<Thread, WeakReference<Cell<T>>> cells = new WeakHashMap<>();
    private final ThreadLocal<Cell<T>> current = ThreadLocal.withInitial(() -> cell(Thread.currentThread()));
    public CarrierLocal(T initial) { this.initial = initial; }
    @TruffleBoundary public synchronized Cell<T> cell(Thread thread) {
        WeakReference<Cell<T>> reference = cells.get(thread);
        Cell<T> value = reference == null ? null : reference.get();
        if (value == null) { value = new Cell<>(initial); cells.put(thread, new WeakReference<>(value)); }
        return value;
    }
    public Cell<T> cell$org_intelligence_thc(Thread thread) { return cell(thread); }
    @TruffleBoundary @Override public T get() { return current.get().value; }
    @TruffleBoundary @Override public void set(T value) { current.get().value = value; }
    @TruffleBoundary @Override public void remove() {
        synchronized (this) {
            WeakReference<Cell<T>> reference = cells.get(Thread.currentThread());
            Cell<T> value = reference == null ? null : reference.get();
            if (value != null) value.value = initial;
        }
        current.remove();
    }
}
