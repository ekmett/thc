// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeTier;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

/** Private three-root proof of logical masks carried by cold call segments. */
class CallMaskSegmentsTest {
    private record Chain(Thunk parent, RootCallTarget a, RootCallTarget b, RootCallTarget c) {}
    private static class Driver extends RootNode {
        @Child private Force force = new Force(new Metrics(true));
        Driver() { super(null); }
        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
        Object force(Thunk thunk) { return Calls.target(getCallTarget(), new Object[]{thunk}); }
    }
    @FunctionalInterface private interface Action<T> { T run() throws Exception; }
    private <T> T entered(Context context, Action<T> action) throws Exception { context.enter(); try { return action.run(); } finally { context.leave(); } }
    private void compile(RootCallTarget target) throws ReflectiveOperationException { var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); assertTrue(type.isInstance(target)); type.getMethod("compile", boolean.class).invoke(target, true); type.getMethod("waitForCompilation").invoke(target); assertEquals(true, type.getMethod("isValidLastTier").invoke(target)); }
    @Test void parkingKeepsTheRequestEvenIfAnotherEvaluatorHasFinishedTheSegment() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); entered(context, () -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var threads = Language.currentState().getThreads(); var request = threads.send(Long.MIN_VALUE, "request identity");
            var target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> { b.beginRoot(); b.beginReturn(); b.beginYield(); b.emitLoadConstant(request); b.endYield(); b.endReturn(); b.endRoot(); }).getNode(0).getCallTarget();
            var segment = new CallSegment((ContinuationResult) Calls.target(target, new Object[]{0L})); var captured = new CallSegmentSuspended(segment); assertSame(request, captured.getAsyncRequest()); segment.setValue(42L); segment.setState(2);
            var parked = BytecodeRoot.ParkCallMask.park(captured, MaskingState.UNMASKED, MaskingState.UNMASKED, new Driver()); assertSame(request, parked.getAsyncRequest());
            // A later evaluator may replace a segment's mutable answer. A
            // missing immutable delivery token must not be recovered from it.
            var changed = new CallSegment((ContinuationResult) Calls.target(target, new Object[]{0L})); var noToken = new CallSegmentSuspended(changed, null, null); changed.setValue((ContinuationResult) Calls.target(target, new Object[]{0L})); assertThrows(RuntimeFault.class, () -> AsyncContinuations.publicSuspension(noToken, new Driver())); return null;
        }); }
    }
    @Test void aClonedThunkTargetKeepsItsContinuationBodyIdentity() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); entered(context, () -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> { b.beginRoot(); b.beginYield(); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endYield(); b.beginReturn(); b.emitLoadConstant(42L); b.endReturn(); b.endRoot(); }).getNode(0).getCallTarget();
            var driver = new Driver(); var warm = new Thunk(target, null); assertThrows(ThunkSuspended.class, () -> driver.force(warm)); assertEquals(42L, driver.force(warm)); DirectCallNode call = null;
            for (var candidate : NodeUtil.findAllNodeInstances(driver, DirectCallNode.class)) if (candidate.getCallTarget() == target) { if (call != null) throw new IllegalArgumentException("Collection contains more than one matching element."); call = candidate; } if (call == null) throw new NoSuchElementException("Collection contains no element matching the predicate.");
            assertTrue(call.cloneCallTarget()); var fresh = new Thunk(target, null); assertThrows(ThunkSuspended.class, () -> driver.force(fresh)); var saved = (ContinuationResult) fresh.getValue(); assertNotSame(target.getRootNode(), saved.getContinuationRootNode().getSourceRootNode()); assertTrue(((GuestRoot) saved.getContinuationRootNode().getSourceRootNode()).isSelf(target)); assertEquals(42L, driver.force(fresh)); assertEquals(2, fresh.getState()); return null;
        }); }
    }
    /** Exactly the private Core call stages, including root-entry and call-active locals. */
    private RootCallTarget maskedCaller(Language language, RootCallTarget callee, MaskingState targetMask) {
        var function = new Closure(null, Closure.NO_PAP_ARGUMENTS, 0, callee); var metrics = new Metrics(true);
        return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot(); var entry = b.createLocal("root entry mask", "object"); var prior = b.createLocal("lexical prior mask", "object"); var active = b.createLocal("caller active mask", "object"); var result = b.createLocal("application result", "object"); var suspended = b.createLocal("child segment", "object");
            b.beginBlock(); b.beginStoreLocal(entry); b.emitCurrentMask(); b.endStoreLocal(); b.beginStoreLocal(prior); b.emitEnterMask(targetMask); b.endStoreLocal();
            b.beginTryFinally(() -> { b.beginRestoreMask(); b.emitLoadLocal(prior); b.endRestoreMask(); });
            b.beginBlock(); b.beginStoreLocal(active); b.emitCurrentMask(); b.endStoreLocal(); b.beginTryCatch(); b.beginStoreLocal(result); b.beginCaptureApplicationResult(0); b.emitLoadConstant(function);
            b.beginApply(0, false, metrics, new boolean[0], PreparedDispatch.prepareApplication(language, 0, false, metrics, null, true, true, false, null)); b.emitLoadConstant(function); b.endApply(); b.emitLoadLocal(active); b.endCaptureApplicationResult(); b.endStoreLocal();
            b.beginBlock(); b.beginStoreLocal(suspended); b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly(); b.endStoreLocal(); b.beginStoreLocal(result); b.beginResumeApplication(); b.emitLoadLocal(suspended); b.beginReenterCallMask(); b.beginYield(); b.beginParkCallMask(); b.emitLoadLocal(suspended); b.emitLoadLocal(entry); b.emitLoadLocal(active); b.endParkCallMask(); b.endYield(); b.emitLoadLocal(active); b.endReenterCallMask(); b.endResumeApplication(); b.endStoreLocal(); b.endBlock(); b.endTryCatch(); b.endBlock(); b.endTryFinally(); b.beginReturn(); b.emitLoadLocal(result); b.endReturn(); b.endBlock(); b.endRoot();
        }).getNode(0).getCallTarget();
    }
    private RootCallTarget maskedLeaf(Language language) { return maskedLeaf(language, null, false); }
    private RootCallTarget maskedLeaf(Language language, Object payload, boolean invalidFinalMask) {
        return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot(); var prior = b.createLocal("leaf prior mask", "object"); b.beginBlock(); b.beginStoreLocal(prior); b.emitEnterMask(MaskingState.MASKED_UNINTERRUPTIBLE); b.endStoreLocal(); b.beginTryFinally(() -> { b.beginRestoreMask(); b.emitLoadLocal(prior); b.endRestoreMask(); });
            b.beginBlock(); b.beginYield(); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endYield(); b.beginYield(); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endYield();
            if (payload != null) { b.beginRaiseIO(false); b.emitLoadConstant(payload); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endRaiseIO(); }
            b.endBlock(); b.endTryFinally(); if (invalidFinalMask) b.emitEnterMask(MaskingState.MASKED_INTERRUPTIBLE); b.beginReturn(); b.emitLoadConstant(42L); b.endReturn(); b.endBlock(); b.endRoot();
        }).getNode(0).getCallTarget();
    }
    @Test void maskedUnmaskedMaskedChainParksAndResumesAcrossCarrierThreads() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var driver = entered(context, Driver::new);
            var chain = entered(context, () -> { var c = maskedLeaf(language); var b = maskedCaller(language, c, MaskingState.UNMASKED); var a = maskedCaller(language, b, MaskingState.MASKED_INTERRUPTIBLE); return new Chain(new Thunk(a, null), a, b, c); }); var parent = chain.parent; var aTarget = chain.a; var bTarget = chain.b; var cTarget = chain.c;
            entered(context, () -> { assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver)); return null; });
            assertEquals(BytecodeTier.CACHED, ((BytecodeRoot) cTarget.getRootNode()).getBytecodeNode().getTier(), "The first yield must execute in the cached interpreter"); var aContinuation = (ContinuationResult) parent.getValue(); assertSame(aTarget.getRootNode(), aContinuation.getContinuationRootNode().getSourceRootNode()); var bSegment = ((CallSegmentSuspended) aContinuation.getResult()).getSegment(); var bContinuation = (ContinuationResult) bSegment.getValue(); var cSegment = ((CallSegmentSuspended) bContinuation.getResult()).getSegment();
            assertSame(bTarget.getRootNode(), bContinuation.getContinuationRootNode().getSourceRootNode()); assertSame(cTarget.getRootNode(), ((ContinuationResult) cSegment.getValue()).getContinuationRootNode().getSourceRootNode()); assertEquals(MaskingState.MASKED_INTERRUPTIBLE, bSegment.getCallerMask()); assertEquals(MaskingState.UNMASKED, bSegment.getLogicalMask()); assertEquals(MaskingState.UNMASKED, cSegment.getCallerMask()); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, cSegment.getLogicalMask()); assertEquals(MaskingState.MASKED_INTERRUPTIBLE, ((CallSegmentSuspended) aContinuation.getResult()).getParkedActiveMask()); assertEquals(MaskingState.UNMASKED, ((CallSegmentSuspended) bContinuation.getResult()).getParkedActiveMask());
            try (var pool = Executors.newSingleThreadExecutor()) {
                var first = pool.submit(() -> entered(context, () -> { SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE); try { assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver)); return thc.runtime.Unit.INSTANCE; } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); } })); first.get(5, TimeUnit.SECONDS); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, cSegment.getLogicalMask());
                var second = pool.submit(() -> entered(context, () -> { SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE); try { long answer = (Long) driver.force(parent); assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver)); return answer; } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); } })); assertEquals(42L, second.get(5, TimeUnit.SECONDS));
            }
            assertEquals(2, cSegment.getState()); assertEquals(2, bSegment.getState()); assertEquals(2, parent.getState());
        }
    }
    @Test void ordinaryNestedMaskedApplicationRunsCompiledWithoutSuspension() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var driver = entered(context, Driver::new);
            var a = entered(context, () -> { var c = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { return 42L; } }.getCallTarget(); var b = maskedCaller(language, c, MaskingState.UNMASKED); var target = maskedCaller(language, b, MaskingState.MASKED_INTERRUPTIBLE); for (int i = 0; i < 8; i++) assertEquals(42L, driver.force(new Thunk(target, null))); compile(b); compile(target); return target; });
            entered(context, () -> { assertEquals(42L, driver.force(new Thunk(a, null))); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver)); return null; });
        }
    }
    @Test void malformedOrdinaryCalleeRestoresCallerMaskBeforeFailingClosed() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var driver = entered(context, Driver::new); entered(context, () -> {
            var target = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { return 42L; } }.getCallTarget(); var function = new Closure(null, Closure.NO_PAP_ARGUMENTS, 0, target); SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE);
            var failure = assertThrows(IllegalStateException.class, () -> BytecodeRoot.CaptureApplicationResult.capture(0, function, 42L, MaskingState.UNMASKED, driver)); assertTrue(Objects.requireNonNull(failure.getMessage()).contains("caller mask")); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver)); return null;
        }); }
    }
    private record SuspendedChain(Thunk parent, CallSegment b, CallSegment c) {}
    @Test void resumedGuestFailureRestoresEachCallerMaskAndCarrierAmbient() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var driver = entered(context, Driver::new); var payload = new Object();
            var chain = entered(context, () -> { var c = maskedLeaf(language, payload, false); var b = maskedCaller(language, c, MaskingState.UNMASKED); var a = maskedCaller(language, b, MaskingState.MASKED_INTERRUPTIBLE); var parent = new Thunk(a, null); assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); var bSegment = ((CallSegmentSuspended) ((ContinuationResult) parent.getValue()).getResult()).getSegment(); var cSegment = ((CallSegmentSuspended) ((ContinuationResult) bSegment.getValue()).getResult()).getSegment(); return new SuspendedChain(parent, bSegment, cSegment); }); var parent = chain.parent;
            try (var pool = Executors.newSingleThreadExecutor()) { var result = pool.submit(() -> entered(context, () -> { SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE); try {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver)); assertSame(payload, assertThrows(GuestException.class, () -> driver.force(parent)).getPayload()); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver)); assertSame(payload, assertThrows(GuestException.class, () -> driver.force(parent)).getPayload()); return thc.runtime.Unit.INSTANCE;
            } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); } })); result.get(5, TimeUnit.SECONDS); }
            assertEquals(3, chain.c.getState()); assertEquals(3, chain.b.getState()); assertEquals(3, parent.getState());
        }
    }
    private record ParentSegment(Thunk parent, CallSegment c) {}
    @Test void invalidResumedMaskFailsClosedWithoutChangingCarrierAmbient() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var driver = entered(context, Driver::new);
            var chain = entered(context, () -> { var c = maskedLeaf(language, null, true); var b = maskedCaller(language, c, MaskingState.UNMASKED); var parent = new Thunk(maskedCaller(language, b, MaskingState.MASKED_INTERRUPTIBLE), null); assertThrows(ThunkSuspended.class, () -> driver.force(parent)); var bSegment = ((CallSegmentSuspended) ((ContinuationResult) parent.getValue()).getResult()).getSegment(); return new ParentSegment(parent, ((CallSegmentSuspended) ((ContinuationResult) bSegment.getValue()).getResult()).getSegment()); }); var parent = chain.parent;
            try (var pool = Executors.newSingleThreadExecutor()) { var result = pool.submit(() -> entered(context, () -> { SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE); try {
                assertThrows(ThunkSuspended.class, () -> driver.force(parent)); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver)); var failure = assertThrows(IllegalStateException.class, () -> driver.force(parent)); assertTrue(Objects.requireNonNull(failure.getMessage()).contains("caller mask")); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver)); return thc.runtime.Unit.INSTANCE;
            } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); } })); result.get(5, TimeUnit.SECONDS); }
            assertEquals(4, chain.c.getState(), "Unknown host failure cannot replay the captured callee"); assertEquals(5, parent.getState(), "Parked parent remains available for a sound future continuation"); entered(context, () -> { assertThrows(RuntimeFault.class, () -> driver.force(parent)); return null; });
        }
    }
}
