// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.RuntimeServiceStatus.fault;

/** A typed operand owner has already saved the callee and every scalar input. */
final class ScalarTupleTailCall extends Expr {
    private final AstSelfLayout layout;
    private final int functionSlot;
    @CompilationFinal(dimensions = 1) private final int[] arguments;
    private final TupleShape shape;
    private final Metrics metrics;
    @Child private AstSelfTarget selfTarget = new AstSelfTarget();
    @Child private volatile TupleDispatch fallback;
    @CompilationFinal(dimensions = 1) private int[] destination;
    @CompilationFinal private int destinationOffset;

    ScalarTupleTailCall(AstSelfLayout layout, int functionSlot, int[] arguments, TupleShape shape, Metrics metrics) {
        this.layout = layout; this.functionSlot = functionSlot; this.arguments = arguments;
        this.shape = shape; this.metrics = metrics;
        CoreRepresentation proof = shape.getProof();
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    /** Validate a resumed callee before the operand owner can evaluate its suffix. */
    static final class Callee extends Expr {
        @Child private Evaluate value;
        Callee(Expr value, Metrics metrics) {
            this.value = new Evaluate(value, metrics);
            setRepresentation(new CoreRepresentation(CoreKind.CLOSURE, true, true, null, null, null, null, null, null));
        }
        @Override public boolean needsCallState() { return value.needsCallState(); }
        @Override public Object execute(VirtualFrame frame) { return executeClosure(frame); }
        @Override public Closure executeClosure(VirtualFrame frame) {
            try { return value.executeRequiredClosure(frame); }
            catch (AstCapture cut) { throw cut.append((saved, input) -> Applications.requireClosure(input)); }
            catch (DelimitedCut cut) {
                throw cut.append(frame, (saved, input, ambient, outer) -> Applications.requireClosure(input.get()));
            }
        }
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("Tuple value requires a destination"); }
    @ExplodeLoop @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Closure function = Applications.requireClosure(FrameAccess.read(frame, functionSlot));
        try {
            if (getRootNode() instanceof FunctionRoot root && root.getRole$org_intelligence_thc() == FunctionRootRole.FUNCTION &&
                function.arity == arguments.length && function.suppliedCount == 0 && function.supplied.length == 0 &&
                selfTarget.matches(function.target))
                throw layout.transfer(frame, function, arguments);
            Object[] values = boxedArguments(frame);
            TupleDispatch dispatch = fallback;
            if (dispatch == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                dispatch = atomic(() -> {
                    if (fallback == null) {
                        destination = slots; destinationOffset = offset;
                        fallback = insert(new TupleDispatch(new AstTupleDestination(shape, slots, offset), metrics,
                            arguments.length, true, null));
                    }
                    return fallback;
                });
            }
            if (destination != slots || destinationOffset != offset) throw new IllegalStateException("Conflicting typed destination");
            dispatch.execute(frame, function, values);
            return null;
        } finally {
            // A delimited transfer can skip the surrounding owner's plain cleanup
            // suffix. Preparation is complete; fallback packets own their values.
            frame.clear(functionSlot);
            for (int slot : arguments) frame.clear(slot);
        }
    }
    @ExplodeLoop private Object[] boxedArguments(VirtualFrame frame) {
        Object[] values = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) values[i] = FrameAccess.read(frame, arguments[i]);
        return values;
    }
}
