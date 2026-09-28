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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** One platform worker per logical HEC, one lifetime routing executor per TSO.
 * The lock protects only ingress and claims; it is never held while mounting a VT.
 * In particular, execute may run BEFORE the previous continuation.run returns. */
public final class LoomScheduler implements AutoCloseable {
    private static final ThreadLocal<Route> CURRENT = new ThreadLocal<>();
    private final TruffleLanguage.Env env;
    private final CpuAffinity affinity;
    private final Constructor<?> builder;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final HashMap<Long, Hec> workers = new HashMap<>();
    private final WeakHashMap<Thread, Route> managed = new WeakHashMap<>();
    private final HashSet<Thread> bootstraps = new HashSet<>();
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

    /** Stable route shared only by this TSO and its short construction bootstrap. */
    final class Route implements Executor {
        final boolean locked;
        volatile long capability;
        volatile GuestThreadId identity;
        private Runnable pending;
        private boolean running, queued;
        private long lastYield;
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
            } finally { lock.unlock(); }
        }
    }

    private final class Hec {
        final long id;
        final ArrayDeque<Route> ready = new ArrayDeque<>();
        final Thread worker;
        volatile boolean affinityApplied;
        Hec(long id) {
            this.id = id;
            worker = Thread.ofPlatform().daemon(true).name("thc-hec-" + id).inheritInheritableThreadLocals(false).unstarted(() -> work(this));
        }
    }

    private Route route(Long pin) {
        acquire();
        try {
            if (stopping || closed) throw new RejectedExecutionException("Loom context is stopping");
            long selected = Math.floorMod(pin == null ? nextCapability++ : pin, capabilities);
            return new Route(selected, pin != null);
        } finally { lock.unlock(); }
    }

    private Hec worker(long id) {
        var worker = workers.get(id);
        if (worker == null) {
            worker = new Hec(id); workers.put(id, worker); worker.worker.start();
        }
        return worker;
    }

    private void assign(Route route, long capability) {
        route.capability = capability;
        if (route.identity != null) route.identity.capability = capability;
    }

    private void publish(Route route) {
        if (route.queued || route.running || route.pending == null) throw new IllegalStateException("Invalid Loom publication");
        assign(route, Math.floorMod(route.capability, capabilities));
        route.queued = true; worker(route.capability).ready.addLast(route); changed.signalAll();
    }

    private Route take(Hec worker) {
        Route selected = worker.ready.pollFirst();
        if (selected == null) {
            // ponytail: a locked ingress scan suffices until contention warrants split queues.
            for (var other : workers.values()) {
                var iterator = other.ready.descendingIterator();
                while (iterator.hasNext()) {
                    var candidate = iterator.next();
                    if (!candidate.locked) { iterator.remove(); selected = candidate; break; }
                }
                if (selected != null) break;
            }
        }
        if (selected != null) {
            if (!selected.queued || selected.running || selected.pending == null) throw new IllegalStateException("Invalid Loom claim");
            selected.queued = false; selected.running = true; assign(selected, worker.id);
            if (selected.identity != null) selected.identity.affinityApplied = selected.locked && worker.affinityApplied;
        }
        return selected;
    }

    private void work(Hec worker) {
        try (var bound = affinity.bindCurrent(worker.id)) {
            worker.affinityApplied = bound != null;
            for (;;) {
                Route route;
                Runnable continuation;
                acquire();
                try {
                    while (true) {
                        if (closed) return;
                        route = worker.id < capabilities ? take(worker) : null;
                        if (route != null) break;
                        changed.awaitUninterruptibly();
                    }
                    continuation = route.pending; route.pending = null;
                } finally { lock.unlock(); }
                try { continuation.run(); }
                finally {
                    acquire();
                    try {
                        // Publication happens only AFTER this mount has fully returned.
                        route.running = false;
                        assign(route, Math.floorMod(route.capability, capabilities));
                        if (route.pending != null) publish(route);
                    } finally { lock.unlock(); }
                }
            }
        } catch (Throwable failure) {
            worker.worker.getUncaughtExceptionHandler().uncaughtException(worker.worker, failure);
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
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    public boolean isCurrent() { var route = CURRENT.get(); return route != null && routeOwner(route) == this; }
    private static LoomScheduler routeOwner(Route route) { return route.owner(); }

    void attach(GuestThreadId identity) {
        var route = CURRENT.get();
        if (route == null || routeOwner(route) != this) throw new RuntimeFault("Loom guest entry requires its managed virtual thread");
        acquire();
        try {
            route.identity = identity; identity.capability = route.capability;
            var worker = workers.get(route.capability);
            identity.affinityApplied = route.locked && worker != null && worker.affinityApplied;
        } finally { lock.unlock(); }
    }

    /** Polls yield only with a waiting competitor, at a bounded time interval. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary public void checkpoint() {
        var route = CURRENT.get();
        if (route == null || routeOwner(route) != this) return;
        if (stopping) throw new Stopped();
        long now = System.nanoTime();
        if (now - route.lastYield < 1_000_000L) return;
        boolean yield;
        acquire();
        try {
            var worker = workers.get(route.capability);
            yield = route.capability >= capabilities || worker != null && !worker.ready.isEmpty();
        } finally { lock.unlock(); }
        if (yield) { route.lastYield = now; Thread.yield(); }
    }

    public Thread newThread(Runnable task, Long pin, Node location) {
        var route = route(pin);
        var construction = new FutureTask<Thread>(() -> env.newTruffleThreadBuilder(() -> {
            CURRENT.set(route);
            try { if (stopping) throw new Stopped(); task.run(); }
            finally { CURRENT.remove(); }
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
                } finally { lock.unlock(); }
                Thread thread = await(construction, location);
                TruffleSafepoint.setBlockedThreadInterruptible(location, Thread::join, bootstrap);
                thread.setUncaughtExceptionHandler((_, failure) -> {
                    if (!(failure instanceof Stopped) || !stopping) failure.printStackTrace();
                });
                acquire();
                try {
                    if (stopping) throw new RejectedExecutionException("Loom context is stopping");
                    managed.put(thread, route);
                } finally { lock.unlock(); }
                return thread;
            } finally {
                // Cancellation of the parent must not abandon a live bootstrap.
                // Its separate registry keeps shutdown responsible for joining it.
                if (!bootstrap.isAlive()) {
                    acquire(); try { bootstraps.remove(bootstrap); } finally { lock.unlock(); }
                }
            }
        } catch (ReflectiveOperationException failure) { throw new RuntimeFault("Loom thread construction failed: " + failure); }
    }

    public <T> T invoke(Node location, Callable<T> action) {
        var result = new Invocation<>(action);
        var thread = newThread(result, null, location);
        thread.setUncaughtExceptionHandler((_, failure) -> result.fail(failure));
        start(thread);
        try { return await(result, location); }
        finally { TruffleSafepoint.setBlockedThreadInterruptible(location, Thread::join, thread); }
    }

    public void start(Thread thread) {
        acquire();
        try {
            if (stopping || closed) throw new RejectedExecutionException("Loom context is stopping");
            if (!managed.containsKey(thread)) throw new IllegalArgumentException("Thread belongs to another Loom scheduler");
            thread.start();
        } finally { lock.unlock(); }
    }

    private static final class Invocation<T> extends FutureTask<T> {
        Invocation(Callable<T> action) { super(action); }
        void fail(Throwable failure) { setException(failure); }
    }

    private static <T> T await(FutureTask<T> task, Node location) {
        return TruffleSafepoint.setBlockedThreadInterruptibleFunction(location, waiting -> {
            try { return waiting.get(); }
            catch (ExecutionException failure) { return rethrow(failure.getCause()); }
        }, task);
    }

    /** Keep resume ingress alive until managed and construction VTs terminate. */
    public void stopThreads() {
        Thread[] threads, constructors;
        acquire();
        try {
            stopping = true;
            threads = managed.keySet().stream().filter(Thread::isAlive).toArray(Thread[]::new);
            constructors = bootstraps.toArray(Thread[]::new);
        }
        finally { lock.unlock(); }
        if (threads.length != 0) env.submitThreadLocal(threads, new ThreadLocalAction(true, false) {
            @Override protected void perform(Access access) { throw new Stopped(); }
        });
        for (var thread : threads) if (thread != Thread.currentThread())
            TruffleSafepoint.setBlockedThreadInterruptible(null, Thread::join, thread);
        // Bootstrap VTs are not Truffle-managed: join, never target a guest action.
        for (var thread : constructors) TruffleSafepoint.setBlockedThreadInterruptible(null, Thread::join, thread);
        acquire(); try { bootstraps.clear(); } finally { lock.unlock(); }
    }

    @Override public void close() {
        ArrayList<Thread> carriers;
        acquire();
        try {
            closed = true; changed.signalAll(); carriers = new ArrayList<>();
            for (var worker : workers.values()) carriers.add(worker.worker);
        } finally { lock.unlock(); }
        boolean interrupted = false;
        for (var carrier : carriers) for (;;) {
            try { carrier.join(); break; } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @SuppressWarnings("removal") private static final class Stopped extends ThreadDeath { }
    @SuppressWarnings("unchecked") private static <T, E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
