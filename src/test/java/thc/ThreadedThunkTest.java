// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** One context, multiple real guest threads, and a single shared thunk. */
class ThreadedThunkTest {
    private static final class Driver extends RootNode {
        @Child private Force force;
        Driver(Metrics metrics) { super(null); force = new Force(metrics); }
        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
        Object force(Thunk thunk) { return Calls.target(getCallTarget(), new Object[]{thunk}); }
    }
    private record Fixture(Thunk thunk, Driver driver) {}
    private <T> T entered(Context context, Callable<T> action) throws Exception {
        context.enter(); try { return action.call(); } finally { context.leave(); }
    }
    // RootNode.execute cannot declare checked exceptions; retain the original await failure unchanged.
    @SuppressWarnings("unchecked") private static <E extends Throwable> void rethrow(Throwable error) throws E { throw (E) error; }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { ThreadedThunkTest.<RuntimeException>rethrow(error); }
    }
    private Fixture fixture(Context context, java.util.function.Supplier<RootNode> body, Metrics metrics) throws Exception {
        return entered(context, () -> new Fixture(new Thunk(body.get().getCallTarget(), null), new Driver(metrics)));
    }
    @Test void foreignThreadsWaitForOnePublicationAndTransitionOnlyOnce() throws Exception {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc");
            var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var state = entered(context, () -> Language.currentState(null));
            assertTrue(state.getSingleThreadedAssumption$org_intelligence_thc().isValid());
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            var evaluations = new AtomicInteger(); var answer = new Object(); var metrics = new Metrics(true);
            var fixture = fixture(context, () -> new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { evaluations.incrementAndGet(); started.countDown(); await(release); return answer; }
            }, metrics);
            var thunk = fixture.thunk; var driver = fixture.driver;
            try (var pool = Executors.newFixedThreadPool(3)) {
                var owner = pool.submit(() -> entered(context, () -> driver.force(thunk)));
                assertTrue(started.await(5, TimeUnit.SECONDS)); var readerStarted = new CountDownLatch(2);
                var readers = new ArrayList<Future<Object>>();
                for (int i = 0; i < 2; i++) readers.add(pool.submit(() -> entered(context, () -> { readerStarted.countDown(); return driver.force(thunk); })));
                assertTrue(readerStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> readers.getFirst().get(50, TimeUnit.MILLISECONDS));
                assertFalse(state.getSingleThreadedAssumption$org_intelligence_thc().isValid()); release.countDown();
                assertSame(answer, owner.get(5, TimeUnit.SECONDS));
                for (var reader : readers) assertSame(answer, reader.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, evaluations.get()); assertEquals(2, thunk.getState()); assertNull(thunk.getTarget());
            assertNull(thunk.getEnvironment()); assertNull(thunk.getOwner()); assertFalse(state.getSingleThreadedAssumption$org_intelligence_thc().isValid());
            assertEquals(1L, metrics.getThunkEvaluations()); assertEquals(2L, metrics.getThunkHits());
            assertEquals(1, metrics.thunkCountsSnapshot().size()); assertEquals(1L, metrics.thunkCountsSnapshot().values().iterator().next().longValue());
        }
    }
    @Test void recursiveOwnerBlackholesButForeignFailureReadersGetSeparateGuestWrappers() throws Exception {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); var driver = entered(context, () -> new Driver(new Metrics(true)));
            Thunk[] recursive = new Thunk[1];
            recursive[0] = entered(context, () -> new Thunk(new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { return driver.force(recursive[0]); }
            }.getCallTarget(), null));
            var blackhole = entered(context, () -> assertThrows(RuntimeFault.class, () -> driver.force(recursive[0])));
            assertTrue(blackhole.getMessage().contains("Blackhole"));
            assertSame(blackhole, entered(context, () -> assertThrows(RuntimeFault.class, () -> driver.force(recursive[0]))));
            var started = new CountDownLatch(1); var release = new CountDownLatch(1); var evaluations = new AtomicInteger(); var payload = new Object();
            var failed = entered(context, () -> new Thunk(new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { evaluations.incrementAndGet(); started.countDown(); await(release); throw new GuestException(payload, this); }
            }.getCallTarget(), null));
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> entered(context, () -> assertThrows(GuestException.class, () -> driver.force(failed))));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var second = pool.submit(() -> entered(context, () -> assertThrows(GuestException.class, () -> driver.force(failed))));
                release.countDown(); var a = first.get(5, TimeUnit.SECONDS); var b = second.get(5, TimeUnit.SECONDS);
                assertNotSame(a, b); assertSame(payload, a.getPayload()); assertSame(payload, b.getPayload());
            }
            assertEquals(1, evaluations.get()); assertEquals(3, failed.getState()); assertNull(failed.getTarget());
        }
    }
    @Test void spuriousWakeupsDoNotStealOwnershipOrPublishAnAnswer() throws Exception {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); var started = new CountDownLatch(1); var release = new CountDownLatch(1); var evaluations = new AtomicInteger();
            var fixture = fixture(context, () -> new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { evaluations.incrementAndGet(); started.countDown(); await(release); return 43L; }
            }, new Metrics(true));
            var thunk = fixture.thunk; var driver = fixture.driver;
            assertSame(thunk, thunk.getMonitor()); assertNotSame(thunk.getMonitor(), new Thunk(Objects.requireNonNull(thunk.getTarget()), null).getMonitor());
            var threads = java.lang.management.ManagementFactory.getThreadMXBean(); var waiterThread = new AtomicReference<Thread>();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var owner = pool.submit(() -> entered(context, () -> driver.force(thunk)));
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    Thread originalOwner; synchronized (thunk.getMonitor()) { originalOwner = thunk.getOwner(); }
                    assertNotNull(originalOwner);
                    var waiter = pool.submit(() -> entered(context, () -> { waiterThread.set(Thread.currentThread()); return driver.force(thunk); }));
                    long waits = -1;
                    for (int i = 0; i < 3; i++) {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (true) {
                            var thread = waiterThread.get(); var info = thread == null ? null : threads.getThreadInfo(thread.threadId(), 32);
                            if (info != null && info.getThreadState() == Thread.State.WAITING && info.getWaitedCount() > waits &&
                                Arrays.stream(info.getStackTrace()).anyMatch(frame -> frame.getClassName().equals(Force.class.getName()) && frame.getMethodName().equals("awaitOwner"))) {
                                waits = info.getWaitedCount(); break;
                            }
                            assertTrue(System.nanoTime() < deadline, "Reader did not re-enter the thunk wait"); Thread.sleep(1);
                        }
                        synchronized (thunk.getMonitor()) {
                            assertEquals(1, thunk.getState()); assertSame(originalOwner, thunk.getOwner()); assertNull(thunk.getValue()); thunk.getMonitor().notifyAll();
                        }
                        assertThrows(TimeoutException.class, () -> waiter.get(20, TimeUnit.MILLISECONDS));
                    }
                    release.countDown(); assertEquals(43L, owner.get(5, TimeUnit.SECONDS)); assertEquals(43L, waiter.get(5, TimeUnit.SECONDS));
                } finally { release.countDown(); }
            }
            assertEquals(1, evaluations.get()); assertEquals(2, thunk.getState()); assertNull(thunk.getOwner()); assertNull(thunk.getTarget()); assertNull(thunk.getEnvironment());
        }
    }
    @Test void unexpectedHostUnwindWakesWaiterWithoutReplayingEffects() throws Exception {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); var started = new CountDownLatch(1); var release = new CountDownLatch(1); var effects = new AtomicInteger();
            var fixture = fixture(context, () -> new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { effects.incrementAndGet(); started.countDown(); await(release); throw new IllegalStateException("after effect"); }
            }, new Metrics(true));
            var thunk = fixture.thunk; var driver = fixture.driver;
            try (var pool = Executors.newFixedThreadPool(2)) {
                var owner = pool.submit(() -> entered(context, () -> assertThrows(IllegalStateException.class, () -> driver.force(thunk))));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var waiter = pool.submit(() -> entered(context, () -> assertThrows(RuntimeFault.class, () -> driver.force(thunk))));
                release.countDown(); assertEquals("after effect", owner.get(5, TimeUnit.SECONDS).getMessage());
                assertTrue(waiter.get(5, TimeUnit.SECONDS).getMessage().contains("no resumable continuation"));
            }
            assertEquals(1, effects.get()); assertEquals(4, thunk.getState()); assertNotNull(thunk.getTarget());
        }
    }
    @Test void asyncDeliveryToAWaiterDoesNotChangeTheOwnersThunk() throws Exception {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc");
            var state = entered(context, () -> TruffleLanguage.ContextReference.create(Language.class).get(null));
            var started = new CountDownLatch(1); var release = new CountDownLatch(1); var evaluations = new AtomicInteger();
            var fixture = fixture(context, () -> new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { evaluations.incrementAndGet(); started.countDown(); await(release); return 43L; }
            }, new Metrics(true));
            var thunk = fixture.thunk; var driver = fixture.driver;
            try (var pool = Executors.newFixedThreadPool(2)) {
                var owner = pool.submit(() -> entered(context, () -> driver.force(thunk))); assertTrue(started.await(5, TimeUnit.SECONDS));
                var waiterThread = new AtomicReference<Thread>();
                var waiter = pool.submit(() -> entered(context, () -> {
                    waiterThread.set(Thread.currentThread()); try { driver.force(thunk); return null; } catch (Throwable failure) { return failure; }
                }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((waiterThread.get() == null || waiterThread.get().getState() != Thread.State.WAITING) && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(Thread.State.WAITING, waiterThread.get() == null ? null : waiterThread.get().getState());
                var delivered = state.getEnv().submitThreadLocal(new Thread[]{waiterThread.get()}, new ThreadLocalAction(true, false) {
                    @Override protected void perform(Access access) { throw new AsyncThunkUnwind("waiter"); }
                });
                var interruption = waiter.get(5, TimeUnit.SECONDS);
                assertTrue(interruption instanceof AsyncThunkUnwind, "The waiter should unwind at its safepoint: " + interruption);
                assertTrue(delivered.isDone()); assertEquals(1, thunk.getState(), "Only the owner can publish or unwind its thunk");
                release.countDown(); assertEquals(43L, owner.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, evaluations.get()); assertEquals(2, thunk.getState());
        }
    }
    @Test void explicitlyCompiledAstAndBytecodeEntriesShareUpdatedCafsAcrossThreads() throws Exception {
        var constructor = map("id", "Done", "name", "Done", "arity", 0, "tag", 1, "kind", "boxed", "strictFields", list(), "fieldLifted", list(), "fieldReps", list());
        var delayed = list("case", list("lit", "int", "0"), "ignored", list(list("default", null, list(), list("con", "Done", 0))));
        var module = map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ThreadedCaf", "instrument", true, "constructors", list(constructor),
            "bindings", list(map("id", "entry", "name", "entry", "type", "Synthetic", "lifted", true, "arity", 0, "expr", delayed)));
        for (String backend : list("ast", "bytecode")) try (var context = MainKt.executionContext(false)) {
            context.initialize("thc");
            var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var state = entered(context, () -> Language.currentState(null));
            try (var pool = Executors.newFixedThreadPool(2)) {
                var bothEntered = new CountDownLatch(2); var depart = new CountDownLatch(1); var entrants = new ArrayList<Future<?>>();
                for (int i = 0; i < 2; i++) entrants.add(pool.submit(() -> entered(context, () -> { bothEntered.countDown(); assertTrue(depart.await(5, TimeUnit.SECONDS)); return null; })));
                assertTrue(bothEntered.await(5, TimeUnit.SECONDS)); depart.countDown(); for (var entrant : entrants) entrant.get(5, TimeUnit.SECONDS);
            }
            assertFalse(state.getSingleThreadedAssumption$org_intelligence_thc().isValid());
            ExecutableProgram program = entered(context, () -> backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module, null, false));
            var thunk = entered(context, () -> (Thunk) program.entryValue("entry"));
            var entry = entered(context, () -> new EntryValue(program, "entry", 0, null, null, null, null, null, false, null));
            entered(context, () -> {
                var warm = new Thunk(Objects.requireNonNull(thunk.getTarget()), thunk.getEnvironment());
                for (int i = 0; i < 8; i++) Calls.target(program.hostEntryTarget(0), new Object[]{warm, new Object[0]});
                assertTrue(context.asValue(entry).invokeMember("compile").asBoolean(), backend); return null;
            });
            try (var pool = Executors.newFixedThreadPool(2)) {
                var launch = new CountDownLatch(1); var workers = new ArrayList<Future<Object>>();
                for (int i = 0; i < 2; i++) workers.add(pool.submit(() -> entered(context, () -> {
                    assertTrue(launch.await(5, TimeUnit.SECONDS)); return Calls.target(program.hostEntryTarget(0), new Object[]{thunk, new Object[0]});
                })));
                launch.countDown(); var first = workers.getFirst().get(5, TimeUnit.SECONDS); assertSame(first, workers.get(1).get(5, TimeUnit.SECONDS), backend);
            }
            assertEquals(2, thunk.getState(), backend); assertNull(thunk.getTarget(), backend); assertNull(thunk.getEnvironment(), backend);
            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > 0, backend);
        }
    }
    @Test void asyncOwnerUnwindDoesNotMemoizeOrReplayAnEffectWithoutAContinuation() throws Exception {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); var state = entered(context, () -> TruffleLanguage.ContextReference.create(Language.class).get(null));
            var started = new CountDownLatch(1); var effects = new AtomicInteger();
            var fixture = fixture(context, () -> new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { effects.incrementAndGet(); started.countDown(); while (true) TruffleSafepoint.poll(this); }
            }, new Metrics(true));
            var thunk = fixture.thunk; var driver = fixture.driver;
            try (var pool = Executors.newSingleThreadExecutor()) {
                var ownerThread = new AtomicReference<Thread>();
                var owner = pool.submit(() -> entered(context, () -> {
                    ownerThread.set(Thread.currentThread()); try { driver.force(thunk); return null; } catch (Throwable failure) { return failure; }
                }));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                state.getEnv().submitThreadLocal(new Thread[]{ownerThread.get()}, new ThreadLocalAction(true, false) {
                    @Override protected void perform(Access access) { throw new AsyncThunkUnwind("ThreadKilled"); }
                });
                assertTrue(owner.get(5, TimeUnit.SECONDS) instanceof AsyncThunkUnwind);
            }
            assertEquals(4, thunk.getState()); assertNotNull(thunk.getTarget(), "The unevaluated body remains available for a future continuation protocol");
            var unsupported = entered(context, () -> assertThrows(RuntimeFault.class, () -> driver.force(thunk)));
            assertTrue(unsupported.getMessage().contains("no resumable continuation")); assertEquals(1, effects.get(), "Refusing replay is necessary after an observable effect");
        }
    }
}
