// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.SavedGuestContinuations.savedGuestContinuation;

/** Cold capture at an AST edge whose callee already saved its body. */
public final class AstControl {
    public static final AstControl INSTANCE = new AstControl();
    private AstControl() {}
    public static boolean enabled(Node node) {
        return node.getRootNode() instanceof GhcBCORoot ||
            node.getRootNode() instanceof FunctionRoot root && root.getEnableAsync$org_intelligence_thc();
    }
    public static boolean captures(Node node) {
        return node.getRootNode() instanceof GhcBCORoot ||
            node.getRootNode() instanceof FunctionRoot root && root.getCapturesContinuations$org_intelligence_thc();
    }
    private static final class ResumeChild implements AstResumeStep {
        private final Object child;
        private final Node node;
        ResumeChild(Object child, Node node) { this.child = child; this.node = node; }
        @Override public Object resume(VirtualFrame frame, Object input) { return resumeChild(child, node, input, this); }
    }
    static AstCapture captureChild(Node node, CallSegmentSuspended suspended) {
        return new AstCapture(suspended, SynchronousMasking.current(node))
            .append(new ResumeChild(suspended.getSegment(), node));
    }
    public static Object resumeChild(Object child, Node node, Object input, AstResumeStep step) {
        if (input instanceof TailCall tail && AstTailAnchor.accepts(AstStacks.astStackScope(node).getTailAnchor(), tail)) throw tail;
        if (input instanceof AstChildSuspension suspended) {
            if (suspended.getChild() != child) throw fault("AST caller received an unrelated child cut");
            Object marker;
            if (child instanceof Thunk thunk) marker = new ThunkSuspended(thunk, suspended.getRequest());
            else if (child instanceof CallSegment segment) marker = new CallSegmentSuspended(segment, null, suspended.getRequest(), false);
            else throw fault("Invalid AST suspended child");
            throw new AstCapture(marker, SynchronousMasking.current(node)).append(step);
        }
        if (!(input instanceof ChildResume resumed)) throw fault("AST child continuation requires ChildResume");
        if (resumed.getFailure() != null) throw resumed.takeFailure();
        boolean valid = child instanceof Thunk thunk && thunk.getState() == 2 && thunk.getValue() == resumed.getValue() ||
            child instanceof CallSegment segment && segment.getState() == 2 && segment.getValue() == resumed.getValue();
        if (!valid) throw fault("AST child continuation lost its completed update");
        return resumed.takeValue();
    }
    private static final class RetryForce implements AstResumeStep {
        private final Node node;
        private final Force force;
        private final Object value;
        RetryForce(Node node, Force force, Object value) { this.node = node; this.force = force; this.value = value; }
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (input != thc.runtime.Unit.INSTANCE) throw fault("AST blackhole continuation requires Unit");
            return force(frame, node, force, value);
        }
    }
    public static Object force(VirtualFrame frame, Node node, Force force, Object value) {
        if (!captures(node)) return force.execute(frame, value);
        return forceCallback(frame, node, force, value);
    }
    /** A shared callback owns an explicit one-shot resume scope in either backend. */
    static Object forceCallback(VirtualFrame frame, Node node, Force force, Object value) {
        try { return force.execute(frame, value); }
        catch (ThunkSuspended suspended) {
            throw new AstCapture(suspended, SynchronousMasking.current(node)).append(new ResumeChild(suspended.getThunk(), node));
        } catch (CallSegmentSuspended suspended) {
            throw new AstCapture(suspended, SynchronousMasking.current(node)).append(new ResumeChild(suspended.getSegment(), node));
        } catch (AsyncBlocked blocked) {
            throw new AstCapture(blocked.getRequest(), SynchronousMasking.current(node)).append(new RetryForce(node, force, value));
        }
    }
    public static Object complete(Node node, Object result) { return complete(node, result, null, null, false); }
    public static Object complete(Node node, Object result, RootCallTarget target) { return complete(node, result, target, null, false); }
    public static Object complete(Node node, Object result, RootCallTarget target, TupleShape shape) { return complete(node, result, target, shape, false); }
    public static Object complete(Node node, Object result, RootCallTarget expectedTarget, TupleShape tupleShape, boolean identityTail) {
        if (!captures(node)) return result;
        return completeCallback(node, result, expectedTarget, tupleShape, identityTail);
    }
    static Object completeCallback(Node node, Object result, RootCallTarget expectedTarget, TupleShape tupleShape, boolean identityTail) {
        if (!(result instanceof TailYield) && !(result instanceof AstTailYield) &&
            !(result instanceof SavedGuestContinuation) && !(result instanceof ContinuationResult)) return result;
        return completeSuspended(node, result, expectedTarget, tupleShape, identityTail);
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    private static Object completeSuspended(Node node, Object result, RootCallTarget expectedTarget, TupleShape tupleShape, boolean identityTail) {
        RootCallTarget tailTarget = result instanceof TailYield tail ? tail.getTarget() :
            result instanceof AstTailYield tail ? tail.getTarget() : null;
        SavedGuestContinuation saved = result instanceof TailYield tail ? savedGuestContinuation(tail.getContinuation()) :
            result instanceof AstTailYield tail ? tail.getContinuation() : savedGuestContinuation(result);
        if (saved == null) return result;
        if (!(saved.getSourceRoot() instanceof GuestRoot root)) throw fault("AST call returned a non-guest continuation");
        if (tailTarget != null && !root.isSelf(tailTarget) || tailTarget == null && expectedTarget != null && !root.isSelf(expectedTarget))
            throw fault("AST call returned an unrelated continuation");
        if (tupleShape != null && !root.hasTupleResult(tupleShape)) throw fault("AST call continuation changed its tuple result shape");
        if (!AsyncContinuations.isYieldMarker(saved.getYielded())) throw fault("AST call returned an unsupported continuation cut");
        MaskingState callerMask = SynchronousMasking.current(node);
        FunctionRoot caller = node.getRootNode() instanceof FunctionRoot function ? function : null;
        if (identityTail && tupleShape == null && saved instanceof AstContinuation ast &&
            (ast.getRootEntrySpill() || ast.getTailSpill()) && ast.stackSpill() && ast.asyncRequest() == null &&
            caller != null && caller.isTailSpillIdentityRoot() && root instanceof FunctionRoot function &&
            function.isTailSpillIdentityRoot() && function.getRole$org_intelligence_thc() == FunctionRootRole.PASS_THROUGH &&
            AstTailResult.sameCarrier(caller.getScalarResultProof(), root.getScalarResultProof())) {
            if (ast.getRootEntrySpill()) ast.certifyTailEntry();
            AstPendingTail pending = new AstPendingTail(ast, tailTarget != null ? tailTarget :
                expectedTarget != null ? expectedTarget : root.getCallTarget(), node, callerMask);
            throw new AstCapture(pending, callerMask).append(pending);
        }
        MaskingState parkedMask = saved.getYielded() instanceof CallSegmentSuspended suspended ? suspended.getParkedActiveMask() : null;
        CallSegment segment = new CallSegment(saved.getIdentity(), parkedMask != null ? parkedMask : callerMask,
            callerMask, tupleShape != null ? tupleShape : root.getTupleResult(), false, false);
        CallSegmentSuspended suspended = new CallSegmentSuspended(segment, null, saved.asyncRequest(), saved.stackSpill());
        throw new AstCapture(suspended, callerMask).append(new ResumeChild(segment, node));
    }
}
