// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.runtime.Unit;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

public class AstStackTest {
    @Test void synchronousCaptureCapabilityDrainsSpillsWithoutEnablingDeliveryOrReplayingPrefixes() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState(); state.getThreads().enterCurrent(null, false, false, null);
                try {
                    state.getMaskingState().set(MaskingState.MASKED_INTERRUPTIBLE); var proof = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
                    var layout = new FrameLayout(); int argument = layout.bind("depth");
                    class Body extends Expr {
                        @Child private DirectCallerNode caller; int prefixes; int suffixes;
                        Body() { setRepresentation(proof); }
                        void install(RootCallTarget target) { caller = insert(new DirectCallerNode(target, new Metrics(false))); }
                        @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                        @Override public long executeLong(VirtualFrame frame) {
                            assertTrue(AstControl.captures(this)); assertFalse(AstControl.enabled(this), "Internal spilling does not grant external delivery"); assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(this));
                            prefixes++; long n = frame.getLong(argument); if (n == 0) return 1L;
                            Object result;
                            try { result = caller.call(frame, new Object[]{0L, n - 1}, false); }
                            catch (AstCapture cut) { throw cut.append(new AstResumeStep() { @Override public Object resume(VirtualFrame frame, Object input) { suffixes++; return (Long) input + 1; } }); }
                            suffixes++; return (Long) result + 1;
                        }
                    }
                    var body = new Body(); var root = new FunctionRoot(language, layout.build(), "sync spilled suffix", null, new int[0], new int[]{argument}, new int[]{0}, body,
                        new Metrics(false), new CoreRepresentation[]{proof}, proof, body.getCoreSourceLocation(), new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, true);
                    body.install(root.getCallTarget()); assertFalse(root.getEnableAsync()); assertEquals(4097L, Calls.target(root.getCallTarget(), new Object[]{0L, 4096L})); assertEquals(4097, body.prefixes); assertEquals(4096, body.suffixes);
                    var stack = state.getThreadPollState().get().getAstStack(); assertTrue(stack.getSpills() > 0); assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving()); assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.getMaskingState().get()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void synchronousPassThroughEntrySpillResumesOnceWithoutInstallingATailCatcher() {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class Body extends Expr { TailCall transfer; int entries; @Override public Object execute(VirtualFrame frame) { entries++; if (entries != 1) throw new IllegalStateException("Pass-through resume installed a loop"); throw transfer; } }
                var body = new Body(); var root = new FunctionRoot(language, new FrameLayout().build(), "sync parked side exit", null, new int[0], new int[0], new int[0], body,
                    new Metrics(false), new CoreRepresentation[0], body.getRepresentation(), body.getCoreSourceLocation(), new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.PASS_THROUGH, true);
                body.transfer = new TailCall(root.getCallTarget(), new Object[]{0L}); var stack = AstStacks.astStackScope(root); stack.setDepth(AstStackScope.MAX_DEPTH - 1); stack.setDriving(true);
                final AstContinuation saved; try { saved = (AstContinuation) Calls.target(root.getCallTarget(), new Object[]{0L}); } finally { stack.setDepth(0); stack.setDriving(false); }
                assertTrue(saved.stackSpill()); assertNull(saved.asyncRequest()); assertEquals(0, body.entries, "Entry spill precedes the side body's effects");
                assertSame(body.transfer, assertThrows(TailCall.class, () -> saved.continueWith(Unit.INSTANCE))); assertEquals(1, body.entries); assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving()); assertThrows(RuntimeFault.class, () -> saved.continueWith(Unit.INSTANCE));
            } finally { context.leave(); }
        }
    }
    private static SavedGuestContinuation classified(Object marker) {
        return new SavedGuestContinuation() {
            private final Object savedRoot = new Object();
            @Override public Object getIdentity() { return this; }
            @Override public Object getYielded() { return marker; }
            @Override public Object getSourceRoot() { return savedRoot; }
            @Override public Object continueWith(Object input) { throw new IllegalStateException("Classification must not resume a continuation"); }
        };
    }
    @Test void stackSpillRecognizesOnlyThePrivateIdentityAndTypedSuspensions() {
        var spoof = new Object() { @Override public boolean equals(Object other) { return true; } @Override public int hashCode() { return 0; } };
        var hostile = new Object() { @Override public boolean equals(Object other) { throw new IllegalStateException("Classification must not invoke guest equality"); } @Override public int hashCode() { return 0; } };
        assertTrue(classified(AstStackSpill.INSTANCE).stackSpill()); for (var marker : Arrays.asList(null, new Object(), spoof, hostile)) assertFalse(classified(marker).stackSpill());
        var thunk = new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Classification must not force a thunk"); } }.getCallTarget(), null); var segment = new CallSegment(classified(null));
        for (boolean spill : new boolean[]{false, true}) {
            assertEquals(spill, classified(new ThunkSuspended(thunk, null, spill)).stackSpill()); var saved = SavedGuestContinuations.savedGuestContinuation(segment.getValue());
            assertEquals(spill, classified(new CallSegmentSuspended(segment, null, saved == null ? null : saved.asyncRequest(), spill)).stackSpill());
        }
    }
    private final Map<String, Object> longRep = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private static List<?> variable(String id) { return List.of("var", id); }
    private static List<?> literal(int value) { return List.of("lit", "int", Integer.toString(value)); }
    private static List<?> apply(Object function, Object... arguments) { return List.of("app", function, Arrays.asList(arguments), Collections.nCopies(arguments.length, false)); }
    private static List<?> prim(String name, Object... arguments) { return apply(List.of("prim", name), arguments); }
    private static List<?> bind(Object value, String name, Object body) { return List.of("case", value, name, List.of(Arrays.asList("default", null, List.of(), body))); }
    private Map<String, Object> module() {
        var recurse = bind(apply(variable("loop"), prim("-#", variable("n"), literal(1)), variable("tick")), "answer", prim("+#", variable("answer"), variable("prefix")));
        var choice = List.of("case", variable("n"), "choice", List.of(List.of("lit", List.of("int", "0"), List.of(), variable("prefix")), Arrays.asList("default", null, List.of(), recurse)));
        var body = bind(apply(variable("tick"), variable("n")), "prefix", choice);
        var parameters = List.of(Map.of("id", "n", "name", "n", "lifted", false, "rep", longRep), Map.of("id", "tick", "name", "tick", "lifted", true, "rep", closure));
        return Map.of("bindings", List.of(Map.of("id", "loop", "name", "loop", "lifted", true, "expr", List.of("lam", parameters, body, Map.of("rep", closure, "resultRep", longRep)))));
    }
    @Test void bytecodeAutonomousCutsRetainEveryNonTailPrefixAndSuffix() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var state = Language.currentState(); state.getThreads().enterCurrent(null, false, true, null);
                try {
                    state.getMaskingState().set(MaskingState.MASKED_INTERRUPTIBLE);
                    var program = new BytecodeProgram(language, module(), true);
                    int[] prefixes = {0};
                    var tick = new Closure(null, 1, new RootNode(language) {
                        @Override public Object execute(VirtualFrame frame) {
                            assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.getMaskingState().get());
                            prefixes[0]++;
                            return 1L;
                        }
                    }.getCallTarget());
                    assertEquals(4097L, Calls.target(program.hostEntryTarget(2),
                        new Object[]{program.entryValue("loop"), new Object[]{4096L, tick}}));
                    assertEquals(4097, prefixes[0], "Internal cuts must not replay any completed prefix");
                    var stack = state.getThreadPollState().get().getAstStack();
                    assertTrue(stack.getSpills() > 0, "Bytecode must cut autonomously, without an async request");
                    assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving());
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.getMaskingState().get());
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void firstInstalledAstCallerSurvivesAnAutonomousCut() throws Exception { installedSpill(false); }
    @Test void coldInstalledBytecodeEntryPreservesAutonomousCutEffects() throws Exception { installedSpill(true); }
    private void installedSpill(boolean bytecode) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", "false")
                .option("engine.Splitting", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var source = new LinkedHashMap<>(module());
                var bindings = new ArrayList<Object>((List<?>) source.get("bindings"));
                var body = caseOf(apply(variable("loop"), variable("n"), variable("tick")), longRep,
                    prim("+#", variable("ignored"), literal(1)), longRep);
                var caller = new LinkedHashMap<>(binder("caller", closure, true));
                caller.put("expr", lambda(List.of(binder("n", longRep, false), binder("tick", closure, true)), body, longRep));
                bindings.add(caller); source.put("bindings", bindings); source.put("instrument", true);
                ExecutableProgram program = bytecode ? new BytecodeProgram(language, source, true) : new Program(language, source, true);
                var target = program.entryTarget("caller");
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                    .invoke(runtime, target);
                var state = Language.currentState(); state.getThreads().enterCurrent();
                try {
                    int[] prefixes = {0};
                    var tick = new Closure(null, 1, new RootNode(language) {
                        @Override public Object execute(VirtualFrame frame) { prefixes[0]++; return 1L; }
                    }.getCallTarget());
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(4098L, Calls.target(program.hostEntryTarget(2),
                        new Object[]{program.entryValue("caller"), new Object[]{4096L, tick}}));
                    assertEquals(4097, prefixes[0]);
                    // This proves compiled entry, not compiled body/spill execution: cold
                    // bytecode may deopt at its unspecialized stack-limit branch before the body.
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                    assertSame(target, program.entryTarget("caller"));
                    if (!bytecode) assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target),
                        "The original installed AST caller must survive its first spill");
                    var stack = state.getThreadPollState().get().getAstStack();
                    assertTrue(stack.getSpills() > 0); assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving());
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void autonomousCutsKeepTheOriginalTransactionLogUntilCommit() {
        for (boolean bytecode : new boolean[]{false, true})
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var state = Language.currentState(); state.getThreads().enterCurrent();
                try {
                    ExecutableProgram program = bytecode ? new BytecodeProgram(language, module(), true) : new Program(language, module(), true);
                    var cell = state.stm.newTVar(17L);
                    int[] prefixes = {0};
                    var tick = new Closure(null, 1, new RootNode(language) {
                        @Override public Object execute(VirtualFrame frame) {
                            assertEquals(17L, state.stm.readIO(cell), "A stack cut must not publish buffered writes");
                            assertEquals(99L, state.stm.read(cell), "A stack cut must retain its original log");
                            prefixes[0]++;
                            return 1L;
                        }
                    }.getCallTarget());
                    assertEquals(4097L, state.stm.atomically(null,
                        () -> { throw new IllegalStateException("Unexpected nested transaction"); }, () -> {
                            state.stm.write(cell, 99L);
                            return Calls.target(program.hostEntryTarget(2),
                                new Object[]{program.entryValue("loop"), new Object[]{4096L, tick}});
                        }));
                    assertEquals(99L, state.stm.readIO(cell)); assertFalse(state.stm.hasTransaction());
                    assertEquals(4097, prefixes[0], "Internal cuts do not restart transaction bodies");
                    var stack = state.getThreadPollState().get().getAstStack();
                    assertTrue(stack.getSpills() > 0); assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    private static Map<String, Object> binder(String id, Map<String, Object> rep, boolean lifted) { return Map.of("id", id, "name", id, "lifted", lifted, "rep", rep); }
    @Test void aRealConflictAfterSpillingRestartsOnlyTheFailedAttempt() throws Exception {
        for (boolean bytecode : new boolean[]{false, true})
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var state = Language.currentState(); state.getThreads().enterCurrent();
                try {
                    ExecutableProgram program = bytecode ? new BytecodeProgram(language, module(), true) : new Program(language, module(), true);
                    var cell = state.stm.newTVar(17L); int[] prefixes = {0}, attempts = {0};
                    var tick = new Closure(null, 1, new RootNode(language) {
                        @Override public Object execute(VirtualFrame frame) {
                            if (++prefixes[0] == 100) {
                                assertTrue(state.getThreadPollState().get().getAstStack().getSpills() > 0);
                                var writer = java.util.concurrent.CompletableFuture.runAsync(() -> state.stm.atomically(null,
                                    () -> { throw new AssertionError("Nested writer"); }, () -> { state.stm.write(cell, 50L); return Unit.INSTANCE; }));
                                try { writer.get(5, java.util.concurrent.TimeUnit.SECONDS); }
                                catch (Exception failure) { throw new AssertionError(failure); }
                            }
                            state.stm.read(cell); // The changed revision raises the original STMConflict.
                            return 1L;
                        }
                    }.getCallTarget());
                    assertEquals(4097L, state.stm.atomically(null, () -> { throw new AssertionError("Nested attempt"); }, () -> {
                        attempts[0]++; long value = (Long) state.stm.read(cell); state.stm.write(cell, value + 1);
                        return Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue("loop"), new Object[]{4096L, tick}});
                    }));
                    assertEquals(2, attempts[0]); assertEquals(100 + 4097, prefixes[0]);
                    assertEquals(51L, state.stm.readIO(cell)); assertFalse(state.stm.hasTransaction());
                    var stack = state.getThreadPollState().get().getAstStack(); assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    private List<?> lambda(List<?> parameters, Object body, Map<String, Object> result) { return List.of("lam", parameters, body, Map.of("rep", closure, "resultRep", result)); }
    private static List<?> caseOf(Object value, Map<String, Object> rep, Object body, Map<String, Object> result) { return List.of("case", value, "ignored", List.of(Arrays.asList("default", null, List.of(), body)), Map.of("rep", result, "binder", binder("ignored", rep, false))); }
    private Map<String, Object> caughtModule() { return caughtModule(false); }
    private Map<String, Object> caughtModule(boolean transaction) {
        Map<String, Object> state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true); var boxed = new LinkedHashMap<>(closure); boxed.put("kind", "data");
        Map<String, Object> io = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of("BoxedRep (Just Lifted)"), "components", List.of(state, boxed), "evaluated", true);
        var voidValue = List.of("void", Map.of("rep", state)); var unit = List.of("con", "Unit", 0, Map.of("rep", boxed));
        var pair = List.of("app", List.of("con", "Pair", 2), List.of(voidValue, unit), List.of(false, true), false, false, Map.of("rep", io));
        var action = lambda(List.of(binder("s", state, false)), caseOf(apply(variable("loop"), variable("n"), variable("tick")), longRep, pair, io), io);
        if (transaction) {
            var atomic = List.of("app", List.of("prim", "atomically#"), List.of(action, voidValue), List.of(true, false), false, false, Map.of("rep", io));
            action = lambda(List.of(binder("sAtomic", state, false)), atomic, io);
        }
        var handler = lambda(List.of(binder("e", boxed, true), binder("t", state, false)), pair, io);
        var caught = List.of("app", List.of("prim", "catch#"), List.of(action, handler, voidValue), List.of(true, true, false), false, false, Map.of("rep", io)); var body = caseOf(caught, io, literal(777), longRep);
        var result = new LinkedHashMap<>(module()); result.put("constructors", List.of(Map.of("id", "Unit", "name", "()", "arity", 0, "tag", 1, "fieldReps", List.of(), "strictFields", List.of(), "fieldLifted", List.of()), Map.of("id", "Pair", "name", "(#,#)", "arity", 2, "tag", 1, "kind", "unboxed-tuple")));
        var bindings = new ArrayList<Object>((List<?>) module().get("bindings")); var binding = new LinkedHashMap<>(binder("caught", closure, true)); binding.put("expr", lambda(List.of(binder("n", longRep, false), binder("tick", closure, true)), body, longRep)); bindings.add(binding);
        if (transaction) {
            // Neither nested atomically nor blocked-owner rescue occurs here. Declare
            // their lazy dependencies as bottom. BlockedOwners.hs exercises genuine
            // blocked-owner exceptions; this test observes external delivery and rollback.
            var lazyException = new LinkedHashMap<>(boxed); lazyException.put("evaluated", false);
            for (var id : List.of(STMOp.NESTED, CoreBlockedExceptions.STM)) {
                var dependency = new LinkedHashMap<>(binder(id, lazyException, true));
                dependency.put("expr", List.of("var", id, Map.of("rep", lazyException)));
                bindings.add(dependency);
            }
        }
        result.put("bindings", bindings); return result;
    }
    @Test void externalDeliveryAfterInternalSpillingAbandonsTheLogWithoutReplay() {
        for (boolean bytecode : new boolean[]{false, true})
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var state = Language.currentState(); state.getThreads().enterCurrent();
                try {
                    ExecutableProgram program = bytecode ? new BytecodeProgram(language, caughtModule(true), true) : new Program(language, caughtModule(true), true);
                    var payload = program.constructorLayout("Unit").create(new Object[0]); var cell = state.stm.newTVar(17L);
                    int[] prefixes = {0}; AsyncRequest[] sent = {null};
                    var tick = new Closure(null, 1, new RootNode(language) {
                        @Override public Object execute(VirtualFrame frame) {
                            state.stm.write(cell, 99L); assertEquals(17L, state.stm.readIO(cell));
                            if (++prefixes[0] == 100) {
                                assertTrue(state.getThreadPollState().get().getAstStack().getSpills() > 0);
                                var target = state.getThreads().currentIdentity();
                                var sender = java.util.concurrent.CompletableFuture.supplyAsync(() -> state.getThreads().send(target, payload));
                                try { sent[0] = sender.get(5, java.util.concurrent.TimeUnit.SECONDS); }
                                catch (Exception failure) { throw new AssertionError(failure); }
                            }
                            return 1L;
                        }
                    }.getCallTarget());
                    assertEquals(777L, Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue("caught"), new Object[]{4096L, tick}}), "bytecode=" + bytecode);
                    assertEquals(100, prefixes[0]); assertEquals(17L, state.stm.readIO(cell)); assertFalse(state.stm.hasTransaction());
                    assertNotNull(sent[0]); assertEquals(AsyncRequestState.ACKNOWLEDGED, sent[0].getState());
                    assertFalse(sent[0].getForceSelf()); assertSame(Thread.currentThread(), sent[0].getTarget());
                    assertEquals(state.getThreads().currentIdentity().getLogicalId(), sent[0].getTargetId());
                    assertEquals(MaskingState.UNMASKED, state.getMaskingState().get());
                    var stack = state.getThreadPollState().get().getAstStack(); assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving());
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void spillsRetainSharedUpdatesPrefixesMasksAndMemoizedFailure() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState(); state.getThreads().enterCurrent();
                try {
                    state.getMaskingState().set(MaskingState.MASKED_INTERRUPTIBLE); var program = new Program(language, module(), true);
                    class Effects { int effects; int failAt = -1; } var effects = new Effects();
                    var tick = new Closure(null, 1, new RootNode(language) { @Override public Object execute(VirtualFrame frame) { assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.getMaskingState().get()); effects.effects++; if (effects.effects == effects.failAt) throw new GuestException("expected failure", this); return 1L; } }.getCallTarget());
                    class Shared { Thunk create() { return new Thunk(new RootNode(language) { @Override public Object execute(VirtualFrame frame) { return Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue("loop"), new Object[]{4096L, tick}}); } }.getCallTarget(), null); } }
                    var shared = new Shared(); var force = new RootNode(language) { @Child Force node = new Force(new Metrics(false), true); @Override public Object execute(VirtualFrame frame) { return node.execute(frame, frame.getArguments()[0]); } }.getCallTarget();
                    var value = shared.create(); assertEquals(4097L, Calls.target(force, new Object[]{value})); assertEquals(4097, effects.effects); assertEquals(4097L, Calls.target(force, new Object[]{value})); assertEquals(4097, effects.effects, "A completed shared update never replays its prefix"); assertEquals(2, value.getState()); assertNull(value.getOwner());
                    var scope = state.getThreadPollState().get().getAstStack(); assertTrue(scope.getSpills() > 0); assertEquals(0, scope.getDepth()); assertFalse(scope.getDriving()); effects.failAt = effects.effects + 300; var failed = shared.create();
                    assertThrows(GuestException.class, () -> Calls.target(force, new Object[]{failed})); assertEquals(effects.failAt, effects.effects); assertThrows(GuestException.class, () -> Calls.target(force, new Object[]{failed})); assertEquals(effects.failAt, effects.effects, "A guest failure remains memoized across a spilled update");
                    assertEquals(3, failed.getState()); assertNull(failed.getOwner()); assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.getMaskingState().get()); assertEquals(0, scope.getDepth()); assertFalse(scope.getDriving()); assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void autonomousDriverLeavesAsyncDeliveryForItsRealHandler() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState(); state.getThreads().enterCurrent();
                try {
                    var program = new Program(language, module(), true); class Effects { int count; AsyncRequest request; } var effects = new Effects();
                    var tick = new Closure(null, 1, new RootNode(language) { @Override public Object execute(VirtualFrame frame) { if (++effects.count == 100) effects.request = state.getThreads().send(state.getThreads().currentIdentity(), "interrupt after spill"); return 1L; } }.getCallTarget());
                    var result = Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue("loop"), new Object[]{4096L, tick}}); var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(result), "Expected the actual async continuation");
                    assertSame(effects.request, saved.asyncRequest()); assertFalse(saved.stackSpill()); assertEquals(AsyncRequestState.CLAIMED, effects.request.getState()); assertEquals(100, effects.count, "The autonomous driver must stop before delivering an async request");
                    var scope = state.getThreadPollState().get().getAstStack(); assertTrue(scope.getSpills() > 0); assertEquals(0, scope.getDepth()); assertFalse(scope.getDriving()); effects.request.acknowledge();
                    var parked = new Thunk(((RootNode) saved.getSourceRoot()).getCallTarget(), null); parked.setValue(saved.getIdentity()); parked.setState(5);
                    var force = new RootNode(language) { @Child Force node = new Force(new Metrics(false), true); @Override public Object execute(VirtualFrame frame) { return node.execute(frame, parked); } }.getCallTarget();
                    assertEquals(4097L, Calls.target(force, new Object[0])); assertEquals(4097, effects.count, "Resumption retains all prefixes from before the async cut"); assertEquals(2, parked.getState()); assertNull(parked.getOwner());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void asyncDeliveryReachesItsCatchScopeSavedBeforeTheSpill() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState(); state.getThreads().enterCurrent();
                try {
                    var program = new Program(language, caughtModule(), true); var payload = program.constructorLayout("Unit").create(new Object[0]); class Effects { int count; AsyncRequest request; } var effects = new Effects();
                    var tick = new Closure(null, 1, new RootNode(language) { @Override public Object execute(VirtualFrame frame) { if (++effects.count == 100) effects.request = state.getThreads().send(state.getThreads().currentIdentity(), payload); return 1L; } }.getCallTarget());
                    assertEquals(777L, Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue("caught"), new Object[]{4096L, tick}})); assertEquals(100, effects.count); assertEquals(AsyncRequestState.ACKNOWLEDGED, effects.request.getState()); assertEquals(MaskingState.UNMASKED, state.getMaskingState().get());
                    var scope = state.getThreadPollState().get().getAstStack(); assertTrue(scope.getSpills() > 0); assertEquals(0, scope.getDepth()); assertFalse(scope.getDriving()); assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void failedSpilledTransactionRollsBackItsOriginalLog() {
        for (boolean bytecode : new boolean[]{false, true})
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState(); state.getThreads().enterCurrent();
                try {
                    ExecutableProgram program = bytecode ? new BytecodeProgram(language, module(), true) : new Program(language, module(), true);
                    var payload = new Object(); int[] prefixes = {0};
                    var tick = new Closure(null, 1, new RootNode(language) {
                        @Override public Object execute(VirtualFrame frame) {
                            if (++prefixes[0] == 100) throw new GuestException(payload, this);
                            return 1L;
                        }
                    }.getCallTarget());
                    var cell = state.stm.newTVar(17L);
                    var failure = assertThrows(GuestException.class, () -> state.stm.atomically(null, () -> { throw new IllegalStateException("Unexpected nested transaction"); }, () -> { state.stm.write(cell, 99L); return Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue("loop"), new Object[]{4096L, tick}}); }));
                    assertSame(payload, failure.getPayload()); assertEquals(100, prefixes[0]);
                    assertEquals(17L, state.stm.readIO(cell)); assertFalse(state.stm.hasTransaction());
                    var scope = state.getThreadPollState().get().getAstStack(); assertTrue(scope.getSpills() > 0);
                    assertEquals(0, scope.getDepth()); assertFalse(scope.getDriving());
                    assertEquals(4097L, Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue("loop"), new Object[]{4096L, tick}}));
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void reparkedParentForwardsTheClaimedRequestToItsSavedCaller() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState(); state.getThreads().enterCurrent();
                try {
                    var program = new Program(language, module(), true); var root = (GuestRoot) ((Closure) program.entryValue("loop")).target.getRootNode();
                    class Records {
                        AstContinuation saved(Object marker, Function<Object, Object> action) {
                            return new AstContinuation(root, marker, MaskingState.UNMASKED, Truffle.getRuntime().createMaterializedFrame(new Object[0], root.getFrameDescriptor()), List.of(new AstResumeStep() { @Override public Object resume(VirtualFrame frame, Object input) { return action.apply(input); } }), StackAnnotations.current(root));
                        }
                        Thunk parked(SavedGuestContinuation record) { var thunk = new Thunk(root.getCallTarget(), null); thunk.setValue(record.getIdentity()); thunk.setState(5); return thunk; }
                        Thunk parent; Thunk child; AsyncRequest request; int prefixes;
                    }
                    var records = new Records();
                    var childRecord = new SavedGuestContinuation() {
                        @Override public Object getIdentity() { return this; } @Override public Object getSourceRoot() { return root; } @Override public Object getYielded() { return Unit.INSTANCE; }
                        @Override public Object continueWith(Object input) {
                            records.prefixes++;
                            // Model another evaluator re-parking after the driver recorded the old identity.
                            synchronized (records.parent.getMonitor()) { records.parent.setValue(records.saved(new ThunkSuspended(records.child), value -> { throw new IllegalStateException("Reparked parent resumed before delivery"); })); }
                            records.request = state.getThreads().send(state.getThreads().currentIdentity(), "race payload"); assertSame(records.request, state.getThreads().poll(root)); return records.saved(records.request, value -> 19L);
                        }
                    };
                    records.child = records.parked(childRecord); records.parent = records.parked(records.saved(new ThunkSuspended(records.child), value -> { throw new IllegalStateException("Stale parent continuation resumed"); }));
                    var outer = records.parked(records.saved(new ThunkSuspended(records.parent), input -> {
                        var cut = (AstChildSuspension) input; assertSame(records.parent, cut.getChild()); assertSame(records.request, cut.getRequest()); assertEquals(AsyncRequestState.CLAIMED, records.request.getState()); records.request.acknowledge(); return 777L;
                    }));
                    var driver = new RootNode(language) { @Child Force force = new Force(new Metrics(false), true); @Override public Object execute(VirtualFrame frame) { return force.execute(frame, outer); } }.getCallTarget();
                    assertEquals(777L, Calls.target(driver, new Object[0])); assertEquals(AsyncRequestState.ACKNOWLEDGED, records.request.getState()); assertEquals(1, records.prefixes); assertEquals(2, outer.getState()); assertEquals(5, records.parent.getState()); assertEquals(5, records.child.getState()); assertNull(records.parent.getOwner()); assertNull(records.child.getOwner());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void reentrantEntryGetsItsOwnDriverWithoutChangingTheOuterDepth() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var program = new Program(language, module(), true); var tick = new Closure(null, 1, new RootNode(language) { @Override public Object execute(VirtualFrame frame) { return 1L; } }.getCallTarget());
                var state = Language.currentState(); state.getThreads().enterCurrent(); var outer = state.getThreadPollState().get().getAstStack(); outer.setDepth(17); outer.setDriving(true); var foreign = state.getThreads().enterForeign(ForeignSafety.SAFE);
                try {
                    state.getThreads().enterCurrent();
                    try {
                        var inner = state.getThreadPollState().get().getAstStack(); assertNotSame(outer, inner); assertEquals(0, inner.getDepth()); assertFalse(inner.getDriving());
                        assertEquals(4097L, Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue("loop"), new Object[]{4096L, tick}})); assertTrue(inner.getSpills() > 0); assertEquals(0, inner.getDepth()); assertFalse(inner.getDriving());
                    } finally { state.getThreads().leaveCurrent(); }
                    assertSame(outer, state.getThreadPollState().get().getAstStack()); assertEquals(17, outer.getDepth()); assertTrue(outer.getDriving());
                } finally { state.getThreads().leaveForeign(foreign); outer.setDepth(0); outer.setDriving(false); state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
}
