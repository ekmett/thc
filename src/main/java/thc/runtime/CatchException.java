// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TupleResults.requireVoidCarrier;
import java.util.ArrayDeque;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.Applications.requireClosure;

public final class CatchException extends Expr {
    private final TupleShape shape;
    private final Metrics metrics;
    @Child private Expr action, handler, state;
    @Child private Force force;
    @Child private volatile TupleDispatch actionCall, handlerCall;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private int[] destinationSlots;
    @CompilerDirectives.CompilationFinal private int destinationOffset = -1;
    public CatchException(TupleShape shape, Expr action, Expr handler, Expr state, Metrics metrics) {
        this.shape = shape; this.action = action; this.handler = handler; this.state = state; this.metrics = metrics;
        force = new Force(metrics);
        CoreRepresentation proof = shape.getProof();
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(),
            proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public void prepareTuple(int[] slots, int offset) {
        if (metrics != null) return;
        if (actionCall != null) {
            if (destinationSlots != slots || destinationOffset != offset) throw new IllegalStateException("Conflicting typed destination");
            return;
        }
        var destination = new AstTupleDestination(shape, slots, offset);
        actionCall = new TupleDispatch(new AstTupleDestination(shape, slots, offset, true), null, 1, false, null);
        handlerCall = new TupleDispatch(destination, null, 2, false, null);
        destinationSlots = slots; destinationOffset = offset;
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("catch# requires a tuple destination"); }
    @Override @ExplodeLoop public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (actionCall == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            atomic(() -> {
                if (actionCall == null) {
                    AstTupleDestination destination = new AstTupleDestination(shape, slots, offset);
                    actionCall = insert(new TupleDispatch(new AstTupleDestination(shape, slots, offset, true), metrics, 1, false, null));
                    handlerCall = insert(new TupleDispatch(destination, metrics, 2, false, null));
                    destinationSlots = slots; destinationOffset = offset;
                }
            });
        }
        if (destinationSlots != slots || destinationOffset != offset) throw new IllegalStateException("Check failed.");
        Object value;
        try { value = action.execute(frame); }
        catch (AstCapture cut) { throw cut.append((saved, input) -> loadHandler(saved, input)); }
        return loadHandler(frame, value);
    }
    private Object loadHandler(VirtualFrame frame, Object actionValue) {
        Object value;
        try { value = handler.execute(frame); }
        catch (AstCapture cut) { throw cut.append((saved, input) -> loadState(saved, actionValue, input)); }
        return loadState(frame, actionValue, value);
    }
    private Object loadState(VirtualFrame frame, Object actionValue, Object handlerValue) {
        Object value;
        try { value = state.execute(frame); }
        catch (AstCapture cut) {
            throw cut.append((saved, input) -> { requireVoidCarrier(input); return runAction(saved, actionValue, handlerValue); });
        }
        requireVoidCarrier(value);
        return runAction(frame, actionValue, handlerValue);
    }
    private Object runAction(VirtualFrame frame, Object actionValue, Object handlerValue) {
        try {
            Object closure;
            try { closure = AstControl.force(frame, this, force, actionValue); }
            catch (AstCapture cut) {
                throw cut.append((saved, input) -> { actionCall.execute(saved, requireClosure(input), new Object[] {thc.runtime.Unit.INSTANCE}); return null; });
            }
            actionCall.execute(frame, requireClosure(closure), new Object[] {thc.runtime.Unit.INSTANCE});
            return null;
        } catch (GuestException guest) { return runHandler(frame, handlerValue, guest.getPayload()); }
        catch (AsyncDelivery delivered) {
            delivered.getRequest().acknowledge();
            return runHandler(frame, handlerValue, delivered.getRequest().getPayload());
        } catch (AstCapture cut) { return capture(frame, handlerValue, cut); }
    }
    private Object capture(VirtualFrame frame, Object handlerValue, AstCapture cut) {
        AsyncRequest request = cut.asyncRequest();
        if (request == null) throw cut.enclose(steps -> new CatchScope(this, handlerValue, steps));
        if (request.getTarget() != Thread.currentThread() || request.getState() != AsyncRequestState.CLAIMED)
            throw new IllegalStateException("AST catch delivery left its target thread or was already consumed");
        try { cut.discard(); }
        catch (RuntimeException | Error cleanup) { throw AsyncContinuations.cleanupFailure(request, cleanup, this); }
        request.acknowledge();
        return runHandler(frame, handlerValue, request.getPayload());
    }
    private static final class CatchScope implements AstResumeStep, DelimitedStep {
        private final CatchException node;
        private final Object handler;
        private final ArrayDeque<AstResumeStep> steps;
        @CompilerDirectives.TruffleBoundary CatchScope(CatchException node, Object handler) { this(node, handler, new ArrayDeque<>()); }
        CatchScope(CatchException node, Object handler, ArrayDeque<AstResumeStep> steps) { this.node = node; this.handler = handler; this.steps = steps; }
        @Override public void discard() { AstContinuations.discardSteps(steps); }
        @Override public Object resume(VirtualFrame frame, Object input) {
            try { return AstContinuations.resumeAstSteps(frame, steps, input); }
            catch (GuestException guest) { return node.runHandler(frame, handler, guest.getPayload()); }
            catch (AsyncDelivery delivered) {
                delivered.getRequest().acknowledge();
                return node.runHandler(frame, handler, delivered.getRequest().getPayload());
            } catch (AstCapture cut) { return node.capture(frame, handler, cut); }
            catch (DelimitedCut cut) { throw cut.append(frame, new CatchScope(node, handler)); }
        }
        @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
            try { return input.get(); }
            catch (GuestException guest) { return node.runHandler(frame, handler, guest.getPayload()); }
            catch (AsyncDelivery delivered) {
                AsyncRequest request = delivered.getRequest();
                if (request.getTarget() != Thread.currentThread() || request.getTargetId() != GuestThreads.current(node).currentId() ||
                    request.getState() != AsyncRequestState.CLAIMED)
                    throw new IllegalStateException("Delimited catch delivery left its target or was already consumed");
                request.acknowledge();
                return node.runHandler(frame, handler, request.getPayload());
            }
        }
    }
    private Object runHandler(VirtualFrame frame, Object handlerValue, Object payload) {
        MaskingState prior = SynchronousMasking.current(this);
        if (prior == MaskingState.UNMASKED) SynchronousMasking.set(this, MaskingState.MASKED_INTERRUPTIBLE);
        try {
            Object closure;
            try { closure = AstControl.force(frame, this, force, handlerValue); }
            catch (AstCapture cut) {
                throw cut.append((saved, input) -> { handlerCall.execute(saved, requireClosure(input), new Object[] {payload, thc.runtime.Unit.INSTANCE}); return null; });
            }
            handlerCall.execute(frame, requireClosure(closure), new Object[] {payload, thc.runtime.Unit.INSTANCE});
            return null;
        } catch (AstCapture cut) { throw cut.enclose(steps -> new AstMaskScope(this, prior, steps)); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedMaskStep(this, prior)); }
        finally { SynchronousMasking.set(this, prior); }
    }
}
