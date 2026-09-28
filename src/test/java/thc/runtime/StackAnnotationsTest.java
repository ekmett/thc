// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.VirtualFrame;
import kotlin.Unit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class StackAnnotationsTest {
    private final Map<String, Object> stateRep = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> objectRep = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> functionRep = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> tupleRep = Map.of("kind", "unknown", "aggregate", "unboxed-tuple",
        "components", List.of(stateRep, objectRep), "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private List<Object> variable(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private Map<String, Object> binder(String id, Map<String, Object> rep, boolean lifted) {
        return Map.of("id", id, "name", id, "rep", rep, "lifted", lifted, "coercion", false);
    }
    private Map<String, Object> module(boolean pause) {
        var callback = variable("action", functionRep);
        var state = variable("s", stateRep);
        List<Object> action;
        if (!pause) action = callback;
        else {
            var token = variable("t", stateRep);
            var checkpoint = List.of("app", List.of("prim", "noDuplicate#"), List.of(token),
                List.of(false), false, false, Map.of("rep", stateRep));
            var call = List.of("app", callback, List.of(token), List.of(false), false, false, Map.of("rep", tupleRep));
            var body = List.of("case", checkpoint, "ignored", List.of(Arrays.asList("default", null, List.of(), call)),
                Map.of("rep", tupleRep, "binder", binder("ignored", stateRep, false)));
            action = List.of("lam", List.of(binder("t", stateRep, false)), body,
                Map.of("rep", functionRep, "resultRep", tupleRep, "entryStrict", List.of(false)));
        }
        var body = List.of("app", List.of("prim", "annotateStack#"),
            List.of(variable("ann", objectRep), action, state), List.of(true, true, false), false, false, Map.of("rep", tupleRep));
        return Map.of("instrument", true, "bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true,
            "expr", List.of("lam", List.of(binder("ann", objectRep, true), binder("action", functionRep, true), binder("s", stateRep, false)),
                body, Map.of("resultRep", tupleRep, "entryStrict", List.of(false, false, false))))));
    }
    @FunctionalInterface private interface Entered { void run(Language language) throws Exception; }
    private void entered(Entered block) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { block.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }
    private static final class Probe extends GuestRoot {
        private final TupleShape shape;
        boolean failure;
        boolean suspend;
        Probe(Language language, TupleShape shape) { super(language, new FrameLayout().build()); this.shape = shape; configureTupleResult(shape); }
        Object finish() {
            Object snapshot = suspend ? StackAnnotations.current(this).values() : ManagedStackSnapshot.capture(this);
            if (failure) throw new GuestException(snapshot, this);
            var result = shape.getLayout().create(); shape.getLayout().setObject(result, 0, snapshot); return result;
        }
        @Override public Object execute(VirtualFrame frame) {
            if (suspend) return new AstCapture(Unit.INSTANCE, SynchronousMasking.current(this)).append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame resumed, Object input) { return finish(); }
            }).freeze(this, frame.materialize());
            return finish();
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
    }
    private ManagedStackSnapshot snapshot(Object result, TupleShape shape) {
        return (ManagedStackSnapshot) shape.getLayout().getObject(TupleResultsKt.ownedTupleResult(result, shape), 0);
    }
    private void install(com.oracle.truffle.api.RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }

    @Test void lazyPayloadIsVisibleInSnapshotAndReturnsOnSuccessAndException() throws Exception {
        entered(language -> {
            var shape = new TupleShape(CoreRepresentations.parse(tupleRep), language);
            var probe = new Probe(language, shape); var action = new Closure(null, 1, probe.getCallTarget());
            var never = new GuestRoot(language, new FrameLayout().build()) {
                @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("annotation was forced"); }
                @Override public long bloom(VirtualFrame frame) { return 0L; }
            };
            var payload = new Thunk(never.getCallTarget(), null);
            for (String backend : List.of("ast", "bytecode")) {
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module(false)) : new BytecodeProgram(language, module(false));
                var target = program.entryTarget("entry");
                var ambient = StackAnnotationState.EMPTY.push("outside"); StackAnnotations.set(null, ambient);
                Supplier<ManagedStackSnapshot> run = () -> snapshot(Calls.target(target, new Object[]{0L, payload, action, Unit.INSTANCE}), shape);
                assertEquals(List.of(payload, "outside"), run.get().getAnnotations());
                assertSame(ambient, StackAnnotations.current(null)); assertEquals(0, payload.getState());
                install(target);
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                var saved = run.get();
                assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                assertEquals(List.of(payload, "outside"), saved.getAnnotations());
                assertSame(ambient, StackAnnotations.current(null)); assertEquals(0, payload.getState());
                probe.failure = true;
                var failure = assertThrows(GuestException.class, run::get);
                assertEquals(List.of(payload, "outside"), ((ManagedStackSnapshot) failure.getPayload()).getAnnotations());
                assertSame(ambient, StackAnnotations.current(null)); probe.failure = false;
                StackAnnotations.set(null, StackAnnotationState.EMPTY);
                assertEquals(List.of(payload, "outside"), saved.getAnnotations());
            }
        });
    }

    @Test void oneShotActionsParkAnnotationsAndRestoreCapturedStateOnResume() throws Exception {
        entered(language -> {
            var shape = new TupleShape(CoreRepresentations.parse(tupleRep), language);
            var probe = new Probe(language, shape); var action = new Closure(null, 1, probe.getCallTarget());
            var outside = StackAnnotationState.EMPTY.push("original"); var resumer = StackAnnotationState.EMPTY.push("resumer");
            StackAnnotations.set(null, outside);
            var ast = new Program(language, module(false), true); probe.suspend = true;
            var saved = (AstContinuation) Calls.target(ast.entryTarget("entry"), new Object[]{0L, "inside", action, Unit.INSTANCE});
            assertSame(outside, StackAnnotations.current(null));
            var astSuspended = (CallSegmentSuspended) saved.getYielded();
            // The same ownership driver used below completes the action segment
            // before giving the annotated caller its validated ChildResume.
            var astThunk = new Thunk(ast.entryTarget("entry"), null); astThunk.setValue(saved); astThunk.setState(5);
            var astDriver = new GuestRoot(language, new FrameLayout().build()) {
                @Child private Force force = new Force(new Metrics(false));
                @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { return force.execute(frame, astThunk); }
            };
            StackAnnotations.set(null, resumer);
            var astResult = Calls.target(astDriver.getCallTarget(), new Object[]{0L});
            assertEquals(List.of("inside", "original"), shape.getLayout().getObject(TupleResultsKt.ownedTupleResult(astResult, shape), 0));
            assertEquals(2, astSuspended.getSegment().getState()); assertEquals(2, astThunk.getState());
            assertSame(resumer, StackAnnotations.current(null)); probe.suspend = false;

            var checkpoint = new BytecodeCheckpoint(); checkpoint.setArmed(true);
            var bytecode = new BytecodeProgram(language, module(true), checkpoint);
            StackAnnotations.set(null, outside);
            var parent = (ContinuationResult) Calls.target(bytecode.entryTarget("entry"), new Object[]{0L, "inside", action, Unit.INSTANCE});
            assertSame(outside, StackAnnotations.current(null));
            var suspended = (CallSegmentSuspended) parent.getResult();
            // Give the real update/continuation driver ownership of this saved root;
            // it commits the child segment before supplying the parent's ChildResume.
            var thunk = new Thunk(bytecode.entryTarget("entry"), null); thunk.setValue(parent); thunk.setState(5);
            var driver = new GuestRoot(language, new FrameLayout().build()) {
                @Child private Force force = new Force(new Metrics(false));
                @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { return force.execute(frame, thunk); }
            };
            StackAnnotations.set(null, resumer);
            var result = Calls.target(driver.getCallTarget(), new Object[]{0L});
            assertEquals(List.of("inside", "original"), snapshot(result, shape).getAnnotations());
            assertEquals(2, suspended.getSegment().getState()); assertEquals(2, thunk.getState());
            assertEquals(1, checkpoint.getVisits().get());
            assertSame(resumer, StackAnnotations.current(null)); StackAnnotations.set(null, StackAnnotationState.EMPTY);
        });
    }

    @Test void multiShotAnnotationReturnRebasesOnResumerAmbientAndRecapture() throws Exception {
        entered(language -> {
            var shape = new TupleShape(CoreRepresentations.parse(tupleRep), language);
            var probe = new Probe(language, shape); var action = new Closure(null, 1, probe.getCallTarget());
            var owner = new GuestRoot(language, new FrameLayout().build()) {
                @Child DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                DelimitedStack stack;
                @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { return stack.resume(site, frame.materialize(), frame.getArguments()[1]); }
            };
            var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, owner.getFrameDescriptor());
            var outside = StackAnnotationState.EMPTY.push("outside prompt"); StackAnnotations.set(null, outside.push("captured"));
            var cut = new DelimitedCut(new PromptTag(Language.currentState()), action, shape, MaskingState.UNMASKED, owner);
            cut.append(frame, new DelimitedAnnotationStep(owner, outside)); owner.stack = new DelimitedStack(cut, shape);
            var installed = owner.stack.closure(language, new Metrics(false)).target;
            for (String label : List.of("first", "second")) {
                var ambient = StackAnnotationState.EMPTY.push(label); StackAnnotations.set(null, ambient);
                if (label.equals("second")) install(installed);
                var calls = installed.getClass().getMethod("getCallCount").invoke(installed);
                assertEquals(List.of("captured", label), snapshot(Calls.target(installed, new Object[]{0L, action, Unit.INSTANCE}), shape).getAnnotations());
                if (label.equals("second")) {
                    assertEquals(calls, installed.getClass().getMethod("getCallCount").invoke(installed), "First call enters the installed continuation root");
                    assertEquals(true, installed.getClass().getMethod("isValidLastTier").invoke(installed));
                }
                assertSame(ambient, StackAnnotations.current(null));
            }
            var recapture = new GuestRoot(language, new FrameLayout().build()) {
                @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) {
                    throw new DelimitedCut(new PromptTag(Language.currentState()), action, shape, MaskingState.UNMASKED, this);
                }
            };
            var second = assertThrows(DelimitedCut.class, () -> Calls.target(owner.getCallTarget(), new Object[]{0L, new Closure(null, 1, recapture.getCallTarget())}));
            assertEquals(List.of("second"), StackAnnotations.current(null).values()); owner.stack = new DelimitedStack(second, shape);
            var ambient = StackAnnotationState.EMPTY.push("third"); StackAnnotations.set(null, ambient);
            assertEquals(List.of("captured", "third"), snapshot(Calls.target(owner.getCallTarget(), new Object[]{0L, action}), shape).getAnnotations());
            probe.failure = true;
            assertThrows(GuestException.class, () -> Calls.target(owner.getCallTarget(), new Object[]{0L, action}));
            assertSame(ambient, StackAnnotations.current(null)); StackAnnotations.set(null, StackAnnotationState.EMPTY);
        });
    }

    @Test void nestedRebaseSharesPrefixesAndContextsRemainIsolated() throws Exception {
        entered(language -> {
            var outside = StackAnnotationState.EMPTY.push("outside"); var middle = outside.push("middle"); var inner = middle.push("inner");
            var ambient = StackAnnotationState.EMPTY.push("ambient");
            var copies = new IdentityHashMap<StackAnnotationState, StackAnnotationState>();
            var returned = middle.rebase(outside, ambient, copies); var active = inner.rebase(outside, ambient, copies);
            assertSame(returned, active.getPrior(), "A recapture at an intervening prompt finds the same lexical prefix");
            assertSame(ambient, returned.getPrior()); StackAnnotations.set(null, active);
            entered(other -> assertTrue(StackAnnotations.current(null).values().isEmpty()));
            assertSame(active, StackAnnotations.current(null));
            var state = Language.currentState(); var threadValue = new java.util.concurrent.CompletableFuture<StackAnnotationState>();
            var thread = new Thread(() -> threadValue.complete(state.getStackAnnotations().get())); thread.start();
            assertSame(StackAnnotationState.EMPTY, threadValue.get(10, java.util.concurrent.TimeUnit.SECONDS));
            thread.join(); StackAnnotations.set(null, StackAnnotationState.EMPTY);
        });
    }
}
