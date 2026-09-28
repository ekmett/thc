// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** A real blocked MVar cut, owned thunk, and continuation resumed on another Java thread. */
class AstContinuationTest {
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... fields) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result; }
    private final Map<String, Object> stateRep = map("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> mvarRep = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
    private final Map<String, Object> dataRep = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> longRep = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> tupleRep = map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", list("BoxedRep (Just Lifted)"), "components", list(stateRep, dataRep), "evaluated", false);
    private final Map<String, Object> nestedTupleRep = map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", list("BoxedRep (Just Lifted)", "IntRep"), "components", list(tupleRep, longRep), "evaluated", false);
    private Map<String, Object> directMVarModule(boolean wrap, boolean strict, boolean caseLiteral, boolean casePayload, boolean wrongCaseResult, boolean unevaluatedCell, boolean nestedTuple) {
        var cell = list("var", "cell", map("rep", mvarRep)); var state = list("void", map("rep", stateRep)); var read = list("app", list("prim", "takeMVar#"), list(cell, state), list(false, false), false, false, map("rep", tupleRep)); Object body;
        if (nestedTuple) body = list("app", list("con", "Outer", 2), list(read, list("lit", "int", "7", map("rep", longRep))), list(false, false), false, false, map("rep", nestedTupleRep));
        else if (wrap) body = list("case", read, "returned", List.of());
        else if (caseLiteral || casePayload) body = list("case", read, "returned", list(list("data", "Pair", list("stateOut", "payload"), casePayload ? list("var", "payload", map("rep", dataRep)) : list("lit", "int", "7", map("rep", longRep)), map("binders", list(map("id", "stateOut", "rep", stateRep), map("id", "payload", "rep", dataRep))))), map("rep", casePayload ? dataRep : longRep, "binder", map("id", "returned", "rep", tupleRep)));
        else body = read;
        var lazyMvar = new LinkedHashMap<>(mvarRep); lazyMvar.put("evaluated", false);
        var parameters = list(map("id", "cell", "name", "cell", "lifted", false, "coercion", false, "rep", unevaluatedCell ? lazyMvar : mvarRep), map("id", "state", "name", "state", "lifted", false, "coercion", false, "rep", stateRep));
        var lambda = list("lam", parameters, body, map("resultRep", nestedTuple ? nestedTupleRep : wrongCaseResult || casePayload ? dataRep : caseLiteral ? longRep : tupleRep, "entryStrict", list(strict, false)));
        return map("bindings", list(map("id", "direct", "name", "direct", "lifted", true, "expr", lambda)), "instrument", true, "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2), map("id", "Outer", "name", "Outer", "kind", "unboxed-tuple", "arity", 2)));
    }
    @Test void ordinaryCallerAndEntryRoutesAreCapturedAndConflictingProofsStillFail() {
        try (var context = Context.newBuilder("thc").build()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); new Program(language, directMVarModule(false, false, false, false, false, false, false), true); new Program(language, directMVarModule(false, true, false, false, false, false, false), true); new Program(language, directMVarModule(false, false, false, true, false, false, false), true);
            assertThrows(RuntimeFault.class, () -> new Program(language, directMVarModule(false, false, true, false, true, false, false), true)); new Program(language, directMVarModule(false, false, false, false, false, true, false), true); assertThrows(UnsupportedCore.class, () -> AstAsyncAdmission.validate(List.of(map("id", "unsupported", "expr", list("unknown")))));
        } finally { context.leave(); } }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void admittedProgramCompiledMVarCutResumesItsTupleOnAnotherJavaThread(boolean nested) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter(); Program program; RootCallTarget target; Language.State state; TupleShape shape;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); state = Language.currentState(); program = new Program(language, directMVarModule(false, false, false, false, false, false, nested), true); target = program.entryTarget("direct"); assertNull(((FunctionRoot) target.getRootNode()).getHandoff(), "An async AST root must not receive a typed caller loan without caller capture"); shape = new TupleShape(CoreRepresentations.parse(nested ? nestedTupleRep : tupleRep), language);
                for (int i = 0; i < 5; i++) { var ready = new ManagedMVar(); assertTrue(ready.tryPut("one")); var result = Calls.target(target, new Object[]{0L, ready, thc.runtime.Unit.INSTANCE}); var owned = TupleResults.ownedTupleResult(result, shape); assertEquals("one", shape.getLayout().getObject(owned, 0)); if (nested) assertEquals(7L, shape.getLayout().getLong(owned, 1)); }
                target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
            var cell = new ManagedMVar(); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var answer = new CompletableFuture<AstContinuation>();
            var thread = new Thread(() -> { context.enter(); state.getThreads().enterCurrent(null, false, true, null); try { var result = (AstContinuation) Calls.target(target, new Object[]{0L, cell, thc.runtime.Unit.INSTANCE}); ((AsyncRequest) result.getYielded()).acknowledge(); answer.complete(result); } catch (Throwable failure) { answer.completeExceptionally(failure); } finally { state.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); } }); thread.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (cell.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1); assertEquals(1, cell.pendingCounts().getTakers()); state.getThreads().send(Objects.requireNonNull(state.getThreads().pollState(thread).getCurrent()).getIdentity(), "cut"); var continuation = answer.get(10, TimeUnit.SECONDS); thread.join(5000); assertFalse(thread.isAlive());
                assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), "The admitted AST entry must be compiled at the captured cut"); var retainedAfterCapture = target.getClass().getMethod("isValidLastTier").invoke(target);
                context.enter(); try {
                    assertTrue(cell.tryPut("forty-one")); var completed = continuation.continueWith(thc.runtime.Unit.INSTANCE); var owned = TupleResults.ownedTupleResult(completed, shape); assertEquals("forty-one", shape.getLayout().getObject(owned, 0)); if (nested) assertEquals(7L, shape.getLayout().getLong(owned, 1)); assertThrows(RuntimeFault.class, () -> continuation.continueWith(thc.runtime.Unit.INSTANCE)); var handoff = shape.getLanguage().getHandoffState().get(); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences()); assertNull(handoff.getPending()); assertSame(target, program.entryTarget("direct"), "Capture must retain the original guest target"); assertEquals(true, retainedAfterCapture, "The first MVar capture must retain installed guest code"); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), "Resuming the MVar tuple must retain the same installed guest code");
                } finally { context.leave(); }
            } finally { if (thread.isAlive()) context.close(true); thread.join(5000); }
        }
    }
    @Test void admittedTupleCaseKeepsItsLongSuffixAfterCompiledMVarCut() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter(); Program program; RootCallTarget target; Language.State state;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); state = Language.currentState(); program = new Program(language, directMVarModule(false, false, true, false, false, false, false), true); target = program.entryTarget("direct"); assertNull(((FunctionRoot) target.getRootNode()).getHandoff()); for (int i = 0; i < 5; i++) { var ready = new ManagedMVar(); assertTrue(ready.tryPut("discarded")); assertEquals(7L, Calls.target(target, new Object[]{0L, ready, thc.runtime.Unit.INSTANCE})); } target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var cell = new ManagedMVar(); var answer = new CompletableFuture<AstContinuation>(); var thread = new Thread(() -> { context.enter(); state.getThreads().enterCurrent(null, false, true, null); try { var captured = (AstContinuation) Calls.target(target, new Object[]{0L, cell, thc.runtime.Unit.INSTANCE}); ((AsyncRequest) captured.getYielded()).acknowledge(); answer.complete(captured); } catch (Throwable failure) { answer.completeExceptionally(failure); } finally { state.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); } }); thread.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (cell.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1); assertEquals(1, cell.pendingCounts().getTakers()); state.getThreads().send(Objects.requireNonNull(state.getThreads().pollState(thread).getCurrent()).getIdentity(), "cut"); var captured = answer.get(10, TimeUnit.SECONDS); thread.join(5000); assertFalse(thread.isAlive()); assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue()); var retainedAfterCapture = target.getClass().getMethod("isValidLastTier").invoke(target);
                context.enter(); try { assertTrue(cell.tryPut("unused")); assertEquals(7L, captured.continueWith(thc.runtime.Unit.INSTANCE)); assertTrue(cell.isEmpty(), "The tuple scrutinee must complete before its saved case suffix"); var handoff = TruffleLanguage.LanguageReference.create(Language.class).get(null).getHandoffState().get(); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences()); assertSame(target, program.entryTarget("direct"), "Capture must retain the original guest target"); assertEquals(true, retainedAfterCapture, "The first MVar capture must retain installed guest code"); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), "Resuming the MVar suffix must retain the same installed guest code"); } finally { context.leave(); }
            } finally { if (thread.isAlive()) context.close(true); thread.join(5000); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void synchronousMVarTakePreservesItsPayloadThroughTheFirstCompiledCall(String backend) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter(); try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var module = directMVarModule(false, false, false, true, false, false, false); ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module, false); var target = program.entryTarget("direct"); var payloadLayout = new DataLayout(language, "MVarPayload", "MVarPayload", new String[]{"LiftedRep"});
                Runnable take = () -> { var payload = payloadLayout.create(new Object[]{new Object()}); var cell = new ManagedMVar(); assertTrue(cell.tryPut(payload)); assertSame(payload, Calls.target(target, new Object[]{0L, cell, thc.runtime.Unit.INSTANCE})); assertTrue(cell.isEmpty(), "The completed take must consume exactly one value"); };
                for (int i = 0; i < 5; i++) take.run(); target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); take.run(); assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue()); assertSame(target, program.entryTarget("direct")); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }
    private static class SharedBody extends GuestRoot {
        private final ManagedMVar cell; private final AtomicInteger prefix; private final boolean unmask; final AtomicBoolean compiledEntry = new AtomicBoolean();
        SharedBody(Language language, ManagedMVar cell, AtomicInteger prefix, boolean unmask) { super(language, new FrameLayout().build()); this.cell = cell; this.prefix = prefix; this.unmask = unmask; }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        private static class Read implements AstResumeStep {
            private final SharedBody owner; private final ManagedMVar cell;
            Read(SharedBody owner, ManagedMVar cell) { this.owner = owner; this.cell = cell; }
            @Override public Object resume(VirtualFrame frame, Object input) { if (input != thc.runtime.Unit.INSTANCE) throw RuntimeFault.fault("Invalid resumed MVar input"); try { return cell.take(owner, true); } catch (AsyncBlocked blocked) { throw new AstCapture(blocked.getRequest(), SynchronousMasking.current(owner)).append(this); } }
        }
        @Override public Object execute(VirtualFrame frame) {
            var prior = SynchronousMasking.current(this); if (unmask) SynchronousMasking.set(this, MaskingState.UNMASKED);
            try { if (CompilerDirectives.inCompiledCode()) compiledEntry.set(true); prefix.incrementAndGet(); try { return cell.take(this, true); } catch (AsyncBlocked blocked) { throw new AstCapture(blocked.getRequest(), SynchronousMasking.current(this)).append(new Read(this, cell)); } } catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); } finally { SynchronousMasking.set(this, prior); }
        }
    }
    private static class MaskedCaller extends GuestRoot {
        private final SharedBody child; private final AtomicInteger prefix, suffix;
        MaskedCaller(Language language, SharedBody child, AtomicInteger prefix, AtomicInteger suffix) { super(language, new FrameLayout().build()); this.child = child; this.prefix = prefix; this.suffix = suffix; }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        private static class ResumeChild implements AstResumeStep {
            private final AstContinuation child; private final AtomicInteger suffix; private final MaskedCaller owner;
            ResumeChild(AstContinuation child, AtomicInteger suffix, MaskedCaller owner) { this.child = child; this.suffix = suffix; this.owner = owner; }
            @Override public Object resume(VirtualFrame frame, Object input) { if (input != thc.runtime.Unit.INSTANCE) throw RuntimeFault.fault("Invalid masked AST caller resume value"); var result = child.continueWith(thc.runtime.Unit.INSTANCE); if (result instanceof AstContinuation continuation) throw new AstCapture(continuation.getYielded(), SynchronousMasking.current(owner)).append(new ResumeChild(continuation, suffix, owner)); suffix.incrementAndGet(); return result; }
        }
        @Override public Object execute(VirtualFrame frame) {
            var prior = SynchronousMasking.current(this); SynchronousMasking.set(this, MaskingState.MASKED_UNINTERRUPTIBLE);
            try { prefix.incrementAndGet(); var result = Calls.target(child.getCallTarget(), new Object[]{0L}); if (result instanceof AstContinuation continuation) throw new AstCapture(continuation.getYielded(), SynchronousMasking.current(this)).append(new ResumeChild(continuation, suffix, this)); suffix.incrementAndGet(); return result; } catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); } finally { SynchronousMasking.set(this, prior); }
        }
    }
    private static class ForceRoot extends RootNode {
        private final Thunk thunk; @Child private Force force = new Force(new Metrics(false), true);
        ForceRoot(Language language, Thunk thunk) { super(language, new FrameLayout().build()); this.thunk = thunk; }
        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, thunk); }
    }
    private record Target(Thread thread, CompletableFuture<Object> answer) {}
    private void exercise(boolean maskedCaller) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter(); Language language; Language.State state; var cell = new ManagedMVar(); var prefix = new AtomicInteger(); var callerPrefix = new AtomicInteger(); var callerSuffix = new AtomicInteger(); Thunk thunk; ForceRoot force; SharedBody body;
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); state = Language.currentState(); body = new SharedBody(language, cell, prefix, maskedCaller); var target = maskedCaller ? new MaskedCaller(language, body, callerPrefix, callerSuffix).getCallTarget() : body.getCallTarget(); for (int i = 0; i < 5; i++) { cell.put(1L, body); assertEquals(1L, Calls.target(target, new Object[]{0L})); }
                var compiled = body.getCallTarget(); compiled.getClass().getMethod("compile", boolean.class).invoke(compiled, true); assertEquals(true, compiled.getClass().getMethod("isValidLastTier").invoke(compiled)); body.compiledEntry.set(false); prefix.set(0); callerPrefix.set(0); callerSuffix.set(0); thunk = new Thunk(target, null); force = new ForceRoot(language, thunk);
            } finally { context.leave(); }
            var targets = new ArrayList<Thread>(); java.util.function.Supplier<Target> startTarget = () -> {
                var answer = new CompletableFuture<Object>(); var target = new Thread(() -> { context.enter(); state.getThreads().enterCurrent(null, false, true, null); try { try { answer.complete(force.getCallTarget().call()); } catch (ThunkSuspended suspended) { try { AsyncContinuations.publicSuspension(suspended, force); } catch (GuestException guest) { answer.complete(guest.getPayload()); } } } catch (Throwable failure) { answer.completeExceptionally(failure); } finally { state.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); } }); targets.add(target); target.start(); return new Target(target, answer);
            };
            try {
                for (int round = 1; round <= 2; round++) {
                    var started = startTarget.get(); var target = started.thread; var answer = started.answer; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (System.nanoTime() < deadline && target.getState() != Thread.State.WAITING) Thread.sleep(1); assertEquals(Thread.State.WAITING, target.getState(), "AST target did not enter MVar wait " + round);
                    var request = state.getThreads().send(Objects.requireNonNull(state.getThreads().pollState(target).getCurrent()).getIdentity(), "stop " + round); assertEquals("stop " + round, answer.get(10, TimeUnit.SECONDS)); target.join(5000); assertFalse(target.isAlive()); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState()); assertEquals(5, thunk.getState()); assertEquals(1, prefix.get()); assertTrue(body.compiledEntry.get(), "The installed AST body must run before the cold cut");
                }
                context.enter(); try { cell.put(41L, force); assertEquals(41L, force.getCallTarget().call()); } finally { context.leave(); }
                assertEquals(2, thunk.getState()); assertEquals(1, prefix.get(), "An observer must resume the saved MVar cut, not restart the body"); if (maskedCaller) { assertEquals(1, callerPrefix.get(), "A masked caller must not replay before the unmasking child"); assertEquals(1, callerSuffix.get(), "The caller suffix must run exactly once after the child"); }
            } finally { boolean alive = false; for (var target : targets) if (target.isAlive()) { alive = true; break; } if (alive) context.close(true); for (var target : targets) target.join(5000); }
        }
    }
    @Test void blockedAstThunkResumesOnAnotherJavaThreadWithoutReplayingPrefix() throws Exception { exercise(false); }
    @Test void maskedCallerStillCapturesAnUnmaskingChild() throws Exception { exercise(true); }
}
