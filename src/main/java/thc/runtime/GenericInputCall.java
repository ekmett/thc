// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.ApplicationKt.requireClosure;
import static thc.runtime.TypedInputsKt.*;
import static thc.runtime.GenericTypedInputsKt.*;

public final class GenericInputCall extends Node {
    private final InputSource source;
    private final int count, start;
    private final boolean tail;
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
        legacy = new IndirectCallerNode(metrics, coldGeneric); force = new Force(metrics); loop = new TailCallLoop(metrics);
        if (destination != null) tupleBounce = new TupleBounce(destination, metrics);
    }
    public Object execute(VirtualFrame frame, Closure initial, Object[] values) { return execute(frame, initial, values, start); }
    public Object execute(VirtualFrame frame, Closure initial, Object[] values, int initialOffset) {
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
            boolean exact = function.arity == remaining, isTail = tail && exact;
            int next = offset + function.arity;
            Object result;
            try {
                Object answer;
                if (input == null) answer = legacy.call(frame, target, scalarPacket(frame, this, function, source, values, offset, function.arity, count + start), isTail);
                else {
                    if (metrics.getEnabled()) metrics.incrementIndirectCalls();
                    try {
                        HandoffStorage storage = prepareGenericInput(frame, this, function, input, source, values, count + start, offset, function.arity, force);
                        long generation = storage.getGeneration();
                        boolean transferred = false;
                        try {
                            if (isTail) {
                                try { checkTypedTail(frame, this, target, storage, input.getPacket(), metrics); }
                                catch (TailCall transfer) { transferred = transfer.getInput() == storage; throw transfer; }
                            }
                            if (metrics.getEnabled()) { var state = input.state(); state.setCalls(state.getCalls() + 1); }
                            answer = Calls.indirect(indirect, target, new Object[] {storage});
                        } finally { if (!transferred) releaseGenericInput(input, storage, generation); }
                    } catch (TailCall transfer) {
                        if (isTail) throw transfer;
                        if (exact && tupleBounce != null && !AstControl.captures(this)) {
                            tupleBounce.execute(frame, transfer); return null;
                        }
                        answer = loop.execute(transfer);
                    }
                }
                result = AstControl.captures(this) ? AstControl.complete(this, answer, target,
                    exact && destination != null ? destination.getShape() : null) : answer;
            } catch (AstCapture cut) {
                CompilerDirectives.transferToInterpreter();
                Object[] savedValues = values == null ? null : values.clone();
                throw cut.append((saved, value) -> finish(saved, value, savedValues, exact, next));
            }
            if (exact) {
                if (destination != null) { destination.consume(frame, this, result); return null; }
                return result;
            }
            offset = next;
            try { function = requireClosure(AstControl.force(frame, this, force, result)); }
            catch (AstCapture cut) {
                CompilerDirectives.transferToInterpreter();
                Object[] savedValues = values == null ? null : values.clone();
                throw cut.append((saved, value) -> execute(saved, requireClosure(value), savedValues, next));
            }
        }
    }
    private Object finish(VirtualFrame frame, Object result, Object[] values, boolean exact, int next) {
        if (exact) {
            if (destination != null) { destination.consume(frame, this, result); return null; }
            return result;
        }
        Closure closure;
        try { closure = requireClosure(AstControl.force(frame, this, force, result)); }
        catch (AstCapture cut) { throw cut.append((saved, value) -> execute(saved, requireClosure(value), values, next)); }
        return execute(frame, closure, values, next);
    }
}
