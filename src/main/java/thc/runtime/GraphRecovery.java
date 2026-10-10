// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.BaseOSRRootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import thc.Language;

/** Context-owned failure receipts. Compiler callbacks never prepare or publish guest code. */
public final class GraphRecovery implements AutoCloseable {
    private static final Pattern GRAPH_TOO_BIG = Pattern.compile(
            "jdk\\.graal\\.compiler\\.truffle\\.GraphTooBigBailoutException: " +
            "Graph too big to safely compile\\. Node count: [0-9]+\\. Graph Size: [0-9]+\\. Limit: [0-9]+\\.");
    private record Observation(OptimizedCallTarget target, String reason, boolean bailout, boolean permanent) {}
    /** Distinct failures from this source generation; the head's identity survives sibling callbacks. */
    record Failure(List<Observation> observations, boolean claimed) {
        OptimizedCallTarget target() { return observations.getFirst().target; }
        String reason() { return observations.getFirst().reason; }
        boolean bailout() { return observations.getFirst().bailout; }
        boolean permanent() { return observations.getFirst().permanent; }
        boolean sameClaim(Failure other) {
            return claimed && other.claimed && observations.getFirst() == other.observations.getFirst();
        }
        Failure remaining() {
            return observations.size() == 1 ? null :
                    new Failure(List.copyOf(observations.subList(1, observations.size())), false);
        }
    }
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

    /** An abandoned task has no failed graph. All other bailouts, including transient ones, retire it. */
    static boolean cancelled(String reason) {
        return reason != null && reason.startsWith(
                "jdk.graal.compiler.core.common.CancellationBailoutException:");
    }

    private ContextRoot owned(OptimizedCallTarget target) {
        if (closed) return null;
        Object token = owner.get();
        ContextRoot root = source(target.getRootNode());
        return token != null && root != null && root.compilationOwner() == token ? root : null;
    }

    void failed(OptimizedCallTarget target, String reason, boolean bailout, boolean permanent) {
        if (cancelled(reason)) return;
        ContextRoot root = owned(target);
        if (root == null) return;
        root.compilationFailureObserved = true;
        if (target.getRootNode() instanceof ContextRoot physicalRoot) physicalRoot.compilationFailureObserved = true;
        // The public callback disables hotness admission and inlining even when the compiler
        // classified the original failure as transient. Preserve that original receipt below.
        // Do not invoke graph-size block compilation recovery on this physical target.
        target.onCompilationFailed(() -> reason, true, true, true, false);
        if (target.isValid()) target.invalidate("retired after compilation failure");
        Failure observed;
        Failure updated;
        do {
            observed = root.graphFailure.get();
            if (observed != null && observed.observations.stream().anyMatch(o -> o.target == target)) return;
            var observations = new ArrayList<Observation>(observed == null ? List.of() : observed.observations);
            observations.add(new Observation(target, reason, bailout, permanent));
            updated = new Failure(List.copyOf(observations), observed != null && observed.claimed);
        } while (!root.graphFailure.compareAndSet(observed, updated));
    }

    Failure claim(ContextRoot root) {
        if (closed || root.compilationOwner() != owner.get() || root.compilationOwner() == null ||
                Language.currentState().getCompilationOwner() != root.compilationOwner()) return null;
        Failure observed = root.graphFailure.get();
        if (observed == null || observed.claimed || observed.target().isSubmittedForCompilation()) return null;
        Failure claimed = new Failure(observed.observations, true);
        return root.graphFailure.compareAndSet(observed, claimed) ? claimed : null;
    }

    boolean publishable(ContextRoot root, Failure claim) {
        Failure observed = root.graphFailure.get();
        return !closed && observed != null && observed.sameClaim(claim) && !claim.target().isSubmittedForCompilation() &&
                root.compilationOwner() == Language.currentState().getCompilationOwner();
    }

    void complete(ContextRoot root, Failure claim, boolean replaced) {
        Failure observed;
        do {
            observed = root.graphFailure.get();
            if (observed == null || !observed.sameClaim(claim)) return;
            // A published replacement retires this generation; otherwise retain untried siblings.
        } while (!root.graphFailure.compareAndSet(observed, replaced ? null : observed.remaining()));
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
    }
}
