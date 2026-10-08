// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import java.lang.ref.Reference;
import java.util.ArrayDeque;

/** Keep the reference alive until actual completion, including every resumed cut. */
public final class KeepAliveExpression extends Expr {
    @Child private Expr kept, state, action;
    public KeepAliveExpression(Expr kept, Expr state, Expr action, CoreRepresentation proof) {
        this.kept = kept; this.state = state; this.action = action;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(),
            proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    private enum Route { GENERIC, INT, LONG, FLOAT, DOUBLE, ADDRESS, DATA, CLOSURE, TUPLE }
    @Override public void prepareTuple(int[] slots, int offset) { action.prepareTuple(slots, offset); }
    private static AstCapture enclose(AstCapture cut, Object value) {
        return cut.enclose(steps -> new KeptScope(value, steps));
    }
    private record KeptScope(Object value, ArrayDeque<AstResumeStep> steps) implements AstResumeStep, DelimitedStep {
        @TruffleBoundary KeptScope(Object value) { this(value, new ArrayDeque<>()); }
        @Override public Object resume(VirtualFrame frame, Object input) {
            try { return AstContinuations.resumeAstSteps(frame, steps, input); }
            catch (AstCapture cut) { throw enclose(cut, value); }
            catch (DelimitedCut cut) { throw cut.append(frame, new KeptScope(value)); }
            finally { Reference.reachabilityFence(value); }
        }
        @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
            try { return input.get(); }
            finally { Reference.reachabilityFence(value); }
        }
    }
    private record ResumeKept(KeepAliveExpression node, Route route, int[] slots, int offset) implements AstResumeStep, DelimitedStep {
        @Override public Object resume(VirtualFrame frame, Object input) {
            try {
                node.loadState(frame, route, slots, offset);
                return node.resumeAction(frame, route, slots, offset);
            } catch (AstCapture cut) { throw enclose(cut, input); }
            catch (DelimitedCut cut) { throw cut.append(frame, new KeptScope(input)); }
            finally { Reference.reachabilityFence(input); }
        }
        @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
            return resume(frame, input.get());
        }
    }
    private record ResumeState(KeepAliveExpression node, Route route, int[] slots, int offset) implements AstResumeStep, DelimitedStep {
        @Override public Object resume(VirtualFrame frame, Object input) {
            ManagedByteArray.requireState(input);
            return node.resumeAction(frame, route, slots, offset);
        }
        @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
            return resume(frame, input.get());
        }
    }
    private Object kept(VirtualFrame frame, Route route, int[] slots, int offset) {
        try { return kept.execute(frame); }
        catch (AstCapture cut) { throw cut.append(new ResumeKept(this, route, slots, offset)); }
        catch (DelimitedCut cut) { throw cut.append(frame, new ResumeKept(this, route, slots, offset)); }
    }
    private void loadState(VirtualFrame frame, Route route, int[] slots, int offset) {
        Object token;
        try { token = state.execute(frame); }
        catch (AstCapture cut) { throw cut.append(new ResumeState(this, route, slots, offset)); }
        catch (DelimitedCut cut) { throw cut.append(frame, new ResumeState(this, route, slots, offset)); }
        ManagedByteArray.requireState(token);
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
    /** Dynamic routing occurs only after an operand suspended; ordinary entries stay typed. */
    private Object resumeAction(VirtualFrame frame, Route route, int[] slots, int offset) {
        try {
            return switch (route) {
                case GENERIC -> action.execute(frame);
                case INT -> action.executeInt(frame);
                case LONG -> action.executeLong(frame);
                case FLOAT -> action.executeFloat(frame);
                case DOUBLE -> action.executeDouble(frame);
                case ADDRESS -> action.executeAddress(frame);
                case DATA -> action.executeDataValue(frame);
                case CLOSURE -> action.executeClosure(frame);
                case TUPLE -> action.executeTuple(frame, slots, offset);
            };
        } catch (UnexpectedResultException failure) { throw propagate(failure); }
    }
    @Override public Object execute(VirtualFrame frame) {
        Object value = kept(frame, Route.GENERIC, null, 0);
        try {
            loadState(frame, Route.GENERIC, null, 0);
            return action.execute(frame);
        } catch (AstCapture cut) { throw enclose(cut, value); }
        catch (DelimitedCut cut) { throw cut.append(frame, new KeptScope(value)); }
        finally { Reference.reachabilityFence(value); }
    }
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        Object value = kept(frame, Route.INT, null, 0);
        try {
            loadState(frame, Route.INT, null, 0);
            return action.executeInt(frame);
        } catch (AstCapture cut) { throw enclose(cut, value); }
        catch (DelimitedCut cut) { throw cut.append(frame, new KeptScope(value)); }
        finally { Reference.reachabilityFence(value); }
    }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object value = kept(frame, Route.LONG, null, 0);
        try {
            loadState(frame, Route.LONG, null, 0);
            return action.executeLong(frame);
        } catch (AstCapture cut) { throw enclose(cut, value); }
        catch (DelimitedCut cut) { throw cut.append(frame, new KeptScope(value)); }
        finally { Reference.reachabilityFence(value); }
    }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        Object value = kept(frame, Route.FLOAT, null, 0);
        try {
            loadState(frame, Route.FLOAT, null, 0);
            return action.executeFloat(frame);
        } catch (AstCapture cut) { throw enclose(cut, value); }
        catch (DelimitedCut cut) { throw cut.append(frame, new KeptScope(value)); }
        finally { Reference.reachabilityFence(value); }
    }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object value = kept(frame, Route.DOUBLE, null, 0);
        try {
            loadState(frame, Route.DOUBLE, null, 0);
            return action.executeDouble(frame);
        } catch (AstCapture cut) { throw enclose(cut, value); }
        catch (DelimitedCut cut) { throw cut.append(frame, new KeptScope(value)); }
        finally { Reference.reachabilityFence(value); }
    }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException {
        Object value = kept(frame, Route.ADDRESS, null, 0);
        try {
            loadState(frame, Route.ADDRESS, null, 0);
            return action.executeAddress(frame);
        } catch (AstCapture cut) { throw enclose(cut, value); }
        catch (DelimitedCut cut) { throw cut.append(frame, new KeptScope(value)); }
        finally { Reference.reachabilityFence(value); }
    }
    @Override public DataValue executeDataValue(VirtualFrame frame) throws UnexpectedResultException {
        Object value = kept(frame, Route.DATA, null, 0);
        try {
            loadState(frame, Route.DATA, null, 0);
            return action.executeDataValue(frame);
        } catch (AstCapture cut) { throw enclose(cut, value); }
        catch (DelimitedCut cut) { throw cut.append(frame, new KeptScope(value)); }
        finally { Reference.reachabilityFence(value); }
    }
    @Override public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException {
        Object value = kept(frame, Route.CLOSURE, null, 0);
        try {
            loadState(frame, Route.CLOSURE, null, 0);
            return action.executeClosure(frame);
        } catch (AstCapture cut) { throw enclose(cut, value); }
        catch (DelimitedCut cut) { throw cut.append(frame, new KeptScope(value)); }
        finally { Reference.reachabilityFence(value); }
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object value = kept(frame, Route.TUPLE, slots, offset);
        try {
            loadState(frame, Route.TUPLE, slots, offset);
            return action.executeTuple(frame, slots, offset);
        } catch (AstCapture cut) { throw enclose(cut, value); }
        catch (DelimitedCut cut) { throw cut.append(frame, new KeptScope(value)); }
        finally { Reference.reachabilityFence(value); }
    }
}
