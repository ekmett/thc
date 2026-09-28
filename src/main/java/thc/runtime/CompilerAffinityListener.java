// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.runtime.AbstractCompilationTask;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import java.util.function.Supplier;

/** Runs after worker scope/initialization, before doCompile. It does not alter
 * pool sizing or cover helpers created during compiler initialization. */
public final class CompilerAffinityListener implements OptimizedTruffleRuntimeListener {
    private final Supplier<? extends AutoCloseable> resetCurrent;
    public CompilerAffinityListener(Supplier<? extends AutoCloseable> resetCurrent) {
        this.resetCurrent = resetCurrent;
    }
    @Override public void onCompilationStarted(OptimizedCallTarget target, AbstractCompilationTask task) {
        Class<?> type = Thread.currentThread().getClass();
        if (!type.getName().equals("com.oracle.truffle.runtime.BackgroundCompileQueue$TruffleCompilerThreadFactory$1") ||
            type.getClassLoader() != OptimizedTruffleRuntime.class.getClassLoader()) return;
        try {
            // Deliberately do not close: the token owns no resource, and the
            // compiler worker remains broad for this and later compilations.
            resetCurrent.get();
        } catch (Exception | LinkageError unavailable) { }
    }
}
