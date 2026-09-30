// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.compiler.TruffleCompilerListener;
import com.oracle.truffle.runtime.AbstractCompilationTask;
import com.oracle.truffle.runtime.BaseOSRRootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import java.lang.ref.WeakReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import thc.Language;

/** Context-owned failure receipts. Compiler callbacks never prepare or publish guest code. */
public final class GraphRecovery implements AutoCloseable {
    private static final Pattern GRAPH_TOO_BIG = Pattern.compile(
            "jdk\\.graal\\.compiler\\.truffle\\.GraphTooBigBailoutException: " +
            "Graph too big to safely compile\\. Node count: [0-9]+\\. Graph Size: [0-9]+\\. Limit: [0-9]+\\.");
    record Failure(OptimizedCallTarget target, String reason, boolean claimed) {}
    private final WeakReference<Object> owner;
    private final OptimizedTruffleRuntime runtime;
    private final Listener listener;
    private volatile boolean closed;

    public GraphRecovery(Language.State state) {
        owner = new WeakReference<>(state.getCompilationOwner());
        if (Truffle.getRuntime() instanceof OptimizedTruffleRuntime optimized) {
            runtime = optimized;
            listener = new Listener(this);
            runtime.addListener(listener);
        } else { runtime = null; listener = null; }
    }

    static ContextRoot source(RootNode root) {
        if (root instanceof AstSameFrameArm.ArmRoot arm) return source(arm.sourceRoot());
        if (root instanceof ContextRoot contextRoot) return contextRoot;
        if (root instanceof ContinuationRootNode continuation)
            return continuation.getSourceRootNode() instanceof RootNode original ? source(original) : null;
        if (root instanceof BaseOSRRootNode) {
            ContextRoot source = null;
            for (Node child : root.getChildren()) {
                if (!(child.getRootNode() instanceof ContextRoot candidate)) continue;
                if (source != null && source != candidate) return null;
                source = candidate;
            }
            return source;
        }
        return null;
    }

    static boolean graphTooBig(String reason, boolean bailout, boolean permanent) {
        return bailout && permanent && reason != null && GRAPH_TOO_BIG.matcher(reason).matches();
    }

    private ContextRoot owned(OptimizedCallTarget target) {
        if (closed) return null;
        Object token = owner.get();
        ContextRoot root = source(target.getRootNode());
        return token != null && root != null && root.compilationOwner() == token ? root : null;
    }

    private void failed(OptimizedCallTarget target, String reason, boolean bailout, boolean permanent) {
        if (!graphTooBig(reason, bailout, permanent) && !(bailout && permanent &&
                "jdk.vm.ci.code.BailoutException: Code installation failed: code is too large".equals(reason))) return;
        ContextRoot root = owned(target);
        if (root == null) return;
        Failure observed;
        do {
            observed = root.graphFailure.get();
            if (observed != null && observed.claimed) return;
        } while (!root.graphFailure.compareAndSet(observed, new Failure(target, reason, false)));
    }

    private void succeeded(OptimizedCallTarget target) {
        ContextRoot root = owned(target);
        if (root == null) return;
        Failure observed = root.graphFailure.get();
        if (observed != null && observed.target == target) root.graphFailure.compareAndSet(observed, null);
    }

    Failure claim(ContextRoot root) {
        if (closed || root.compilationOwner() != owner.get() || root.compilationOwner() == null ||
                Language.currentState().getCompilationOwner() != root.compilationOwner()) return null;
        Failure observed = root.graphFailure.get();
        if (observed == null || observed.claimed || observed.target.isSubmittedForCompilation()) return null;
        if (observed.target.isValid()) { root.graphFailure.compareAndSet(observed, null); return null; }
        Failure claimed = new Failure(observed.target, observed.reason, true);
        return root.graphFailure.compareAndSet(observed, claimed) ? claimed : null;
    }

    boolean publishable(ContextRoot root, Failure claim) {
        return !closed && root.graphFailure.get() == claim && !claim.target.isSubmittedForCompilation() &&
                !claim.target.isValid() && root.compilationOwner() == Language.currentState().getCompilationOwner();
    }

    @Override public void close() {
        closed = true;
        if (runtime != null) runtime.removeListener(listener);
        owner.clear();
    }

    private static final class Listener implements OptimizedTruffleRuntimeListener {
        private final WeakReference<GraphRecovery> service;
        Listener(GraphRecovery service) { this.service = new WeakReference<>(service); }
        @Override public void onCompilationFailed(OptimizedCallTarget target, String reason, boolean bailout,
                boolean permanent, int tier, Supplier<String> lazyStackTrace) {
            GraphRecovery current = service.get();
            if (current != null) current.failed(target, reason, bailout, permanent);
        }
        @Override public void onCompilationSuccess(OptimizedCallTarget target, AbstractCompilationTask task,
                TruffleCompilerListener.GraphInfo graph, TruffleCompilerListener.CompilationResultInfo result) {
            GraphRecovery current = service.get();
            if (current != null) current.succeeded(target);
        }
    }
}
