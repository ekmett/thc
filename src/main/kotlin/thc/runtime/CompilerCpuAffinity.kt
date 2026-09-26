// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.runtime.AbstractCompilationTask
import com.oracle.truffle.runtime.OptimizedCallTarget
import com.oracle.truffle.runtime.OptimizedTruffleRuntime
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener

/** The first successful provider supplies a process-lifetime baseline, captured
 * before guest pinning. Pass only its bound reset method, never a guest context.
 * This is a pinned Graal implementation API, not a portable Truffle extension.
 */
internal object CompilerCpuAffinity {
    private var installed = false

    @Synchronized
    fun install(resetCurrent: () -> AutoCloseable?): Boolean {
        if (installed) return true
        return try {
            val runtime = Truffle.getRuntime() as? OptimizedTruffleRuntime ?: return false
            runtime.addListener(CompilerAffinityListener(resetCurrent))
            installed = true
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}

/** Compilation-start executes on the compiler worker before doCompile, but
 * after its thread scope/compiler initialization. It neither changes already
 * chosen pool sizing nor covers helpers created during that initialization.
 */
internal class CompilerAffinityListener(
    private val resetCurrent: () -> AutoCloseable?
) : OptimizedTruffleRuntimeListener {
    override fun onCompilationStarted(target: OptimizedCallTarget, task: AbstractCompilationTask) {
        val type = Thread.currentThread().javaClass
        // Never reset a synchronous guest/host caller, virtual carrier, or a
        // similarly named thread. An unfamiliar runtime implementation declines.
        if (type.name != "com.oracle.truffle.runtime.BackgroundCompileQueue\$TruffleCompilerThreadFactory\$1" ||
            type.classLoader !== OptimizedTruffleRuntime::class.java.classLoader) return
        try {
            // The token owns no resource. Not closing it deliberately keeps the
            // worker broad for this and later compilations, including descendants.
            resetCurrent()
        } catch (_: Exception) {
            // Unsupported/denied affinity must not abort compilation.
        } catch (_: LinkageError) {
            // Optional native access or a changed runtime may be unavailable.
        }
    }
}
