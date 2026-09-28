// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.Node;

public final class TargetCache extends Node {
    private final Metrics metrics;
    @Child private DispatchCallTarget dispatch = DispatchCallTargetNodeGen.create();
    public TargetCache(Metrics metrics) { this.metrics = metrics; }
    public Object call(RootCallTarget target, Object[] arguments) { return dispatch.execute(this, target, arguments, metrics); }
    public Object call(RootCallTarget target, Object[] arguments, Metrics invocation) { return dispatch.execute(this, target, arguments, invocation); }
}
