// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;

class ThunkDispatchTest {
    private static final class ForceDriver extends RootNode {
        @Child private Force force;
        ForceDriver(Metrics metrics) { super(null); force = new Force(metrics); }
        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
        Object apply(Thunk thunk) { return Calls.target(getCallTarget(), new Object[]{thunk}); }
    }
    private void withCapture(Consumer<CapturedFrame> action) {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var layout = new FrameLayout(); int slot = layout.bind("captured");
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
                FrameAccess.write(frame, slot, 3_000_000_000L);
                action.accept(new CaptureLayout(language, new boolean[]{true}).capture(frame, new int[]{slot}));
            } finally { context.leave(); }
        }
    }
    @Test void closedAndCapturedThunksKeepTheirArgumentsAfterTargetCacheSaturates() { withCapture(environment -> {
        int[] evaluations = {0};
        record Case(boolean captured, long increment) {}
        Case[] cases = {new Case(false, 11L), new Case(true, 22L), new Case(false, 33L), new Case(true, 44L), new Case(false, 55L)};
        RootCallTarget[] targets = new RootCallTarget[cases.length];
        for (int i = 0; i < cases.length; i++) {
            var item = cases[i];
            targets[i] = new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) {
                    evaluations[0]++; assertEquals(0L, frame.getArguments()[0]);
                    assertEquals(item.captured ? 2 : 1, frame.getArguments().length);
                    long base = 0;
                    if (item.captured) { assertSame(environment, frame.getArguments()[1]); base = ((CapturedFrame) frame.getArguments()[1]).getLong(0); }
                    return base + item.increment;
                }
            }.getCallTarget();
        }
        var metrics = new Metrics(true); var driver = new ForceDriver(metrics);
        // Both direct shapes, cache overflow, then every shape again through the indirect path.
        for (int step = 0; step < 10; step++) {
            int index = step < 5 ? step : 9 - step; var item = cases[index];
            var thunk = new Thunk(targets[index], item.captured ? environment : null);
            long expected = (item.captured ? 3_000_000_000L : 0L) + item.increment;
            assertEquals(expected, driver.apply(thunk)); assertEquals(2, thunk.getState());
            assertNull(thunk.getEnvironment(), "Successful update releases the captured environment");
            assertEquals(expected, driver.apply(thunk));
        }
        assertEquals(10, evaluations[0], "Each thunk target is entered exactly once");
        assertEquals(10L, metrics.getThunkEvaluations()); assertEquals(10L, metrics.getThunkHits());
        assertEquals(3L, metrics.getDirectCacheMisses()); assertTrue(metrics.getIndirectCalls() > 0L);
    }); }
    @Test void capturedThunkTailBounceStillUpdatesTheOriginalThunk() { withCapture(environment -> {
        int[] completions = {0};
        var finalTarget = new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) {
                completions[0]++; assertEquals(0L, frame.getArguments()[0]); assertSame(environment, frame.getArguments()[1]);
                return ((CapturedFrame) frame.getArguments()[1]).getLong(0);
            }
        }.getCallTarget();
        var bouncingTarget = new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) { throw new TailCall(finalTarget, new Object[]{null, frame.getArguments()[1]}); }
        }.getCallTarget();
        var driver = new ForceDriver(new Metrics(true)); var thunk = new Thunk(bouncingTarget, environment);
        assertEquals(3_000_000_000L, driver.apply(thunk)); assertEquals(2, thunk.getState()); assertNull(thunk.getEnvironment());
        assertEquals(3_000_000_000L, driver.apply(thunk)); assertEquals(1, completions[0]);
    }); }
}
