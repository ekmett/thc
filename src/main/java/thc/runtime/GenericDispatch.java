// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;
import static thc.runtime.Applications.requireClosure;

/** Saturated megamorphic overapplication consumes arguments in a loop. */
@GenerateInline
public abstract class GenericDispatch extends Node {
    public abstract Object execute(VirtualFrame frame, Node inliningTarget, Closure function, Object[] arguments,
        int logicalCount, ArgumentLayout layout, boolean tailCall, Metrics metrics, int initialOffset);
    @Specialization
    public static Object apply(VirtualFrame frame, Node node, Closure initial, Object[] arguments,
            int logicalCount, ArgumentLayout layout, boolean tailCall, Metrics metrics, int initialOffset,
            @Cached(value = "createCaller(metrics)", neverDefault = true) IndirectCallerNode caller,
            @Cached(value = "createForce(metrics)", neverDefault = true) Force force,
            @Cached(value = "createTyped(layout, logicalCount, tailCall, metrics)", neverDefault = true) GenericInputCall typed,
            @Cached InlinedConditionProfile underapplied, @Cached InlinedConditionProfile exact) {
        Closure function = initial;
        int offset = initialOffset;
        while (true) {
            TruffleSafepoint.poll(node);
            int remaining = logicalCount - offset;
            int physicalOffset = ArgumentLayout.offset(layout, offset);
            ArgumentLayout.validate(function, layout, offset, Math.min(function.arity, remaining));
            if (function.target.getRootNode() instanceof GuestRoot root && root.getTypedInput() != null)
                return typed.execute(frame, function, arguments, offset);
            if (underapplied.profile(node, function.arity > remaining)) {
                if (metrics.getEnabled()) metrics.incrementPapAllocations();
                return function.papCompact(arguments, physicalOffset, arguments.length - physicalOffset, remaining);
            }
            int count = function.arity;
            int physicalCount = ArgumentLayout.offset(layout, offset + count) - physicalOffset;
            boolean hasEnvironment = function.environment != null;
            int skip = hasEnvironment ? 2 : 1;
            Object[] packet = new Object[skip + function.supplied.length + physicalCount];
            if (hasEnvironment) packet[1] = function.environment;
            System.arraycopy(function.supplied, 0, packet, skip, function.supplied.length);
            System.arraycopy(arguments, physicalOffset, packet, skip + function.supplied.length, physicalCount);
            if (exact.profile(node, count == remaining)) return caller.call(frame, function.target, packet, tailCall);
            if (AstControl.captures(node)) {
                int next = offset + count;
                Object result;
                try { result = caller.call(frame, function.target, packet, false); }
                catch (AstCapture cut) {
                    CompilerDirectives.transferToInterpreter();
                    Object[] savedArguments = arguments.clone();
                    throw cut.append((saved, input) -> resumeOverapplication(saved, node, input, savedArguments,
                        logicalCount, layout, tailCall, metrics, next, caller, force, typed, underapplied, exact));
                }
                Object forced;
                try { forced = AstControl.force(frame, node, force, result); }
                catch (AstCapture cut) {
                    CompilerDirectives.transferToInterpreter();
                    Object[] savedArguments = arguments.clone();
                    throw cut.append((saved, input) -> apply(saved, node, requireClosure(input), savedArguments,
                        logicalCount, layout, tailCall, metrics, next, caller, force, typed, underapplied, exact));
                }
                function = requireClosure(forced); offset = next;
                continue;
            }
            Object result;
            if (!DelimitedControl.INSTANCE.enabled(node)) result = caller.call(frame, function.target, packet, false);
            else {
                try {
                    result = caller.call(frame, function.target, packet, false);
                    DelimitedControl.captureBytecode(result, null);
                } catch (DelimitedCut cut) {
                    CompilerDirectives.transferToInterpreter();
                    Object[] remainingArguments = arguments.clone();
                    int next = offset + count;
                    throw cut.append(frame, new DelimitedStep() {
                        @Override public Object resume(MaterializedFrame saved, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
                            Object answer = apply(saved, node, requireClosure(force.execute(saved, input.get())), remainingArguments.clone(),
                                logicalCount, layout, tailCall, metrics, next, caller, force, typed, underapplied, exact);
                            DelimitedControl.captureBytecode(answer, null);
                            return answer;
                        }
                        @Override public Object finish(Object result, DelimitedActionSite site) { return result; }
                    });
                }
            }
            offset += count;
            function = requireClosure(force.execute(frame, result));
        }
    }
    private static Object resumeOverapplication(VirtualFrame frame, Node node, Object value, Object[] arguments,
            int logicalCount, ArgumentLayout layout, boolean tailCall, Metrics metrics, int next,
            IndirectCallerNode caller, Force force, GenericInputCall typed, InlinedConditionProfile underapplied, InlinedConditionProfile exact) {
        Object result;
        try { result = AstControl.force(frame, node, force, value); }
        catch (AstCapture cut) {
            throw cut.append((saved, input) -> apply(saved, node, requireClosure(input), arguments,
                logicalCount, layout, tailCall, metrics, next, caller, force, typed, underapplied, exact));
        }
        return apply(frame, node, requireClosure(result), arguments, logicalCount, layout, tailCall, metrics,
            next, caller, force, typed, underapplied, exact);
    }
    public static IndirectCallerNode createCaller(Metrics metrics) { return IndirectCallerNode.create(metrics); }
    public static Force createForce(Metrics metrics) { return new Force(metrics); }
    public static GenericInputCall createTyped(ArgumentLayout layout, int count, boolean tail, Metrics metrics) {
        return new GenericInputCall(new ScalarArrayInputSource(layout), count, tail, metrics, null, 0);
    }
}
