// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;

/**
 * Immutable call-site setup is independent of observing a function target.
 * An untouched compiled site shares the existing generic application algorithm;
 * ordinary interpreter calls still populate the unchanged adaptive target PIC.
 */
public final class PreparedDispatch extends Node {
    private final int count;
    private final boolean tail;
    private final Metrics metrics;
    private final ArgumentLayout layout;
    private final Closure coldApplication;
    @Child private DirectCallNode coldCall;
    @Child private Dispatch adaptive;
    @Child private IndirectCallerNode caller;
    @Child private Force force;
    @Child private GenericInputCall typed;
    @CompilerDirectives.CompilationFinal private volatile boolean observed;

    public PreparedDispatch(int count, boolean tail, Metrics metrics, boolean[] evaluated,
                            ArgumentLayout layout) {
        this(count, tail, metrics, evaluated, layout, null);
    }
    public PreparedDispatch(int count, boolean tail, Metrics metrics, boolean[] evaluated,
                            ArgumentLayout layout, RootCallTarget coldTarget) {
        coldApplication = coldTarget == null ? null : new Closure(null, 2, coldTarget);
        coldCall = coldTarget == null ? null : DirectCallNode.create(coldTarget);
        this.count = count;
        this.tail = tail;
        this.metrics = metrics;
        this.layout = layout;
        adaptive = Dispatch.create(count, tail, metrics, evaluated, layout);
        caller = new IndirectCallerNode(metrics, true);
        force = GenericDispatch.createForce(metrics);
        typed = new GenericInputCall(new ScalarArrayInputSource(layout), count, tail, metrics, null, 0, true);
    }

    public Object execute(VirtualFrame frame, Closure function, Object[] arguments) {
        boolean cold = metrics == null || !observed && CompilerDirectives.inCompiledCode();
        // Overapplication owns a live suffix even when an earlier exact call has
        // populated the interpreter PIC. The shared root retains that suffix
        // across every prefix capture; exact calls and PAPs keep their target PIC.
        if (coldCall != null && (cold || function.arity < count)) {
            GuestRoot caller = (GuestRoot) getRootNode();
            MaskingState callerMask = SynchronousMasking.current(this);
            Object answer = Calls.direct(coldCall, new Object[]{caller.bloom(frame), function, arguments});
            if (answer instanceof TailCall transfer) throw transfer;
            // A tail continuation has no bytecode suffix to checkpoint. Retain
            // the actual helper target through the existing tail-result carrier.
            if (tail) return BytecodeRoot.tailResult(answer, coldApplication, 2, true);
            return BytecodeRoot.CaptureApplicationResult.capture(2, coldApplication, answer, callerMask, this);
        }
        if (cold) {
            Metrics invocation = metrics != null ? metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame);
            return GenericDispatch.apply(frame, this, function, arguments, count, layout, tail, invocation, 0,
                caller, force, typed, InlinedConditionProfile.getUncached(), InlinedConditionProfile.getUncached());
        }
        if (!observed) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            observed = true;
        }
        // A prepared bytecode call owns completion on every dispatch route.
        // The adaptive route's authority is the original callee; the generic
        // route above completes against the shared application's actual target.
        MaskingState callerMask = coldApplication != null && !tail ? SynchronousMasking.current(this) : null;
        Object answer = adaptive.execute(frame, function, arguments);
        return callerMask == null ? answer
            : BytecodeRoot.CaptureApplicationResult.capture(count, function, answer, callerMask, this);
    }
    static RootCallTarget prepareApplication(TruffleLanguage<?> language, int count, boolean tail,
            Metrics metrics, ArgumentLayout layout, boolean async, boolean eagerPolls, boolean delimited,
            ForeignExceptionBridge foreignBridge) {
        FunctionRoot root = FunctionRoot.application(language, "generic application/" + count + (tail ? " tail" : ""),
            new ApplicationBody(count, tail, metrics, layout), metrics, async, delimited);
        root.configureEagerAsyncPolls(eagerPolls);
        root.configureForeignExceptionBridge(foreignBridge);
        return root.getCallTarget();
    }
    private static final class ApplicationBody extends Expr {
        private final int count;
        private final boolean tail;
        private final Metrics metrics;
        private final ArgumentLayout layout;
        @Child private IndirectCallerNode caller;
        @Child private Force force;
        @Child private GenericInputCall typed;
        ApplicationBody(int count, boolean tail, Metrics metrics, ArgumentLayout layout) {
            this.count = count; this.tail = tail; this.metrics = metrics; this.layout = layout;
            caller = new IndirectCallerNode(metrics, true);
            force = GenericDispatch.createForce(metrics);
            typed = new GenericInputCall(new ScalarArrayInputSource(layout), count, tail, metrics, null, 0, true);
        }
        @Override public Object execute(VirtualFrame frame) {
            return GenericDispatch.apply(frame, this, (Closure) frame.getObject(FrameLayout.TAIL_FUNCTION),
                (Object[]) frame.getObject(FrameLayout.TAIL_ARGUMENTS), count, layout, tail, metrics, 0,
                caller, force, typed, InlinedConditionProfile.getUncached(), InlinedConditionProfile.getUncached());
        }
    }
}
