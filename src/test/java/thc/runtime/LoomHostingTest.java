// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.EntryValue;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
public class LoomHostingTest {
    private Context context() {
        return Context.newBuilder("thc").allowCreateThread(true).allowExperimentalOptions(true)
            .option("thc.ThreadHosting", "loom").build();
    }
    private record Task<T>(Thread thread, CompletableFuture<T> result) { }
    private <T> Task<T> start(Language.State state, Long pin, Callable<T> action) {
        var result = new CompletableFuture<T>();
        var threads = state.getThreads();
        var thread = threads.newThread(state.getEnv(), () -> {
            threads.enterCurrent(MaskingState.UNMASKED, true, true, pin);
            try { result.complete(action.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            finally { threads.leaveCurrent(); }
        }, pin, null);
        thread.setUncaughtExceptionHandler((_, failure) -> result.completeExceptionally(failure));
        threads.startThread(thread);
        return new Task<>(thread, result);
    }
    private <T> T await(Future<T> future) {
        return TruffleSafepoint.setBlockedThreadInterruptibleFunction(null, task -> {
            try { return task.get(5, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) { throw new AssertionError(failure); }
        }, future);
    }
    private void latch(CountDownLatch latch) {
        TruffleSafepoint.setBlockedThreadInterruptible(null, waiting -> assertTrue(waiting.await(5, TimeUnit.SECONDS)), latch);
    }
    /** A real public entry observes the executing guest thread. */
    private long guestThreadKind(Context context) {
        context.initialize("thc"); context.enter();
        try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            var target = new RootNode(language) {
                @Override public Object execute(VirtualFrame frame) {
                    Language.currentState(this).getThreads().currentIdentity();
                    return Thread.currentThread().isVirtual() ? 1L : 0L;
                }
            }.getCallTarget();
            var program = new ExecutableProgram() {
                public boolean getAsynchronousExceptions() { return true; }
                public RootCallTarget hostEntryTarget(int arity) { return target; }
                public Object entryValue(String name) { return Unit.INSTANCE; }
                public RootCallTarget entryTarget(String name) { return target; }
                public DataLayout constructorLayout(String id) { throw new UnsupportedOperationException(); }
                public Map<String, Object> diagnostics() { return Map.of(); }
            };
            return context.asValue(new EntryValue(program, "threadKind", 0)).execute().asLong();
        } finally { context.leave(); }
    }

    @Test public void platformIsTheDefaultHost() {
        try (var context = Context.newBuilder("thc").build()) { assertEquals(0L, guestThreadKind(context)); }
    }

    @Test public void selectedLoomHostsThePublicEntryOnAVirtualThread() {
        try (var context = context()) {
            assertEquals(1L, guestThreadKind(context));
        }
    }

    @Test public void oneHecRunsAProducerWhileManyMVarTakersAreParked() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getThreads().setCapabilityCount(1);
                var cell = new ManagedMVar();
                var tasks = new ArrayList<Task<Long>>();
                for (int i = 0; i < 64; i++) tasks.add(start(state, null, () -> (Long) cell.take(null)));
                var producer = start(state, null, () -> {
                    for (long i = 1; i <= 64; i++) cell.put(i, null);
                    return 0L;
                });
                long sum = 0; for (var task : tasks) sum += await(task.result());
                assertEquals(2080L, sum); assertEquals(0L, await(producer.result()));
                assertTrue(cell.isEmpty());
                for (var task : tasks) assertTrue(task.thread().isVirtual());
            } finally { context.leave(); }
        }
    }

    @Test public void childConstructionUsesAnIndependentRouteAndPreservesParentLocals() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getThreads().setCapabilityCount(2);
                var local = new ThreadLocal<String>();
                var parent = start(state, 0L, () -> {
                    var identity = state.getThreads().currentIdentity();
                    var javaThread = Thread.currentThread();
                    state.getMaskingState().set(MaskingState.MASKED_INTERRUPTIBLE); local.set("parent");
                    var child = start(state, 1L, () -> {
                        assertNull(local.get()); assertEquals(MaskingState.UNMASKED, state.getMaskingState().get());
                        assertEquals(1L, state.getThreads().currentIdentity().getCapability());
                        return state.getThreads().currentIdentity();
                    });
                    var childId = await(child.result());
                    assertNotSame(identity, childId); assertEquals(0L, identity.getCapability());
                    assertSame(javaThread, Thread.currentThread()); assertEquals("parent", local.get());
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.getMaskingState().get());
                    assertSame(identity, state.getThreads().currentIdentity());
                    return 17L;
                });
                assertEquals(17L, await(parent.result()));
            } finally { context.leave(); }
        }
    }

    @Test public void resizeMovesParkedTsoWithoutChangingIdentityMaskOrLocals() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getThreads().setCapabilityCount(2);
                var ready = new CountDownLatch(1); var release = new ManagedMVar();
                var local = new ThreadLocal<String>();
                var task = start(state, 1L, () -> {
                    var id = state.getThreads().currentIdentity(); var javaThread = Thread.currentThread();
                    assertEquals(1L, id.getCapability()); local.set("saved");
                    state.getMaskingState().set(MaskingState.MASKED_UNINTERRUPTIBLE); ready.countDown();
                    release.take(null);
                    assertSame(javaThread, Thread.currentThread()); assertSame(id, state.getThreads().currentIdentity());
                    assertEquals("saved", local.get()); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, state.getMaskingState().get());
                    return id.getCapability();
                });
                latch(ready);
                // Executing on the sole old worker proves the waiter has unmounted.
                await(start(state, 1L, () -> null).result());
                state.getThreads().setCapabilityCount(1); assertTrue(release.tryPut(1L));
                assertEquals(0L, await(task.result()));
            } finally { context.leave(); }
        }
    }

    @Test public void queuedRunnableStealsToIdleHecWithItsIdentityMaskAndLocals() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var releaseCarrier = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                var state = Language.currentState(); state.getThreads().setCapabilityCount(2);
                await(start(state, 0L, () -> null).result()); await(start(state, 1L, () -> null).result());
                var parked = new CountDownLatch(1); var cell = new ManagedMVar();
                var original = new java.util.concurrent.atomic.AtomicLong(); var local = new ThreadLocal<String>();
                var task = start(state, null, () -> {
                    var id = state.getThreads().currentIdentity(); var thread = Thread.currentThread();
                    original.set(id.getCapability()); local.set("migrating");
                    state.getMaskingState().set(MaskingState.MASKED_INTERRUPTIBLE); parked.countDown();
                    cell.take(null);
                    assertSame(thread, Thread.currentThread()); assertSame(id, state.getThreads().currentIdentity());
                    assertEquals("migrating", local.get()); assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.getMaskingState().get());
                    return id.getCapability();
                });
                latch(parked); await(start(state, original.get(), () -> null).result());
                var occupied = new CountDownLatch(1);
                var blocker = start(state, original.get(), () -> {
                    occupied.countDown(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    // No park or guest poll: hold exactly the original exclusive HEC.
                    while (!releaseCarrier.get() && System.nanoTime() < deadline) Thread.onSpinWait();
                    assertTrue(releaseCarrier.get()); return null;
                });
                latch(occupied); assertTrue(cell.tryPut(1L));
                assertEquals(1L - original.get(), await(task.result()));
                releaseCarrier.set(true); await(blocker.result());
            } finally { releaseCarrier.set(true); context.leave(); }
        }
    }

    @Test public void foreignAndAllocationLimitsAreExplicitOnVirtualThreads() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState();
                await(start(state, null, () -> {
                    var threads = state.getThreads();
                    assertThrows(UnsupportedCore.class, () -> threads.enterForeign(ForeignSafety.SAFE));
                    assertThrows(UnsupportedCore.class, () -> threads.enterForeign(ForeignSafety.INTERRUPTIBLE));
                    assertThrows(UnsupportedCore.class, () -> state.getSignals().install(2, -1, ManagedAddress.nullAddress()));
                    assertThrows(RuntimeFault.class, threads::allocationCounter);
                    assertThrows(RuntimeFault.class, () -> threads.setAllocationCounter(10));
                    assertEquals(RuntimeServiceStatus.UNSUPPORTED, RuntimeThreadServices.accounting(107));
                    assertEquals(RuntimeServiceStatus.DENIED, RuntimeThreadServices.query(threads, false, 103, 0L, 0L));
                    var previous = threads.enterForeign(ForeignSafety.UNSAFE); threads.leaveForeign(previous);
                    return null;
                }).result());
            } finally { context.leave(); }
        }
    }

    @Test public void hostingRejectsUnknownModesAndMissingThreadPermission() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("thc.ThreadHosting", "automatic").build()) {
            var failure = assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> context.initialize("thc"));
            assertTrue(failure.getMessage().contains("Unknown THC thread hosting mode"));
        }
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("thc.ThreadHosting", "loom").build()) {
            var failure = assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> context.initialize("thc"));
            assertTrue(failure.getMessage().contains("thread creation permission"));
        }
    }

    @Test public void unavailableCustomSchedulerHookFailsWithoutPlatformFallback() throws Exception {
        var command = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
            "--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED", "-cp",
            System.getProperty("thc.testRuntimeClasspath"), LoomHostingTest.class.getName()).redirectErrorStream(true);
        for (var variable : java.util.List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) command.environment().remove(variable);
        var process = command.start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output); assertTrue(output.contains("missing-scheduler-hook"), output);
    }

    public static void main(String[] arguments) {
        try (var context = Context.newBuilder("thc").allowCreateThread(true).allowExperimentalOptions(true)
                .option("thc.ThreadHosting", "loom").build()) {
            try { context.initialize("thc"); throw new AssertionError("Loom silently accepted the unavailable hook"); }
            catch (org.graalvm.polyglot.PolyglotException failure) {
                if (!failure.getMessage().contains("Loom hosting requires --add-opens")) throw failure;
                System.out.println("missing-scheduler-hook");
            }
        }
    }

    @Test public void shutdownWakesAndJoinsMVarWaitersBeforeClosingTheirCarriers() {
        var context = context(); context.initialize("thc"); context.enter();
        Task<Object> task;
        var ready = new CountDownLatch(1);
        try {
            var state = Language.currentState(); state.getThreads().setCapabilityCount(1);
            task = start(state, null, () -> { ready.countDown(); return new ManagedMVar().take(null); });
            latch(ready);
        } finally { context.leave(); }
        context.close(); assertFalse(task.thread().isAlive()); assertTrue(task.result().isDone());
    }

    @Test public void mvarCancellationAndCommitRaceOnTwoManagedHecs() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getThreads().setCapabilityCount(2);
                for (var operation : ManagedMVar.Operation.values()) for (int i = 0; i < 16; i++) {
                    var cell = new ManagedMVar(); var old = new Object(); var value = new Object();
                    if (operation == ManagedMVar.Operation.PUT) assertTrue(cell.tryPut(old));
                    var request = switch (operation) {
                        case TAKE -> cell.beginTake(); case READ -> cell.beginRead(); case PUT -> cell.beginPut(value);
                    };
                    var waiter = start(state, null, () -> {
                        try { assertSame(operation == ManagedMVar.Operation.PUT ? null : value, request.await()); return true; }
                        catch (java.util.concurrent.CancellationException cancelled) { return false; }
                    });
                    var gate = new CountDownLatch(1);
                    var cancel = start(state, 0L, () -> { gate.await(); return request.cancel(); });
                    var commit = start(state, 1L, () -> {
                        gate.await();
                        if (operation == ManagedMVar.Operation.PUT) assertSame(old, cell.tryTake().getValue());
                        else assertTrue(cell.tryPut(value));
                        return null;
                    });
                    gate.countDown(); boolean cancelled = await(cancel.result()); await(commit.result());
                    assertEquals(!cancelled, await(waiter.result())); assertFalse(request.cancel());
                    assertEquals(cancelled ? ManagedMVar.RequestState.CANCELLED : ManagedMVar.RequestState.COMMITTED, request.getState());
                    assertFalse(request.isQueued()); assertNull(request.pendingPutValue());
                    boolean full = operation == ManagedMVar.Operation.READ || (operation == ManagedMVar.Operation.TAKE ? cancelled : !cancelled);
                    assertEquals(full, !cell.isEmpty()); if (full) assertSame(value, cell.tryTake().getValue());
                    assertEquals(new ManagedMVar.PendingCounts(0, 0, 0), cell.pendingCounts());
                }
            } finally { context.leave(); }
        }
    }

    @Test public void stoppingRejectsNewAndPreviouslyConstructedTsosButResumesExistingWaiters() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var threads = state.getThreads(); threads.setCapabilityCount(1);
                var ready = new CountDownLatch(1);
                var waiter = start(state, null, () -> { ready.countDown(); return new ManagedMVar().take(null); });
                latch(ready);
                var unstarted = threads.newThread(state.getEnv(), () -> fail("Stopped TSO started"), null, null);
                threads.stopHostedThreads(); assertFalse(waiter.thread().isAlive()); assertTrue(waiter.result().isDone());
                assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> threads.startThread(unstarted));
                assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> threads.newThread(state.getEnv(), () -> {}, null, null));
            } finally { context.leave(); }
        }
    }

    @Test public void executeBeforePreviousCallbackReturnsCannotMountTheRouteTwice() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try (var scheduler = new LoomScheduler(Language.currentState().getEnv(), new CpuAffinity(null, 2))) {
                var route = scheduler.new Route(0L, false);
                var other = scheduler.new Route(1L, true);
                var submitted = new CountDownLatch(1); var release = new CountDownLatch(1);
                var second = new CountDownLatch(1); var otherReady = new CountDownLatch(1);
                var active = new AtomicInteger(); var maximum = new AtomicInteger();
                var failure = new AtomicReference<Throwable>();
                other.execute(otherReady::countDown); latch(otherReady);
                route.execute(() -> {
                    try {
                        maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                        route.execute(() -> {
                            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                            active.decrementAndGet(); second.countDown();
                        });
                        submitted.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (Throwable error) { failure.set(error); }
                    finally { active.decrementAndGet(); }
                });
                try {
                    latch(submitted);
                    assertFalse(second.await(100, TimeUnit.MILLISECONDS), "Submitted continuation must wait for the prior callback to return");
                } finally { release.countDown(); }
                latch(second); assertNull(failure.get()); assertEquals(1, maximum.get());
            } catch (InterruptedException failure) { throw new AssertionError(failure); }
            finally { context.leave(); }
        }
    }

    @Test public void contendedMetadataNeverParksAVirtualThreadBehindItsOldWrapper() throws Exception {
        try (var context = Context.newBuilder("thc").allowCreateThread(true).build()) {
            context.initialize("thc"); context.enter();
            try (var scheduler = new LoomScheduler(Language.currentState().getEnv(), new CpuAffinity(null, 1))) {
                // Control the contention window, without injecting a scheduler hook.
                var field = LoomScheduler.class.getDeclaredField("lock"); field.setAccessible(true);
                var lock = (java.util.concurrent.locks.ReentrantLock) field.get(scheduler);
                var ready = new CountDownLatch(1); var entering = new CountDownLatch(1); var finished = new CountDownLatch(1);
                var proceed = new java.util.concurrent.atomic.AtomicBoolean();
                var thread = scheduler.newThread(() -> {
                    ready.countDown(); while (!proceed.get()) Thread.onSpinWait();
                    entering.countDown(); scheduler.resize(1); finished.countDown();
                }, null, null);
                scheduler.start(thread); latch(ready); lock.lock();
                try {
                    proceed.set(true); latch(entering);
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
                    while (!lock.hasQueuedThread(thread) && System.nanoTime() < until) Thread.onSpinWait();
                    assertFalse(lock.hasQueuedThread(thread), "A parked VT ahead of its old wrapper creates a scheduler lock cycle");
                } finally { lock.unlock(); }
                latch(finished); TruffleSafepoint.setBlockedThreadInterruptible(null, Thread::join, thread);
            } finally { context.leave(); }
        }
    }

    @Test public void cancelledParentCannotAbandonAQueuedChildBootstrapDuringShutdown() throws Exception {
        try (var context = Context.newBuilder("thc").allowCreateThread(true).build()) {
            context.initialize("thc"); context.enter();
            try (var scheduler = new LoomScheduler(Language.currentState().getEnv(), new CpuAffinity(null, 2));
                 var stopper = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var occupied = new CountDownLatch(1); var release = new CountDownLatch(1); var parentReady = new CountDownLatch(1);
                scheduler.new Route(1L, true).execute(() -> {
                    occupied.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                }); latch(occupied);
                var parent = scheduler.newThread(() -> {
                    parentReady.countDown(); scheduler.newThread(() -> fail("Cancelled construction started a child"), 1L, null);
                }, 0L, null);
                parent.setUncaughtExceptionHandler((_, failure) -> {}); scheduler.start(parent); latch(parentReady);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (parent.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait();
                assertEquals(Thread.State.WAITING, parent.getState(), "Parent awaits construction queued on occupied HEC 1");
                var stopped = stopper.submit(() -> {
                    context.enter(); try { scheduler.stopThreads(); return true; } finally { context.leave(); }
                });
                try {
                    TruffleSafepoint.setBlockedThreadInterruptible(null, Thread::join, parent);
                    assertThrows(java.util.concurrent.TimeoutException.class, () -> stopped.get(100, TimeUnit.MILLISECONDS),
                        "Shutdown must still wait for the queued bootstrap after the managed parent terminates");
                } finally {
                    release.countDown(); var drained = new CountDownLatch(1);
                    scheduler.new Route(1L, true).execute(drained::countDown); latch(drained);
                }
                assertTrue(await(stopped)); assertFalse(parent.isAlive());
            } finally { context.leave(); }
        }
    }
}
