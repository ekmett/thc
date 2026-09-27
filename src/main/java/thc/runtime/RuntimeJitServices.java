// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleRuntime;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.compiler.TruffleCompilerListener;
import com.oracle.truffle.runtime.AbstractCompilationTask;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import thc.Language;
import java.lang.ref.WeakReference;
import java.util.function.Supplier;

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
public final class RuntimeJitServices implements AutoCloseable {
    private static final Supplier<TruffleRuntime> JVM_RUNTIME = Truffle::getRuntime;
    private final Supplier<? extends TruffleRuntime> runtimeProvider;
    private final WeakReference<Language> language;
    private OptimizedTruffleRuntime runtime;
    private Long availability;
    private Listener observer;
    private boolean closed;
    private final long[] counters = new long[6];

    public RuntimeJitServices(Language language) { this(language, JVM_RUNTIME); }

    public RuntimeJitServices(Language language, Supplier<? extends TruffleRuntime> runtimeProvider) {
        this.language = new WeakReference<>(language);
        this.runtimeProvider = runtimeProvider;
    }

    /** 400: enabled; 401..406: starts, successes, failures, invalidations,
     * deoptimizations, queue events. All queries require index = detail = 0. */
    @TruffleBoundary
    public synchronized long query(int selector, long index, long detail) {
        if (selector < 400 || selector > 406 || index != 0L || detail != 0L)
            throw new RuntimeFault("Invalid THC.Internal.JIT query: " + selector + "/" + index + "/" + detail);
        long status = status();
        if (status != 0L) return status;
        if (selector == 400) return observer == null ? 0L : 1L;
        if (observer == null) return RuntimeServiceStatus.DISABLED;
        return counters[selector - 401];
    }

    /** Control 400: 0 disables, 1 enables; successful changes return zero.
     * Enabling an enabled service is idempotent and never registers twice. */
    @TruffleBoundary
    public synchronized long control(int selector, long setting) {
        if (selector != 400 || setting < 0L || setting > 1L)
            throw new RuntimeFault("Invalid THC.Internal.JIT control: " + selector + "/" + setting);
        long status = status();
        if (status != 0L) return status;
        if (setting == 0L) {
            removeObserver();
        } else if (observer == null) {
            var candidate = new Listener(this, runtime);
            try {
                runtime.addListener(candidate);
                observer = candidate;
            } catch (SecurityException ignored) { return RuntimeServiceStatus.DENIED; }
            catch (UnsupportedOperationException ignored) { return RuntimeServiceStatus.UNSUPPORTED; }
        }
        return 0L;
    }

    private long status() {
        if (closed || language.get() == null) return RuntimeServiceStatus.UNAVAILABLE;
        if (availability != null) return availability;
        long status;
        try {
            var selected = runtimeProvider.get();
            runtime = selected instanceof OptimizedTruffleRuntime optimized ? optimized : null;
            status = runtime == null ? RuntimeServiceStatus.UNSUPPORTED : 0L;
        } catch (SecurityException ignored) { status = RuntimeServiceStatus.DENIED; }
        catch (UnsupportedOperationException | NoClassDefFoundError | NoSuchMethodError ignored) {
            status = RuntimeServiceStatus.UNSUPPORTED;
        }
        availability = status;
        return status;
    }

    private synchronized void record(Listener source, OptimizedCallTarget target, int index) {
        // Also reject callbacks already in flight from a removed registration.
        if (closed || observer != source) return;
        var owner = language.get();
        if (owner == null) return;
        var root = target.getRootNode();
        var info = root.getLanguageInfo();
        if (info == null || !"thc".equals(info.getId()) || root.getLanguage(Language.class) != owner) return;
        if (counters[index] != Long.MAX_VALUE) counters[index]++;
    }

    private void removeObserver() {
        var previous = observer;
        if (previous == null) return;
        observer = null;
        runtime.removeListener(previous);
    }

    @Override
    @TruffleBoundary
    public synchronized void close() {
        if (closed) return;
        closed = true;
        removeObserver();
        language.clear();
    }

    /** The process-wide listener registry must not keep a context, its language,
     * or the service alive. Normal disposal removes the observer immediately;
     * a late callback also removes an orphaned observer without retaining roots. */
    private static final class Listener implements OptimizedTruffleRuntimeListener {
        private final WeakReference<RuntimeJitServices> owner;
        private final OptimizedTruffleRuntime runtime;

        Listener(RuntimeJitServices owner, OptimizedTruffleRuntime runtime) {
            this.owner = new WeakReference<>(owner);
            this.runtime = runtime;
        }

        private void record(OptimizedCallTarget target, int index) {
            var service = owner.get();
            if (service == null) runtime.removeListener(this);
            else service.record(this, target, index);
        }

        @Override public void onCompilationStarted(OptimizedCallTarget target, AbstractCompilationTask task) {
            record(target, 0);
        }
        @Override public void onCompilationSuccess(OptimizedCallTarget target, AbstractCompilationTask task,
                TruffleCompilerListener.GraphInfo graph, TruffleCompilerListener.CompilationResultInfo result) {
            record(target, 1);
        }
        @Override public void onCompilationFailed(OptimizedCallTarget target, String reason, boolean bailout,
                boolean permanentBailout, int tier, Supplier<String> lazyStackTrace) {
            record(target, 2);
        }
        @Override public void onCompilationInvalidated(OptimizedCallTarget target, Object source, CharSequence reason) {
            record(target, 3);
        }
        @Override public void onCompilationDeoptimized(OptimizedCallTarget target, Frame frame, String reason) {
            record(target, 4);
        }
        @Override public void onCompilationQueued(OptimizedCallTarget target, int tier) { record(target, 5); }
    }
}
