// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.EntryValue;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic Core protocol controls; no external fixture or replacement execution. */
@Timeout(60)
class AdaptiveAsyncTest {
    private static final class LeafCallerLoop extends Node implements RepeatingNode {
        @Child private DirectCallNode call;
        volatile boolean running = true;
        volatile boolean compiled;
        volatile long answer;

        LeafCallerLoop(RootCallTarget target) { call = DirectCallNode.create(target); }

        @Override public boolean executeRepeating(VirtualFrame frame) {
            if (!running) return false;
            answer = (long) call.call();
            compiled = CompilerDirectives.inCompiledCode();
            return true;
        }
    }

    @Test void compiledLoopCallingNoninlinedLeafRetainsItsOwnSafepoint() throws Exception {
        try (var context = Context.newBuilder("thc").allowCreateThread(true).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("compiler.Inlining", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            final Language.State owner;
            final OptimizedCallTarget leaf;
            final OptimizedCallTarget caller;
            final LeafCallerLoop body;
            try {
                owner = Language.currentState();
                // Boxing inside execute would keep a callee return poll and hide the caller bug.
                Object leafAnswer = 17L;
                leaf = (OptimizedCallTarget) new RootNode(language()) {
                    @Override public Object execute(VirtualFrame frame) { return leafAnswer; }
                    @Override public String getName() { return "poll-free leaf"; }
                }.getCallTarget();
                body = new LeafCallerLoop(leaf);
                caller = (OptimizedCallTarget) new RootNode(language()) {
                    @Child private LoopNode loop = Truffle.getRuntime().createLoopNode(body);
                    @Override public Object execute(VirtualFrame frame) { loop.execute(frame); return body.answer; }
                    @Override public String getName() { return "noninlined leaf caller loop"; }
                }.getCallTarget();
                assertFalse(leaf.wasExecuted()); assertFalse(caller.wasExecuted());
                leaf.compile(true); leaf.waitForCompilation();
                caller.compile(true); caller.waitForCompilation();
                assertTrue(leaf.isValidLastTier()); assertTrue(caller.isValidLastTier());
                assertFalse(leaf.wasExecuted()); assertFalse(caller.wasExecuted());
                assertSame(leaf, body.call.getCurrentCallTarget());
            } finally { context.leave(); }

            var result = new CompletableFuture<Object>();
            var worker = new Thread(() -> {
                context.enter();
                try { result.complete(caller.call()); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
                finally { context.leave(); }
            }, "compiled-leaf-caller");
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!body.compiled && !result.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                assertTrue(body.compiled, "The first caller invocation enters installed loop code");
                assertFalse(result.isDone());
                assertTrue(leaf.isValidLastTier()); assertTrue(caller.isValidLastTier());
                assertSame(leaf, body.call.getCurrentCallTarget());
                var reached = new AtomicBoolean();
                var action = owner.getEnv().submitThreadLocal(new Thread[]{worker}, new ThreadLocalAction(true, false) {
                    @Override protected void perform(Access access) {
                        reached.set(true);
                        body.running = false;
                    }
                });
                action.get(10, TimeUnit.SECONDS);
                assertTrue(reached.get(), "The caller loop services the action while its leaf omits return polling");
                assertEquals(17L, result.get(10, TimeUnit.SECONDS));
            } finally {
                body.running = false;
                worker.join(TimeUnit.SECONDS.toMillis(10));
                assertFalse(worker.isAlive(), "The bounded cleanup stops the caller loop");
            }
        }
    }

    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    private static final Map<String, Object> LONG = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private static final Map<String, Object> VOID = map("kind", "void", "primReps", List.of(), "evaluated", true);
    private static final Map<String, Object> CLOSURE = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static Map<String, Object> binder(String id, Map<String, Object> rep) {
        return map("id", id, "name", id, "lifted", rep == CLOSURE, "coercion", false, "rep", rep);
    }
    private static Object variable(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private static Object call(String id) {
        return list("app", variable(id, CLOSURE), list(list("void", map("rep", VOID))), list(false), false, false, map("rep", LONG));
    }
    private static Map<String, Object> parentModule() {
        var relay1 = list("app", variable("relay1", CLOSURE), list(variable("child", CLOSURE)), list(false), false, false, map("rep", LONG));
        var relay2 = list("app", variable("relay2", CLOSURE), list(variable("child", CLOSURE)), list(false), false, false, map("rep", LONG));
        var body = list("app", list("prim", "+#"), list(call("before"), relay1), list(false, false), false, false, map("rep", LONG));
        return map("instrument", true, "bindings", list(map("id", "parent", "name", "parent", "arity", 2, "lifted", true,
            "expr", list("lam", list(binder("before", CLOSURE), binder("child", CLOSURE)), body, map("resultRep", LONG))),
            map("id", "relay1", "name", "relay1", "arity", 1, "lifted", true,
                "expr", list("lam", list(binder("child", CLOSURE)), relay2, map("resultRep", LONG))),
            map("id", "relay2", "name", "relay2", "arity", 1, "lifted", true,
                "expr", list("lam", list(binder("child", CLOSURE)), call("child"), map("resultRep", LONG)))));
    }
    private static Context context(String hosting) {
        return Context.newBuilder("thc").allowCreateThread(true).allowExperimentalOptions(true)
            .option("thc.ThreadHosting", hosting).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.Splitting", "false")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private static Language language() { return TruffleLanguage.LanguageReference.create(Language.class).get(null); }
    private static FunctionRoot scalarRoot(Language language, Expr body) {
        var proof = CoreRepresentations.parse(LONG);
        body.setRepresentation(proof);
        var root = new FunctionRoot(language, new FrameLayout().build(), "adaptive child", null,
            new int[0], new int[0], new int[0], body, new Metrics(false), new CoreRepresentation[0], proof,
            null, new boolean[]{false}, null, null, new int[0], null, true, new int[0][], false, FunctionRootRole.FUNCTION, false);
        root.configureEagerAsyncPolls(false);
        return root;
    }
    private static EntryValue publicEntry(RootCallTarget target) {
        return new EntryValue(new ExecutableProgram() {
            public boolean getAsynchronousExceptions() { return false; }
            public RootCallTarget hostEntryTarget(int arity) { return target; }
            public Object entryValue(String name) { return Unit.INSTANCE; }
            public RootCallTarget entryTarget(String name) { return target; }
            public DataLayout constructorLayout(String id) { throw new UnsupportedOperationException(); }
            public Map<String, Object> diagnostics() { return Map.of(); }
        }, "admission", 0);
    }
    private static Closure forkAction(Language language, Language.State owner, CompletableFuture<Boolean> observed) {
        var data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
        var proof = CoreRepresentations.parse(map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps",
            list("BoxedRep (Just Lifted)"), "components", list(VOID, data), "evaluated", false));
        var shape = new TupleShape(proof, language); var layout = new FrameLayout(); int result = layout.bind("result");
        var body = new Expr() {
            @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Typed fork result required"); }
            @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
                observed.complete(!owner.getSingleGuestOriginAssumption().isValid());
                FrameAccess.write(frame, slots[offset], Unit.INSTANCE); return null;
            }
        };
        body.setRepresentation(proof);
        var root = new FunctionRoot(language, layout.build(), "adaptive fork action", null, new int[0], new int[0], new int[0],
            body, new Metrics(false), new CoreRepresentation[0], proof, null, new boolean[]{false}, null, shape,
            new int[]{result}, null, true, new int[0][], false, FunctionRootRole.FUNCTION, false);
        root.configureEagerAsyncPolls(false);
        return new Closure(null, 1, root.getCallTarget());
    }
    private static Object drain(Language language, SavedGuestContinuation saved) {
        return new RootNode(language) {
            @Child private Force force = new Force(new Metrics(false), true);
            @Override public Object execute(VirtualFrame frame) { return force.drainStack(saved, null); }
        }.getCallTarget().call();
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void compiledParentRetainsPrimitiveOperandWhenChildInvalidatesBeforeReturning(String backend) throws Exception {
        try (var context = context("platform")) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var language = language(); var threads = state.getThreads();
                state.admitGuestOrigin(); threads.enterCurrent();
                try {
                    var prefix = new AtomicInteger(); var suffix = new AtomicInteger(); var armed = new AtomicBoolean();
                    var forked = new CompletableFuture<Boolean>(); var action = forkAction(language, state, forked);
                    var before = scalarRoot(language, new Expr() {
                        @Override public Object execute(VirtualFrame frame) { prefix.incrementAndGet(); return 100L; }
                    });
                    var child = scalarRoot(language, new Expr() {
                        @Override public Object execute(VirtualFrame frame) {
                            suffix.incrementAndGet();
                            if (!armed.get()) return 42L;
                            assertTrue(state.getSingleGuestOriginAssumption().isValid());
                            GuestThreadOps.fork(this, action, true);
                            var sent = threads.send(threads.currentIdentity(), "mid-expression");
                            assertSame(sent, GuestThreads.pollCurrent(this, false));
                            throw new AstCapture(sent, SynchronousMasking.current(this)).append((saved, input) -> 42L);
                        }
                    });
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, parentModule(), false) : new BytecodeProgram(language, parentModule(), false);
                    var target = program.entryTarget("parent");
                    var caller = new GuestRoot(language, new FrameLayout().build()) {
                        @Child private DirectCallerNode call = new DirectCallerNode(target, new Metrics(true));
                        @Override public long bloom(VirtualFrame frame) { return 0L; }
                        @Override public Object execute(VirtualFrame frame) { return call.call(frame, frame.getArguments(), false); }
                    }.getCallTarget();
                    var arguments = new Object[]{0L, new Closure(null, 1, before.getCallTarget()), new Closure(null, 1, child.getCallTarget())};
                    for (int i = 0; i < 5; i++) assertEquals(142L, Calls.target(caller, arguments));
                    assertTrue(state.getSingleGuestOriginAssumption().isValid());
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    var runtime = Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                    int prefixes = prefix.get(), suffixes = suffix.get();
                    long compiled = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    armed.set(true);
                    var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(Calls.target(caller, arguments)));
                    assertFalse(state.getSingleGuestOriginAssumption().isValid());
                    assertTrue(forked.get(10, TimeUnit.SECONDS), "The child sees invalidation before its first effect");
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > compiled);
                    saved.asyncRequest().acknowledge();
                    assertEquals(142L, drain(language, saved));
                    assertEquals(prefixes + 1, prefix.get()); assertEquals(suffixes + 1, suffix.get());
                    assertSame(target, program.entryTarget("parent"));
                    var handoff = language.getHandoffState().get();
                    if (backend.equals("ast") && language.getHandoffLayouts().getEnabled()) assertTrue(handoff.getCalls() > 0);
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                    assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                } finally { threads.leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void selfThrowBypassesSpeculationAndBothMasks(String backend) {
        var identity = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
        var payload = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
        var body = list("app", list("prim", "killThread#"), list(variable("id", identity), variable("payload", payload),
            list("void", map("rep", VOID))), list(false, true, false), false, false, map("rep", VOID));
        var module = map("bindings", list(map("id", "kill", "name", "kill", "lifted", true, "expr", list("lam",
            list(binder("id", identity), map("id", "payload", "name", "payload", "lifted", true, "rep", payload)), body, map("resultRep", VOID)))));
        try (var context = context("platform")) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState(); var language = language(); var threads = owner.getThreads();
                owner.admitGuestOrigin(); threads.enterCurrent();
                try {
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module, false) : new BytecodeProgram(language, module, false);
                    var target = program.entryTarget("kill"); var lazy = new Object();
                    for (var mask : MaskingState.values()) {
                        owner.getMaskingState().set(mask);
                        var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(
                            Calls.target(target, new Object[]{0L, threads.currentIdentity(), lazy})));
                        var request = Objects.requireNonNull(saved.asyncRequest());
                        assertTrue(request.getForceSelf()); assertSame(lazy, request.getPayload());
                        assertEquals(AsyncRequestState.CLAIMED, request.getState()); request.acknowledge();
                        assertEquals(Unit.INSTANCE, drain(language, saved));
                        assertEquals(mask, owner.getMaskingState().get());
                        assertTrue(owner.getSingleGuestOriginAssumption().isValid());
                    }
                } finally { threads.leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"platform", "loom"})
    void publicOriginSurvivesHostingAndSameOriginSafeCallbacks(String hosting) {
        try (var context = context(hosting); var other = context("platform")) {
            context.initialize("thc"); context.enter();
            final Language.State owner;
            try {
                owner = Language.currentState();
                var target = new RootNode(language()) {
                    @Override public Object execute(VirtualFrame frame) {
                        assertTrue(owner.getSingleGuestOriginAssumption().isValid());
                        var permission = owner.getThreads().enterForeign(ForeignSafety.SAFE);
                        try { owner.admitGuestOrigin(); }
                        finally { owner.getThreads().leaveForeign(permission); }
                        return 7L;
                    }
                }.getCallTarget();
                var entry = context.asValue(publicEntry(target));
                assertEquals(7L, entry.execute().asLong()); assertEquals(7L, entry.execute().asLong());
                assertTrue(owner.getSingleGuestOriginAssumption().isValid());
                if (hosting.equals("loom")) assertFalse(owner.getSingleThreadedAssumption().isValid());
            } finally { context.leave(); }
            other.initialize("thc"); other.enter();
            try { Language.currentState().admitGuestConcurrency(); }
            finally { other.leave(); }
            assertTrue(owner.getSingleGuestOriginAssumption().isValid());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"platform", "loom"})
    void safeCrossContextCallbackRetainsThePublicOriginThroughHosting(String hosting) {
        try (var caller = context(hosting); var callee = context("platform")) {
            callee.initialize("thc"); callee.enter();
            final Language.State calleeOwner; final org.graalvm.polyglot.Value callback;
            try {
                calleeOwner = Language.currentState();
                callback = callee.asValue(publicEntry(new RootNode(language()) {
                    @Override public Object execute(VirtualFrame frame) {
                        assertTrue(calleeOwner.getSingleGuestOriginAssumption().isValid()); return 9L;
                    }
                }.getCallTarget()));
            } finally { callee.leave(); }
            assertEquals(9L, callback.execute().asLong());
            caller.initialize("thc"); caller.enter();
            try {
                var owner = Language.currentState();
                var entry = caller.asValue(publicEntry(new RootNode(language()) {
                    @Override public Object execute(VirtualFrame frame) {
                        var permission = owner.getThreads().enterForeign(ForeignSafety.SAFE);
                        try { return callback.execute().asLong(); }
                        finally { owner.getThreads().leaveForeign(permission); }
                    }
                }.getCallTarget()));
                assertEquals(9L, entry.execute().asLong());
                assertTrue(owner.getSingleGuestOriginAssumption().isValid());
                assertTrue(calleeOwner.getSingleGuestOriginAssumption().isValid());
            } finally { caller.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"platform", "loom"})
    void differentOriginInvalidatesBeforeGuestEntryWhileOriginalStackIsActive(String hosting) throws Exception {
        try (var context = context(hosting)) {
            context.initialize("thc"); context.enter();
            final Language.State owner; final org.graalvm.polyglot.Value entry;
            var first = new CountDownLatch(1); var second = new CountDownLatch(1); var calls = new AtomicInteger();
            try {
                owner = Language.currentState();
                entry = context.asValue(publicEntry(new RootNode(language()) {
                    @Override public Object execute(VirtualFrame frame) {
                        if (calls.incrementAndGet() == 1) {
                            assertTrue(owner.getSingleGuestOriginAssumption().isValid()); first.countDown();
                            try (var admission = LoomScheduler.suspendCurrentGuest()) {
                                TruffleSafepoint.setBlockedThreadInterruptible(this, gate -> assertTrue(gate.await(10, TimeUnit.SECONDS)), second);
                            }
                            assertFalse(owner.getSingleGuestOriginAssumption().isValid());
                        } else { assertFalse(owner.getSingleGuestOriginAssumption().isValid()); second.countDown(); }
                        return 9L;
                    }
                }.getCallTarget()));
            } finally { context.leave(); }
            var running = CompletableFuture.supplyAsync(() -> entry.execute().asLong());
            try {
                assertTrue(first.await(10, TimeUnit.SECONDS));
                assertEquals(9L, entry.execute().asLong()); assertEquals(9L, running.get(10, TimeUnit.SECONDS));
                assertEquals(2, calls.get());
            } finally { second.countDown(); }
        }
    }
    @Test void invalidForkDoesNotTransition() {
        try (var context = context("platform")) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState();
                var root = scalarRoot(language(), new Expr() { @Override public Object execute(VirtualFrame frame) { return 0L; } });
                assertThrows(RuntimeFault.class, () -> GuestThreadOps.fork(root, 3L, true));
                assertTrue(state.getSingleGuestOriginAssumption().isValid());
                assertFalse(state.isGuestConcurrencyAdmitted());
            } finally { context.leave(); }
        }
    }
    @Test void ordinaryPollAssumptionInvalidatesInstalledCode() throws Exception {
        try (var context = context("platform")) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var threads = state.getThreads(); var language = language();
                state.admitGuestOrigin(); threads.enterCurrent();
                try {
                    var root = scalarRoot(language, new Expr() { @Override public Object execute(VirtualFrame frame) { return 11L; } });
                    var target = root.getCallTarget();
                    for (int i = 0; i < 5; i++) assertEquals(11L, target.call(0L, Unit.INSTANCE));
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    assertFalse(GuestThreads.ordinaryPollEnabled(root));
                    state.admitGuestConcurrency();
                    assertEquals(false, target.getClass().getMethod("isValidLastTier").invoke(target), "Compiled poll depends on the origin assumption");
                    assertTrue(GuestThreads.ordinaryPollEnabled(root));
                    assertEquals(11L, target.call(0L, Unit.INSTANCE));
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    var request = threads.send(threads.currentIdentity(), "post-transition poll");
                    var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(target.call(0L, Unit.INSTANCE)));
                    assertSame(request, saved.asyncRequest()); request.acknowledge();
                    assertEquals(11L, drain(language, saved));
                } finally { threads.leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void firstExternalRequestReachesRequestFreeCompiledConcurrentLoop(String backend) throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build();
             var context = Context.newBuilder("thc").engine(engine).allowCreateThread(true).build();
             var other = Context.newBuilder("thc").engine(engine).build()) {
            context.initialize("thc"); other.initialize("thc");
            final RootCallTarget otherTarget;
            other.enter();
            try {
                var owner = Language.currentState(); owner.admitGuestConcurrency(); owner.getThreads().enterCurrent();
                try {
                    otherTarget = scalarRoot(language(), new Expr() {
                        @Override public Object execute(VirtualFrame frame) { return 17L; }
                    }).getCallTarget();
                    for (int i = 0; i < 5; i++) assertEquals(17L, otherTarget.call(0L, Unit.INSTANCE));
                    otherTarget.getClass().getMethod("compile", boolean.class).invoke(otherTarget, true);
                    assertEquals(true, otherTarget.getClass().getMethod("isValidLastTier").invoke(otherTarget));
                } finally { owner.getThreads().leaveCurrent(); }
            } finally { other.leave(); }
            context.enter();
            try {
                var owner = Language.currentState(); var language = language(); var threads = owner.getThreads();
                owner.admitGuestConcurrency(); threads.enterCurrent();
                try {
                    var prefix = new AtomicInteger(); var suffix = new AtomicInteger(); var limit = new AtomicInteger(5);
                    var observed = new AtomicReference<>(new CompletableFuture<Boolean>());
                    var before = scalarRoot(language, new Expr() {
                        @Override public Object execute(VirtualFrame frame) { prefix.incrementAndGet(); return 100L; }
                    });
                    var child = scalarRoot(language, new Expr() {
                        @Override public Object execute(VirtualFrame frame) {
                            suffix.incrementAndGet();
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                            for (int i = 0; i < limit.get(); i++) {
                                TruffleSafepoint.poll(this);
                                var request = GuestThreads.pollCurrent(this, false);
                                if (request != null) throw new AstCapture(request, SynchronousMasking.current(this))
                                    .append((saved, input) -> 42L);
                                observed.get().complete(CompilerDirectives.inCompiledCode());
                                if (System.nanoTime() >= deadline) throw new AssertionError("External request did not reach the ordinary cut");
                            }
                            return 42L;
                        }
                    });
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, parentModule(), false) : new BytecodeProgram(language, parentModule(), false);
                    var target = program.entryTarget("parent");
                    var caller = new GuestRoot(language, new FrameLayout().build()) {
                        @Child private DirectCallerNode call = new DirectCallerNode(target, new Metrics(true));
                        @Override public long bloom(VirtualFrame frame) { return 0L; }
                        @Override public Object execute(VirtualFrame frame) { return call.call(frame, frame.getArguments(), false); }
                    }.getCallTarget();
                    var arguments = new Object[]{0L, new Closure(null, 1, before.getCallTarget()), new Closure(null, 1, child.getCallTarget())};
                    for (int i = 0; i < 5; i++) assertEquals(142L, Calls.target(caller, arguments));
                    var identity = threads.currentIdentity(); var payload = new Object();
                    for (int phase = 0; phase < 2; phase++) {
                        child.getCallTarget().getClass().getMethod("compile", boolean.class).invoke(child.getCallTarget(), true);
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        var runtime = Truffle.getRuntime();
                        var installed = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                        runtime.getClass().getMethod("bypassedInstalledCode", installed).invoke(runtime, child.getCallTarget());
                        runtime.getClass().getMethod("bypassedInstalledCode", installed).invoke(runtime, target);
                        var compiledLoop = new CompletableFuture<Boolean>(); observed.set(compiledLoop); limit.set(Integer.MAX_VALUE);
                        var sent = CompletableFuture.supplyAsync(() -> {
                            try { assertTrue(compiledLoop.get(10, TimeUnit.SECONDS), "Request-free loop executes installed code before publication"); }
                            catch (Exception failure) { throw new CompletionException(failure); }
                            return threads.send(identity, payload);
                        });
                        int prefixes = prefix.get(), suffixes = suffix.get();
                        long compiled = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(Calls.target(caller, arguments)));
                        var request = sent.get(10, TimeUnit.SECONDS);
                        assertSame(request, saved.asyncRequest()); assertSame(payload, request.getPayload());
                        assertFalse(request.getForceSelf()); assertEquals(AsyncRequestState.CLAIMED, request.getState());
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > compiled);
                        assertEquals(true, otherTarget.getClass().getMethod("isValidLastTier").invoke(otherTarget), "Another context's request does not retire this target");
                        request.acknowledge(); assertEquals(142L, drain(language, saved));
                        assertEquals(prefixes + 1, prefix.get()); assertEquals(suffixes + 1, suffix.get());
                        assertSame(target, program.entryTarget("parent"));
                    }
                    var handoff = language.getHandoffState().get();
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                    assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                } finally { threads.leaveCurrent(); }
            } finally { context.leave(); }
            other.enter();
            try {
                var threads = Language.currentState().getThreads(); threads.enterCurrent();
                try { assertEquals(17L, otherTarget.call(0L, Unit.INSTANCE)); }
                finally { threads.leaveCurrent(); }
            } finally { other.leave(); }
        }
    }
    @Test void genericThreadAdmissionDoesNotChangeGuestOrigin() throws Exception {
        try (var context = context("platform")) {
            context.initialize("thc"); context.enter();
            final Language.State state;
            var ran = new CompletableFuture<Boolean>();
            try {
                state = Language.currentState(); state.admitGuestOrigin();
                var thread = state.getThreads().newThread(state.getEnv(), () -> ran.complete(state.getSingleGuestOriginAssumption().isValid()), null, null);
                state.getThreads().startThread(thread);
                TruffleSafepoint.setBlockedThreadInterruptible(null, Thread::join, thread);
                assertTrue(ran.get(10, TimeUnit.SECONDS));
                assertTrue(state.getSingleGuestOriginAssumption().isValid());
                assertFalse(state.getSingleThreadedAssumption().isValid());
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"platform", "loom"})
    void preparedPublicEntryAdmitsAnotherOriginBeforeGuestEffects(String hosting) throws Exception {
        var module = map("bindings", list(map("id", "identity", "name", "identity", "arity", 1, "lifted", true,
            "expr", list("lam", list(binder("x", LONG)), variable("x", LONG), map("resultRep", LONG)))));
        try (var context = context(hosting)) {
            context.initialize("thc"); context.enter();
            final org.graalvm.polyglot.Value entry; final Language.State state;
            try {
                state = Language.currentState();
                var code = Program.prepareCode(language(), module, List.of("identity"));
                entry = context.asValue(new EntryValue(code.newInstance(language()), "identity", 1));
            } finally { context.leave(); }
            assertEquals(7L, entry.execute(7L).asLong());
            assertEquals(9L, CompletableFuture.supplyAsync(() -> entry.execute(9L).asLong()).get(10, TimeUnit.SECONDS));
            assertFalse(state.getSingleGuestOriginAssumption().isValid());
            assertTrue(state.isGuestConcurrencyAdmitted());
            assertEquals(11L, entry.execute(11L).asLong());
        }
    }
}
