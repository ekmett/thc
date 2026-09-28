// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import java.util.Arrays;
import static thc.runtime.Applications.appendWithHeader;
import static thc.runtime.Applications.requireClosure;

/** Cadenza exact/PAP/overapplication specializations with fixed site arity. */
@ReportPolymorphism
public abstract class Dispatch extends Node {
    public final int argsSize;
    public final boolean tailCall;
    public final Metrics metrics;
    @CompilerDirectives.CompilationFinal(dimensions = 1) public boolean[] evaluatedArguments = new boolean[0];
    @CompilerDirectives.CompilationFinal public ArgumentLayout argumentLayout;
    @Child private volatile InputDispatch typed;
    protected Dispatch(int argsSize, boolean tailCall, Metrics metrics) { this.argsSize = argsSize; this.tailCall = tailCall; this.metrics = metrics; }
    private Object typed(VirtualFrame frame, Closure function, Object[] arguments) {
        InputDispatch child = typed;
        if (child == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            child = atomic(() -> {
                if (typed == null) typed = insert(new InputDispatch(new ScalarArrayInputSource(argumentLayout), argsSize, tailCall, metrics, null, 0));
                return typed;
            });
        }
        return child.execute(frame, function, arguments);
    }
    public abstract Object execute(VirtualFrame frame, Closure function, Object[] arguments);
    @Specialization(guards = {"function.arity == argsSize", "function.target == cachedTarget"}, limit = "3")
    protected Object direct(VirtualFrame frame, Closure function, Object[] arguments,
            @Cached("function.target") RootCallTarget cachedTarget,
            @Cached("targetInputLayout(cachedTarget)") ArgumentLayout formalLayout,
            @Cached("function.supplied.length") int prefixSize, @Cached("function.suppliedCount") int prefixCount,
            @Cached("function.environment != null") boolean hasEnvironment,
            @Cached("createCaller(cachedTarget, prefixCount)") DirectCallerNode caller) {
        if (cachedTarget.getRootNode() instanceof GuestRoot root && root.getTypedInput() != null) return typed(frame, function, arguments);
        ArgumentLayout.validate(formalLayout, prefixCount, argumentLayout, 0, argsSize);
        Object[] packet = appendWithHeader(hasEnvironment ? 2 : 1, function.supplied, prefixSize, arguments, ArgumentLayout.width(argumentLayout, argsSize));
        if (hasEnvironment) packet[1] = function.environment;
        return caller.call(frame, packet, tailCall);
    }
    @Specialization(guards = {"function.arity < argsSize", "function.arity == arity", "function.target == cachedTarget"}, limit = "3")
    protected Object directOverapplied(VirtualFrame frame, Closure function, Object[] arguments,
            @Cached("function.arity") int arity, @Cached("function.target") RootCallTarget cachedTarget,
            @Cached("targetInputLayout(cachedTarget)") ArgumentLayout formalLayout,
            @Cached("function.supplied.length") int prefixSize, @Cached("function.suppliedCount") int prefixCount,
            @Cached("function.environment != null") boolean hasEnvironment,
            @Cached("createCaller(cachedTarget, prefixCount)") DirectCallerNode caller,
            @Cached("createRemainder(arity)") Dispatch rest, @Cached("createForce()") Force force) {
        if (cachedTarget.getRootNode() instanceof GuestRoot root && root.getTypedInput() != null) return typed(frame, function, arguments);
        ArgumentLayout.validate(formalLayout, prefixCount, argumentLayout, 0, arity);
        Object[] packet = appendWithHeader(hasEnvironment ? 2 : 1, function.supplied, prefixSize, arguments, ArgumentLayout.width(argumentLayout, arity));
        if (hasEnvironment) packet[1] = function.environment;
        if (AstControl.captures(this)) {
            Object result;
            try { result = caller.call(frame, packet, false); }
            catch (AstCapture cut) {
                Object[] remaining = Arrays.copyOfRange(arguments, ArgumentLayout.offset(argumentLayout, arity), arguments.length);
                throw cut.append((saved, input) -> resumeOverapplication(saved, input, remaining, rest, force));
            }
            Object[] remaining = Arrays.copyOfRange(arguments, ArgumentLayout.offset(argumentLayout, arity), arguments.length);
            return resumeOverapplication(frame, result, remaining, rest, force);
        }
        Object result;
        if (!DelimitedControl.INSTANCE.enabled(this)) result = force.execute(frame, caller.call(frame, packet, false));
        else {
            try {
                Object answer = caller.call(frame, packet, false);
                DelimitedControl.captureBytecode(answer, null);
                result = force.execute(frame, answer);
            } catch (DelimitedCut cut) {
                Object[] remaining = Arrays.copyOfRange(arguments, ArgumentLayout.offset(argumentLayout, arity), arguments.length);
                throw cut.append(frame, new DelimitedStep() {
                    @Override public Object resume(MaterializedFrame saved, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
                        Object answer = rest.execute(saved, requireClosure(force.execute(saved, input.get())), remaining.clone());
                        DelimitedControl.captureBytecode(answer, null);
                        return answer;
                    }
                    @Override public Object finish(Object result, DelimitedActionSite site) { return result; }
                });
            }
        }
        return rest.execute(frame, requireClosure(result), Arrays.copyOfRange(arguments, ArgumentLayout.offset(argumentLayout, arity), arguments.length));
    }
    private Object resumeOverapplication(VirtualFrame frame, Object value, Object[] remaining, Dispatch rest, Force force) {
        Object result;
        try { result = AstControl.force(frame, this, force, value); }
        catch (AstCapture cut) { throw cut.append((saved, input) -> rest.execute(saved, requireClosure(input), remaining)); }
        return rest.execute(frame, requireClosure(result), remaining);
    }
    @Specialization(guards = "function.arity > argsSize")
    protected Object underapplied(VirtualFrame frame, Closure function, Object[] arguments) {
        if (function.target.getRootNode() instanceof GuestRoot root && root.getTypedInput() != null) return typed(frame, function, arguments);
        if (metrics.getEnabled()) metrics.incrementPapAllocations();
        ArgumentLayout.validate(function, argumentLayout, 0, argsSize);
        return function.papCompact(arguments, 0, arguments.length, argsSize);
    }
    @Specialization(guards = "function.arity == argsSize", replaces = "direct")
    protected Object indirect(VirtualFrame frame, Closure function, Object[] arguments,
            @Cached(value = "createIndirectCaller()", neverDefault = true) IndirectCallerNode caller) {
        if (function.target.getRootNode() instanceof GuestRoot root && root.getTypedInput() != null) return typed(frame, function, arguments);
        boolean hasEnvironment = function.environment != null;
        ArgumentLayout.validate(function, argumentLayout, 0, Math.min(argsSize, function.arity));
        Object[] packet = appendWithHeader(hasEnvironment ? 2 : 1, function.supplied, function.supplied.length, arguments, ArgumentLayout.width(argumentLayout, argsSize));
        if (hasEnvironment) packet[1] = function.environment;
        return caller.call(frame, function.target, packet, tailCall);
    }
    @Specialization(guards = "function.arity < argsSize", replaces = "directOverapplied")
    protected Object indirectOverapplied(VirtualFrame frame, Closure function, Object[] arguments, @Bind Node node,
            @Cached(inline = true) GenericDispatch generic) {
        return generic.execute(frame, node, function, arguments, argsSize, argumentLayout, tailCall, metrics, 0);
    }
    public ArgumentLayout targetInputLayout(RootCallTarget target) { return target.getRootNode() instanceof GuestRoot root ? root.getInputLayout() : null; }
    public DirectCallerNode createCaller(RootCallTarget target, int prefixSize) { return new DirectCallerNode(target, metrics, evaluatedArguments, prefixSize); }
    public IndirectCallerNode createIndirectCaller() { return IndirectCallerNode.create(metrics); }
    public Dispatch createRemainder(int arity) {
        return create(argsSize - arity, tailCall, metrics,
            evaluatedArguments.length == 0 ? evaluatedArguments : Arrays.copyOfRange(evaluatedArguments, arity, evaluatedArguments.length),
            argumentLayout == null ? null : argumentLayout.suffix(arity));
    }
    public Force createForce() { return new Force(metrics); }
    public static Dispatch create(int argsSize, boolean tailCall, Metrics metrics) { return create(argsSize, tailCall, metrics, new boolean[0], null); }
    public static Dispatch create(int argsSize, boolean tailCall, Metrics metrics, boolean[] evaluated) { return create(argsSize, tailCall, metrics, evaluated, null); }
    public static Dispatch create(int argsSize, boolean tailCall, Metrics metrics, boolean[] evaluated, ArgumentLayout layout) {
        if (evaluated.length != 0 && evaluated.length != argsSize) throw new IllegalArgumentException("Failed requirement.");
        Dispatch dispatch = DispatchNodeGen.create(argsSize, tailCall, metrics);
        dispatch.evaluatedArguments = evaluated.clone(); dispatch.argumentLayout = layout;
        return dispatch;
    }
}
