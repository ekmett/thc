// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TypedInputs.*;
import static thc.runtime.Applications.requireClosure;

final class InputCallArm extends Node {
    private final InputSource source;
    private final int count, start, arity, prefixCount;
    private final boolean tail, hasEnvironment;
    private final Metrics metrics;
    private final TupleDestination destination;
    private final RootCallTarget target;
    private final GuestRoot root;
    private final TypedInputLayout input;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] strictPositions;
    @Child private DirectCallNode direct;
    @Child private DirectCallerNode legacy;
    @Child private Force force;
    @Child private TailCallLoop loop;
    @Child private TupleBounce tupleBounce;
    @Child private InputDispatch remainder;
    InputCallArm(InputSource source, int count, boolean tail, Metrics metrics, TupleDestination destination, int start, Closure function) {
        this.source = source; this.count = count; this.tail = tail; this.metrics = metrics; this.destination = destination; this.start = start;
        target = function.target; arity = function.arity; prefixCount = function.suppliedCount; hasEnvironment = function.environment != null;
        root = (GuestRoot) java.util.Objects.requireNonNull(target.getRootNode(), "null cannot be cast to non-null type thc.runtime.GuestRoot");
        input = root.getTypedInput();
        strictPositions = input == null ? new int[0] : strictInputPositions(root, input);
        direct = DirectCallNode.create(target); legacy = new DirectCallerNode(target, metrics, new boolean[0], prefixCount);
        force = new Force(metrics); loop = new TailCallLoop(metrics);
        if (destination != null) tupleBounce = new TupleBounce(destination, metrics);
        if (arity < count) remainder = new InputDispatch(source, count - arity, tail, metrics, destination, start + arity);
        ArgumentLayout.validate(root.getInputLayout(), prefixCount, source.getLayout(), start, Math.min(arity, count));
        if (arity <= count) checkInputResult(root, destination, arity == count);
        if (metrics.getEnabled()) metrics.incrementDirectCacheMisses();
    }
    boolean matches(Closure function) {
        return function.target == target && function.arity == arity && function.suppliedCount == prefixCount &&
            (function.environment != null) == hasEnvironment;
    }
    Object execute(VirtualFrame frame, Closure function, Object[] values) {
        if (arity > count) {
            if (destination != null) throw fault("Aggregate result application is under-saturated");
            if (metrics.getEnabled()) metrics.incrementPapAllocations();
            return input != null ? typedPap(function, input, source, frame, this, values, start, count, prefixCount, arity) :
                legacyPap(frame, this, function, source, values, start, count);
        }
        if (input != null && strictPositions.length != 0 && capturesInput(this)) {
            Object[] overrides;
            try { overrides = forceInputCaptured(frame, this, function, input, source, values, start, strictPositions, force, prefixCount); }
            catch (AstCapture cut) {
                throw cut.append((saved, value) -> callEntered(saved, function, values, (Object[]) value, true));
            }
            return callEntered(frame, function, values, overrides, true);
        }
        return callEntered(frame, function, values, null, false);
    }
    private Object callEntered(VirtualFrame frame, Closure function, Object[] values, Object[] overrides, boolean prepared) {
        boolean isTail = tail && arity == count;
        Object result;
        try {
            Object answer;
            try {
                if (input == null) answer = legacy.call(frame, scalarPacket(frame, this, function, source, values, start, arity, -1), isTail, arity == count && destination != null ? root.getTupleResult() : null);
                else {
                    HandoffStorage loan = prepared
                        ? packInput(frame, this, function, input, source, values, start, arity, prefixCount, strictPositions, overrides)
                        : prepareInput(frame, this, function, input, source, values, start, arity, force, prefixCount, strictPositions);
                    long generation = loan.getGeneration();
                    boolean transferred = false;
                    try {
                        if (isTail) {
                            try { checkTypedTail(frame, this, target, loan, input.getPacket(), metrics); }
                            catch (TailCall transfer) { transferred = transfer.getInput() == loan; throw transfer; }
                        }
                        if (metrics.getEnabled()) { HandoffState state = input.state(); state.setCalls(state.getCalls() + 1); }
                        long invokedGeneration = loan.getGeneration();
                        try { answer = Calls.direct(direct, new Object[] {loan}); }
                        finally { input.releaseIfOwned(loan, invokedGeneration); }
                    } finally { if (!transferred) input.releaseIfOwned(loan, generation); }
                }
            } catch (TailCall transfer) {
                if (isTail || arity == count && tupleBounce != null) throw transfer;
                answer = loop.execute(transfer);
            }
            result = AstControl.captures(this) ? AstControl.complete(this, answer, target,
                arity == count && destination != null ? root.getTupleResult() : null) : answer;
        } catch (TailCall transfer) {
            if (isTail) throw transfer;
            if (arity == count && tupleBounce != null) {
                tupleBounce.execute(frame, transfer); return null;
            }
            throw transfer;
        } catch (AstCapture cut) {
            CompilerDirectives.transferToInterpreter();
            Object[] savedValues = values == null ? null : values.clone();
            throw cut.append((saved, value) -> finish(saved, value, savedValues));
        } catch (DelimitedCut cut) {
            throw captureCall(frame, cut, values);
        }
        return finish(frame, result, values);
    }
    private Object finish(VirtualFrame frame, Object result, Object[] values) {
        if (arity < count) {
            Closure closure;
            try { closure = requireClosure(AstControl.force(frame, this, force, result)); }
            catch (AstCapture cut) {
                CompilerDirectives.transferToInterpreter();
                Object[] savedValues = values == null ? null : values.clone();
                throw cut.append((saved, value) -> remainder.execute(saved, requireClosure(value), savedValues));
            } catch (DelimitedCut cut) {
                throw captureCall(frame, cut, values);
            }
            InputDispatch rest = remainder;
            if (rest == null) CompilerDirectives.transferToInterpreter();
            return java.util.Objects.requireNonNull(rest).execute(frame, closure, values);
        }
        if (destination != null) { destination.consume(frame, this, result, root.getTupleResult()); return null; }
        return result;
    }
    private DelimitedCut captureCall(VirtualFrame frame, DelimitedCut cut, Object[] values) {
        if (!DelimitedControl.enabled(this)) return cut;
        if (arity == count) return destination == null ? cut : DelimitedControl.tupleCut(cut, frame.materialize(), destination, this, root.getTupleResult());
        Object[] savedValues = values == null ? null : values.clone();
        return cut.append(frame, new DelimitedPendingApplication() {
            @Override public TupleDestination getDestination() { return destination; }
            @Override public Object resume(MaterializedFrame saved, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
                Object[] arguments = savedValues == null ? null : savedValues.clone();
                Object result;
                try { result = InputCallArm.this.finish(saved, input.get(), arguments); }
                catch (DelimitedCut nested) {
                    if (destination != null) DelimitedControl.tupleCut(nested, saved, destination, InputCallArm.this);
                    throw nested;
                }
                return destination == null ? result : destination.delimitedResult(saved, InputCallArm.this);
            }
        });
    }
}
