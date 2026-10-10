// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
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
        // Deliberate bottom models only the declared lazy dependency for continuation
        // transport. src/examples/BlockedOwners.hs supplies the genuine GHC/native oracle.
        var blocked = map("id", CoreBlockedExceptions.MVAR, "name", CoreBlockedExceptions.MVAR,
                "type", "SomeException", "lifted", true, "arity", 0, "rep", dataRep,
                "expr", list("var", CoreBlockedExceptions.MVAR, map("rep", dataRep)));
        return map("bindings", list(map("id", "direct", "name", "direct", "lifted", true, "expr", lambda), blocked), "instrument", true, "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2), map("id", "Outer", "name", "Outer", "kind", "unboxed-tuple", "arity", 2)));
    }
    @ParameterizedTest @ValueSource(strings = {"ast-tail", "ast-suffix", "bytecode-tail", "bytecode-suffix"})
    void tupleCallerCapturesItsFirstCompiledAsyncRequestAndResumesWithoutReplay(String mode) throws Exception {
        boolean tail = mode.endsWith("tail");
        var module = directMVarModule(false, false, false, false, false, false, false);
        var closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var state = list("void", map("rep", stateRep));
        var duration = list("var", "duration", map("rep", longRep));
        var direct = ((List<Map<String, Object>>) module.get("bindings")).getFirst();
        var directLambda = (List<Object>) direct.get("expr");
        var directArguments = new ArrayList<>((List<Object>) directLambda.get(1));
        directArguments.add(map("id", "duration", "lifted", false, "rep", longRep));
        directLambda.set(1, directArguments);
        var delay = list("app", list("prim", "delay#"), list(duration, state), list(false, false), false, false, map("rep", stateRep));
        directLambda.set(2, list("case", delay, "delayed", list(list("default", null, List.of(), directLambda.get(2))),
                map("rep", tupleRep, "binder", map("id", "delayed", "rep", stateRep))));
        directLambda.set(3, map("resultRep", tupleRep, "entryStrict", list(false, false, false)));
        var call = list("app", list("var", "direct", map("rep", closure)),
                list(list("var", "cell", map("rep", mvarRep)), state, duration), list(false, false, false), false, false, map("rep", tupleRep));
        var resultRep = tail ? tupleRep : nestedTupleRep;
        var suffix = tail ? call : list("app", list("con", "Outer", 2),
                list(call, list("lit", "int", "7", map("rep", longRep))), list(false, false), false, false, map("rep", resultRep));
        var prefix = list("app", list("prim", "takeMVar#"),
                list(list("var", "prefix", map("rep", mvarRep)), state), list(false, false), false, false, map("rep", tupleRep));
        var body = list("case", prefix, "prefixPair", list(list("data", "Pair", list("prefixState", "before"), suffix,
                map("binders", list(map("id", "prefixState", "rep", stateRep), map("id", "before", "rep", dataRep))))),
                map("rep", resultRep, "binder", map("id", "prefixPair", "rep", tupleRep)));
        var bindings = new ArrayList<>((List<Map<String, Object>>) module.get("bindings"));
        bindings.add(map("id", "caller", "name", "caller", "lifted", true, "expr", list("lam",
                list(map("id", "prefix", "lifted", false, "rep", mvarRep), map("id", "cell", "lifted", false, "rep", mvarRep),
                        map("id", "duration", "lifted", false, "rep", longRep)),
                body, map("resultRep", resultRep))));
        module.put("bindings", bindings);
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            final ExecutableProgram program; final Language.State owner; final RootCallTarget caller, callee, resume; final TupleShape shape;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                program = mode.startsWith("ast") ? new Program(language, module, true) : new BytecodeProgram(language, module, true);
                caller = program.entryTarget("caller"); callee = program.entryTarget("direct");
                shape = Objects.requireNonNull(((GuestRoot) caller.getRootNode()).getTupleResult());
                // Same ordinary pre-install setup as the direct MVar controls above; no prior suspension.
                for (int i = 0; i < 5; i++) {
                    var readyPrefix = new ManagedMVar(); var ready = new ManagedMVar(); var payload = new Object();
                    assertTrue(readyPrefix.tryPut("setup")); assertTrue(ready.tryPut(payload));
                    var value = TupleResults.ownedTupleResult(Calls.target(caller, new Object[]{0L, readyPrefix, ready, 0L}), shape);
                    assertSame(payload, shape.getLayout().getObject(value, 0));
                    if (!tail) assertEquals(7L, shape.getLayout().getLong(value, 1));
                }
                for (var target : List.of(callee, caller)) {
                    assertEquals(true, target.getClass().getMethod("compile", boolean.class).invoke(target, true));
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    var runtime = Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                            .invoke(runtime, target);
                }
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) {
                        return force.drainStack((SavedGuestContinuation) frame.getArguments()[0], shape);
                    }
                }.getCallTarget();
            } finally { context.leave(); }
            var prefixCell = new ManagedMVar(); var blocked = new ManagedMVar(); var payload = new Object();
            assertTrue(prefixCell.tryPut("once"));
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var answer = new CompletableFuture<SavedGuestContinuation>();
            var identity = new java.util.concurrent.atomic.AtomicReference<GuestThreadId>();
            var worker = new Thread(() -> {
                context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    identity.set(owner.getThreads().currentIdentity());
                    var result = Calls.target(caller, new Object[]{0L, prefixCell, blocked, 300_000L});
                    var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(
                            result instanceof TailYield yielded ? yielded.getContinuation() : result));
                    Objects.requireNonNull(saved.asyncRequest()).acknowledge(); answer.complete(saved);
                } catch (Throwable failure) { answer.completeExceptionally(failure); }
                finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            });
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while ((identity.get() == null || identity.get().getStatus() != GuestThreadStatus.DELAY)
                        && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                if (answer.isCompletedExceptionally()) answer.get(1, TimeUnit.SECONDS);
                assertEquals(GuestThreadStatus.DELAY, identity.get() == null ? null : identity.get().getStatus());
                assertTrue(prefixCell.isEmpty());
                var request = owner.getThreads().send(identity.get(), "tuple call cut");
                var saved = answer.get(10, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive());
                assertSame(request, saved.asyncRequest()); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                assertTrue(request.compiledCapture, "The exact first tuple request is claimed in installed code");
                assertEquals(before + 2, ((Number) program.diagnostics().get("compiledEntries")).longValue(), "Both original roots enter installed code once");
                context.enter();
                try {
                    assertTrue(blocked.tryPut(payload));
                    var value = TupleResults.ownedTupleResult(Calls.target(resume, new Object[]{saved}), shape);
                    assertSame(payload, shape.getLayout().getObject(value, 0));
                    if (!tail) assertEquals(7L, shape.getLayout().getLong(value, 1));
                    assertTrue(prefixCell.isEmpty(), "Completed caller prefix cannot replay"); assertTrue(blocked.isEmpty());
                    assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(caller.getRootNode()));
                    var handoff = shape.getLanguage().getHandoffState().get(); assertNull(handoff.getPending());
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                    assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                    assertSame(caller, program.entryTarget("caller")); assertSame(callee, program.entryTarget("direct"));
                } finally { context.leave(); }
            } finally { if (worker.isAlive()) context.close(true); worker.join(5000); }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void firstCompiledScalarOverapplicationRetainsItsSuffixAfterACalleeCut(boolean tail) throws Exception {
        var module = directMVarModule(false, false, true, false, false, false, false);
        var closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var bindings = new ArrayList<>((List<Map<String, Object>>) module.get("bindings"));
        var direct = bindings.getFirst();
        var lambda = (List<Object>) direct.get("expr");
        var takeCase = (List<Object>) lambda.get(2);
        var arm = ((List<List<Object>>) takeCase.get(3)).getFirst();
        // The MVar effect happens before returning the function that consumes
        // the saved surplus argument.
        arm.set(3, list("lam", list(map("id", "extra", "name", "extra", "lifted", false, "rep", longRep)),
                list("var", "extra", map("rep", longRep)), map("rep", closure, "resultRep", longRep)));
        takeCase.set(4, map("rep", closure, "binder", map("id", "returned", "rep", tupleRep)));
        lambda.set(3, map("rep", closure, "resultRep", closure, "entryStrict", list(false, false)));
        var call = list("app", list("var", "fn", map("rep", closure)),
                list(list("var", "cell", map("rep", mvarRep)), list("void", map("rep", stateRep)),
                        list("lit", "int", "37", map("rep", longRep))),
                list(false, false, false), false, false, map("rep", longRep));
        var body = tail ? call : list("app", list("prim", "+#"),
                list(call, list("lit", "int", "5", map("rep", longRep))),
                list(false, false), false, false, map("rep", longRep));
        bindings.add(map("id", "caller", "name", "caller", "lifted", true, "rep", closure,
                "expr", list("lam", list(map("id", "fn", "name", "fn", "lifted", true, "rep", closure),
                        map("id", "cell", "name", "cell", "lifted", false, "rep", mvarRep)),
                        body, map("rep", closure, "resultRep", longRep))));
        module.put("bindings", bindings);
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            final Language language; final Language.State owner; final BytecodeProgram program;
            final RootCallTarget caller; final Closure function; final RootCallTarget helper; final RootCallTarget resume;
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                program = new BytecodeProgram(language, module, true); caller = program.entryTarget("caller");
                function = (Closure) program.entryValue("direct");
                var targets = new LinkedHashSet<RootCallTarget>(ThreadInventoryCoreEvidence.targets(function.target));
                var callerTargets = ThreadInventoryCoreEvidence.targets(caller);
                var helpers = callerTargets.stream().filter(target -> target.getRootNode() instanceof FunctionRoot).toList();
                assertEquals(1, helpers.size(), "The caller owns one shared scalar application target");
                helper = helpers.getFirst();
                targets.addAll(callerTargets);
                ThreadInventoryCoreEvidence.install(new ArrayList<>(targets));
                assertEquals(0L, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) {
                        return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]);
                    }
                }.getCallTarget();
            } finally { context.leave(); }
            var cell = new ManagedMVar(); var answer = new CompletableFuture<SavedGuestContinuation>();
            var worker = new Thread(() -> {
                context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    Object value = Calls.target(caller, new Object[]{0L, function, cell});
                    if (value instanceof AstTailYield yielded) value = yielded.getContinuation();
                    if (value instanceof TailYield yielded) value = yielded.getContinuation();
                    var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(value));
                    Objects.requireNonNull(saved.asyncRequest()).acknowledge(); answer.complete(saved);
                } catch (Throwable failure) { answer.completeExceptionally(failure); }
                finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            });
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (cell.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                if (answer.isCompletedExceptionally()) answer.get(1, TimeUnit.SECONDS);
                assertEquals(1, cell.pendingCounts().getTakers());
                var request = owner.getThreads().send(Objects.requireNonNull(owner.getThreads().pollState(worker).getCurrent()).getIdentity(), "scalar suffix cut");
                var saved = answer.get(10, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive());
                assertSame(request, saved.asyncRequest());
                assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                // ManagedMVar claims through pollCurrentWithoutYield. The existing
                // tuple poll-cut control proves compiledCapture's bytecode-poll contract.
                final CallSegment segment;
                final AstContinuation helperSaved;
                if (tail) {
                    segment = null;
                    helperSaved = assertInstanceOf(AstContinuation.class, saved);
                } else {
                    var suspended = assertInstanceOf(CallSegmentSuspended.class, saved.getYielded());
                    assertSame(request, suspended.getAsyncRequest());
                    segment = suspended.getSegment();
                    helperSaved = assertInstanceOf(AstContinuation.class,
                            SavedGuestContinuations.savedGuestContinuation(segment.getValue()));
                    assertSame(caller.getRootNode(), saved.getSourceRoot());
                    assertSame(saved.getIdentity(), SavedGuestContinuations.savedGuestContinuation(saved.getIdentity()).getIdentity());
                }
                assertSame(helper.getRootNode(), helperSaved.getSourceRoot());
                assertSame(helperSaved, helperSaved.getIdentity());
                assertSame(request, helperSaved.asyncRequest());
                assertEquals(3L, ((Number) program.diagnostics().get("compiledEntries")).longValue(),
                        "The original caller, shared scalar helper and first callee enter compiled code before any execution warmup");
                context.enter();
                try {
                    assertTrue(cell.tryPut("consumed once"));
                    assertEquals(tail ? 37L : 42L, Calls.target(resume, new Object[]{saved}));
                    assertTrue(cell.isEmpty(), "The completed MVar prefix must not replay on suffix resume");
                    assertThrows(RuntimeFault.class, () -> helperSaved.continueWith(Unit.INSTANCE),
                            "The actual helper activation owns one-shot resumption");
                    if (segment != null) {
                        assertEquals(2, segment.getState());
                        assertEquals(37L, segment.getValue());
                        assertNull(segment.getOwner());
                    }
                    assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(caller.getRootNode()));
                    ThreadInventoryCoreEvidence.released(language);
                } finally { context.leave(); }
            } finally { if (worker.isAlive()) context.close(true); worker.join(5000); }
        }
    }
    @Test void ordinaryCallerAndEntryRoutesAreCapturedAndConflictingProofsStillFail() {
        try (var context = Context.newBuilder("thc").build()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); new Program(language, directMVarModule(false, false, false, false, false, false, false), true); new Program(language, directMVarModule(false, true, false, false, false, false, false), true); new Program(language, directMVarModule(false, false, false, true, false, false, false), true);
            assertThrows(RuntimeFault.class, () -> new Program(language, directMVarModule(false, false, true, false, true, false, false), true)); new Program(language, directMVarModule(false, false, false, false, false, true, false), true); assertThrows(UnsupportedCore.class, () -> AstAsyncAdmission.validate(List.of(map("id", "unsupported", "expr", list("unknown")))));
        } finally { context.leave(); } }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void firstInstalledMVarEntryPreservesTupleAcrossColdCapture(boolean nested) throws Exception {
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
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (cell.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1); assertEquals(1, cell.pendingCounts().getTakers()); var request = state.getThreads().send(Objects.requireNonNull(state.getThreads().pollState(thread).getCurrent()).getIdentity(), "cut"); var continuation = answer.get(10, TimeUnit.SECONDS); thread.join(5000); assertFalse(thread.isAlive());
                assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), "The original AST root must enter installed code once before its first cut");
                assertSame(request, continuation.asyncRequest()); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                // Stock Truffle profiles completed return types. The first AstContinuation
                // result may invalidate that profile; installed entry is not cut retention.
                context.enter(); try {
                    assertTrue(cell.tryPut("forty-one")); var completed = continuation.continueWith(thc.runtime.Unit.INSTANCE); var owned = TupleResults.ownedTupleResult(completed, shape); assertEquals("forty-one", shape.getLayout().getObject(owned, 0)); if (nested) assertEquals(7L, shape.getLayout().getLong(owned, 1)); assertTrue(cell.isEmpty()); assertThrows(RuntimeFault.class, () -> continuation.continueWith(thc.runtime.Unit.INSTANCE)); var handoff = shape.getLanguage().getHandoffState().get(); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences()); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences()); assertNull(handoff.getPending()); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.getRootNode())); assertSame(target, program.entryTarget("direct"), "Capture must retain the original guest target");
                } finally { context.leave(); }
            } finally { if (thread.isAlive()) context.close(true); thread.join(5000); }
        }
    }
    @Test void firstInstalledMVarEntryPreservesCaseSuffixAcrossColdCapture() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter(); Program program; RootCallTarget target; Language.State state;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); state = Language.currentState(); program = new Program(language, directMVarModule(false, false, true, false, false, false, false), true); target = program.entryTarget("direct"); assertNull(((FunctionRoot) target.getRootNode()).getHandoff()); for (int i = 0; i < 5; i++) { var ready = new ManagedMVar(); assertTrue(ready.tryPut("discarded")); assertEquals(7L, Calls.target(target, new Object[]{0L, ready, thc.runtime.Unit.INSTANCE})); } target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var cell = new ManagedMVar(); var answer = new CompletableFuture<AstContinuation>(); var thread = new Thread(() -> { context.enter(); state.getThreads().enterCurrent(null, false, true, null); try { var captured = (AstContinuation) Calls.target(target, new Object[]{0L, cell, thc.runtime.Unit.INSTANCE}); ((AsyncRequest) captured.getYielded()).acknowledge(); answer.complete(captured); } catch (Throwable failure) { answer.completeExceptionally(failure); } finally { state.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); } }); thread.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (cell.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1); assertEquals(1, cell.pendingCounts().getTakers()); var request = state.getThreads().send(Objects.requireNonNull(state.getThreads().pollState(thread).getCurrent()).getIdentity(), "cut"); var captured = answer.get(10, TimeUnit.SECONDS); thread.join(5000); assertFalse(thread.isAlive()); assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                assertSame(request, captured.asyncRequest()); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                // The completed Long return profile need not survive its first continuation.
                // Resume the saved suffix once, without recompiling or replaying the entry.
                context.enter(); try { assertTrue(cell.tryPut("unused")); assertEquals(7L, captured.continueWith(thc.runtime.Unit.INSTANCE)); assertTrue(cell.isEmpty(), "The tuple scrutinee must complete before its saved case suffix"); assertThrows(RuntimeFault.class, () -> captured.continueWith(thc.runtime.Unit.INSTANCE)); var handoff = TruffleLanguage.LanguageReference.create(Language.class).get(null).getHandoffState().get(); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences()); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences()); assertNull(handoff.getPending()); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.getRootNode())); assertSame(target, program.entryTarget("direct"), "Capture must retain the original guest target"); } finally { context.leave(); }
            } finally { if (thread.isAlive()) context.close(true); thread.join(5000); }
        }
    }
    @Test void resumedJoinDropsDeadBodyLocalsButRetainsOuterCapturesAndTupleResult() throws Exception {
        var module = directMVarModule(false, false, false, false, false, false, false);
        var closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var state = list("void", map("rep", stateRep));
        var remaining = list("var", "remaining", map("rep", longRep));
        var one = list("lit", "int", "1", map("rep", longRep));
        var next = list("app", list("prim", "-#"), list(remaining, one), list(false, false), false, false, map("rep", longRep));
        var recur = list("app", list("var", "loop", map("rep", closure)), list(next), list(false), false, false, map("rep", nestedTupleRep));
        var pair = list("app", list("con", "Pair", 2), list(state, list("var", "payload", map("rep", dataRep))), list(false, true), false, false, map("rep", tupleRep));
        var done = list("app", list("con", "Outer", 2), list(pair, list("var", "count", map("rep", longRep))), list(false, false), false, false, map("rep", nestedTupleRep));
        var body = list("case", remaining, "remainingCase", list(list("lit", list("int", "1"), List.of(), done), list("default", null, List.of(), recur)),
                map("rep", nestedTupleRep, "binder", map("id", "remainingCase", "rep", longRep)));
        var read = list("app", list("prim", "takeMVar#"), list(list("var", "cell", map("rep", mvarRep)), state), list(false, false), false, false, map("rep", tupleRep));
        var step = list("case", read, "returned", list(list("data", "Pair", list("stateOut", "payload"), body,
                map("binders", list(map("id", "stateOut", "rep", stateRep), map("id", "payload", "rep", dataRep))))),
                map("rep", nestedTupleRep, "binder", map("id", "returned", "rep", tupleRep)));
        var loop = map("id", "loop", "name", "loop", "lifted", true, "rep", closure, "joinValueArity", 1, "joinResultRep", nestedTupleRep,
                "expr", list("lam", list(map("id", "remaining", "lifted", false, "rep", longRep)), step, map("rep", closure, "resultRep", nestedTupleRep)));
        var entry = list("app", list("var", "loop", map("rep", closure)), list(list("var", "count", map("rep", longRep))), list(false), false, false, map("rep", nestedTupleRep));
        var bindings = new ArrayList<>((List<Map<String, Object>>) module.get("bindings"));
        bindings.set(0, map("id", "direct", "name", "direct", "lifted", true,
                "expr", list("lam", list(map("id", "cell", "lifted", false, "rep", mvarRep), map("id", "count", "lifted", false, "rep", longRep)),
                        list("let", true, list(loop), entry, map("rep", nestedTupleRep)), map("resultRep", nestedTupleRep))));
        module.put("bindings", bindings);
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            final Program program; final RootCallTarget target; final Language.State owner; final TupleShape shape;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                program = new Program(language, module, true); target = program.entryTarget("direct"); shape = new TupleShape(CoreRepresentations.parse(nestedTupleRep), language);
                for (int i = 0; i < 5; i++) {
                    var ready = new ManagedMVar(); var payload = new Object(); assertTrue(ready.tryPut(payload));
                    var value = TupleResults.ownedTupleResult(Calls.target(target, new Object[]{0L, ready, 1L}), shape);
                    assertSame(payload, shape.getLayout().getObject(value, 0)); assertEquals(1L, shape.getLayout().getLong(value, 1));
                }
                assertEquals(true, target.getClass().getMethod("compile", boolean.class).invoke(target, true));
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
            var cell = new ManagedMVar(); var first = new Object(); var last = new Object(); assertTrue(cell.tryPut(first));
            var answer = new CompletableFuture<AstContinuation>();
            var worker = new Thread(() -> {
                context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    var saved = (AstContinuation) Calls.target(target, new Object[]{0L, cell, 2L});
                    Objects.requireNonNull(saved.asyncRequest()).acknowledge(); answer.complete(saved);
                } catch (Throwable failure) { answer.completeExceptionally(failure); }
                finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            });
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (cell.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                if (answer.isCompletedExceptionally()) answer.get(1, TimeUnit.SECONDS);
                assertEquals(1, cell.pendingCounts().getTakers());
                var request = owner.getThreads().send(Objects.requireNonNull(owner.getThreads().pollState(worker).getCurrent()).getIdentity(), "join reentry");
                var saved = answer.get(10, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive()); assertSame(request, saved.asyncRequest());
                // A suspended second iteration must not retain the first iteration's dead payload.
                var field = AstContinuation.class.getDeclaredField("frame"); field.setAccessible(true);
                var frame = (com.oracle.truffle.api.frame.MaterializedFrame) field.get(saved);
                for (int i = 0; i < frame.getFrameDescriptor().getNumberOfSlots(); i++)
                    if (frame.isObject(i)) assertNotSame(first, frame.getObject(i), "Completed join must release its dead payload");
                context.enter();
                try {
                    assertTrue(cell.tryPut(last));
                    var value = TupleResults.ownedTupleResult(saved.continueWith(thc.runtime.Unit.INSTANCE), shape);
                    assertSame(last, shape.getLayout().getObject(value, 0)); assertEquals(2L, shape.getLayout().getLong(value, 1));
                    assertTrue(cell.isEmpty()); assertThrows(RuntimeFault.class, () -> saved.continueWith(thc.runtime.Unit.INSTANCE));
                    var handoff = shape.getLanguage().getHandoffState().get(); assertNull(handoff.getPending());
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                    assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                } finally { context.leave(); }
            } finally { if (worker.isAlive()) context.close(true); worker.join(5000); }
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
