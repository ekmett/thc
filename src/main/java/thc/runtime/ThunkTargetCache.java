// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.Node;

public final class ThunkTargetCache extends Node {
    private final Metrics metrics;
    @Child private DispatchThunkTarget dispatch = DispatchThunkTargetNodeGen.create();
    public ThunkTargetCache(Metrics metrics) { this.metrics = metrics; }
    public Object call(RootCallTarget target, CapturedFrame environment) { return dispatch.execute(this, target, environment, metrics); }
}
