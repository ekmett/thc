// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Synthetic protocol controls, not native-GHC fixture or performance evidence. */
class CoreUnitAsyncPolicyTest {
    @TempDir Path directory;
    @AfterEach void releaseIdleMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private final String boundary = "optimized-Core-after-Tidy-before-CorePrep";
    private final Map<String, Object> stateRep = map("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> longRep = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> dataRep = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closureRep = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private List<Object> literal(int value) { return list("lit", "int", Integer.toString(value), map("rep", longRep)); }
    private List<Object> traceThen(List<Object> body, Map<String, Object> result) {
        return list("case", list("app", list("prim", "traceEvent#", map("rep", closureRep)), list(list("lit", "string-bytes", "64656d616e64",
            map("rep", map("kind", "address", "primReps", list("AddrRep"), "evaluated", true))), list("void", map("rep", stateRep))),
            list(false, false), false, false, map("rep", stateRep)), "traced", list(list("default", null, List.of(), body, map("binders", List.of()))),
            map("rep", result, "binder", map("id", "traced", "lifted", false, "rep", stateRep)));
    }
    private Map<String, Object> function(String id, List<Object> body) {
        return map("id", id, "name", id.substring(id.lastIndexOf('.') + 1), "type", "Int# -> Int#", "lifted", true, "arity", 1, "rep", closureRep,
            "expr", list("lam", list(map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false, "rep", longRep)), body, map("rep", closureRep, "resultRep", longRep)));
    }
    /** Model units use the real CBD encoder; runtime loading has no JSON fallback. */
    private Map<String, Object> unit(String name, List<Map<String, Object>> bindings, List<Object> constructors) throws Exception {
        var module = map("schema", 1, "ghc", "9.14.1", "unit", "u" + name, "module", name, "boundary", boundary,
            "constructors", constructors, "bindings", bindings);
        return map("id", "u" + name, "depends", List.of(), "modules", list(CoreCbdFixtures.module(directory.resolve(name + ".cbd"), module)));
    }
    private Path fixture(boolean traceEntry) throws Exception {
        var a = unit("A", List.of(function("uA:A.entry", traceEntry ? traceThen(literal(7), longRep) : literal(7))), List.of());
        var boxed = list("app", list("con", "uB:B.Box", 1, map("rep", closureRep)), list(literal(17)), list(false), true, true, map("rep", with(dataRep, "evaluated", true)));
        var caf = map("id", "uB:B.caf", "name", "caf", "type", "Box", "lifted", true, "arity", 0, "rep", dataRep, "expr", traceThen(boxed, dataRep));
        var b = unit("B", List.of(function("uB:B.function", traceThen(literal(19), longRep)), caf), list(map("id", "uB:B.Box", "name", "Box", "kind", "boxed", "arity", 1, "tag", 1,
            "fieldReps", list(list("IntRep")), "fieldTypes", list(longRep), "strictFields", list(false), "fieldLifted", list(false))));
        return Files.writeString(directory.resolve("packages.json"), Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", list(a, b))));
    }
    private String request(Path path, String backend, Boolean async) { return CoreFormatTestSupport.request(List.of("@" + path), "uA:A.entry", backend, false, async, false); }
    private long count(ExecutableProgram program, String name) { return ((Number) program.diagnostics().get(name)).longValue(); }
    private AsyncRequest externalSend(GuestThreads threads, long id) throws Exception {
        var pending = CompletableFuture.supplyAsync(() -> threads.send(id, "pending demand")).get(5, TimeUnit.SECONDS);
        assertFalse(pending.getForceSelf()); return pending;
    }
    private Context context(ByteArrayOutputStream output) {
        var context = Context.newBuilder("thc").err(output).build();
        context.initialize("thc"); context.enter();
        try { Language.currentState().getRuntimeTrace().control(500, 1); } finally { context.leave(); }
        return context;
    }
    private CoreUnitProgram program(Language.State owner) { var programs = owner.getCoreUnitPrograms(); assertEquals(1, programs.size()); return programs.getFirst(); }
    @Test void defaultOffAndExplicitOverridesRetainCaptureOnBothBackends() throws Exception {
        var manifest = fixture(false);
        for (var backend : List.of("ast", "bytecode")) for (var async : Arrays.asList(null, false, true))
            try (var context = context(new ByteArrayOutputStream())) {
                var entry = context.eval("thc", request(manifest, backend, async));
                assertEquals(Boolean.TRUE.equals(async), ((Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString())).get("asyncExceptions"));
                context.enter();
                try {
                    var owner = Language.currentState(null); var program = program(owner);
                    assertTrue(program.getCapturesContinuations());
                    assertEquals(Boolean.TRUE.equals(async), ((GuestRoot) program.entryTarget("uA:A.entry").getRootNode()).getEagerAsyncPolls());
                    assertTrue(owner.getSingleGuestOriginAssumption().isValid());
                } finally { context.leave(); }
                assertEquals(7L, entry.execute(0).asLong());
            }
    }
    @Test void publicWrapperHonorsExternalDeliveryPolicyAndBytecodeInspection() throws Exception {
        var manifest = fixture(true);
        for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true}) {
            boolean[] observed = {false};
            var output = new ByteArrayOutputStream() {
                @Override public void write(byte[] bytes, int start, int length) {
                    if (!observed[0]) {
                        observed[0] = true; var threads = Language.currentState(null).getThreads();
                        var slot = Objects.requireNonNull(threads.pollState(Thread.currentThread()).getCurrent());
                        assertTrue(slot.getExternalAsync(), backend + " wrapper capture capability");
                        // A different Java thread prevents self-throwTo from bypassing policy.
                        try { CompletableFuture.runAsync(() -> {
                            var pending = threads.send(slot.getIdentity(), "policy probe"); assertFalse(pending.getForceSelf());
                            assertEquals(AsyncRequestState.PENDING, pending.getState()); assertTrue(pending.cancel()); assertEquals(AsyncRequestState.CANCELLED, pending.getState());
                        }).get(5, TimeUnit.SECONDS); } catch (Exception failure) { throw new AssertionError(failure); }
                    }
                    super.write(bytes, start, length);
                }
            };
            try (var context = context(output)) {
                var entry = context.eval("thc", request(manifest, backend, async)); assertFalse(observed[0], "loading must not execute the entry effect");
                assertEquals(7L, entry.execute(0).asLong()); assertTrue(observed[0]); assertEquals("[thc trace event] demand\n", output.toString(StandardCharsets.UTF_8));
                assertEquals(backend.equals("bytecode"), entry.hasMember("bytecode")); if (backend.equals("bytecode")) assertFalse(entry.getMember("bytecode").asString().isBlank());
            }
        }
    }
    @Test void signalBindingUsesWrapperPolicyBeforeAcquiringTransportOrDemandingDispatcher() throws Exception {
        var manifest = fixture(false);
        for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true}) try (var context = context(new ByteArrayOutputStream())) {
            context.eval("thc", request(manifest, backend, async)); context.enter();
            try {
                var owner = Language.currentState(null); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var program = program(owner);
                assertEquals(async, program.getAsynchronousExceptions()); long decoded = count(program, "coreCompactDecodedBindings");
                var signals = new ManagedSignals(owner, language, true, () -> NativeSignalTransport.userSignalAvailable(),
                    () -> { throw new IllegalStateException("binding alone must not acquire the native signal transport"); });
                try {
                    signals.bind(program);
                    assertTrue(owner.getSingleGuestOriginAssumption().isValid(), "Binding does not publish a signal worker");
                    assertEquals(decoded, count(program, "coreCompactDecodedBindings"));
                } finally { signals.close(); }
            } finally { context.leave(); }
        }
    }
    @Test void firstColdFunctionDemandDoesNotClaimPendingDeliveryOrMemoizeControlAsFailure() throws Exception {
        var manifest = fixture(false);
        for (var backend : List.of("ast", "bytecode")) {
            var output = new ByteArrayOutputStream();
            try (var context = context(output)) {
                context.eval("thc", request(manifest, backend, true)); context.enter(); var owner = Language.currentState(null); var threads = owner.getThreads();
                long id = threads.enterCurrent(null, false, true, null);
                try {
                    var program = program(owner); var pending = externalSend(threads, id); var closure = (Closure) program.entryValue("uB:B.function");
                    assertEquals(AsyncRequestState.PENDING, pending.getState(), backend + " preparation is not guest execution"); assertSame(closure, program.entryValue("uB:B.function"));
                    assertEquals(2L, count(program, "coreCompactDecodedBindings")); assertEquals(0, output.size());
                    var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(Calls.target(closure.target, new Object[]{0L, 1L})));
                    assertSame(pending, SavedGuestContinuations.asyncRequest(saved)); assertEquals(AsyncRequestState.CLAIMED, pending.getState()); assertEquals(0, output.size(), "pending delivery precedes the function effect");
                    pending.acknowledge(); assertEquals(19L, saved.continueWith(thc.runtime.Unit.INSTANCE)); assertSame(closure, program.entryValue("uB:B.function"));
                    assertEquals(2L, count(program, "coreCompactDecodedBindings")); assertEquals("[thc trace event] demand\n", output.toString(StandardCharsets.UTF_8));
                } finally { threads.leaveCurrent(thc.runtime.GuestThreadStatus.FINISHED); context.leave(); }
            }
        }
    }
    @Test void firstColdCafDemandPreservesUnevaluatedIdentityAcrossDeliveryAndResume() throws Exception {
        var manifest = fixture(false);
        for (var backend : List.of("ast", "bytecode")) {
            var output = new ByteArrayOutputStream();
            try (var context = context(output)) {
                context.eval("thc", request(manifest, backend, true)); context.enter(); var owner = Language.currentState(null); var threads = owner.getThreads();
                long id = threads.enterCurrent(null, false, true, null);
                try {
                    var program = program(owner); var pending = externalSend(threads, id); var thunk = (Thunk) program.entryValue("uB:B.caf");
                    assertEquals(AsyncRequestState.PENDING, pending.getState()); assertEquals(0, thunk.getState()); assertSame(thunk, program.entryValue("uB:B.caf"));
                    assertEquals(0L, count(program, "thunkEvaluations")); assertEquals(0, output.size());
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var metrics = new Metrics(true);
                    var force = new RootNode(language, new FrameLayout().build()) {
                        @Child private Force evaluator = new Force(metrics, true);
                        @Override public Object execute(VirtualFrame frame) { return evaluator.execute(frame, frame.getArguments()[0]); }
                    }.getCallTarget();
                    var suspension = assertThrows(ThunkSuspended.class, () -> Calls.target(force, new Object[]{thunk})); assertSame(pending, suspension.getAsyncRequest());
                    assertEquals(AsyncRequestState.CLAIMED, pending.getState()); assertEquals(5, thunk.getState()); assertEquals(0, output.size()); pending.acknowledge();
                    var value = (DataValue) Calls.target(force, new Object[]{thunk}); assertEquals(17L, value.getLayout().readLong(value, 0));
                    assertEquals(2, thunk.getState()); assertSame(thunk, program.entryValue("uB:B.caf")); assertSame(value, Calls.target(force, new Object[]{thunk}));
                    assertEquals(1L, metrics.getThunkEvaluations()); assertEquals(2L, count(program, "coreCompactDecodedBindings")); assertEquals("[thc trace event] demand\n", output.toString(StandardCharsets.UTF_8));
                } finally { threads.leaveCurrent(thc.runtime.GuestThreadStatus.FINISHED); context.leave(); }
            }
        }
    }
}
