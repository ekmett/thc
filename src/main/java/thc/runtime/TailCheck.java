// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;

public final class TailCheck extends Node {
    private final Metrics metrics;
    @Child private BloomValue bloomValue = BloomValueNodeGen.create();
    private final BranchProfile bounceProfile = BranchProfile.create(), unrollProfile = BranchProfile.create();
    public TailCheck(Metrics metrics) { this.metrics = metrics; }
    public void check(VirtualFrame frame, RootCallTarget target, Object[] arguments) {
        if (!(getRootNode() instanceof GuestRoot source)) { bounce(target, arguments); return; }
        long mask = source.bloom(frame);
        if (!(target.getRootNode() instanceof GuestRoot destination))
            throw new RuntimeFault("Tail call target does not use the THC calling convention");
        if ((mask & destination.mask) == destination.mask) bounce(target, arguments);
        else { unrollProfile.enter(); arguments[0] = bloomValue.execute(mask); }
    }
    private void bounce(RootCallTarget target, Object[] arguments) {
        bounceProfile.enter();
        if (metrics.getEnabled()) metrics.incrementTailBounces();
        throw new TailCall(target, arguments);
    }
}
