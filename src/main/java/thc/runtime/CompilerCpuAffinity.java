// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import java.util.function.Supplier;

/** The first successful provider supplies a process-lifetime baseline captured
 * before guest pinning. Retain its reset method, never a guest context. */
public final class CompilerCpuAffinity {
    private CompilerCpuAffinity() {}
    private static boolean installed;
    public static synchronized boolean install(Supplier<? extends AutoCloseable> resetCurrent) {
        if (installed) return true;
        try {
            if (!(Truffle.getRuntime() instanceof OptimizedTruffleRuntime runtime)) return false;
            runtime.addListener(new CompilerAffinityListener(resetCurrent));
            installed = true;
            return true;
        } catch (Exception | LinkageError unavailable) { return false; }
    }
}
