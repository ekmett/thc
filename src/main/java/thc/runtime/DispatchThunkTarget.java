// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;

/** Cache the target before forming fixed one-slot or two-slot thunk packets. */
@ReportPolymorphism @GenerateInline @GenerateUncached
public abstract class DispatchThunkTarget extends Node {
    public abstract Object execute(Node inliningTarget, RootCallTarget target, CapturedFrame environment, Metrics metrics);
    @Specialization(guards = "target == cachedTarget", limit = "3")
    protected Object direct(RootCallTarget target, CapturedFrame environment, Metrics metrics,
            @Cached("target") RootCallTarget cachedTarget, @Cached("environment != null") boolean hasEnvironment,
            @Cached("createDirect(cachedTarget, metrics)") DirectCallNode call) {
        return hasEnvironment ? Calls.direct(call, new Object[] {0L, environment}) : Calls.direct(call, new Object[] {0L});
    }
    @Specialization(replaces = "direct")
    protected Object indirect(RootCallTarget target, CapturedFrame environment, Metrics metrics, @Cached("create()") IndirectCallNode call) {
        if (metrics.getEnabled()) metrics.incrementIndirectCalls();
        return environment != null ? Calls.indirect(call, target, new Object[] {0L, environment}) : Calls.indirect(call, target, new Object[] {0L});
    }
    public static DirectCallNode createDirect(RootCallTarget target, Metrics metrics) {
        if (metrics.getEnabled()) metrics.incrementDirectCacheMisses();
        return DirectCallNode.create(target);
    }
}
