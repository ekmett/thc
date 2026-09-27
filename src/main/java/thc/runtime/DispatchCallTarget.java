// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;

@ReportPolymorphism @GenerateInline @GenerateUncached
public abstract class DispatchCallTarget extends Node {
    public abstract Object execute(Node inliningTarget, CallTarget target, Object[] arguments, Metrics metrics);
    @Specialization(guards = "target == cachedTarget", limit = "3")
    protected Object direct(CallTarget target, Object[] arguments, Metrics metrics,
            @Cached("target") CallTarget cachedTarget, @Cached("createDirect(cachedTarget, metrics)") DirectCallNode call) {
        return Calls.direct(call, arguments);
    }
    @Specialization(replaces = "direct")
    protected Object indirect(CallTarget target, Object[] arguments, Metrics metrics, @Cached("create()") IndirectCallNode call) {
        if (metrics.getEnabled()) metrics.incrementIndirectCalls();
        return Calls.indirect(call, target, arguments);
    }
    public static DirectCallNode createDirect(CallTarget target, Metrics metrics) {
        if (metrics.getEnabled()) metrics.incrementDirectCacheMisses();
        return DirectCallNode.create(target);
    }
}
