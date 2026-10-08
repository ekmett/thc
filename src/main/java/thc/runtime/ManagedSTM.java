// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import static thc.runtime.RuntimeFault.fault;

/** A context-owned commit domain. Locks cover only storage, validation and wake
 * registration, never guest actions, handlers, forcing or payload equality. Logs
 * detach on every exit. Private one-shot stack cuts retain their live attempt;
 * external async unwinds retire it. A shared child subsequently demanded inside
 * a fresh attempt inherits that attempt, never the retired log. */
public final class ManagedSTM implements AutoCloseable {
    public static final class Entry {
        final Object revision;
        final Object original;
        Object value;
        boolean written;
        public Entry(Object revision, Object original, Object value, boolean written) {
            this.revision = revision; this.original = original; this.value = value; this.written = written;
        }
        public Entry copy() { return new Entry(revision, original, value, written); }
        public Object getRevision() { return revision; }
        public Object getOriginal() { return original; }
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
        public boolean getWritten() { return written; }
        public void setWritten(boolean written) { this.written = written; }
    }
    public static final class Transaction {
        final IdentityHashMap<ManagedTVar, Entry> entries;
        private volatile boolean active = true;
        boolean active() { return active; }
        public Transaction() { this(new IdentityHashMap<>()); }
        public Transaction(IdentityHashMap<ManagedTVar, Entry> entries) { this.entries = entries; }
        public IdentityHashMap<ManagedTVar, Entry> getEntries() { return entries; }
    }
    private final ReentrantLock lock = new ReentrantLock();
    private final ThreadLocal<Transaction> current = new ThreadLocal<>();
    private final Assumption unused = Assumption.create("THC no transaction has been associated");
    private final WeakHashMap<ManagedTVar, Boolean> cells = new WeakHashMap<>();
    private final WeakHashMap<RetryWait, Boolean> waiters = new WeakHashMap<>();
    private boolean closed;
    private static final TruffleSafepoint.InterruptibleFunction<RetryWait, Object> AWAIT_RETRY = RetryWait::await;

    private void live() { if (closed) throw fault("STM context has closed"); }
    private ManagedTVar cell(Object reference) {
        if (!(reference instanceof ManagedTVar result)) throw fault("Expected managed TVar#");
        if (result.owner != this) throw fault("TVar# belongs to another context");
        return result;
    }
    private Transaction transaction() {
        var tx = current.get();
        if (tx == null) throw fault("STM operation outside atomically#");
        return tx;
    }
    private boolean valid(Transaction tx) {
        for (var item : tx.entries.entrySet()) if (item.getKey().revision != item.getValue().revision) return false;
        return true;
    }
    private void validate(Transaction tx) { live(); if (!valid(tx)) throw STMConflict.INSTANCE; }
    private Entry entry(Transaction tx, ManagedTVar cell) {
        var entry = tx.entries.get(cell);
        if (entry == null) { entry = new Entry(cell.revision, cell.value, cell.value, false); tx.entries.put(cell, entry); }
        return entry;
    }
    @TruffleBoundary public ManagedTVar newTVar(Object value) {
        lock.lock();
        try { live(); var cell = new ManagedTVar(this, value); cells.put(cell, true); return cell; }
        finally { lock.unlock(); }
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    public Object read(Object reference) {
        lock.lock();
        try {
            var cell = cell(reference); var tx = transaction();
            // Validate reads before inconsistent snapshots escape into pure guest computation.
            validate(tx); return entry(tx, cell).value;
        } finally { lock.unlock(); }
    }
    @TruffleBoundary public Object readIO(Object reference) {
        lock.lock(); try { live(); return cell(reference).value; } finally { lock.unlock(); }
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    public void write(Object reference, Object value) {
        lock.lock();
        try { var cell = cell(reference); var tx = transaction(); validate(tx); var entry = entry(tx, cell); entry.value = value; entry.written = true; }
        finally { lock.unlock(); }
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    public RuntimeException retry() {
        lock.lock(); try { validate(transaction()); } finally { lock.unlock(); }
        throw STMRetry.INSTANCE;
    }

    // Package-local log/storage operations let STMCall preserve the former inlined
    // control flow without heap callbacks capturing a VirtualFrame across retry loops.
    @TruffleBoundary Transaction begin() {
        lock.lock(); try { live(); var tx = new Transaction(); restore(tx); return tx; } finally { lock.unlock(); }
    }
    @TruffleBoundary void restore(Transaction tx) {
        if (tx == null) current.remove();
        else { unused.invalidate(); current.set(tx); }
    }
    /** Only private one-shot continuations retain this association; external cuts abandon their log. */
    Transaction currentTransaction() {
        // Resumable roots save this association even in programs that never use STM.
        return unused.isValid() ? null : associatedTransaction();
    }
    @TruffleBoundary private Transaction associatedTransaction() { return current.get(); }
    @TruffleBoundary void retire(Transaction tx) { tx.active = false; }
    @TruffleBoundary(transferToInterpreterOnException = false)
    void commit(Transaction tx) {
        lock.lock();
        try {
            validate(tx);
            for (var item : tx.entries.entrySet()) {
                var cell = item.getKey(); var entry = item.getValue();
                if (entry.written && cell.value != entry.value) {
                    cell.value = entry.value; cell.revision = new Object();
                    if (cell.waiters != null) for (var waiter : cell.waiters) waiter.changedLocked();
                }
            }
        } finally { lock.unlock(); }
    }
    @TruffleBoundary boolean validException(Transaction tx) {
        lock.lock(); try { live(); return valid(tx); } finally { lock.unlock(); }
    }
    @TruffleBoundary void await(Transaction tx, Node node, boolean async) {
        var request = new RetryWait(tx, async ? node : null);
        try {
            if (node == null) {
                try { request.await(); } catch (InterruptedException failure) { throw rethrow(failure); }
            } else TruffleSafepoint.setBlockedThreadInterruptibleFunction(node, AWAIT_RETRY, request);
        } finally { request.cancel(); }
    }
    /** Protocol convenience; guest execution uses STMCall's direct scope/loop operations. */
    public <T> T atomically(Node node, Runnable nested, Supplier<T> action) { return atomically(node, nested, false, action); }
    public <T> T atomically(Node node, Runnable nested, boolean async, Supplier<T> action) {
        if (hasTransaction()) nested.run();
        while (true) {
            var tx = begin();
            try { T result = action.get(); commit(tx); return result; }
            catch (STMConflict ignored) { /* Replay only transactional effects. */ }
            catch (STMRetry ignored) { restore(null); await(tx, node, async); }
            catch (GuestException failure) { if (validException(tx)) throw failure; }
            finally { retire(tx); restore(null); }
        }
    }
    /** Abort a child scope but retain its reads, like stmAbortTransaction. */
    @TruffleBoundary void abort(Transaction parent, Transaction child) {
        for (var item : child.entries.entrySet()) {
            var entry = item.getValue();
            parent.entries.putIfAbsent(item.getKey(), new Entry(entry.revision, entry.original, entry.original, false));
        }
        retire(child);
    }
    @TruffleBoundary Transaction parent() { return transaction(); }
    public <T> T orElse(Supplier<T> action, Supplier<T> alternative) {
        var parent = parent();
        try { return nested(parent, action); } catch (STMRetry ignored) { return nested(parent, alternative); }
    }
    public <T> T catchSTM(Supplier<T> action, Function<Object, T> handler) {
        var parent = parent();
        try { return nested(parent, action); } catch (GuestException failure) { return handler.apply(failure.getPayload()); }
    }
    @TruffleBoundary Transaction beginNested(Transaction parent) {
        var entries = new IdentityHashMap<ManagedTVar, Entry>();
        for (var item : parent.entries.entrySet()) entries.put(item.getKey(), item.getValue().copy());
        var child = new Transaction(entries); restore(child); return child;
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    void commitNested(Transaction parent, Transaction child) {
        lock.lock(); try { validate(child); } finally { lock.unlock(); }
        parent.entries.clear(); parent.entries.putAll(child.entries);
        retire(child);
    }
    private <T> T nested(Transaction parent, Supplier<T> action) {
        var child = beginNested(parent);
        try { T result = action.get(); commitNested(parent, child); return result; }
        catch (Throwable failure) { abort(parent, child); throw failure; }
        finally { restore(parent); }
    }
    final class RetryWait {
        private final Node checkpoint;
        private final IdentityHashMap<ManagedTVar, Object> versions = new IdentityHashMap<>();
        private final Condition ready = lock.newCondition();
        private boolean submitted;
        private boolean changed;
        RetryWait(Transaction tx, Node checkpoint) {
            this.checkpoint = checkpoint;
            for (var item : tx.entries.entrySet()) versions.put(item.getKey(), item.getValue().revision);
        }
        void changedLocked() {
            if (changed) return;
            for (var item : versions.entrySet()) if (item.getKey().revision != item.getValue()) {
                changed = true; ready.signalAll(); break;
            }
        }
        Object await() throws InterruptedException {
            if (checkpoint != null) GuestThreads.checkpointCurrent(checkpoint);
            lock.lockInterruptibly();
            try {
                live();
                if (!submitted) {
                    submitted = true; changedLocked();
                    if (!changed) {
                        // The read dependencies own the wait; the context only inventories it for shutdown.
                        for (var cell : versions.keySet()) {
                            if (cell.waiters == null) cell.waiters = new LinkedHashSet<>();
                            cell.waiters.add(this);
                        }
                        waiters.put(this, true);
                    }
                }
                while (!changed) {
                    live();
                    var request = checkpoint == null ? null : GuestThreads.pollCurrentWithoutYield(checkpoint, true);
                    if (request != null) { cancelLocked(); throw new AsyncBlocked(request, checkpoint); }
                    var blocked = GuestThreads.blocking(GuestThreadStatus.STM);
                    try { ready.await(); }
                    finally {
                        lock.unlock();
                        try { blocked.close(); }
                        finally { lock.lock(); }
                    }
                }
                live();
                return thc.runtime.Unit.INSTANCE;
            } finally { lock.unlock(); }
        }
        void cancelLocked() {
            waiters.remove(this);
            for (var cell : versions.keySet()) if (cell.waiters != null) {
                cell.waiters.remove(this);
                if (cell.waiters.isEmpty()) cell.waiters = null;
            }
            versions.clear();
        }
        void cancel() { lock.lock(); try { cancelLocked(); } finally { lock.unlock(); } }
        void closeLocked() { ready.signalAll(); }
    }
    public int pendingWaiters() { lock.lock(); try { return waiters.size(); } finally { lock.unlock(); } }
    @TruffleBoundary public boolean hasTransaction() { return current.get() != null; }
    @Override @TruffleBoundary public void close() {
        lock.lock();
        try {
            closed = true;
            for (var cell : cells.keySet()) cell.value = null;
            cells.clear();
            for (var waiter : waiters.keySet()) waiter.closeLocked();
        } finally { lock.unlock(); }
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
