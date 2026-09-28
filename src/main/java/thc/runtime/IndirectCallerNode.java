// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;

public final class IndirectCallerNode extends Node {
    private final Metrics metrics;
    @Child private IndirectEntryArguments entryArguments;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();
    @Child private TailCallLoop loop;
    @Child private TailCheck tailCheck;
    private final BranchProfile normalProfile, tailProfile;
    public IndirectCallerNode(Metrics metrics) {
        this(metrics, false);
    }
    public IndirectCallerNode(Metrics metrics, boolean coldGeneric) {
        this.metrics = metrics; entryArguments = new IndirectEntryArguments(metrics);
        loop = new TailCallLoop(metrics); tailCheck = new TailCheck(metrics);
        normalProfile = coldGeneric ? BranchProfile.getUncached() : BranchProfile.create();
        tailProfile = coldGeneric ? BranchProfile.getUncached() : BranchProfile.create();
    }
    public Object call(VirtualFrame frame, RootCallTarget target, Object[] arguments, boolean tailCall) {
        entryArguments.execute(frame, target, arguments);
        if (metrics.getEnabled()) metrics.incrementIndirectCalls();
        Object result;
        if (tailCall) { tailCheck.check(frame, target, arguments); result = Calls.indirect(callNode, target, arguments); }
        else {
            try { arguments[0] = 0L; result = Calls.indirect(callNode, target, arguments); normalProfile.enter(); }
            catch (TailCall tail) { tailProfile.enter(); result = loop.execute(tail); }
        }
        return AstControl.captures(this) ? AstControl.complete(this, result, target) : result;
    }
    public static IndirectCallerNode create(Metrics metrics) { return new IndirectCallerNode(metrics); }
}
