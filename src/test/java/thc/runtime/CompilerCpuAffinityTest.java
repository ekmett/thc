// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.AbstractCompilationTask;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompilerCpuAffinityTest {
    @Test void synchronousCallerAndRenamedOrdinaryThreadAreUntouched() throws Exception {
        Truffle.getRuntime();
        SynchronousCallerCheck.run();
    }
    // Loading AbstractCompilationTask requires exports installed by Truffle.getRuntime().
    // Keep it out of the outer class that JUnit verifies during discovery.
    private static final class SynchronousCallerCheck {
        static void run() throws Exception {
            var calls = new AtomicInteger();
            var listener = new CompilerAffinityListener(() -> { calls.incrementAndGet(); return null; });
            var target = (OptimizedCallTarget) RootNode.createConstantNode(42).getCallTarget();
            var task = new AbstractCompilationTask() {
                @Override public boolean isCancelled() { return false; }
                @Override public boolean isLastTier() { return true; }
                @Override public boolean hasNextTier() { return false; }
            };
            listener.onCompilationStarted(target, task);
            var thread = Thread.ofPlatform().name("TruffleCompilerThread-999").start(new Runnable() {
                @Override public void run() { listener.onCompilationStarted(target, task); }
            });
            thread.join();
            assertEquals(0, calls.get());
        }
    }
    @Test void compilerWorkerResetsWithoutClosingTokenOrChangingCaller() throws Exception {
        var calls = new AtomicInteger();
        var closes = new AtomicInteger();
        var worker = new AtomicReference<Thread>();
        var caller = Thread.currentThread();
        var runtime = (OptimizedTruffleRuntime) Truffle.getRuntime();
        var listener = new CompilerAffinityListener(() -> {
            worker.set(Thread.currentThread()); calls.incrementAndGet();
            return () -> closes.incrementAndGet();
        });
        runtime.addListener(listener);
        try {
            var target = (OptimizedCallTarget) RootNode.createConstantNode(42).getCallTarget();
            assertEquals(42, target.call());
            target.compile(true);
            runtime.waitForCompilation(target, 30_000);
            assertTrue(target.isValidLastTier());
            assertEquals(42, target.call());
            assertTrue(target.isValidLastTier());
        } finally { runtime.removeListener(listener); }
        assertTrue(calls.get() > 0);
        assertNotSame(caller, worker.get());
        assertEquals(0, closes.get());
    }
    @Test void unavailableNativeResetDoesNotFailCompilation() throws Exception {
        var calls = new AtomicInteger();
        var runtime = (OptimizedTruffleRuntime) Truffle.getRuntime();
        var listener = new CompilerAffinityListener(() -> {
            calls.incrementAndGet(); throw new UnsupportedOperationException("affinity unavailable");
        });
        runtime.addListener(listener);
        try {
            var target = (OptimizedCallTarget) RootNode.createConstantNode(17).getCallTarget();
            assertEquals(17, target.call());
            target.compile(true);
            runtime.waitForCompilation(target, 30_000);
            assertTrue(target.isValidLastTier());
            assertEquals(17, target.call());
        } finally { runtime.removeListener(listener); }
        assertTrue(calls.get() > 0);
    }
}
