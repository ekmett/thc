// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import static thc.runtime.RuntimeServiceStatus.fault;

final class GenericTupleCaller extends Node {
    private final TupleDestination destination;
    private final Metrics metrics;
    private final boolean tail;
    private final int argsSize;
    private final ArgumentLayout inputLayout;
    @Child private IndirectCallNode call = IndirectCallNode.create();
    @Child private IndirectEntryArguments entry;
    @Child private IndirectCallerNode scalar;
    @Child private Force force;
    @Child private TailCheck tailCheck;
    @Child private TupleBounce bounce;
    @Child private GenericInputCall typed;
    GenericTupleCaller(TupleDestination destination, Metrics metrics, boolean tail, int argsSize, ArgumentLayout inputLayout) {
        this.destination = destination; this.metrics = metrics; this.tail = tail; this.argsSize = argsSize; this.inputLayout = inputLayout;
        entry = new IndirectEntryArguments(metrics); scalar = new IndirectCallerNode(metrics); force = new Force(metrics);
        tailCheck = new TailCheck(metrics); bounce = new TupleBounce(destination, metrics);
        typed = new GenericInputCall(new ScalarArrayInputSource(inputLayout), argsSize, tail, metrics, destination, 0);
    }
    void execute(VirtualFrame frame, Closure initial, Object[] arguments) { execute(frame, initial, arguments, 0); }
    void execute(VirtualFrame frame, Closure initial, Object[] arguments, int initialOffset) {
        Closure function = initial;
        int offset = initialOffset;
        while (true) {
            TruffleSafepoint.poll(this);
            int remaining = argsSize - offset;
            if (function.arity > remaining) throw fault("Tuple result application is under-saturated");
            int count = function.arity;
            ArgumentLayout.validate(function, inputLayout, offset, count);
            int physicalOffset = ArgumentLayout.offset(inputLayout, offset);
            int physicalCount = ArgumentLayout.offset(inputLayout, offset + count) - physicalOffset;
            boolean exact = count == remaining;
            if (!(function.target.getRootNode() instanceof GuestRoot root)) throw fault("Invalid tuple call target");
            if (root.getTypedInput() != null) { typed.execute(frame, function, arguments, offset); return; }
            if (exact && !root.hasTupleResult(destination.getShape())) throw fault("Tuple call target result shape mismatch");
            if (!exact && root.getTupleResult() != null) throw fault("Cannot overapply an unboxed tuple");
            int skip = function.environment == null ? 1 : 2;
            Object[] packet = new Object[skip + function.supplied.length + physicalCount];
            if (function.environment != null) packet[1] = function.environment;
            System.arraycopy(function.supplied, 0, packet, skip, function.supplied.length);
            System.arraycopy(arguments, physicalOffset, packet, skip + function.supplied.length, physicalCount);
            if (!exact) {
                if (AstControl.captures(this)) {
                    int next = offset + count;
                    Object result;
                    try { result = scalar.call(frame, function.target, packet, false); }
                    catch (AstCapture cut) {
                        CompilerDirectives.transferToInterpreter();
                        Object[] savedArguments = arguments.clone();
                        throw cut.append(new AstResumeStep() {
                            @Override public Object resume(VirtualFrame resumed, Object input) { return resumeOverapplication(resumed, input, savedArguments, next); }
                        });
                    } catch (DelimitedCut cut) {
                        throw captureOverapplication(frame, cut, arguments, next);
                    }
                    Closure closure;
                    try { closure = Applications.requireClosure(AstControl.force(frame, this, force, result)); }
                    catch (AstCapture cut) {
                        CompilerDirectives.transferToInterpreter();
                        Object[] savedArguments = arguments.clone();
                        throw cut.append(new AstResumeStep() {
                            @Override public Object resume(VirtualFrame resumed, Object input) {
                                execute(resumed, Applications.requireClosure(input), savedArguments, next);
                                return null;
                            }
                        });
                    }
                    function = closure; offset = next;
                    continue;
                }
                Object result;
                if (!DelimitedControl.enabled(this)) result = force.execute(frame, scalar.call(frame, function.target, packet, false));
                else try {
                    Object answer = scalar.call(frame, function.target, packet, false);
                    DelimitedControl.captureBytecode(answer, null);
                    result = force.execute(frame, answer);
                } catch (DelimitedCut cut) {
                    throw captureOverapplication(frame, cut, arguments, offset + count);
                }
                function = Applications.requireClosure(result);
                offset += count;
                continue;
            }
            try {
                entry.execute(frame, function.target, packet);
                if (metrics.getEnabled()) metrics.incrementIndirectCalls();
                if (tail) {
                    tailCheck.check(frame, function.target, packet);
                    destination.consume(frame, this, Calls.indirect(call, function.target, packet), root.getTupleResult());
                } else {
                    packet[0] = 0L;
                    try { destination.consume(frame, this, Calls.indirect(call, function.target, packet), root.getTupleResult()); }
                    catch (TailCall transfer) { bounce.execute(frame, transfer); }
                }
            } catch (DelimitedCut cut) {
                if (!DelimitedControl.enabled(this)) throw cut;
                throw DelimitedControl.tupleCut(cut, frame.materialize(), destination, this, root.getTupleResult());
            }
            return;
        }
    }
    private DelimitedCut captureOverapplication(VirtualFrame frame, DelimitedCut cut, Object[] arguments, int next) {
        CompilerDirectives.transferToInterpreter();
        Object[] savedArguments = arguments.clone();
        return cut.append(frame, new DelimitedPendingApplication() {
            @Override public TupleDestination getDestination() { return destination; }
            @Override public Object resume(MaterializedFrame resumed, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
                try { resumeOverapplication(resumed, input.get(), savedArguments.clone(), next); }
                catch (DelimitedCut nested) {
                    DelimitedControl.tupleCut(nested, resumed, destination, GenericTupleCaller.this);
                    throw nested;
                }
                return destination.delimitedResult(resumed, GenericTupleCaller.this);
            }
        });
    }
    private Object resumeOverapplication(VirtualFrame frame, Object value, Object[] arguments, int next) {
        Closure closure;
        try { closure = Applications.requireClosure(AstControl.force(frame, this, force, value)); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame resumed, Object input) {
                    execute(resumed, Applications.requireClosure(input), arguments, next);
                    return null;
                }
            });
        }
        execute(frame, closure, arguments, next);
        return null;
    }
}
