// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Evaluate scalar inputs once before changing the owning frame on a parallel self transfer. */
final class ScalarTupleTailCall extends Expr {
    private final AstSelfLayout layout;
    @Child private Evaluate callee;
    @Children private Expr[] inputs;
    @CompilationFinal(dimensions = 1) private final int[] temporaries;
    private final TupleShape shape;
    private final Metrics metrics;
    @Child private AstSelfTarget selfTarget = new AstSelfTarget();
    @Child private volatile TupleDispatch fallback;
    @CompilationFinal(dimensions = 1) private int[] destination;
    @CompilationFinal private int destinationOffset;

    ScalarTupleTailCall(AstSelfLayout layout, Expr function, Expr[] inputs, FrameLayout frame, TupleShape shape, Metrics metrics) {
        this.layout = layout;
        this.callee = new Evaluate(function, metrics);
        this.inputs = inputs;
        this.temporaries = new int[inputs.length];
        for (int i = 0; i < inputs.length; i++)
            temporaries[i] = frame.bind("<tuple self argument " + i + ">", FrameSlotKind.Long);
        frame.clearInitially(temporaries);
        this.shape = shape;
        this.metrics = metrics;
        CoreRepresentation proof = shape.getProof();
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) { throw fault("Tuple value requires a destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Closure function;
        try { function = callee.executeRequiredClosure(frame); }
        catch (AstCapture cut) {
            CompilerDirectives.transferToInterpreter();
            throw cut.append((saved, input) -> afterCallee(saved, Applications.requireClosure(input),
                0, slots, offset));
        } catch (DelimitedCut cut) {
            CompilerDirectives.transferToInterpreter();
            throw cut.append(frame, (saved, input, ambient, outer) -> afterCallee(saved,
                Applications.requireClosure(input.get()), 0, slots, offset));
        }
        return afterCallee(frame, function, 0, slots, offset);
    }

    @ExplodeLoop private Object afterCallee(VirtualFrame frame, Closure function, int start, int[] slots, int offset) {
        try {
            for (int i = start; i < inputs.length; i++) {
                // While this application owns these slots, both the ordinary and saved
                // writers install primitive tags, even if a sibling widened the descriptor.
                try { frame.setLong(temporaries[i], inputs[i].executeRequiredLong(frame)); }
                catch (AstCapture cut) {
                    CompilerDirectives.transferToInterpreter();
                    long[] prefix = savePrefix(frame, i);
                    throw cut.append((saved, input) -> resumeInput(saved, function, prefix, input, slots, offset));
                } catch (DelimitedCut cut) {
                    CompilerDirectives.transferToInterpreter();
                    long[] prefix = savePrefix(frame, i);
                    throw cut.append(frame, (saved, input, ambient, outer) ->
                        resumeInput(saved, function, prefix, input.get(), slots, offset));
                }
            }
            if (getRootNode() instanceof FunctionRoot root && root.getRole$org_intelligence_thc() == FunctionRootRole.FUNCTION &&
                function.arity == inputs.length && function.suppliedCount == 0 && function.supplied.length == 0 &&
                selfTarget.matches(function.target)) throw layout.transferLongArguments(frame, function, temporaries);
            TupleDispatch dispatch = fallback(slots, offset);
            Object[] boxed = new Object[inputs.length];
            for (int i = 0; i < inputs.length; i++) boxed[i] = frame.getLong(temporaries[i]);
            dispatch.execute(frame, function, boxed);
            return null;
        } finally {
            for (int slot : temporaries) frame.clear(slot);
        }
    }
    private long[] savePrefix(VirtualFrame frame, int count) {
        long[] prefix = new long[count];
        for (int i = 0; i < count; i++) prefix[i] = frame.getLong(temporaries[i]);
        return prefix;
    }
    private Object resumeInput(VirtualFrame frame, Closure function, long[] prefix, Object input, int[] slots, int offset) {
        if (!(input instanceof Long number)) throw fault("Expected primitive Long argument");
        // Each resumption reinstalls the immutable prefix in its own activation.
        for (int i = 0; i < prefix.length; i++) frame.setLong(temporaries[i], prefix[i]);
        frame.setLong(temporaries[prefix.length], number);
        return afterCallee(frame, function, prefix.length + 1, slots, offset);
    }
    private TupleDispatch fallback(int[] slots, int offset) {
        TupleDispatch dispatch = fallback;
        if (dispatch == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            dispatch = atomic(() -> {
                if (fallback == null) {
                    destination = slots; destinationOffset = offset;
                    fallback = insert(new TupleDispatch(new AstTupleDestination(shape, slots, offset), metrics,
                        inputs.length, true, null));
                }
                return fallback;
            });
        }
        if (destination != slots || destinationOffset != offset) throw new IllegalStateException("Conflicting typed destination");
        return dispatch;
    }
}
