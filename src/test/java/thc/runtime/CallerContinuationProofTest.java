// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

/** A child yield becomes a second real bytecode continuation at its caller. */
class CallerContinuationProofTest {
    private static class Driver extends RootNode {
        @Child private Force force = new Force(new Metrics(true));
        Driver() { super(null); }
        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
        Object force(Thunk thunk) { return Calls.target(getCallTarget(), new Object[]{thunk}); }
    }
    private record ThreeRoots(Thunk child, Thunk a, Thunk b, Driver driver) {}
    private record FourRoots(Thunk child, Thunk shared, List<Thunk> callers, Driver driver) {}
    private record Caller(Thunk child, Thunk caller, Driver driver) {}
    private record Callers(Thunk child, List<Thunk> callers, Driver driver) {}
    @FunctionalInterface private interface Action<T> { T run() throws Exception; }
    private <T> T entered(Context context, Action<T> action) throws Exception { context.enter(); try { return action.run(); } finally { context.leave(); } }
    private void compile(RootCallTarget target) throws ReflectiveOperationException { var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); assertTrue(type.isInstance(target)); type.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, type.getMethod("isValidLastTier").invoke(target)); }
    @Test void callerOperandBelowTrySurvivesTwoChildSuspensions() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var childEffects = new AtomicInteger(); var callerEffects = new AtomicInteger(); var compiledCallerEffects = new AtomicInteger();
            var setup = entered(context, () -> {
                var warm = new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { return new ThunkYieldProofRoot.Answer(42L, this); } }.getCallTarget(), null); var selectedChild = new AtomicReference<>(warm); var callerTarget = ThunkYieldProofRoot.caller(language, selectedChild, callerEffects, compiledCallerEffects); var driver = new Driver();
                for (int i = 0; i < 8; i++) assertEquals(142L, driver.force(new Thunk(callerTarget, null))); compile(callerTarget); var child = new Thunk(ThunkYieldProofRoot.target(language, childEffects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), new Object()), null); selectedChild.set(child); return new Caller(child, new Thunk(callerTarget, null), driver);
            }); var child = setup.child; var caller = setup.caller; var driver = setup.driver;
            int callerEffectsBefore = callerEffects.get(), compiledBefore = compiledCallerEffects.get(); var first = entered(context, () -> assertThrows(ThunkSuspended.class, () -> driver.force(caller))); assertSame(caller, first.getThunk()); assertEquals(5, child.getState()); assertEquals(5, caller.getState()); var callerSegment = (ContinuationResult) caller.getValue(); var internalSignal = (ThunkSuspended) callerSegment.getResult(); assertSame(child, internalSignal.getThunk()); assertEquals(0, internalSignal.getStackTraceElementLimit()); assertTrue(TruffleStackTrace.getStackTrace(internalSignal).isEmpty()); var frame = callerSegment.getFrame();
            boolean liveOperand = false; for (int i = 0; i < frame.getFrameDescriptor().getNumberOfSlots(); i++) if (frame.isLong(i) && frame.getLong(i) == 100L) { liveOperand = true; break; }
            var description = new ArrayList<String>(); for (int i = 0; i < frame.getFrameDescriptor().getNumberOfSlots(); i++) description.add(i + "=" + (frame.isLong(i) ? frame.getLong(i) : frame.isObject(i) ? frame.getObject(i) : "unset") + " long=" + frame.isLong(i)); assertTrue(liveOperand, "The primitive left operand remains live below the try/call boundary: " + description);
            var second = entered(context, () -> assertThrows(ThunkSuspended.class, () -> driver.force(caller))); assertSame(caller, second.getThunk(), "The caller's update boundary remains suspended"); assertEquals(5, child.getState()); assertEquals(5, caller.getState()); assertSame(callerSegment, caller.getValue()); assertEquals(142L, entered(context, () -> driver.force(caller))); assertEquals(2, caller.getState()); assertEquals(2, child.getState()); assertEquals(1, childEffects.get()); assertEquals(callerEffectsBefore + 1, callerEffects.get()); assertTrue(compiledCallerEffects.get() > compiledBefore, "The initial caller yield must execute installed guest code");
        }
    }
    @Test void twoCallerThunksShareOneChildAndPublishToTheirReaders() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var childEffects = new AtomicInteger(); var callerEffects = new AtomicInteger();
            var setup = entered(context, () -> { var child = new Thunk(ThunkYieldProofRoot.target(language, childEffects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), new Object()), null); var callerTarget = ThunkYieldProofRoot.caller(language, child, callerEffects); return new Callers(child, List.of(new Thunk(callerTarget, null), new Thunk(callerTarget, null)), new Driver()); }); var child = setup.child; var callers = setup.callers; var driver = setup.driver;
            entered(context, () -> { for (var caller : callers) assertSame(caller, assertThrows(ThunkSuspended.class, () -> driver.force(caller)).getThunk()); return null; }); assertEquals(5, child.getState()); boolean allParked = true; for (var caller : callers) if (caller.getState() != 5) { allParked = false; break; } assertTrue(allParked);
            try (var pool = Executors.newFixedThreadPool(4)) { var start = new CountDownLatch(1); var results = new ArrayList<Future<Object>>(); for (int i = 0; i < 4; i++) { final int index = i; results.add(pool.submit(() -> entered(context, () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return driver.force(callers.get(index % 2)); }))); } start.countDown(); for (var result : results) assertEquals(142L, result.get(5, TimeUnit.SECONDS)); }
            assertEquals(2, child.getState()); boolean allDone = true; for (var caller : callers) if (caller.getState() != 2) { allDone = false; break; } assertTrue(allDone); assertEquals(1, childEffects.get(), "The original child entry runs only once"); assertEquals(2, callerEffects.get(), "Each caller runs its prefix only once");
        }
    }
    @Test void ordinaryGuestFailureIsNotCapturedAsAnInternalSuspension() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var payload = new Object();
            var setup = entered(context, () -> { var child = new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { throw new GuestException(payload, this); } }.getCallTarget(), null); var caller = new Thunk(ThunkYieldProofRoot.caller(language, child, new AtomicInteger()), null); return new Caller(child, caller, new Driver()); }); var child = setup.child; var caller = setup.caller; var driver = setup.driver;
            var failure = entered(context, () -> assertThrows(GuestException.class, () -> driver.force(caller))); assertSame(payload, failure.getPayload()); assertEquals(3, child.getState()); assertEquals(3, caller.getState()); assertFalse(caller.getValue() instanceof ContinuationResult);
        }
    }
    @Test void childFailureAfterSuspensionReentersCallerAndMemoizesNormally() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var payload = new Object(); var childEffects = new AtomicInteger(); var callerEffects = new AtomicInteger();
            var setup = entered(context, () -> { var driver = new Driver(); var failure = new GuestException(payload, driver); var child = new Thunk(ThunkYieldProofRoot.target(language, childEffects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), failure), null); var caller = new Thunk(ThunkYieldProofRoot.caller(language, child, callerEffects), null); return new Caller(child, caller, driver); }); var child = setup.child; var caller = setup.caller; var driver = setup.driver;
            entered(context, () -> { assertSame(caller, assertThrows(ThunkSuspended.class, () -> driver.force(caller)).getThunk()); assertSame(caller, assertThrows(ThunkSuspended.class, () -> driver.force(caller)).getThunk()); var failure = assertThrows(GuestException.class, () -> driver.force(caller)); assertSame(payload, failure.getPayload()); assertSame(payload, assertThrows(GuestException.class, () -> driver.force(caller)).getPayload()); return null; });
            assertEquals(3, child.getState()); assertEquals(3, caller.getState()); assertFalse(caller.getValue() instanceof ContinuationResult); assertFalse(caller.getValue() instanceof ThunkSuspended); assertEquals(1, childEffects.get()); assertEquals(1, callerEffects.get());
        }
    }
    @Test void twoCompiledCallersKeepPrimitiveOperandsAcrossThreeRootChain() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var childEffects = new AtomicInteger(); var lowerEffects = new AtomicInteger(); var upperEffects = new AtomicInteger(); var lowerCompiled = new AtomicInteger(); var upperCompiled = new AtomicInteger();
            var setup = entered(context, () -> {
                var driver = new Driver(); var warm = new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { return new ThunkYieldProofRoot.Answer(42L, this); } }.getCallTarget(), null); var lowerChild = new AtomicReference<>(warm); var lowerTarget = ThunkYieldProofRoot.caller(language, lowerChild, lowerEffects, lowerCompiled); var upperChild = new AtomicReference<>(new Thunk(lowerTarget, null)); var upperTarget = ThunkYieldProofRoot.caller(language, upperChild, upperEffects, upperCompiled);
                for (int i = 0; i < 8; i++) { assertEquals(142L, driver.force(new Thunk(lowerTarget, null))); assertEquals(242L, driver.force(new Thunk(upperTarget, null))); } compile(lowerTarget); compile(upperTarget); var child = new Thunk(ThunkYieldProofRoot.target(language, childEffects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), new Object()), null); var lower = new Thunk(lowerTarget, null); var upper = new Thunk(upperTarget, null); lowerChild.set(child); upperChild.set(lower); return new ThreeRoots(child, lower, upper, driver);
            }); var child = setup.child; var lower = setup.a; var upper = setup.b; var driver = setup.driver;
            int lowerBefore = lowerEffects.get(), upperBefore = upperEffects.get(), lowerCompiledBefore = lowerCompiled.get(), upperCompiledBefore = upperCompiled.get(); entered(context, () -> { assertSame(upper, assertThrows(ThunkSuspended.class, () -> driver.force(upper)).getThunk()); return null; }); assertEquals(5, child.getState());
            for (var caller : List.of(lower, upper)) { assertEquals(5, caller.getState()); var frame = ((ContinuationResult) caller.getValue()).getFrame(); boolean liveOperand = false; for (int i = 0; i < frame.getFrameDescriptor().getNumberOfSlots(); i++) if (frame.isLong(i) && frame.getLong(i) == 100L) { liveOperand = true; break; } assertTrue(liveOperand, "Each compiled caller keeps its primitive operand below the child call"); }
            assertTrue(lowerCompiled.get() > lowerCompiledBefore); assertTrue(upperCompiled.get() > upperCompiledBefore); entered(context, () -> { assertSame(upper, assertThrows(ThunkSuspended.class, () -> driver.force(upper)).getThunk()); assertEquals(242L, driver.force(upper)); return null; }); assertEquals(1, childEffects.get()); assertEquals(lowerBefore + 1, lowerEffects.get()); assertEquals(upperBefore + 1, upperEffects.get()); assertEquals(2, child.getState()); assertEquals(2, lower.getState()); assertEquals(2, upper.getState());
        }
    }
    @Test void deepParkedChainResolvesIterativelyAfterRepeatedChildYield() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var childEffects = new AtomicInteger(); var callerEffects = new AtomicInteger();
            var setup = entered(context, () -> { var child = new Thunk(ThunkYieldProofRoot.target(language, childEffects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), new Object()), null); var previous = child; var callers = new ArrayList<Thunk>(); for (int i = 0; i < 64; i++) { var caller = new Thunk(ThunkYieldProofRoot.caller(language, previous, callerEffects), null); previous = caller; callers.add(caller); } return new Callers(child, callers, new Driver()); }); var child = setup.child; var callers = setup.callers; var driver = setup.driver; var top = callers.getLast();
            entered(context, () -> { assertSame(top, assertThrows(ThunkSuspended.class, () -> driver.force(top)).getThunk()); return null; }); assertEquals(5, child.getState()); boolean allParked = true; for (var caller : callers) if (caller.getState() != 5) { allParked = false; break; } assertTrue(allParked); assertEquals(64, callerEffects.get());
            entered(context, () -> { assertSame(top, assertThrows(ThunkSuspended.class, () -> driver.force(top)).getThunk()); return null; }); boolean stillParked = true; for (var caller : callers) if (caller.getState() != 5) { stillParked = false; break; } assertTrue(stillParked); assertEquals(64, callerEffects.get(), "No caller prefix replays after the child yields twice"); assertEquals(6442L, entered(context, () -> driver.force(top))); assertEquals(1, childEffects.get()); assertEquals(64, callerEffects.get()); assertEquals(2, child.getState()); boolean allDone = true; for (var caller : callers) if (caller.getState() != 2) { allDone = false; break; } assertTrue(allDone);
        }
    }
    @Test void twoCallersShareAThreeRootDependencyAndWakeTheirReaders() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var childEffects = new AtomicInteger(); var callerEffects = new AtomicInteger();
            var setup = entered(context, () -> { var child = new Thunk(ThunkYieldProofRoot.target(language, childEffects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), new Object()), null); var shared = new Thunk(ThunkYieldProofRoot.caller(language, child, callerEffects), null); var topTarget = ThunkYieldProofRoot.caller(language, shared, callerEffects); return new FourRoots(child, shared, List.of(new Thunk(topTarget, null), new Thunk(topTarget, null)), new Driver()); }); var child = setup.child; var shared = setup.shared; var callers = setup.callers; var driver = setup.driver;
            entered(context, () -> { for (var caller : callers) assertSame(caller, assertThrows(ThunkSuspended.class, () -> driver.force(caller)).getThunk()); return null; }); assertEquals(5, child.getState()); assertEquals(5, shared.getState()); boolean allParked = true; for (var caller : callers) if (caller.getState() != 5) { allParked = false; break; } assertTrue(allParked);
            try (var pool = Executors.newFixedThreadPool(4)) { var start = new CountDownLatch(1); var results = new ArrayList<Future<Object>>(); for (int i = 0; i < 4; i++) { final int index = i; results.add(pool.submit(() -> entered(context, () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return driver.force(callers.get(index % 2)); }))); } start.countDown(); for (var result : results) assertEquals(242L, result.get(5, TimeUnit.SECONDS)); }
            assertEquals(1, childEffects.get()); assertEquals(3, callerEffects.get()); assertEquals(2, shared.getState()); boolean allDone = true; for (var caller : callers) if (caller.getState() != 2) { allDone = false; break; } assertTrue(allDone);
        }
    }
    @Test void guestFailurePropagatesThroughThreeParkedCallersWithoutReplay() throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null)); var payload = new Object(); var childEffects = new AtomicInteger(); var callerEffects = new AtomicInteger();
            var setup = entered(context, () -> { var driver = new Driver(); var child = new Thunk(ThunkYieldProofRoot.target(language, childEffects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), new GuestException(payload, driver)), null); var previous = child; var callers = new ArrayList<Thunk>(); for (int i = 0; i < 3; i++) { var caller = new Thunk(ThunkYieldProofRoot.caller(language, previous, callerEffects), null); previous = caller; callers.add(caller); } return new Callers(child, callers, driver); }); var child = setup.child; var callers = setup.callers; var driver = setup.driver; var top = callers.getLast();
            entered(context, () -> { assertSame(top, assertThrows(ThunkSuspended.class, () -> driver.force(top)).getThunk()); assertSame(top, assertThrows(ThunkSuspended.class, () -> driver.force(top)).getThunk()); assertSame(payload, assertThrows(GuestException.class, () -> driver.force(top)).getPayload()); assertSame(payload, assertThrows(GuestException.class, () -> driver.force(top)).getPayload()); return null; }); assertEquals(1, childEffects.get()); assertEquals(3, callerEffects.get()); assertEquals(3, child.getState()); boolean allFailed = true; for (var caller : callers) if (caller.getState() != 3) { allFailed = false; break; } assertTrue(allFailed);
        }
    }
}
