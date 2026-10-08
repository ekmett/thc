// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.DirectCallNode;
import java.util.Arrays;
import static thc.runtime.RuntimeServiceStatus.fault;

final class DirectTupleCaller extends Node {
    private final TupleDestination destination;
    private final int argsSize;
    private final boolean tail;
    private final ArgumentLayout inputLayout;
    private final RootCallTarget target;
    private final int arity, prefixSize, prefixCount;
    private final ArgumentLayout formalLayout;
    private final boolean hasEnvironment;
    private final TupleShape resultShape;
    @Child private EntryArguments entry;
    @Child private DirectCallNode call;
    @Child private TailCheck tailCheck;
    @Child private TupleBounce bounce;
    @Child private DirectCallerNode scalar;
    @Child private TupleDispatch rest;
    @Child private Force force;
    DirectTupleCaller(TupleDestination destination, Metrics metrics, int argsSize, boolean tail, Closure function, ArgumentLayout inputLayout) {
        this.destination = destination; this.argsSize = argsSize; this.tail = tail; this.inputLayout = inputLayout;
        target = function.target; arity = function.arity; prefixSize = function.supplied.length; prefixCount = function.suppliedCount;
        formalLayout = target.getRootNode() instanceof GuestRoot root ? root.getInputLayout() : null;
        hasEnvironment = function.environment != null;
        entry = new EntryArguments(target, metrics, new boolean[0], prefixCount);
        call = DirectCallNode.create(target); tailCheck = new TailCheck(metrics); bounce = new TupleBounce(destination, metrics);
        scalar = arity < argsSize ? new DirectCallerNode(target, metrics, new boolean[0], prefixCount) : null;
        rest = arity < argsSize ? new TupleDispatch(destination, metrics, argsSize - arity, tail, inputLayout == null ? null : inputLayout.suffix(arity)) : null;
        force = new Force(metrics);
        if (metrics.getEnabled()) metrics.incrementDirectCacheMisses();
        if (!(target.getRootNode() instanceof GuestRoot root)) throw fault("Invalid tuple call target");
        resultShape = root.getTupleResult();
        if (arity > argsSize) throw fault("Tuple result application is under-saturated");
        if (arity == argsSize && !root.hasTupleResult(destination.getShape())) throw fault("Tuple call target result shape mismatch");
        if (arity < argsSize && root.getTupleResult() != null) throw fault("Cannot overapply an unboxed tuple");
    }
    boolean matches(Closure function) {
        return function.target == target && function.arity == arity && function.supplied.length == prefixSize &&
            function.suppliedCount == prefixCount && (function.environment != null) == hasEnvironment;
    }
    void execute(VirtualFrame frame, Closure function, Object[] arguments) {
        ArgumentLayout.validate(formalLayout, prefixCount, inputLayout, 0, arity);
        int physicalCount = ArgumentLayout.width(inputLayout, arity);
        int skip = hasEnvironment ? 2 : 1;
        Object[] packet = new Object[skip + prefixSize + physicalCount];
        if (hasEnvironment) packet[1] = function.environment;
        System.arraycopy(function.supplied, 0, packet, skip, prefixSize);
        System.arraycopy(arguments, 0, packet, skip + prefixSize, physicalCount);
        if (arity < argsSize) {
            DirectCallerNode scalarCall = scalar;
            if (scalarCall == null) throw fault("Missing tuple overapplication scalar caller");
            if (AstControl.captures(this)) {
                Object result;
                try { result = scalarCall.call(frame, packet, false); }
                catch (AstCapture cut) {
                    Object[] remaining = Arrays.copyOfRange(arguments, physicalCount, arguments.length);
                    throw cut.append(new AstResumeStep() {
                        @Override public Object resume(VirtualFrame resumed, Object input) { return resumeOverapplication(resumed, input, remaining); }
                    });
                } catch (DelimitedCut cut) {
                    throw captureOverapplication(frame, cut, arguments, physicalCount);
                }
                resumeOverapplication(frame, result, Arrays.copyOfRange(arguments, physicalCount, arguments.length));
                return;
            }
            Object result;
            if (!DelimitedControl.enabled(this)) result = force.execute(frame, scalarCall.call(frame, packet, false));
            else try {
                Object answer = scalarCall.call(frame, packet, false);
                DelimitedControl.captureBytecode(answer, null);
                result = force.execute(frame, answer);
            } catch (DelimitedCut cut) {
                throw captureOverapplication(frame, cut, arguments, physicalCount);
            }
            Closure closure = Applications.requireClosure(result);
            TupleDispatch remainingCall = rest;
            if (remainingCall == null) throw fault("Missing tuple overapplication remainder");
            remainingCall.execute(frame, closure, Arrays.copyOfRange(arguments, physicalCount, arguments.length));
            return;
        }
        try {
            entry.execute(frame, packet);
            if (tail) {
                tailCheck.check(frame, target, packet);
                destination.consume(frame, this, Calls.direct(call, packet), resultShape);
            } else {
                packet[0] = 0L;
                try { destination.consume(frame, this, Calls.direct(call, packet), resultShape); }
                catch (TailCall transfer) { bounce.execute(frame, transfer); }
            }
        } catch (DelimitedCut cut) {
            if (!DelimitedControl.enabled(this)) throw cut;
            throw DelimitedControl.tupleCut(cut, frame.materialize(), destination, this, resultShape);
        }
    }
    private DelimitedCut captureOverapplication(VirtualFrame frame, DelimitedCut cut, Object[] arguments, int physicalCount) {
        Object[] remaining = Arrays.copyOfRange(arguments, physicalCount, arguments.length);
        return cut.append(frame, new DelimitedPendingApplication() {
            @Override public TupleDestination getDestination() { return destination; }
            @Override public Object resume(MaterializedFrame resumed, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
                resumeOverapplication(resumed, input.get(), remaining.clone());
                return destination.delimitedResult(resumed, DirectTupleCaller.this);
            }
        });
    }
    private Object resumeOverapplication(VirtualFrame frame, Object value, Object[] remaining) {
        Closure closure;
        try { closure = Applications.requireClosure(AstControl.force(frame, this, force, value)); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame resumed, Object input) {
                    TupleDispatch remainingCall = rest;
                    if (remainingCall == null) throw fault("Missing tuple overapplication remainder");
                    remainingCall.execute(resumed, Applications.requireClosure(input), remaining);
                    return null;
                }
            });
        }
        TupleDispatch remainingCall = rest;
        if (remainingCall == null) throw fault("Missing tuple overapplication remainder");
        remainingCall.execute(frame, closure, remaining);
        return null;
    }
}
