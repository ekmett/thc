// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.Applications.requireClosure;
import static thc.runtime.TypedInputs.*;
import static thc.runtime.GenericTypedInputs.*;

public final class GenericInputCall extends Node {
    private final InputSource source;
    private final int count, start;
    private final boolean tail, coldGeneric;
    private final Metrics metrics;
    private final TupleDestination destination;
    @Child private IndirectCallNode indirect = IndirectCallNode.create();
    @Child private IndirectCallerNode legacy;
    @Child private Force force;
    @Child private TailCallLoop loop;
    @Child private TupleBounce tupleBounce;
    public GenericInputCall(InputSource source, int count, boolean tail, Metrics metrics, TupleDestination destination, int start) {
        this(source, count, tail, metrics, destination, start, false);
    }
    public GenericInputCall(InputSource source, int count, boolean tail, Metrics metrics, TupleDestination destination, int start,
            boolean coldGeneric) {
        this.source = source; this.count = count; this.tail = tail; this.metrics = metrics; this.destination = destination; this.start = start;
        this.coldGeneric = coldGeneric;
        legacy = new IndirectCallerNode(metrics, coldGeneric); force = new Force(metrics); loop = new TailCallLoop(metrics);
        if (destination != null) tupleBounce = new TupleBounce(destination, metrics);
    }
    public Object execute(VirtualFrame frame, Closure initial, Object[] values) { return execute(frame, initial, values, start); }
    public Object execute(VirtualFrame frame, Closure initial, Object[] values, int initialOffset) {
        Metrics metrics = this.metrics != null ? this.metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame);
        Closure function = initial;
        int offset = initialOffset;
        while (true) {
            int remaining = count + start - offset;
            var target = function.target;
            GuestRoot root = ColdCallChecks.guestRoot(target.getRootNode());
            TypedInputLayout input = root.getTypedInput();
            int used = Math.min(function.arity, remaining);
            validateGenericInput(root, function.suppliedCount, source.getLayout(), offset, used, destination, function.arity == remaining, function.arity > remaining);
            if (function.arity > remaining) {
                if (destination != null) throw fault("Aggregate result application is under-saturated");
                if (metrics.getEnabled()) metrics.incrementPapAllocations();
                return input != null ? genericTypedPap(function, input, source, frame, this, values, count + start, offset, remaining) :
                    legacyGenericPap(frame, this, function, source, values, count + start, offset, remaining);
            }
            int[] strict = input != null && capturesInput(this) ? strictInputPositions(root, input) : null;
            boolean prepared = strict != null && strict.length != 0;
            Object[] overrides = null;
            if (prepared) {
                Closure current = function; int currentOffset = offset;
                try { overrides = forceGenericInputCaptured(frame, this, current, input, source, values, count + start, currentOffset, strict, force); }
                catch (AstCapture cut) {
                    throw cut.append((saved, value) -> callPrepared(saved, current, values, currentOffset, input, strict, (Object[]) value, metrics));
                }
            }
            boolean exact = function.arity == remaining, isTail = tail && exact;
            TupleShape resultShape = exact && destination != null ? root.getTupleResult() : null;
            int next = offset + function.arity;
            Object result;
            try {
                Object answer;
                try {
                    if (input == null) answer = legacy.call(frame, target, scalarPacket(frame, this, function, source, values, offset, function.arity, count + start), isTail, resultShape);
                    else {
                        answer = invokeTyped(frame, function, values, offset, input, strict, overrides, prepared, isTail, metrics);
                    }
                } catch (TailCall transfer) {
                    if (isTail || exact && tupleBounce != null) throw transfer;
                    answer = loop.execute(transfer, metrics);
                }
                result = AstControl.captures(this) ? AstControl.complete(this, answer, target,
                    resultShape) : answer;
            } catch (TailCall transfer) {
                if (isTail) throw transfer;
                if (exact && tupleBounce != null) {
                    tupleBounce.execute(frame, transfer); return null;
                }
                throw transfer;
            } catch (AstCapture cut) {
                CompilerDirectives.transferToInterpreter();
                Object[] savedValues = values == null ? null : values.clone();
                throw cut.append((saved, value) -> finish(saved, value, savedValues, exact, next, resultShape));
            } catch (DelimitedCut cut) {
                throw captureCall(frame, cut, values, exact, next, resultShape);
            }
            if (exact) {
                if (destination != null) { destination.consume(frame, this, result, resultShape); return null; }
                return result;
            }
            offset = next;
            try { function = requireClosure(AstControl.force(frame, this, force, result)); }
            catch (AstCapture cut) {
                CompilerDirectives.transferToInterpreter();
                Object[] savedValues = values == null ? null : values.clone();
                throw cut.append((saved, value) -> execute(saved, requireClosure(value), savedValues, next));
            } catch (DelimitedCut cut) {
                throw captureCall(frame, cut, values, false, next, null);
            }
        }
    }
    private Object invokeTyped(VirtualFrame frame, Closure function, Object[] values, int offset,
            TypedInputLayout input, int[] strict, Object[] overrides, boolean prepared, boolean isTail, Metrics metrics) {
        if (metrics.getEnabled()) metrics.incrementIndirectCalls();
        HandoffStorage storage = prepared
            ? packGenericInput(frame, this, function, input, source, values, count + start, offset, function.arity, strict, overrides)
            : prepareGenericInput(frame, this, function, input, source, values, count + start, offset, function.arity, force);
        long generation = storage.getGeneration();
        boolean transferred = false;
        try {
            if (isTail) {
                try { checkTypedTail(frame, this, function.target, storage, input.getPacket(), metrics); }
                catch (TailCall transfer) { transferred = transfer.getInput() == storage; throw transfer; }
            }
            if (metrics.getEnabled()) { var state = input.state(); state.setCalls(state.getCalls() + 1); }
            return coldGeneric ? function.target.call(this, new Object[] {storage}) : Calls.indirect(indirect, function.target, new Object[] {storage});
        } finally { if (!transferred) releaseGenericInput(input, storage, generation); }
    }
    private Object callPrepared(VirtualFrame frame, Closure function, Object[] values, int offset,
            TypedInputLayout input, int[] strict, Object[] overrides, Metrics metrics) {
        boolean exact = function.arity == count + start - offset, isTail = tail && exact;
        TupleShape resultShape = exact && destination != null ? ((GuestRoot) function.target.getRootNode()).getTupleResult() : null;
        int next = offset + function.arity;
        Object result;
        try {
            try {
                result = invokeTyped(frame, function, values, offset, input, strict, overrides, true, isTail, metrics);
            } catch (TailCall transfer) {
                if (isTail || exact && tupleBounce != null) throw transfer;
                result = loop.execute(transfer, metrics);
            }
            result = AstControl.complete(this, result, function.target, resultShape);
        } catch (TailCall transfer) {
            if (isTail) throw transfer;
            if (exact && tupleBounce != null) { tupleBounce.execute(frame, transfer); return null; }
            throw transfer;
        } catch (AstCapture cut) {
            throw cut.append((saved, value) -> finish(saved, value, values, exact, next, resultShape));
        } catch (DelimitedCut cut) {
            throw captureCall(frame, cut, values, exact, next, resultShape);
        }
        return finish(frame, result, values, exact, next, resultShape);
    }
    private Object finish(VirtualFrame frame, Object result, Object[] values, boolean exact, int next, TupleShape resultShape) {
        if (exact) {
            if (destination != null) { destination.consume(frame, this, result, resultShape); return null; }
            return result;
        }
        Closure closure;
        try { closure = requireClosure(AstControl.force(frame, this, force, result)); }
        catch (AstCapture cut) { throw cut.append((saved, value) -> execute(saved, requireClosure(value), values, next)); }
        catch (DelimitedCut cut) { throw captureCall(frame, cut, values, false, next, null); }
        return execute(frame, closure, values, next);
    }
    private DelimitedCut captureCall(VirtualFrame frame, DelimitedCut cut, Object[] values, boolean exact, int next, TupleShape resultShape) {
        if (!DelimitedControl.enabled(this)) return cut;
        if (exact) return destination == null ? cut : DelimitedControl.tupleCut(cut, frame.materialize(), destination, this, resultShape);
        Object[] savedValues = values == null ? null : values.clone();
        return cut.append(frame, new DelimitedPendingApplication() {
            @Override public TupleDestination getDestination() { return destination; }
            @Override public Object resume(MaterializedFrame saved, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
                Object[] arguments = savedValues == null ? null : savedValues.clone();
                Object result;
                try { result = GenericInputCall.this.finish(saved, input.get(), arguments, false, next, null); }
                catch (DelimitedCut nested) {
                    if (destination != null) DelimitedControl.tupleCut(nested, saved, destination, GenericInputCall.this);
                    throw nested;
                }
                return destination == null ? result : destination.delimitedResult(saved, GenericInputCall.this);
            }
        });
    }
}
