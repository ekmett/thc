// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import java.util.function.Supplier;
import java.util.ArrayDeque;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.Applications.requireClosure;
import static thc.runtime.TupleResults.*;

public final class DelimitedActionSite extends Node {
    private final Language language;
    private final Metrics metrics;
    @Child private PreparedDispatch one, two;
    @Child private Force force;
    @Child private TailCallLoop trampoline;
    public DelimitedActionSite(Language language, Metrics metrics) {
        this.language = language; this.metrics = metrics;
        one = new PreparedDispatch(1, false, metrics, new boolean[0], null);
        two = new PreparedDispatch(2, false, metrics, new boolean[0], null);
        force = new Force(metrics); trampoline = new TailCallLoop(metrics);
    }
    private Metrics invocationMetrics(VirtualFrame frame) {
        return metrics != null ? metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame);
    }
    public Object tail(VirtualFrame frame, TailCall transfer) { return trampoline.execute(transfer, invocationMetrics(frame)); }
    public Object finish(Object result, TupleShape shape) { return finish(result, shape, null); }
    public Object finish(Object result, TupleShape shape, RootCallTarget expectedTarget) {
        return finish(result, shape, expectedTarget, metrics);
    }
    private Object finish(Object result, TupleShape shape, RootCallTarget expectedTarget, Metrics invocationMetrics) {
        DelimitedControl.captureBytecode(result, shape);
        SavedGuestContinuation saved = switch (result) {
            case TailYield tail -> SavedGuestContinuations.savedGuestContinuation(tail.getContinuation());
            case AstTailYield tail -> tail.getContinuation();
            case null, default -> SavedGuestContinuations.savedGuestContinuation(result);
        };
        Object answer;
        if (saved == null) answer = result;
        else {
            RootCallTarget target = switch (result) {
                case TailYield tail -> tail.getTarget();
                case AstTailYield tail -> tail.getTarget();
                case null, default -> expectedTarget;
            };
            if (target != null) {
                if (!(saved.getSourceRoot() instanceof GuestRoot root)) throw fault("Non-guest delimited invocation cut");
                if (!root.isSelf(target)) throw fault("Delimited invocation returned an unrelated continuation");
                if (shape != null && !root.hasTupleResult(shape)) throw fault("Delimited invocation changed its tuple result shape");
            }
            AsyncRequest request = saved.asyncRequest();
            if (request != null) throw new AsyncDelivery(request, this);
            if (!AsyncContinuations.isYieldMarker(saved.getYielded())) throw fault("Unsupported delimited invocation cut");
            // Pending operations leave the invocation; only genuine stack spills are drained here.
            if (PendingWait.of(saved) != null) return AstControl.completeCallback(this, result, target, shape, false);
            answer = force.drainStack(saved, shape, true, false, invocationMetrics);
            if (PendingWait.of(answer) != null) throw AstControl.captureCompletion(this,
                SavedGuestContinuations.savedGuestContinuation(answer), shape);
            DelimitedControl.asyncResult(answer, this);
        }
        return shape == null ? answer : ownedTupleResult(answer, shape);
    }
    public Object captured(VirtualFrame frame, TupleShape shape, Supplier<Object> body) {
        try { return body.get(); }
        catch (AstCapture cut) { return finishCapture(frame, shape, cut); }
    }
    Object finishCapture(VirtualFrame frame, TupleShape shape, AstCapture cut) {
        AsyncRequest request = cut.asyncRequest();
        if (request != null) throw new AsyncDelivery(request, this);
        if (PendingWait.of(cut.getYielded()) != null) {
            if (shape != null) cut.append((saved, input) -> ownedTupleResult(input, shape));
            throw cut;
        }
        if (!(getRootNode() instanceof GuestRoot root)) throw fault("Missing delimited invocation root");
        return finish(cut.freeze(root, frame.materialize()), shape, null, invocationMetrics(frame));
    }
    private Object forceAction(VirtualFrame frame, Object action) {
        try { return force.execute(frame, action); }
        catch (ThunkSuspended cut) {
            if (cut.getAsyncRequest() != null) throw new AsyncDelivery(cut.getAsyncRequest(), this);
            if (PendingWait.of(cut) != null) throw AstControl.captureChild(this, cut);
            return drainAction(cut.getThunk(), invocationMetrics(frame));
        } catch (CallSegmentSuspended cut) {
            if (cut.getAsyncRequest() != null) throw new AsyncDelivery(cut.getAsyncRequest(), this);
            if (PendingWait.of(cut) != null) throw AstControl.captureChild(this, cut);
            return drainAction(cut.getSegment(), invocationMetrics(frame));
        } catch (PendingWait cut) { throw AstControl.captureForce(this, force, action, cut); }
        catch (AsyncBlocked cut) { throw new AsyncDelivery(cut.getRequest(), this); }
    }
    private Object drainAction(Object boundary, Metrics invocationMetrics) {
        try { return force.drainDelimitedBoundary(boundary, invocationMetrics); }
        catch (ThunkSuspended cut) {
            if (cut.getAsyncRequest() != null) throw new AsyncDelivery(cut.getAsyncRequest(), this);
            throw AstControl.captureChild(this, cut);
        } catch (CallSegmentSuspended cut) {
            if (cut.getAsyncRequest() != null) throw new AsyncDelivery(cut.getAsyncRequest(), this);
            throw AstControl.captureChild(this, cut);
        } catch (PendingWait cut) { throw AstControl.captureForce(this, force, boundary, cut); }
        catch (AsyncBlocked cut) { throw new AsyncDelivery(cut.getRequest(), this); }
    }
    public Object invoke(VirtualFrame frame, Object action, Object[] arguments, TupleShape shape) {
        Object resolved;
        try { resolved = forceAction(frame, action); }
        catch (AstCapture cut) {
            // Operands were already evaluated. Apply the resolved closure, without replaying the action head.
            throw cut.append((saved, input) -> invokeClosure(saved, requireClosure(input), arguments, shape));
        }
        return invokeClosure(frame, requireClosure(resolved), arguments, shape);
    }
    private Object invokeClosure(VirtualFrame frame, Closure closure, Object[] arguments, TupleShape shape) {
        Object result;
        try { result = (arguments.length == 1 ? one : two).execute(frame, closure, arguments); }
        catch (AstCapture cut) { return finishCapture(frame, shape, cut); }
        catch (AsyncBlocked blocked) { throw new AsyncDelivery(blocked.getRequest(), this); }
        // A local pending result already owns its completion suffix; do not reinterpret it as the caller's result shape.
        return finish(result, shape, closure.target, invocationMetrics(frame));
    }
    public Object handle(VirtualFrame frame, DelimitedCut cut, TupleShape shape) {
        Closure continuation = snapshot(cut, shape, invocationMetrics(frame));
        return invoke(frame, cut.getHandler(), new Object[] {continuation, thc.runtime.Unit.INSTANCE}, shape);
    }
    @TruffleBoundary private Closure snapshot(DelimitedCut cut, TupleShape shape, Metrics invocationMetrics) { return new DelimitedStack(cut, shape).closure(language, invocationMetrics); }
    public Object prompt(VirtualFrame frame, Object tag, Object action, Object state, TupleShape shape) {
        requireVoidCarrier(state);
        PromptTag identity = DelimitedControl.tag(this, tag);
        try { return invoke(frame, action, new Object[] {thc.runtime.Unit.INSTANCE}, shape); }
        catch (AstCapture cut) { throw cut.enclose(steps -> (saved, input) -> resumePrompt(saved, identity, shape, steps, input)); }
        catch (DelimitedCut cut) {
            if (cut.getTag() == identity) return handle(frame, cut, shape);
            throw cut.append(frame, new DelimitedPromptStep(identity, this, shape));
        }
    }
    private Object resumePrompt(VirtualFrame frame, PromptTag identity, TupleShape shape, ArrayDeque<AstResumeStep> steps, Object input) {
        try { return AstContinuations.resumeAstSteps(frame, steps, input); }
        catch (AstCapture cut) { throw cut.enclose(next -> (saved, value) -> resumePrompt(saved, identity, shape, next, value)); }
        catch (DelimitedCut cut) {
            if (cut.getTag() == identity) return handle(frame, cut, shape);
            throw cut.append(frame, new DelimitedPromptStep(identity, this, shape));
        }
    }
    @TruffleBoundary private void validateDelivery(AsyncRequest request) {
        if (request.getTarget() != Thread.currentThread() || request.getTargetId() != GuestThreads.current(this).currentId() ||
            request.getState() != AsyncRequestState.CLAIMED)
            throw new IllegalStateException("Delimited catch delivery left its target or was already consumed");
    }
    public Object handleException(VirtualFrame frame, Object handler, AbstractTruffleException failure, TupleShape shape) {
        Object payload;
        if (failure instanceof GuestException guest) payload = guest.getPayload();
        else if (failure instanceof AsyncDelivery delivered) {
            AsyncRequest request = delivered.getRequest();
            validateDelivery(request);
            request.acknowledge(); payload = request.getPayload();
        } else throw failure;
        MaskingState prior = SynchronousMasking.current(this);
        if (prior == MaskingState.UNMASKED) SynchronousMasking.set(this, MaskingState.MASKED_INTERRUPTIBLE);
        try { return invoke(frame, handler, new Object[] {payload, thc.runtime.Unit.INSTANCE}, shape); }
        catch (AstCapture cut) { throw cut.enclose(steps -> new AstMaskScope(this, prior, steps)); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedMaskStep(this, prior)); }
        finally { SynchronousMasking.set(this, prior); }
    }
    public Object caught(VirtualFrame frame, Object action, Object handler, Object state, TupleShape shape) {
        requireVoidCarrier(state);
        try { return invoke(frame, action, new Object[] {thc.runtime.Unit.INSTANCE}, shape); }
        catch (GuestException failure) { return handleException(frame, handler, failure, shape); }
        catch (AsyncDelivery delivered) { return handleException(frame, handler, delivered, shape); }
        catch (AstCapture cut) { return captureCaught(frame, handler, shape, cut); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedCatchStep(this, handler, shape)); }
    }
    private Object captureCaught(VirtualFrame frame, Object handler, TupleShape shape, AstCapture cut) {
        AsyncRequest request = cut.asyncRequest();
        if (request != null) return handleException(frame, handler, new AsyncDelivery(request, this), shape);
        throw cut.enclose(steps -> (saved, input) -> resumeCaught(saved, handler, shape, steps, input));
    }
    private Object resumeCaught(VirtualFrame frame, Object handler, TupleShape shape, ArrayDeque<AstResumeStep> steps, Object input) {
        try { return AstContinuations.resumeAstSteps(frame, steps, input); }
        catch (GuestException failure) { return handleException(frame, handler, failure, shape); }
        catch (AsyncDelivery delivered) { return handleException(frame, handler, delivered, shape); }
        catch (AstCapture cut) { return captureCaught(frame, handler, shape, cut); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedCatchStep(this, handler, shape)); }
    }
    public Object masked(VirtualFrame frame, Object action, Object state, TupleShape shape, MaskingState target) {
        requireVoidCarrier(state);
        MaskingState prior = SynchronousMasking.current(this);
        SynchronousMasking.set(this, target);
        try { return invoke(frame, action, new Object[] {thc.runtime.Unit.INSTANCE}, shape); }
        catch (AstCapture cut) { throw cut.enclose(steps -> new AstMaskScope(this, prior, steps)); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedMaskStep(this, prior)); }
        finally { SynchronousMasking.set(this, prior); }
    }
    public Object annotated(VirtualFrame frame, Object annotation, Object action, Object state, TupleShape shape) {
        requireVoidCarrier(state);
        StackAnnotationState prior = StackAnnotations.enter(this, annotation);
        try { return invoke(frame, action, new Object[] {thc.runtime.Unit.INSTANCE}, shape); }
        catch (AstCapture cut) { throw cut.enclose(steps -> new AstAnnotationScope(this, prior, steps)); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedAnnotationStep(this, prior)); }
        finally { StackAnnotations.set(this, prior); }
    }
}
