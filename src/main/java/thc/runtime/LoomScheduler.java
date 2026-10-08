// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.ThreadLocalAction;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** One exclusive guest permit per logical HEC, one lifetime routing executor per TSO.
 * The lock protects only ingress and claims; it is never held while mounting a VT.
 * In particular, execute may run BEFORE the previous continuation.run returns. */
public final class LoomScheduler implements AutoCloseable {
    private static final ThreadLocal<Route> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Admission> BOUND = new ThreadLocal<>();
    private final TruffleLanguage.Env env;
    private final CpuAffinity affinity;
    private final Constructor<?> builder;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final HashMap<Long, Hec> workers = new HashMap<>();
    private final WeakHashMap<Thread, Route> managed = new WeakHashMap<>();
    private final HashSet<Thread> bootstraps = new HashSet<>();
    private final ArrayList<Thread> allCarriers = new ArrayList<>();
    private final HashSet<Admission> callbacks = new HashSet<>();
    private final ArrayDeque<Semaphore> wakeups = new ArrayDeque<>();
    private long capabilities, nextCapability;
    private volatile boolean stopping;
    private boolean closed;

    public LoomScheduler(TruffleLanguage.Env env, CpuAffinity affinity) {
        this.env = env; this.affinity = affinity; capabilities = affinity.getCount();
        if (!env.isCreateThreadAllowed()) throw new RuntimeFault("Loom hosting requires context thread creation permission");
        if (Runtime.version().feature() != 25) throw new RuntimeFault("Loom hosting requires the pinned JDK 25 custom scheduler hook");
        try {
            builder = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder").getDeclaredConstructor(Executor.class);
            builder.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new RuntimeFault("Loom hosting requires --add-opens=java.base/java.lang=ALL-UNNAMED on the pinned JDK 25: " + failure);
        }
    }

    private void acquire() {
        // An unmounted VT queued on this lock would precede its old wrapper's
        // release in the AQS queue, but cannot mount until that wrapper returns.
        // Keep virtual callers mounted through these short metadata sections.
        // HEC workers may park; none of these sections executes guest code.
        if (Thread.currentThread().isVirtual()) while (!lock.tryLock()) Thread.onSpinWait();
        else lock.lock();
    }

    /** Unpark can synchronously enter another context's routing executor. */
    private void unlock() {
        ArrayList<Semaphore> pending = null;
        if (lock.getHoldCount() == 1 && !wakeups.isEmpty()) {
            pending = new ArrayList<>(wakeups); wakeups.clear();
        }
        lock.unlock();
        if (pending != null) for (var wake : pending) wake.release();
    }

    private static Admission currentAdmission() {
        var callback = BOUND.get();
        if (callback != null) return callback;
        var route = CURRENT.get(); return route == null ? null : route.admission;
    }

    /** Stable route shared only by this TSO and its short construction bootstrap. */
    final class Route implements Executor {
        final boolean locked;
        volatile long capability;
        volatile GuestThreadId identity;
        private Runnable pending;
        private boolean running, queued;
        private Carrier carrier;
        private volatile Admission admission = new Admission(this, null, false);
        Route(long capability, boolean locked) { this.capability = capability; this.locked = locked; }
        LoomScheduler owner() { return LoomScheduler.this; }
        @Override public void execute(Runnable continuation) {
            acquire();
            try {
                if (closed) throw new RejectedExecutionException("Loom context scheduler has closed");
                if (pending != null) throw new IllegalStateException("Duplicate pending Loom continuation");
                pending = Objects.requireNonNull(continuation);
                // afterYield and external unpark can submit while running is still true.
                if (!running) publish(this);
            } finally { unlock(); }
        }
    }

    private final class Hec {
        final long id;
        final ArrayDeque<Route> ready = new ArrayDeque<>();
        final ArrayDeque<Admission> waiting = new ArrayDeque<>();
        final ArrayList<Carrier> carriers = new ArrayList<>();
        Admission permit;
        int idle, releasedMounts, serial;
        Hec(long id) { this.id = id; }
    }

    private final class Carrier {
        final Hec hec;
        final Thread thread;
        boolean released;
        volatile boolean affinityApplied;
        Carrier(Hec hec) {
            this.hec = hec;
            thread = Thread.ofPlatform().daemon(true).name("thc-hec-" + hec.id + "-" + hec.serial++)
                .inheritInheritableThreadLocals(false).unstarted(() -> work(this));
        }
    }

    /** Mount exclusion and guest admission are deliberately independent. */
    final class Admission implements AutoCloseable {
        final Route route;
        final Admission previous;
        final boolean callback;
        final Thread thread;
        final CountDownLatch finished;
        GuestThreadId identity;
        final Semaphore ready = new Semaphore(0);
        Hec hec;
        long lastYield;
        boolean suspended, held, waiting;
        Admission(Route route, Admission previous, boolean callback) {
            this.route = route; this.previous = previous; this.callback = callback;
            thread = callback ? Thread.currentThread() : null; finished = callback ? new CountDownLatch(1) : null;
        }
        LoomScheduler owner() { return LoomScheduler.this; }
        @Override public void close() { resumeGuest(this, null); }
    }

    private void refresh(Admission admission) {
        if (admission.route == null || admission.route.owner() != this) return;
        var carrier = admission.route.carrier;
        if (carrier == null) return;
        var active = admission.route.admission;
        boolean released = active.owner() != this || !active.held;
        if (released != carrier.released) {
            carrier.released = released;
            carrier.hec.releasedMounts += released ? 1 : -1;
        }
    }

    private void claim(Admission admission, Hec hec) {
        if (hec.permit != null || admission.held) throw new IllegalStateException("HEC already admitted a guest");
        admission.hec = hec; hec.permit = admission; admission.held = true; refresh(admission);
    }

    private void release(Admission admission) {
        release(admission, false);
    }

    private void release(Admission admission, boolean preferRunnable) {
        if (!admission.held) return;
        var hec = admission.hec;
        if (hec.permit != admission) throw new IllegalStateException("Guest does not own its HEC permit");
        hec.permit = null; admission.held = false; refresh(admission);
        // A pinned callback cannot yield its continuation. Reserve a queued
        // guest's permit before the callback can attempt to reacquire it.
        if (preferRunnable) for (var route : hec.ready) {
            var next = route.admission;
            if (next.owner() == this && !next.suspended && !next.waiting && !next.held) {
                claim(next, hec); break;
            }
        }
        if (hec.permit == null) {
            var next = hec.waiting.pollFirst();
            if (next != null) {
                next.waiting = false; claim(next, hec); wakeups.addLast(next.ready);
            }
        }
        ensureCarrier(hec); changed.signalAll();
    }

    /** Called before native entry, while the original continuation is still mounted. */
    Admission suspendGuest() {
        var admission = currentAdmission();
        if (admission == null || admission.owner() != this) return null;
        acquire();
        try {
            if (admission.suspended) return null;
            if (!admission.held) throw new IllegalStateException("Guest suspended without HEC admission");
            admission.suspended = true; release(admission); return admission;
        } finally { unlock(); }
    }

    static Admission suspendCurrentGuest() {
        var admission = currentAdmission();
        return admission == null ? null : admission.owner().suspendGuest();
    }

    Admission enterCallback(Node location) {
        var route = CURRENT.get();
        var previous = currentAdmission();
        Admission callback;
        acquire();
        try {
            if (stopping || closed) throw new RejectedExecutionException("Loom context is stopping");
            if (previous != null && (!previous.suspended || previous.held)) throw new IllegalStateException("Foreign caller retained guest admission");
            callback = new Admission(route, previous, true);
            callback.hec = previous != null && previous.owner() == this ? previous.hec : worker(Math.floorMod(nextCapability++, capabilities));
            callback.suspended = true; callbacks.add(callback);
            if (route != null) route.admission = callback;
            BOUND.set(callback);
        } finally { unlock(); }
        try { resumeGuest(callback, location); return callback; }
        catch (Throwable failure) { leaveCallback(callback); throw failure; }
    }

    void leaveCallback(Admission callback) {
        acquire();
        try {
            var route = callback.route;
            if (currentAdmission() != callback || !callback.callback) throw new IllegalStateException("Callback admission exited out of order");
            callback.suspended = true; release(callback);
            if (route != null) {
                route.admission = callback.previous;
                if (route.owner() == this) route.identity = callback.previous.identity;
            }
            if (callback.previous != null && callback.previous.callback) BOUND.set(callback.previous); else BOUND.remove();
            callbacks.remove(callback);
        } finally { unlock(); }
        callback.finished.countDown();
    }

    void resumeGuest(Admission admission, Node location) {
        if (admission == null) return;
        boolean wait;
        acquire();
        try {
            if (!admission.suspended || admission.held || admission.waiting) throw new IllegalStateException("Invalid guest readmission");
            remapBoundCallback(admission);
            admission.suspended = false;
            wait = admission.hec.permit != null;
            if (wait) { admission.waiting = true; admission.hec.waiting.addLast(admission); }
            else claim(admission, admission.hec);
        } finally { unlock(); }
        try {
            while (wait) {
                TruffleSafepoint.setBlockedThreadInterruptible(location, Semaphore::acquire, admission.ready);
                acquire();
                try {
                    // A retiring HEC may revoke and move an unmounted reservation.
                    // Its already-published wake is only a notification, not a permit.
                    remapBoundCallback(admission);
                    wait = !admission.held;
                    if (!wait) admission.ready.drainPermits();
                } finally { unlock(); }
            }
        } catch (Throwable failure) {
            acquire();
            try {
                if (admission.waiting) { admission.hec.waiting.remove(admission); admission.waiting = false; }
                admission.suspended = true; release(admission); admission.ready.drainPermits();
            } finally { unlock(); }
            throw failure;
        }
    }

    private void ensureCarrier(Hec hec) {
        // One dispatch carrier plus one for each actually retained released mount.
        // An unmount immediately returns its compensation allowance.
        if (hec.idle == 0 && !hec.ready.isEmpty() && hec.carriers.size() <= hec.releasedMounts) {
            allCarriers.removeIf(thread -> !thread.isAlive());
            var carrier = new Carrier(hec); hec.carriers.add(carrier); hec.idle++;
            try { carrier.thread.start(); allCarriers.add(carrier.thread); }
            catch (Throwable failure) { hec.idle--; hec.carriers.remove(carrier); throw failure; }
        }
    }

    private Route route(Long pin) {
        acquire();
        try {
            if (stopping || closed) throw new RejectedExecutionException("Loom context is stopping");
            long selected = Math.floorMod(pin == null ? nextCapability++ : pin, capabilities);
            return new Route(selected, pin != null);
        } finally { unlock(); }
    }

    private Hec worker(long id) {
        var worker = workers.get(id);
        if (worker == null) {
            worker = new Hec(id); workers.put(id, worker);
        }
        return worker;
    }

    private void assign(Route route, long capability) {
        route.capability = capability;
        for (var admission = route.admission; admission != null; admission = admission.previous)
            if (admission.owner() == this && admission.identity != null) admission.identity.capability = capability;
    }

    private void publish(Route route) {
        if (route.queued || route.running || route.pending == null) throw new IllegalStateException("Invalid Loom publication");
        assign(route, Math.floorMod(route.capability, capabilities));
        route.queued = true;
        var hec = worker(route.capability);
        var admission = route.admission;
        if (admission.owner() == this) remap(admission, hec);
        hec.ready.addLast(route); ensureCarrier(hec); changed.signalAll();
    }

    /** Move only at an unmounted boundary, including a foreign route's remount. */
    private void remap(Admission admission, Hec hec) {
        if (admission.hec == null || admission.hec == hec) return;
        boolean pending = admission.waiting || admission.held;
        if (admission.waiting) { admission.hec.waiting.remove(admission); admission.waiting = false; }
        release(admission);
        admission.hec = hec;
        if (admission.identity != null) admission.identity.capability = hec.id;
        if (pending) {
            if (hec.permit == null) { claim(admission, hec); wakeups.addLast(admission.ready); }
            else { admission.waiting = true; hec.waiting.addLast(admission); }
        }
    }

    /** A platform callback has no route unmount; releasing admission is its boundary. */
    private void remapBoundCallback(Admission admission) {
        if (admission.route == null) remap(admission, worker(Math.floorMod(admission.hec.id, capabilities)));
    }

    private Route take(Hec worker) {
        Route selected = null;
        var own = worker.ready.iterator();
        while (own.hasNext()) {
            var candidate = own.next();
            if (admissible(candidate, worker)) { own.remove(); selected = candidate; break; }
        }
        if (selected == null) {
            // ponytail: a locked ingress scan suffices until contention warrants split queues.
            for (var other : workers.values()) {
                var iterator = other.ready.descendingIterator();
                while (iterator.hasNext()) {
                    var candidate = iterator.next();
                    if (!candidate.locked && candidate.admission.owner() == this && !candidate.admission.waiting &&
                        !candidate.admission.held && admissible(candidate, worker)) { iterator.remove(); selected = candidate; break; }
                }
                if (selected != null) break;
            }
        }
        if (selected != null) {
            if (!selected.queued || selected.running || selected.pending == null) throw new IllegalStateException("Invalid Loom claim");
            selected.queued = false; selected.running = true; assign(selected, worker.id);
            var admission = selected.admission;
            for (var entry = admission; entry != null; entry = entry.previous) if (entry.owner() == this) entry.hec = worker;
            if (admission.owner() == this && !admission.suspended && !admission.held) claim(admission, worker);
        }
        return selected;
    }

    private boolean admissible(Route route, Hec worker) {
        return route.admission.owner() != this || route.admission.suspended || worker.permit == null || worker.permit == route.admission;
    }

    /** A foreign context's VT can unmount while executing this context's callback. */
    private void admitMount(Admission admission) {
        acquire();
        try {
            for (;;) {
                remap(admission, worker(Math.floorMod(admission.hec.id, capabilities)));
                if (admission.suspended || admission.held || admission.hec.permit == null) break;
                changed.awaitUninterruptibly();
            }
            if (!admission.suspended && !admission.held) claim(admission, admission.hec);
        } finally { unlock(); }
    }

    private void releaseMount(Admission admission) {
        acquire(); try { release(admission); } finally { unlock(); }
    }

    private void work(Carrier carrier) {
        var worker = carrier.hec;
        try (var bound = affinity.bindCurrent(worker.id)) {
            carrier.affinityApplied = bound != null;
            for (;;) {
                Route route;
                Runnable continuation;
                acquire();
                try {
                    while (true) {
                        if (closed || worker.carriers.size() > worker.releasedMounts + 1) {
                            worker.idle--; worker.carriers.remove(carrier); return;
                        }
                        route = worker.id < capabilities ? take(worker) : null;
                        if (route != null) break;
                        changed.awaitUninterruptibly();
                    }
                    continuation = route.pending; route.pending = null;
                    worker.idle--; route.carrier = carrier; refresh(route.admission);
                    if (route.identity != null) route.identity.affinityApplied = route.locked && carrier.affinityApplied;
                    ensureCarrier(worker);
                } finally { unlock(); }
                try {
                    var admission = route.admission;
                    if (admission.owner() != this) admission.owner().admitMount(admission);
                    continuation.run();
                }
                finally {
                    var admission = route.admission;
                    if (admission.owner() != this) admission.owner().releaseMount(admission);
                    acquire();
                    try {
                        // Publication happens only AFTER this mount has fully returned.
                        if (carrier.released) { carrier.released = false; worker.releasedMounts--; }
                        route.carrier = null; worker.idle++;
                        if (route.admission.owner() == this) release(route.admission);
                        route.running = false;
                        assign(route, Math.floorMod(route.capability, capabilities));
                        if (route.pending != null) publish(route);
                    } finally { unlock(); }
                }
            }
        } catch (Throwable failure) {
            carrier.thread.getUncaughtExceptionHandler().uncaughtException(carrier.thread, failure);
        }
    }

    /** Remap parked/queued TSOs now; mounted TSOs remap when their run returns. */
    public void resize(long count) {
        acquire();
        try {
            capabilities = count; nextCapability %= count;
            var ready = new ArrayList<Route>();
            for (var worker : workers.values()) { ready.addAll(worker.ready); worker.ready.clear(); }
            for (var route : managed.values()) if (!route.running) assign(route, Math.floorMod(route.capability, count));
            for (var route : ready) { route.queued = false; publish(route); }
            for (var callback : callbacks) if (!callback.held) remapBoundCallback(callback);
            changed.signalAll();
        } finally { unlock(); }
    }

    public boolean isCurrent() { var admission = currentAdmission(); return admission != null && admission.owner() == this; }

    void attach(GuestThreadId identity) {
        var admission = currentAdmission();
        if (admission == null || admission.owner() != this) throw new RuntimeFault("Loom guest entry requires its managed virtual thread or admitted bound callback");
        acquire();
        try {
            var route = admission.route;
            if (route != null && route.owner() == this) route.identity = identity;
            identity.capability = admission.hec.id; admission.identity = identity;
            identity.affinityApplied = route != null && route.owner() == this && route.locked && route.carrier != null && route.carrier.affinityApplied;
        } finally { unlock(); }
    }

    void detach(GuestThreadId identity) {
        var admission = currentAdmission();
        acquire();
        try {
            if (admission.identity == identity) admission.identity = null;
            var route = admission.route;
            if (route != null && route.identity == identity) route.identity = null;
        } finally { unlock(); }
    }

    /** Polls yield only with a waiting competitor, at a bounded time interval. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary public void checkpoint() {
        var admission = currentAdmission();
        if (admission == null || admission.owner() != this) return;
        if (stopping) throw new Stopped();
        long now = System.nanoTime();
        if (now - admission.lastYield < 1_000_000L) return;
        boolean yield;
        acquire();
        try {
            if (admission.suspended || !admission.held) return;
            var worker = admission.hec;
            yield = worker.id >= capabilities || !worker.ready.isEmpty() || !worker.waiting.isEmpty();
            if (yield && admission.callback) { admission.suspended = true; release(admission, true); }
        } finally { unlock(); }
        if (yield) {
            admission.lastYield = now;
            if (admission.callback) resumeGuest(admission, null); else Thread.yield();
        }
    }

    public Thread newThread(Runnable task, Long pin, Node location) {
        var route = route(pin);
        var construction = new FutureTask<Thread>(() -> env.newTruffleThreadBuilder(() -> {
            CURRENT.set(route);
            try { if (stopping) throw new Stopped(); task.run(); }
            finally { BOUND.remove(); CURRENT.remove(); }
        }).virtual(true).build());
        try {
            var bootstrap = ((Thread.Builder.OfVirtual) builder.newInstance(route))
                .inheritInheritableThreadLocals(false).unstarted(construction);
            try {
                acquire();
                try {
                    if (stopping || closed) throw new RejectedExecutionException("Loom context is stopping");
                    // Admission and start are atomic with the shutdown snapshot.
                    bootstraps.add(bootstrap); bootstrap.start();
                } finally { unlock(); }
                Thread thread = awaitConstruction(construction, bootstrap, location);
                thread.setUncaughtExceptionHandler((_, failure) -> {
                    if (!(failure instanceof Stopped) || !stopping) failure.printStackTrace();
                });
                acquire();
                try {
                    if (stopping) throw new RejectedExecutionException("Loom context is stopping");
                    managed.put(thread, route);
                } finally { unlock(); }
                return thread;
            } finally {
                // Cancellation of the parent must not abandon a live bootstrap.
                // Its separate registry keeps shutdown responsible for joining it.
                if (!bootstrap.isAlive()) {
                    acquire(); try { bootstraps.remove(bootstrap); } finally { unlock(); }
                }
            }
        } catch (ReflectiveOperationException failure) { throw new RuntimeFault("Loom thread construction failed: " + failure); }
    }

    private Thread awaitConstruction(FutureTask<Thread> construction, Thread bootstrap, Node location) {
        if (env.getContext().isEntered()) {
            Thread thread = await(construction, location);
            join(bootstrap, location);
            return thread;
        }
        // JVM service workers have no entered Truffle safepoint or guest HEC to release.
        // Keep construction owned through interruption; shutdown also tracks the bootstrap.
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    Thread thread = construction.get();
                    bootstrap.join();
                    return thread;
                } catch (InterruptedException ignored) { interrupted = true; }
                catch (ExecutionException failure) { return rethrow(failure.getCause()); }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    public <T> T invoke(Node location, Callable<T> action) {
        var result = new Invocation<>(action);
        var thread = newThread(result, null, location);
        thread.setUncaughtExceptionHandler((_, failure) -> result.fail(failure));
        start(thread);
        try { return await(result, location); }
        finally { join(thread, location); }
    }

    public void start(Thread thread) {
        acquire();
        try {
            if (stopping || closed) throw new RejectedExecutionException("Loom context is stopping");
            if (!managed.containsKey(thread)) throw new IllegalArgumentException("Thread belongs to another Loom scheduler");
            thread.start();
        } finally { unlock(); }
    }

    private static final class Invocation<T> extends FutureTask<T> {
        Invocation(Callable<T> action) { super(action); }
        void fail(Throwable failure) { setException(failure); }
    }

    private static <T> T await(FutureTask<T> task, Node location) {
        try (var admission = suspendCurrentGuest()) {
            return TruffleSafepoint.setBlockedThreadInterruptibleFunction(location, waiting -> {
                try { return waiting.get(); }
                catch (ExecutionException failure) { return rethrow(failure.getCause()); }
            }, task);
        }
    }

    private static void join(Thread thread, Node location) {
        try (var admission = suspendCurrentGuest()) {
            TruffleSafepoint.setBlockedThreadInterruptible(location, Thread::join, thread);
        }
    }

    /** Keep resume ingress alive until managed and construction VTs terminate. */
    public void stopThreads() {
        Thread[] threads, constructors;
        Admission[] bound;
        acquire();
        try {
            stopping = true;
            threads = managed.keySet().stream().filter(Thread::isAlive).toArray(Thread[]::new);
            constructors = bootstraps.toArray(Thread[]::new);
            bound = callbacks.toArray(Admission[]::new);
        }
        finally { unlock(); }
        var targets = java.util.stream.Stream.concat(java.util.Arrays.stream(threads), java.util.Arrays.stream(bound).map(callback -> callback.thread))
            .distinct().toArray(Thread[]::new);
        if (targets.length != 0) env.submitThreadLocal(targets, new ThreadLocalAction(true, false) {
            @Override protected void perform(Access access) { throw new Stopped(); }
        });
        for (var thread : threads) if (thread != Thread.currentThread())
            TruffleSafepoint.setBlockedThreadInterruptible(null, Thread::join, thread);
        for (var callback : bound) if (callback.thread != Thread.currentThread())
            TruffleSafepoint.setBlockedThreadInterruptible(null, CountDownLatch::await, callback.finished);
        // Bootstrap VTs are not Truffle-managed: join, never target a guest action.
        for (var thread : constructors) TruffleSafepoint.setBlockedThreadInterruptible(null, Thread::join, thread);
        acquire(); try { bootstraps.clear(); } finally { unlock(); }
    }

    @Override public void close() {
        ArrayList<Thread> carriers;
        acquire();
        try {
            closed = true; changed.signalAll(); carriers = new ArrayList<>();
            carriers.addAll(allCarriers);
        } finally { unlock(); }
        boolean interrupted = false;
        for (var carrier : carriers) for (;;) {
            try { carrier.join(); break; } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @SuppressWarnings("removal") private static final class Stopped extends ThreadDeath { }
    @SuppressWarnings("unchecked") private static <T, E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
