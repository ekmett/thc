// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
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
    @Child private Dispatch adaptive;
    @Child private IndirectCallerNode caller;
    @Child private Force force;
    @Child private GenericInputCall typed;
    @CompilerDirectives.CompilationFinal private volatile boolean observed;

    public PreparedDispatch(int count, boolean tail, Metrics metrics, boolean[] evaluated,
                            ArgumentLayout layout) {
        this.count = count;
        this.tail = tail;
        this.metrics = metrics;
        this.layout = layout;
        adaptive = Dispatch.Companion.create(count, tail, metrics, evaluated, layout);
        caller = new IndirectCallerNode(metrics, true);
        force = GenericDispatch.createForce(metrics);
        typed = new GenericInputCall(new ScalarArrayInputSource(layout), count, tail, metrics, null, 0, true);
    }

    public Object execute(VirtualFrame frame, Closure function, Object[] arguments) {
        if (!observed && CompilerDirectives.inCompiledCode()) {
            return GenericDispatch.apply(frame, this, function, arguments, count, layout, tail, metrics, 0,
                caller, force, typed, InlinedConditionProfile.getUncached(), InlinedConditionProfile.getUncached());
        }
        if (!observed) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            observed = true;
        }
        return adaptive.execute(frame, function, arguments);
    }
}
