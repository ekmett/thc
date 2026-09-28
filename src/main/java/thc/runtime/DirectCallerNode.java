// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;

public final class DirectCallerNode extends Node {
    private final RootCallTarget target;
    private final Metrics metrics;
    @Child private EntryArguments entryArguments;
    @Child private LeadingCaseReturnNode leadingCaseReturn;
    @Child private DirectCallNode callNode;
    @Child private HandoffCaller handoff;
    @Child private TailCallLoop loop;
    @Child private TailCheck tailCheck;
    private final BranchProfile normalProfile = BranchProfile.create(), tailProfile = BranchProfile.create();
    public DirectCallerNode(RootCallTarget target, Metrics metrics) { this(target, metrics, new boolean[0], 0); }
    public DirectCallerNode(RootCallTarget target, Metrics metrics, boolean[] knownEvaluated) { this(target, metrics, knownEvaluated, 0); }
    public DirectCallerNode(RootCallTarget target, Metrics metrics, boolean[] knownEvaluated, int prefixSize) {
        this.target = target; this.metrics = metrics;
        entryArguments = new EntryArguments(target, metrics, knownEvaluated, prefixSize);
        GuestRoot root = target.getRootNode() instanceof GuestRoot guest ? guest : null;
        if (root != null && !(root instanceof FunctionRoot ast && ast.getCapturesContinuations$org_intelligence_thc()) &&
            root.getLeadingCaseReturn() != null) leadingCaseReturn = new LeadingCaseReturnNode(root.getLeadingCaseReturn(), metrics);
        callNode = DirectCallNode.create(target);
        if (root instanceof FunctionRoot ast && ast.getHandoff$org_intelligence_thc() != null) handoff = new HandoffCaller(target, ast.getHandoff$org_intelligence_thc(), metrics);
        loop = new TailCallLoop(metrics); tailCheck = new TailCheck(metrics);
        if (metrics.getEnabled()) metrics.incrementDirectCacheMisses();
    }
    public RootCallTarget getTarget() { return target; }
    public Object call(VirtualFrame frame, Object[] arguments, boolean tailCall) {
        return call(frame, arguments, tailCall, null);
    }
    public Object call(VirtualFrame frame, Object[] arguments, boolean tailCall, TupleShape tupleResult) {
        if (AstControl.captures(this)) {
            try { entryArguments.executeCaptured(frame, arguments); }
            catch (AstCapture cut) { throw cut.append((saved, input) -> callEntered(saved, arguments, tailCall, tupleResult)); }
        } else entryArguments.execute(frame, arguments);
        return callEntered(frame, arguments, tailCall, tupleResult);
    }
    private Object callEntered(VirtualFrame frame, Object[] arguments, boolean tailCall, TupleShape tupleResult) {
        if (leadingCaseReturn != null) { Object result = leadingCaseReturn.execute(arguments); if (result != null) return result; }
        Object result;
        if (tailCall) {
            if (handoff != null && getRootNode() instanceof FunctionRoot root && root.handoffDestination(frame) >= 0)
                return handoff.call(frame, arguments, callNode, true);
            tailCheck.check(frame, target, arguments);
            result = Calls.direct(callNode, arguments);
        } else {
            try {
                arguments[0] = 0L;
                result = handoff != null ? handoff.call(frame, arguments, callNode, false) : Calls.direct(callNode, arguments);
                normalProfile.enter();
            } catch (TailCall tail) { if (tupleResult != null) throw tail; tailProfile.enter(); result = loop.execute(tail); }
        }
        return AstControl.captures(this) ? AstControl.complete(this, result, target, tupleResult) : result;
    }
    public static DirectCallerNode create(RootCallTarget target, Metrics metrics) { return new DirectCallerNode(target, metrics); }
}
