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
    private final boolean coldGeneric;
    @Child private IndirectEntryArguments entryArguments;
    @Child private IndirectCallNode callNode;
    @Child private TailCallLoop loop;
    @Child private TailCheck tailCheck;
    private final BranchProfile normalProfile, tailProfile;
    public IndirectCallerNode(Metrics metrics) {
        this(metrics, false);
    }
    public IndirectCallerNode(Metrics metrics, boolean coldGeneric) {
        this.metrics = metrics; this.coldGeneric = coldGeneric; entryArguments = new IndirectEntryArguments(metrics);
        callNode = IndirectCallNode.create();
        loop = new TailCallLoop(metrics); tailCheck = new TailCheck(metrics, coldGeneric);
        normalProfile = coldGeneric ? BranchProfile.getUncached() : BranchProfile.create();
        tailProfile = coldGeneric ? BranchProfile.getUncached() : BranchProfile.create();
    }
    public Object call(VirtualFrame frame, RootCallTarget target, Object[] arguments, boolean tailCall) {
        return call(frame, target, arguments, tailCall, null);
    }
    public Object call(VirtualFrame frame, RootCallTarget target, Object[] arguments, boolean tailCall, TupleShape tupleResult) {
        if (AstControl.captures(this)) {
            try { entryArguments.executeCaptured(frame, target, arguments); }
            catch (AstCapture cut) { throw cut.append((saved, input) -> callEntered(saved, target, arguments, tailCall, tupleResult)); }
        } else entryArguments.execute(frame, target, arguments);
        return callEntered(frame, target, arguments, tailCall, tupleResult);
    }
    private Object callEntered(VirtualFrame frame, RootCallTarget target, Object[] arguments, boolean tailCall, TupleShape tupleResult) {
        Metrics invocation = metrics != null ? metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame);
        if (invocation.getEnabled()) invocation.incrementIndirectCalls();
        // The public location-aware call preserves residual guest entry without
        // an observed exception profile on a cached IndirectCallNode.
        Object result;
        if (tailCall) { tailCheck.check(frame, target, arguments); result = (coldGeneric ? target.call(this, arguments) : Calls.indirect(callNode, target, arguments)); }
        else {
            try { arguments[0] = 0L; result = (coldGeneric ? target.call(this, arguments) : Calls.indirect(callNode, target, arguments)); normalProfile.enter(); }
            catch (TailCall tail) { if (tupleResult != null) throw tail; tailProfile.enter(); result = loop.execute(tail, invocation); }
        }
        return AstControl.captures(this) ? AstControl.complete(this, result, target, tupleResult) : result;
    }
    public static IndirectCallerNode create(Metrics metrics) { return new IndirectCallerNode(metrics); }
}
