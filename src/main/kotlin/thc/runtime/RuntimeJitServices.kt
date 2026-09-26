// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleRuntime
import com.oracle.truffle.api.frame.Frame
import com.oracle.truffle.compiler.TruffleCompilerListener
import com.oracle.truffle.runtime.AbstractCompilationTask
import com.oracle.truffle.runtime.OptimizedCallTarget
import com.oracle.truffle.runtime.OptimizedTruffleRuntime
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener
import thc.Language
import java.lang.ref.WeakReference
import java.util.function.Supplier

/**
 * Opt-in diagnostics for THC.Internal.JIT, deliberately tied to the pinned
 * Graal implementation API. This observes events; it does not request compilation,
 * change compilation thresholds, or enable JVM-wide diagnostic instrumentation.
 *
 * Attribution relies on Language's EXCLUSIVE context policy. Cloned roots and
 * continuation roots retain their language, unlike the compiler worker's current
 * context (which cannot identify the compiled target's owner). A future SHARED
 * policy must replace this attribution before enabling the service.
 *
 * Counters count callbacks during enabled periods, not live compiled targets or
 * resident machine code. For example, a compilation started while disabled can
 * complete while enabled. Fields are independently sampled, not an atomic
 * snapshot; callers must not require starts >= completions. Disable/re-enable
 * retains counters. Each counter saturates at Long.MAX_VALUE.
 *
 * The listener is not an exhaustive JVM retirement/deoptimization stream. On
 * pinned Graal, transferToInterpreterAndInvalidate can retire code without an
 * invalidation or deoptimization callback. Never infer code liveness or absence
 * of deoptimizations from a zero event count.
 */
internal class RuntimeJitServices(
    language: Language,
    private val runtimeProvider: () -> TruffleRuntime? = { Truffle.getRuntime() }
) : AutoCloseable {
    private val language = WeakReference(language)
    private var runtime: OptimizedTruffleRuntime? = null
    private var availability: Long? = null
    private var observer: Listener? = null
    private var closed = false
    private val counters = LongArray(6)

    /** 400: enabled; 401..406: starts, successes, failures, invalidations,
     * deoptimizations, queue events. All queries require index = detail = 0. */
    @TruffleBoundary
    @Synchronized
    fun query(selector: Int, index: Long, detail: Long): Long {
        if (selector !in 400..406 || index != 0L || detail != 0L)
            throw RuntimeFault("Invalid THC.Internal.JIT query: $selector/$index/$detail")
        val status = status()
        if (status != 0L) return status
        if (selector == 400) return if (observer == null) 0L else 1L
        if (observer == null) return RuntimeServiceStatus.DISABLED
        return counters[selector - 401]
    }

    /** Control 400: 0 disables, 1 enables; successful changes return zero.
     * Enabling an enabled service is idempotent and never registers twice. */
    @TruffleBoundary
    @Synchronized
    fun control(selector: Int, setting: Long): Long {
        if (selector != 400 || setting !in 0L..1L)
            throw RuntimeFault("Invalid THC.Internal.JIT control: $selector/$setting")
        val status = status()
        if (status != 0L) return status
        if (setting == 0L) {
            removeObserver()
        } else if (observer == null) {
            val selected = runtime!!
            val candidate = Listener(this, selected)
            try {
                selected.addListener(candidate)
                observer = candidate
            } catch (_: SecurityException) {
                return RuntimeServiceStatus.DENIED
            } catch (_: UnsupportedOperationException) {
                return RuntimeServiceStatus.UNSUPPORTED
            }
        }
        return 0L
    }

    private fun status(): Long {
        if (closed || language.get() == null) return RuntimeServiceStatus.UNAVAILABLE
        availability?.let { return it }
        val status = try {
            runtime = runtimeProvider() as? OptimizedTruffleRuntime
            if (runtime == null) RuntimeServiceStatus.UNSUPPORTED else 0L
        } catch (_: SecurityException) {
            RuntimeServiceStatus.DENIED
        } catch (_: UnsupportedOperationException) {
            RuntimeServiceStatus.UNSUPPORTED
        } catch (_: NoClassDefFoundError) {
            RuntimeServiceStatus.UNSUPPORTED
        } catch (_: NoSuchMethodError) {
            RuntimeServiceStatus.UNSUPPORTED
        }
        availability = status
        return status
    }

    @Synchronized
    private fun record(source: Listener, target: OptimizedCallTarget, index: Int) {
        // Also reject callbacks already in flight from a removed registration.
        if (closed || observer !== source) return
        val owner = language.get() ?: return
        val root = target.rootNode
        if (root.languageInfo?.id != "thc" || root.getLanguage(Language::class.java) !== owner) return
        if (counters[index] != Long.MAX_VALUE) counters[index]++
    }

    private fun removeObserver() {
        val previous = observer ?: return
        observer = null
        runtime!!.removeListener(previous)
    }

    @TruffleBoundary
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        removeObserver()
        language.clear()
    }

    /** The process-wide listener registry must not keep a context, its language,
     * or the service alive. Normal disposal removes the observer immediately;
     * a late callback also removes an orphaned observer without retaining roots. */
    private class Listener(owner: RuntimeJitServices, private val runtime: OptimizedTruffleRuntime) :
        OptimizedTruffleRuntimeListener {
        private val owner = WeakReference(owner)
        private fun record(target: OptimizedCallTarget, index: Int) {
            val service = owner.get()
            if (service == null) runtime.removeListener(this)
            else service.record(this, target, index)
        }

        override fun onCompilationStarted(target: OptimizedCallTarget, task: AbstractCompilationTask) = record(target, 0)

        override fun onCompilationSuccess(target: OptimizedCallTarget, task: AbstractCompilationTask,
            graph: TruffleCompilerListener.GraphInfo, result: TruffleCompilerListener.CompilationResultInfo) = record(target, 1)

        override fun onCompilationFailed(target: OptimizedCallTarget, reason: String?, bailout: Boolean,
            permanentBailout: Boolean, tier: Int, lazyStackTrace: Supplier<String>?) = record(target, 2)

        override fun onCompilationInvalidated(target: OptimizedCallTarget, source: Any?, reason: CharSequence?) = record(target, 3)

        override fun onCompilationDeoptimized(target: OptimizedCallTarget, frame: Frame?, reason: String?) = record(target, 4)

        override fun onCompilationQueued(target: OptimizedCallTarget, tier: Int) = record(target, 5)
    }
}
