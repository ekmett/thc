// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.util.Arrays;
import java.util.concurrent.Callable;

/** Cache the complete call shape before making its packet. Each arm consumes
 * its result before joining control flow, keeping virtual carriers out of PIC phis. */
public final class TupleDispatch extends Node {
    private final TupleDestination destination;
    private final Metrics metrics;
    private final int argsSize;
    private final boolean tail;
    private final ArgumentLayout inputLayout;
    @Children private volatile DirectTupleCaller[] direct = new DirectTupleCaller[0];
    @Child private GenericTupleCaller generic;
    @Child private volatile GenericInputCall cold;
    @CompilerDirectives.CompilationFinal private volatile boolean observed;
    @CompilationFinal private boolean megamorphic;
    @Child private volatile InputDispatch typed;
    public TupleDispatch(TupleDestination destination, Metrics metrics, int argsSize, boolean tail) {
        this(destination, metrics, argsSize, tail, null);
    }
    public TupleDispatch(TupleDestination destination, Metrics metrics, int argsSize, boolean tail, ArgumentLayout inputLayout) {
        this.destination = destination; this.metrics = metrics; this.argsSize = argsSize;
        this.tail = tail; this.inputLayout = inputLayout;
        if (metrics == null) typed = new InputDispatch(new ScalarArrayInputSource(inputLayout), argsSize, tail, null, destination);
        else generic = new GenericTupleCaller(destination, metrics, tail, argsSize, inputLayout);
    }
    /** Prepare immutable cold-call children without observing a guest target. */
    void prepareForAOT() {
        if (metrics != null && cold == null) atomic(() -> {
            if (cold == null) cold = insert(new GenericInputCall(new ScalarArrayInputSource(inputLayout), argsSize, tail, metrics, destination, 0, true));
            return null;
        });
    }
    public void execute(VirtualFrame frame, Closure function, Object[] arguments) {
        try { executeCall(frame, function, arguments); }
        catch (DelimitedCut cut) {
            if (!DelimitedControl.enabled(this)) throw cut;
            CompilerDirectives.transferToInterpreter();
            throw DelimitedControl.tupleCut(cut, frame.materialize(), destination, this);
        }
    }
    @ExplodeLoop private void executeCall(VirtualFrame frame, Closure function, Object[] arguments) {
        if (metrics == null) { typed.execute(frame, function, arguments); return; }
        if (cold != null && !observed && CompilerDirectives.inCompiledCode()) {
            cold.execute(frame, function, arguments); return;
        }
        if (function.target.getRootNode() instanceof GuestRoot root && root.getTypedInput() != null) {
            InputDispatch child = typed;
            if (child == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                observed = true;
                child = atomic((Callable<InputDispatch>) () -> {
                    if (typed == null) typed = insert(new InputDispatch(new ScalarArrayInputSource(inputLayout), argsSize, tail, metrics, destination));
                    return typed;
                });
            }
            child.execute(frame, function, arguments);
            return;
        }
        for (DirectTupleCaller caller : direct) if (caller.matches(function)) {
            caller.execute(frame, function, arguments); return;
        }
        if (!megamorphic) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            observed = true;
            DirectTupleCaller caller = atomic((Callable<DirectTupleCaller>) () -> {
                for (DirectTupleCaller existing : direct) if (existing.matches(function)) return existing;
                if (megamorphic) return null;
                if (direct.length < 3) {
                    DirectTupleCaller added = insert(new DirectTupleCaller(destination, metrics, argsSize, tail, function, inputLayout));
                    DirectTupleCaller[] updated = Arrays.copyOf(direct, direct.length + 1);
                    updated[direct.length] = added;
                    direct = updated;
                    return added;
                }
                megamorphic = true;
                return null;
            });
            if (caller != null) { caller.execute(frame, function, arguments); return; }
        }
        generic.execute(frame, function, arguments);
    }
}
