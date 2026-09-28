// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;

public final class TailCheck extends Node {
    private final Metrics metrics;
    private final boolean coldGeneric;
    @Child private BloomValue bloomValue = BloomValueNodeGen.create();
    private final BranchProfile bounceProfile, unrollProfile;
    public TailCheck(Metrics metrics) { this(metrics, false); }
    public TailCheck(Metrics metrics, boolean coldGeneric) {
        this.metrics = metrics; this.coldGeneric = coldGeneric;
        bounceProfile = coldGeneric ? BranchProfile.getUncached() : BranchProfile.create();
        unrollProfile = coldGeneric ? BranchProfile.getUncached() : BranchProfile.create();
    }
    public void check(VirtualFrame frame, RootCallTarget target, Object[] arguments) {
        Metrics invocation = metrics != null ? metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame);
        if (!(getRootNode() instanceof GuestRoot source)) { bounce(target, arguments, invocation); return; }
        long mask = source.bloom(frame);
        if (!(target.getRootNode() instanceof GuestRoot destination))
            throw new RuntimeFault("Tail call target does not use the THC calling convention");
        if ((mask & destination.mask) == destination.mask) bounce(target, arguments, invocation);
        else { unrollProfile.enter(); arguments[0] = coldGeneric ? Long.valueOf(mask) : bloomValue.execute(mask); }
    }
    private void bounce(RootCallTarget target, Object[] arguments, Metrics invocation) {
        bounceProfile.enter();
        if (invocation.getEnabled()) invocation.incrementTailBounces();
        throw new TailCall(target, arguments);
    }
}
