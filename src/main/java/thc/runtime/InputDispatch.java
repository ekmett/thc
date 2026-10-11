// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import java.util.Arrays;

/** Each bounded cache arm consumes aggregate completion before its result can merge. */
public final class InputDispatch extends Node {
    private final InputSource source;
    private final int count, start;
    private final boolean tail;
    private final Metrics metrics;
    private final TupleDestination destination;
    @Children private volatile InputCallArm[] direct = new InputCallArm[0];
    @Child private GenericInputCall generic;
    @Child private volatile GenericInputCall cold;
    @CompilerDirectives.CompilationFinal private volatile boolean observed;
    @CompilerDirectives.CompilationFinal private boolean megamorphic;
    public InputDispatch(InputSource source, int count, boolean tail, Metrics metrics) { this(source, count, tail, metrics, null, 0); }
    public InputDispatch(InputSource source, int count, boolean tail, Metrics metrics, TupleDestination destination) { this(source, count, tail, metrics, destination, 0); }
    public InputDispatch(InputSource source, int count, boolean tail, Metrics metrics, TupleDestination destination, int start) {
        this.source = source; this.count = count; this.tail = tail; this.metrics = metrics; this.destination = destination; this.start = start;
        generic = new GenericInputCall(source, count, tail, metrics, destination, start, metrics == null);
    }
    /** Prepare immutable cold-call children without observing a guest target. */
    void prepareForAOT() {
        if (metrics != null && cold == null) atomic(() -> {
            if (cold == null) cold = insert(new GenericInputCall(source, count, tail, metrics, destination, start, true));
            return null;
        });
    }
    public Object execute(VirtualFrame frame, Closure function) { return execute(frame, function, null); }
    @ExplodeLoop public Object execute(VirtualFrame frame, Closure function, Object[] values) {
        if (metrics == null) return generic.execute(frame, function, values);
        if (cold != null && !observed && CompilerDirectives.inCompiledCode())
            return cold.execute(frame, function, values);
        for (InputCallArm arm : direct) if (arm.matches(function)) return arm.execute(frame, function, values);
        if (!megamorphic) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            observed = true;
            InputCallArm arm = atomic(() -> {
                for (InputCallArm cached : direct) if (cached.matches(function)) return cached;
                if (megamorphic) return null;
                if (direct.length < 3) {
                    InputCallArm created = insert(new InputCallArm(source, count, tail, metrics, destination, start, function));
                    InputCallArm[] expanded = Arrays.copyOf(direct, direct.length + 1);
                    expanded[direct.length] = created;
                    direct = expanded;
                    return created;
                }
                megamorphic = true;
                return null;
            });
            if (arm != null) return arm.execute(frame, function, values);
        }
        return generic.execute(frame, function, values);
    }
}
