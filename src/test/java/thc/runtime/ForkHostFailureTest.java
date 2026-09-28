// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import com.oracle.truffle.api.bytecode.BytecodeEncodingException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Actual fork# and readMVar# on each lowerer; only the child failure is injected. */
@Timeout(30)
class ForkHostFailureTest {
    static Stream<Arguments> variants() {
        return Stream.of("ast", "bytecode").flatMap(backend -> Stream.of("platform", "loom").map(hosting -> Arguments.of(backend, hosting)));
    }
    private static Map<String, Object> leaf(String kind, boolean evaluated, String... reps) {
        return Map.of("kind", kind, "evaluated", evaluated, "primReps", List.of(reps));
    }
    private static final Map<String, Object> STATE = leaf("void", true);
    private static final Map<String, Object> OBJECT = leaf("object", true, "BoxedRep (Just Unlifted)");
    private static final Map<String, Object> DATA = leaf("data", false, "BoxedRep (Just Lifted)");
    private static final Map<String, Object> ACTION = leaf("closure", false, "BoxedRep (Just Lifted)");
    private static final Map<String, Object> LONG = leaf("long", true, "IntRep");
    private static Map<String, Object> pair(Map<String, Object> second) {
        return Map.of("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple", "components", List.of(STATE, second), "primReps", second.get("primReps"));
    }
    private static Map<String, Object> binder(String id, Map<String, Object> proof, boolean lifted) { return Map.of("id", id, "rep", proof, "lifted", lifted); }
    private static List<?> variable(String id, Map<String, Object> proof) { return List.of("var", id, Map.of("rep", proof)); }
    private static List<?> after(String primitive, List<?> args, List<Boolean> flags, String id, Map<String, Object> second, Object body) {
        var tuple = pair(second);
        var call = List.of("app", List.of("prim", primitive), args, flags, false, false, Map.of("rep", tuple));
        return List.of("case", call, id, List.of(List.of("data", "Pair", List.of(id + "State", id + "Value"), body,
            Map.of("binders", List.of(binder(id + "State", STATE, false), binder(id + "Value", second, second == DATA))))),
            Map.of("rep", LONG, "binder", binder(id, tuple, false)));
    }
    private static Map<String, Object> module() {
        var state = List.of("void", Map.of("rep", STATE));
        var read = after("readMVar#", List.of(variable("cell", OBJECT), state), List.of(false, false), "read", DATA,
            List.of("lit", "int", "42", Map.of("rep", LONG)));
        var body = after("fork#", List.of(variable("action", ACTION), state), List.of(true, false), "fork", OBJECT, read);
        return Map.of("constructors", List.of(Map.of("id", "Pair", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2)),
            "bindings", List.of(Map.of("id", "entry", "name", "entry", "arity", 2, "lifted", true,
                "expr", List.of("lam", List.of(binder("action", ACTION, true), binder("cell", OBJECT, false)), body,
                    Map.of("rep", leaf("closure", true, "BoxedRep (Just Lifted)"), "resultRep", LONG)))));
    }
    @FunctionalInterface private interface Failure { void run(Language.State state); }
    @FunctionalInterface private interface Check { void run(Context context, Future<Long> parent, ManagedMVar cell, CountDownLatch release, CompletableFuture<Thread> child, ByteArrayOutputStream errors) throws Exception; }
    private void exercise(String backend, String hosting, Failure failure, Check check) throws Exception {
        var errors = new ByteArrayOutputStream();
        var context = Context.newBuilder("thc").allowCreateThread(true).allowExperimentalOptions(true).err(errors)
            .option("engine.Compilation", "false").option("thc.ThreadHosting", hosting).build();
        var executor = Executors.newSingleThreadExecutor(); var release = new CountDownLatch(1); var child = new CompletableFuture<Thread>(); var cell = new ManagedMVar();
        try {
            Future<Long> parent = executor.submit(() -> {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState();
                    if (hosting.equals("loom")) state.getThreads().setCapabilityCount(1);
                    var target = new RootNode(language) {
                        @Override public Object execute(VirtualFrame frame) {
                            Thread.currentThread().setUncaughtExceptionHandler((_, uncaught) -> uncaught.printStackTrace(new PrintStream(errors, true)));
                            child.complete(Thread.currentThread());
                            try (var admission = LoomScheduler.suspendCurrentGuest()) {
                                TruffleSafepoint.setBlockedThreadInterruptible(this, CountDownLatch::await, release);
                            }
                            failure.run(state);
                            throw new AssertionError("The injected child failure returned");
                        }
                    }.getCallTarget();
                    var thunk = new Thunk(target, new CaptureLayout(language, new boolean[0]).captureValues(new Object[0]));
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module(), true) : new BytecodeProgram(language, module(), true);
                    return context.asValue(new ManagedMVarContextCall(program, "entry", new Object[]{thunk, cell})).execute().asLong();
                } finally { context.leave(); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!cell.pendingCounts().equals(new ManagedMVar.PendingCounts(0, 1, 0))) {
                if (parent.isDone()) { parent.get(); fail("Parent returned before readMVar# queued"); }
                if (System.nanoTime() >= deadline) fail("Parent never queued readMVar#");
                Thread.yield();
            }
            child.get(5, TimeUnit.SECONDS);
            check.run(context, parent, cell, release, child, errors);
        } finally {
            release.countDown();
            try { context.close(true); } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
        }
        assertEquals(new ManagedMVar.PendingCounts(0, 0, 0), cell.pendingCounts());
        var later = new Object(); assertTrue(cell.tryPut(later)); assertSame(later, cell.tryTake().getValue(), "The retired read must not retain or consume a later value");
    }
    @ParameterizedTest @MethodSource("variants") void internalChildFailureUnwindsBlockedParent(String backend, String hosting) throws Exception {
        fatal(backend, hosting, new RuntimeFault("fork compiler failure"));
    }
    @ParameterizedTest @MethodSource("variants") void bytecodeEncodingFailureUnwindsBlockedParent(String backend, String hosting) throws Exception {
        fatal(backend, hosting, BytecodeEncodingException.create("fork compiler failure"));
    }
    private void fatal(String backend, String hosting, RuntimeException failure) throws Exception {
        failure.initCause(new IllegalArgumentException("original compiler cause"));
        exercise(backend, hosting, _ -> { throw failure; }, (context, parent, cell, release, child, errors) -> {
            release.countDown();
            var observed = assertThrows(ExecutionException.class, () -> parent.get(5, TimeUnit.SECONDS)).getCause();
            var internal = assertInstanceOf(PolyglotException.class, observed); assertTrue(internal.isInternalError(), internal.toString());
            var report = new ByteArrayOutputStream(); internal.printStackTrace(new PrintStream(report, true));
            assertTrue(report.toString().contains("fork compiler failure"), report.toString());
            assertTrue(report.toString().contains("original compiler cause"), report.toString());
            assertTrue(errors.toString().contains("fork compiler failure"), errors.toString());
            assertTrue(errors.toString().contains("original compiler cause"), errors.toString());
        });
    }
    @ParameterizedTest @MethodSource("variants") void ordinaryGuestDeathDoesNotCancelParent(String backend, String hosting) throws Exception {
        guestDeath(backend, hosting, new GuestException(new Object(), null));
    }
    @ParameterizedTest @MethodSource("variants") void callbackAsyncDeathDoesNotCancelParent(String backend, String hosting) throws Exception {
        guestDeath(backend, hosting, new ForeignCallbackAsyncFailure(new Object(), new GuestException(new Object(), null), null));
    }
    private void guestDeath(String backend, String hosting, RuntimeException failure) throws Exception {
        exercise(backend, hosting, _ -> { throw failure; }, (context, parent, cell, release, child, errors) -> {
            release.countDown(); child.get().join(5000); assertFalse(child.get().isAlive()); assertFalse(parent.isDone());
            assertTrue(cell.tryPut(new Object())); assertEquals(42L, parent.get(5, TimeUnit.SECONDS));
            assertTrue(cell.tryTake().getPresent());
        });
    }
    @ParameterizedTest @MethodSource("variants") void contextInterruptRemainsReusable(String backend, String hosting) throws Exception {
        exercise(backend, hosting, _ -> { throw new GuestException(new Object(), null); }, (context, parent, cell, release, child, errors) -> {
            context.interrupt(Duration.ofSeconds(5));
            var observed = assertThrows(ExecutionException.class, () -> parent.get(5, TimeUnit.SECONDS)).getCause();
            assertTrue(assertInstanceOf(PolyglotException.class, observed).isInterrupted(), observed.toString());
            child.get().join(5000); assertFalse(child.get().isAlive()); assertEquals(1L, release.getCount());
            context.enter(); try { assertNotNull(Language.currentState()); } finally { context.leave(); }
        });
    }
    @ParameterizedTest @MethodSource("variants") void childHardExitKeepsItsExitStatus(String backend, String hosting) throws Exception {
        exercise(backend, hosting, state -> state.getEnv().getContext().closeExited(null, 17), (context, parent, cell, release, child, errors) -> {
            release.countDown();
            var observed = assertThrows(ExecutionException.class, () -> parent.get(5, TimeUnit.SECONDS)).getCause();
            var exited = assertInstanceOf(PolyglotException.class, observed); assertTrue(exited.isExit(), exited.toString()); assertEquals(17, exited.getExitStatus());
        });
    }
}
