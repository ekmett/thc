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
    private <T> T admitted(GuestThreads threads, Callable<T> action) throws Exception {
        if (threads.needsHosting()) return threads.hostEntry(null, () -> admitted(threads, action));
        threads.enterCurrent(null, false, true, null);
        try { return action.call(); }
        finally { threads.leaveCurrent(); }
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
        try (var context = Main.executionContext(false)) {
            context.initialize("thc");
            var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var state = entered(context, () -> Language.currentState(null));
            assertTrue(state.getSingleThreadedAssumption().isValid());
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
                assertFalse(state.getSingleThreadedAssumption().isValid()); release.countDown();
                assertSame(answer, owner.get(5, TimeUnit.SECONDS));
                for (var reader : readers) assertSame(answer, reader.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, evaluations.get()); assertEquals(2, thunk.getState()); assertNull(thunk.getTarget());
            assertNull(thunk.getEnvironment()); assertNull(thunk.getOwner()); assertFalse(state.getSingleThreadedAssumption().isValid());
            assertEquals(1L, metrics.getThunkEvaluations()); assertEquals(2L, metrics.getThunkHits());
            assertEquals(1, metrics.thunkCountsSnapshot().size()); assertEquals(1L, metrics.thunkCountsSnapshot().values().iterator().next().longValue());
        }
    }
    @Test void recursiveOwnerBlackholesButForeignFailureReadersGetSeparateGuestWrappers() throws Exception {
        try (var context = Main.executionContext(false)) {
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
        try (var context = Main.executionContext(false)) {
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
        try (var context = Main.executionContext(false)) {
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
        try (var context = Main.executionContext(false)) {
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
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            context.initialize("thc");
            var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var state = entered(context, () -> Language.currentState(null));
            try (var pool = Executors.newFixedThreadPool(2)) {
                var bothEntered = new CountDownLatch(2); var depart = new CountDownLatch(1); var entrants = new ArrayList<Future<?>>();
                for (int i = 0; i < 2; i++) entrants.add(pool.submit(() -> entered(context, () -> { bothEntered.countDown(); assertTrue(depart.await(5, TimeUnit.SECONDS)); return null; })));
                assertTrue(bothEntered.await(5, TimeUnit.SECONDS)); depart.countDown(); for (var entrant : entrants) entrant.get(5, TimeUnit.SECONDS);
            }
            assertFalse(state.getSingleThreadedAssumption().isValid());
            ExecutableProgram program = entered(context, () -> backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module, null, false));
            var thunk = entered(context, () -> (Thunk) program.entryValue("entry"));
            var entry = entered(context, () -> new EntryValue(program, "entry", 0));
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
    private static Map<String, Object> sparkRep(String kind, String primitive, boolean evaluated) {
        return map("kind", kind, "primReps", primitive == null ? list() : list(primitive), "evaluated", evaluated);
    }
    private ExecutableProgram sparkCaller(Language language, String backend) {
        var lifted = sparkRep("object", "BoxedRep (Just Lifted)", false);
        var state = sparkRep("void", null, true); var integer = sparkRep("long", "IntRep", true);
        var closure = sparkRep("closure", "BoxedRep (Just Lifted)", true);
        var args = list(map("id", "payload", "lifted", true, "rep", lifted));
        var call = list("app", list("prim", "par#"), list(list("var", "payload", map("rep", lifted))),
            list(true), false, false, map("rep", integer));
        var source = map("schema", 1, "ghc", "9.14.1", "instrument", true, "constructors", list(),
            "bindings", list(map("id", "hint", "name", "hint", "arity", 1, "lifted", true, "rep", closure,
                "expr", list("lam", args, call, map("rep", closure, "resultRep", integer)))));
        return backend.equals("ast") ? new Program(language, source, true, false) : new BytecodeProgram(language, source, null, true);
    }
    private ExecutableProgram sparkQueries(Language language, String backend) {
        var lazy = sparkRep("object", "BoxedRep (Just Lifted)", false);
        var boxed = sparkRep("data", "BoxedRep (Just Lifted)", true);
        var state = sparkRep("void", null, true); var integer = sparkRep("long", "IntRep", true);
        var closure = sparkRep("closure", "BoxedRep (Just Lifted)", true);
        var definitions = new ArrayList<Object>();
        for (var name : list("spark", "count", "poll", "failSpark")) {
            boolean payload = name.equals("spark") || name.equals("failSpark");
            var components = payload ? list(state, lazy) : name.equals("count") ? list(state, integer) : list(state, integer, lazy);
            var tuple = map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps",
                payload ? list("BoxedRep (Just Lifted)") : name.equals("count") ? list("IntRep") : list("IntRep", "BoxedRep (Just Lifted)"),
                "evaluated", true, "components", components);
            Object token = list("var", "s", map("rep", state));
            if (name.equals("failSpark")) token = list("app", list("prim", "raise#"),
                list(list("var", "payload", map("rep", lazy))), list(true), false, false, map("rep", state));
            var call = list("app", list("prim", payload ? "spark#" : name.equals("count") ? "numSparks#" : "getSpark#"),
                payload ? list(list("var", "payload", map("rep", lazy)), token) : list(token),
                payload ? list(true, false) : list(false), false, false, map("rep", tuple));
            var ids = payload ? list("s2", "value") : name.equals("count") ? list("s2", "n") : list("s2", "n", "value");
            var binders = new ArrayList<Object>();
            for (int i = 0; i < ids.size(); i++) binders.add(map("id", ids.get(i), "lifted", i != 0 && (payload || i == 2), "rep", components.get(i)));
            Object result = name.equals("count") ? list("var", "n", map("rep", integer)) :
                list("app", list("con", payload ? "Envelope" : "Poll", payload ? 1 : 2, map("rep", closure)),
                    payload ? list(list("var", "value", map("rep", lazy))) : list(list("var", "n", map("rep", integer)), list("var", "value", map("rep", lazy))),
                    payload ? list(true) : list(false, true), false, false, map("rep", boxed));
            var resultRep = name.equals("count") ? integer : boxed;
            var body = list("case", call, "tuple", list(list("data", ids.size() == 2 ? "tuple2" : "tuple3", ids, result, map("binders", binders))),
                map("rep", resultRep, "binder", map("id", "tuple", "lifted", false, "rep", tuple)));
            var args = new ArrayList<Object>();
            if (payload) args.add(map("id", "payload", "lifted", true, "rep", lazy));
            args.add(map("id", "s", "lifted", false, "rep", state));
            definitions.add(map("id", name, "name", name, "arity", args.size(), "lifted", true, "rep", closure,
                "expr", list("lam", args, body, map("rep", closure, "resultRep", resultRep))));
        }
        var source = map("schema", 1, "ghc", "9.14.1", "instrument", true, "constructors", list(sparkEnvelope(),
            map("id", "Poll", "name", "Poll", "arity", 2, "tag", 1, "kind", "boxed", "fieldReps", list(list("IntRep"), list("BoxedRep (Just Lifted)")),
                "fieldLifted", list(false, true), "strictFields", list(false, false)),
            map("id", "tuple2", "name", "(#,#)", "arity", 2, "tag", 1, "kind", "unboxed-tuple"),
            map("id", "tuple3", "name", "(#,,#)", "arity", 3, "tag", 1, "kind", "unboxed-tuple")), "bindings", definitions);
        return backend.equals("ast") ? new Program(language, source, true, false) : new BytecodeProgram(language, source, null, true);
    }
    private static final class SparkWorkRoot extends GuestRoot {
        private final java.util.function.Supplier<Object> body;
        SparkWorkRoot(Language language, java.util.function.Supplier<Object> body) {
            super(language, new FrameLayout().build()); this.body = body;
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) { return body.get(); }
    }
    private Context sparkContext(int capacity) {
        return Context.newBuilder("thc").allowCreateThread(true).allowExperimentalOptions(true)
            .option("thc.SparkQueueCapacity", Integer.toString(capacity))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build();
    }
    private Map<String, Object> sparkEnvelope() {
        return map("id", "Envelope", "name", "Envelope", "arity", 1, "tag", 1, "kind", "boxed",
            "fieldReps", list(list("BoxedRep (Just Lifted)")), "fieldLifted", list(true), "strictFields", list(false));
    }
    private Thunk sparkWork(Context context, Language language, String backend, java.util.function.Supplier<Object> body) throws Exception {
        // The hinted thunk has real backend capture machinery. The controlled hook
        // supplies bounded synchronization/effects, not a claimed capture capability.
        return entered(context, () -> {
            var closure = sparkRep("closure", "BoxedRep (Just Lifted)", true);
            var lifted = sparkRep("object", "BoxedRep (Just Lifted)", false);
            var integer = sparkRep("long", "IntRep", true);
            var call = list("app", list("var", "hook", map("rep", closure)),
                list(list("lit", "int", "0", map("rep", integer))), list(false), false, false, map("rep", sparkRep("object", "BoxedRep (Just Lifted)", true)));
            var expr = list("let", false, list(map("id", "work", "name", "work", "arity", 0, "lifted", true, "rep", lifted, "expr", call)),
                list("app", list("con", "Envelope", 1, map("rep", closure)), list(list("var", "work", map("rep", lifted))),
                    list(true), false, false, map("rep", sparkRep("data", "BoxedRep (Just Lifted)", true))),
                map("rep", sparkRep("data", "BoxedRep (Just Lifted)", true)));
            var source = map("schema", 1, "ghc", "9.14.1", "constructors", list(sparkEnvelope()), "bindings", list(
                map("id", "make", "name", "make", "arity", 1, "lifted", true, "rep", closure, "expr",
                    list("lam", list(map("id", "hook", "lifted", true, "rep", closure)), expr, map("rep", closure, "resultRep", sparkRep("data", "BoxedRep (Just Lifted)", true))))));
            ExecutableProgram program = backend.equals("ast") ? new Program(language, source, true, false) : new BytecodeProgram(language, source, null, true);
            var hook = new Closure(null, 1, new SparkWorkRoot(language, body).getCallTarget());
            var envelope = (DataValue) ScalarTestCalls.callScalarTestTarget(program.entryTarget("make"), new Object[]{0L, hook});
            return (Thunk) envelope.getLayout().read(envelope, 0);
        });
    }
    @Test void sparkedWorkRunsBeforeDemandAndFirstCompiledHintsShareTheOriginalThunk() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = sparkContext(2)) {
            context.initialize("thc");
            var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var caller = entered(context, () -> sparkCaller(language, backend));
            var target = caller.entryTarget("hint");
            for (boolean installed : list(false, true)) {
                if (installed) entered(context, () -> {
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); return null;
                });
                var started = new CountDownLatch(1); var release = new CountDownLatch(1); var evaluations = new AtomicInteger(); var answer = new Object();
                var thunk = sparkWork(context, language, backend, () -> { evaluations.incrementAndGet(); started.countDown(); await(release); return answer; });
                var driver = entered(context, () -> new Driver(new Metrics(false)));
                try (var pool = Executors.newSingleThreadExecutor()) {
                    long before = ((Number) caller.diagnostics().get("compiledEntries")).longValue();
                    entered(context, () -> { assertEquals(1L, ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, thunk})); return null; });
                    assertTrue(started.await(5, TimeUnit.SECONDS), backend + " starts speculative evaluation before demand");
                    if (installed) {
                        assertTrue(((Number) caller.diagnostics().get("compiledEntries")).longValue() > before);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    }
                    var demanding = new CountDownLatch(1);
                    try {
                        var demand = pool.submit(() -> entered(context, () -> { demanding.countDown(); return driver.force(thunk); }));
                        assertTrue(demanding.await(5, TimeUnit.SECONDS));
                        assertThrows(TimeoutException.class, () -> demand.get(50, TimeUnit.MILLISECONDS), "Demand waits for the worker's owned thunk");
                        release.countDown();
                        assertSame(answer, demand.get(5, TimeUnit.SECONDS)); assertEquals(1, evaluations.get());
                        assertSame(answer, entered(context, () -> driver.force(thunk)));
                    } finally { release.countDown(); }
                } finally { release.countDown(); }
            }
        }
    }
    @Test void sparkedGuestFailureIsDeferredAndDoesNotStopUnrelatedWork() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = sparkContext(2)) {
            context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var caller = entered(context, () -> sparkCaller(language, backend)); var failed = new CountDownLatch(1); var payload = new Object();
            var bad = sparkWork(context, language, backend, () -> { failed.countDown(); throw new GuestException(payload, null); });
            var completed = new CountDownLatch(1); var answer = new Object();
            var good = sparkWork(context, language, backend, () -> { completed.countDown(); return answer; });
            entered(context, () -> { assertEquals(1L, ScalarTestCalls.callScalarTestTarget(caller.entryTarget("hint"), new Object[]{0L, bad})); return null; });
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            entered(context, () -> { assertEquals(1L, ScalarTestCalls.callScalarTestTarget(caller.entryTarget("hint"), new Object[]{0L, good})); return null; });
            assertTrue(completed.await(5, TimeUnit.SECONDS)); var driver = entered(context, () -> new Driver(new Metrics(false)));
            var failure = entered(context, () -> assertThrows(GuestException.class, () -> driver.force(bad)));
            assertSame(payload, failure.getPayload()); assertSame(answer, entered(context, () -> driver.force(good)));
        }
    }
    private Thunk blockingSpark(Context context, Language language, String backend, ManagedMVar ready, ManagedMVar gate) throws Exception {
        return entered(context, () -> {
            var reference = sparkRep("object", "BoxedRep (Just Unlifted)", true);
            var state = sparkRep("void", null, true); var closure = sparkRep("closure", "BoxedRep (Just Lifted)", true);
            var boxed = sparkRep("data", "BoxedRep (Just Lifted)", true); var lazy = with(boxed, "evaluated", false);
            var result = map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", list("BoxedRep (Just Lifted)"),
                "evaluated", true, "components", list(state, boxed));
            var done = list("con", "Done", 0, map("rep", boxed));
            var put = list("app", list("prim", "putMVar#"), list(list("var", "ready", map("rep", reference)), done, list("var", "s", map("rep", state))),
                list(false, true, false), false, false, map("rep", state));
            var take = list("app", list("prim", "takeMVar#"), list(list("var", "gate", map("rep", reference)), list("var", "s1", map("rep", state))),
                list(false, false), false, false, map("rep", result));
            var after = list("case", take, "pair", list(list("data", "tuple2", list("s2", "ignored"), done,
                map("binders", list(map("id", "s2", "lifted", false, "rep", state), map("id", "ignored", "lifted", true, "rep", boxed))))),
                map("rep", boxed, "binder", map("id", "pair", "lifted", false, "rep", result)));
            var body = list("case", put, "s1", list(list("default", null, list(), after)),
                map("rep", boxed, "binder", map("id", "s1", "lifted", false, "rep", state)));
            var envelope = list("app", list("con", "Envelope", 1, map("rep", closure)), list(list("var", "work", map("rep", lazy))),
                list(true), false, false, map("rep", boxed));
            var make = list("let", false, list(map("id", "work", "name", "work", "arity", 0, "lifted", true, "rep", lazy, "expr", body)), envelope, map("rep", boxed));
            var args = list(map("id", "ready", "lifted", false, "rep", reference), map("id", "gate", "lifted", false, "rep", reference),
                map("id", "s", "lifted", false, "rep", state));
            var source = map("schema", 1, "ghc", "9.14.1", "constructors", list(sparkEnvelope(),
                map("id", "Done", "name", "Done", "arity", 0, "tag", 1, "kind", "boxed", "fieldReps", list(), "fieldLifted", list(), "strictFields", list()),
                map("id", "tuple2", "name", "(#,#)", "arity", 2, "tag", 1, "kind", "unboxed-tuple")),
                "bindings", list(map("id", "make", "name", "make", "arity", 3, "lifted", true, "rep", closure,
                    "expr", list("lam", args, make, map("rep", closure, "resultRep", boxed)))));
            ExecutableProgram program = backend.equals("ast") ? new Program(language, source, true, false) : new BytecodeProgram(language, source, null, true);
            var container = (DataValue) ScalarTestCalls.callScalarTestTarget(program.entryTarget("make"), new Object[]{0L, ready, gate, Unit.INSTANCE});
            return (Thunk) container.getLayout().read(container, 0);
        });
    }
    @Test void cancellingSparkWorkerLeavesTheSameThunkResumableWithoutReplayingItsEffect() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = sparkContext(2)) {
            context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var state = entered(context, () -> Language.currentState(null)); var caller = entered(context, () -> sparkCaller(language, backend));
            var ready = new ManagedMVar(); var gate = new ManagedMVar();
            var thunk = blockingSpark(context, language, backend, ready, gate);
            entered(context, () -> { assertEquals(1L, ScalarTestCalls.callScalarTestTarget(caller.entryTarget("hint"), new Object[]{0L, thunk})); return null; });
            Object marker;
            try (var reader = Executors.newSingleThreadExecutor()) {
                var reading = reader.submit(() -> entered(context, () -> ready.take(null)));
                try { marker = reading.get(5, TimeUnit.SECONDS); }
                finally { if (!reading.isDone()) ready.tryPut(Unit.INSTANCE); }
            }
            var worker = thunk.getOwner(); assertNotNull(worker);
            var threads = state.getThreads();
            assertEquals(threads.isLoom(), worker.isVirtual());
            var request = entered(context, () -> admitted(threads, () -> {
                for (var candidate : threads.snapshot()) if (candidate instanceof GuestThreadId id && id.getCarrier().get() == worker)
                    return threads.send(id, "stop speculative worker");
                throw new AssertionError("Spark worker has no registered guest identity");
            }));
            worker.join(5000); assertFalse(worker.isAlive(), backend + " cooperative worker cancellation completes");
            assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
            assertTrue(gate.tryPut(marker)); var driver = entered(context, () -> new Driver(new Metrics(false)));
            var resumed = entered(context, () -> admitted(threads, () -> driver.force(thunk)));
            assertEquals(marker.toString(), resumed.toString(), backend + " demand resumes to Done");
            assertFalse(ready.tryTake().getPresent(), "The pre-suspension ready effect must not repeat");
            assertSame(resumed, entered(context, () -> admitted(threads, () -> driver.force(thunk))));
        }
    }
    @Test void boundedSparkQueuePreservesIdentityRejectsOverflowAndCompletesStateBeforeEnqueue() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = sparkContext(1); var other = sparkContext(1)) {
            context.initialize("thc"); other.initialize("thc");
            var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var caller = entered(context, () -> sparkCaller(language, backend)); var queries = entered(context, () -> sparkQueries(language, backend));
            var foreignLanguage = entered(other, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var foreign = sparkWork(other, foreignLanguage, backend, () -> { throw new AssertionError("Cross-context hint entered"); });
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            var blocker = sparkWork(context, language, backend, () -> { started.countDown(); await(release); return Unit.INSTANCE; });
            var effects = new AtomicInteger(); var queued = sparkWork(context, language, backend, () -> { effects.incrementAndGet(); return Unit.INSTANCE; });
            var overflow = sparkWork(context, language, backend, () -> { effects.incrementAndGet(); return Unit.INSTANCE; });
            try {
                entered(context, () -> { ScalarTestCalls.callScalarTestTarget(caller.entryTarget("hint"), new Object[]{0L, blocker}); return null; });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                entered(context, () -> {
                    for (var name : list("spark", "count", "poll", "failSpark")) {
                        var target = (com.oracle.truffle.runtime.OptimizedCallTarget) queries.entryTarget(name);
                        target.compile(true); assertTrue(target.isValid(), backend + " " + name);
                    }
                    assertThrows(GuestException.class, () -> ScalarTestCalls.callScalarTestTarget(queries.entryTarget("failSpark"), new Object[]{0L, queued, Unit.INSTANCE}));
                    assertTrue(((com.oracle.truffle.runtime.OptimizedCallTarget) queries.entryTarget("count")).isValid(), backend + " count remains installed before first call");
                    long before = ((Number) queries.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(0L, ScalarTestCalls.callScalarTestTarget(queries.entryTarget("count"), new Object[]{0L, Unit.INSTANCE}));
                    assertTrue(((Number) queries.diagnostics().get("compiledEntries")).longValue() > before, backend + " first installed count call");
                    assertTrue(((com.oracle.truffle.runtime.OptimizedCallTarget) queries.entryTarget("count")).isValid());
                    ScalarTestCalls.callScalarTestTarget(caller.entryTarget("hint"), new Object[]{0L, foreign});
                    assertEquals(0L, ScalarTestCalls.callScalarTestTarget(queries.entryTarget("count"), new Object[]{0L, Unit.INSTANCE}));
                    before = ((Number) queries.diagnostics().get("compiledEntries")).longValue();
                    assertTrue(((com.oracle.truffle.runtime.OptimizedCallTarget) queries.entryTarget("spark")).isValid(), backend + " spark remains installed before first call");
                    var retained = (DataValue) ScalarTestCalls.callScalarTestTarget(queries.entryTarget("spark"), new Object[]{0L, queued, Unit.INSTANCE});
                    assertTrue(((Number) queries.diagnostics().get("compiledEntries")).longValue() > before, backend + " first installed spark call");
                    assertTrue(((com.oracle.truffle.runtime.OptimizedCallTarget) queries.entryTarget("spark")).isValid());
                    assertSame(queued, retained.getLayout().read(retained, 0));
                    ScalarTestCalls.callScalarTestTarget(caller.entryTarget("hint"), new Object[]{0L, overflow});
                    assertEquals(1L, ScalarTestCalls.callScalarTestTarget(queries.entryTarget("count"), new Object[]{0L, Unit.INSTANCE}));
                    before = ((Number) queries.diagnostics().get("compiledEntries")).longValue();
                    assertTrue(((com.oracle.truffle.runtime.OptimizedCallTarget) queries.entryTarget("poll")).isValid(), backend + " poll remains installed before first call");
                    var polled = (DataValue) ScalarTestCalls.callScalarTestTarget(queries.entryTarget("poll"), new Object[]{0L, Unit.INSTANCE});
                    assertTrue(((Number) queries.diagnostics().get("compiledEntries")).longValue() > before, backend + " first installed poll call");
                    assertTrue(((com.oracle.truffle.runtime.OptimizedCallTarget) queries.entryTarget("poll")).isValid());
                    assertEquals(1L, polled.getLayout().read(polled, 0)); assertSame(queued, polled.getLayout().read(polled, 1));
                    var empty = (DataValue) ScalarTestCalls.callScalarTestTarget(queries.entryTarget("poll"), new Object[]{0L, Unit.INSTANCE});
                    assertEquals(0L, empty.getLayout().read(empty, 0)); assertEquals("False", empty.getLayout().read(empty, 1).toString());
                    return null;
                });
                assertEquals(0, effects.get(), "Neither overflow nor dequeued work is forced by hint/query transport");
            } finally { release.countDown(); }
        }
    }
    @Test void disposingSparkContextStopsClaimedWorkAndDiscardsUnstartedHints() throws Exception {
        for (var backend : list("ast", "bytecode")) {
            var context = sparkContext(1); Thread worker = null; Thunk queued = null; com.oracle.truffle.api.CallTarget original = null;
            var effects = new AtomicInteger();
            try {
                context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
                var caller = entered(context, () -> sparkCaller(language, backend)); var ready = new ManagedMVar(); var gate = new ManagedMVar();
                var claimed = blockingSpark(context, language, backend, ready, gate);
                entered(context, () -> { ScalarTestCalls.callScalarTestTarget(caller.entryTarget("hint"), new Object[]{0L, claimed}); return null; });
                try (var reader = Executors.newSingleThreadExecutor()) {
                    var reading = reader.submit(() -> entered(context, () -> ready.take(null)));
                    try { reading.get(5, TimeUnit.SECONDS); }
                    finally { if (!reading.isDone()) ready.tryPut(Unit.INSTANCE); }
                }
                worker = claimed.getOwner(); assertNotNull(worker);
                queued = sparkWork(context, language, backend, () -> { effects.incrementAndGet(); return Unit.INSTANCE; }); original = queued.getTarget();
                var unstarted = queued;
                entered(context, () -> { ScalarTestCalls.callScalarTestTarget(caller.entryTarget("hint"), new Object[]{0L, unstarted}); return null; });
            } finally { context.close(); }
            worker.join(5000); assertFalse(worker.isAlive(), "Disposal joins the managed worker");
            assertEquals(0, effects.get(), "Shutdown must discard queued work without entering it");
            assertSame(original, queued.getTarget()); assertNull(queued.getOwner());
        }
    }
    @Test void disabledSparkHintsKeepWorkUnforcedAndDoNotAdmitAWorker() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = sparkContext(0)) {
            context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var state = entered(context, () -> Language.currentState(null)); var caller = entered(context, () -> sparkCaller(language, backend));
            var evaluations = new AtomicInteger(); var answer = new Object();
            var thunk = sparkWork(context, language, backend, () -> { evaluations.incrementAndGet(); return answer; });
            entered(context, () -> { assertEquals(1L, ScalarTestCalls.callScalarTestTarget(caller.entryTarget("hint"), new Object[]{0L, thunk})); return null; });
            assertEquals(0, evaluations.get()); assertFalse(state.isGuestConcurrencyAdmitted());
            assertSame(answer, entered(context, () -> new Driver(new Metrics(false)).force(thunk))); assertEquals(1, evaluations.get());
        }
    }
    @Test void asyncOwnerUnwindDoesNotMemoizeOrReplayAnEffectWithoutAContinuation() throws Exception {
        try (var context = Main.executionContext(false)) {
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
