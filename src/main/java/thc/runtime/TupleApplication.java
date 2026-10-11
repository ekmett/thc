// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class TupleApplication extends Expr {
    private final Language language;
    private final TupleShape shape;
    @Child private Evaluate function;
    @Children private Expr[] arguments;
    private final boolean tail;
    private final Metrics metrics;
    @CompilationFinal(dimensions = 1) private final int[] vectorSlots;
    private final ArgumentLayout inputLayout;
    @Child private volatile TupleDispatch dispatch;
    @CompilationFinal(dimensions = 1) private int[] destinationSlots;
    @CompilationFinal private int destinationOffset = -1;
    private final VectorLayout vector;
    public TupleApplication(Language language, TupleShape shape, Expr function, Expr[] arguments, boolean tail, Metrics metrics) {
        this(language, shape, function, arguments, tail, metrics, null);
    }
    public TupleApplication(Language language, TupleShape shape, Expr function, Expr[] arguments, boolean tail, Metrics metrics, int[] vectorSlots) {
        this.language = language; this.shape = shape; this.function = new Evaluate(function, metrics);
        this.arguments = arguments; this.tail = tail; this.metrics = metrics; this.vectorSlots = vectorSlots;
        ArrayList<CoreRepresentation> proofs = new ArrayList<>(arguments.length);
        for (Expr argument : arguments) {
            proofs.add(argument.getRepresentation());
            if (argument.getRepresentation().isEmptyTuple()) argument.prepareTuple(ArgumentLayout.EMPTY_TUPLE_SLOTS, 0);
        }
        inputLayout = ArgumentLayout.fromProofs(proofs);
        vector = shape.getProof().isVector() ? new VectorLayout(shape.getProof()) : null;
        if (vector != null && (vectorSlots == null || vectorSlots.length != vector.getWidth())) throw new IllegalArgumentException("Failed requirement.");
        if (inputLayout != null && inputLayout.getRequiresTyped()) throw new IllegalArgumentException("Failed requirement.");
        if (vector != null) prepareTuple(vectorSlots, 0);
        CoreRepresentation p = shape.getProof();
        setRepresentation(p.copy(p.getKind(), true, p.getPresent(), p.getPrimReps(), p.getComponents(), p.getVector(), p.getAlternatives(), p.getTagSlot(), p.getAlternativeSlots()));
    }
    @Override public void prepareTuple(int[] slots, int offset) {
        if (vector != null) { slots = vectorSlots; offset = 0; }
        if (destinationSlots != null) {
            if (destinationSlots != slots || destinationOffset != offset) throw new IllegalStateException("Conflicting typed destination");
            return;
        }
        destinationSlots = slots; destinationOffset = offset;
        if (metrics == null) dispatch = new TupleDispatch(new AstTupleDestination(shape, slots, offset), null, arguments.length, tail, inputLayout);
    }
    /** Adopt only the aggregate destination already determined during lowering. */
    void prepareForAOT() {
        if (dispatch == null && destinationSlots != null) atomic(() -> {
            if (dispatch == null) dispatch = insert(new TupleDispatch(new AstTupleDestination(shape, destinationSlots, destinationOffset),
                metrics, arguments.length, tail, inputLayout));
            return null;
        });
    }
    @Override public Object execute(VirtualFrame frame) {
        VectorLayout layout = vector;
        if (layout == null) throw fault("Tuple value requires a destination");
        try { executeInto(frame, vectorSlots, 0); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame resumed, Object input) { return layout.read(resumed, vectorSlots, 0); }
            });
        } catch (DelimitedCut cut) {
            throw cut.append(frame, (saved, input, ambient, outer) -> { input.get(); return layout.read(saved, vectorSlots, 0); });
        }
        return layout.read(frame, vectorSlots, 0);
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (vector == null) return executeInto(frame, slots, offset);
        try { executeInto(frame, vectorSlots, 0); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame resumed, Object input) {
                    vector.copy(resumed, vectorSlots, 0, slots, offset);
                    return null;
                }
            });
        } catch (DelimitedCut cut) {
            throw cut.append(frame, (saved, input, ambient, outer) -> { input.get(); vector.copy(saved, vectorSlots, 0, slots, offset); return null; });
        }
        vector.copy(frame, vectorSlots, 0, slots, offset);
        return null;
    }
    @ExplodeLoop private Object executeInto(VirtualFrame frame, int[] slots, int offset) {
        Closure closure;
        try { closure = function.executeRequiredClosure(frame); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame resumed, Object input) {
                    return executeArguments(resumed, slots, offset, Applications.requireClosure(input), new Object[ArgumentLayout.width(inputLayout, arguments.length)], 0);
                }
            });
        } catch (DelimitedCut cut) {
            throw cut.append(frame, (saved, input, ambient, outer) -> executeArguments(saved, slots, offset,
                Applications.requireClosure(input.get()), new Object[ArgumentLayout.width(inputLayout, arguments.length)], 0));
        }
        return executeArguments(frame, slots, offset, closure, new Object[ArgumentLayout.width(inputLayout, arguments.length)], 0);
    }
    @ExplodeLoop private Object executeArguments(VirtualFrame frame, int[] slots, int offset, Closure closure, Object[] values, int start) {
        for (int index = start; index < arguments.length; index++) {
            try {
                if (inputLayout != null && inputLayout.isEmpty(index)) arguments[index].executeTuple(frame, ArgumentLayout.EMPTY_TUPLE_SLOTS, 0);
                else values[ArgumentLayout.offset(inputLayout, index)] = arguments[index].execute(frame);
            } catch (AstCapture cut) {
                int savedIndex = index;
                throw cut.append((saved, input) -> resumeArgument(saved, slots, offset, closure, values, savedIndex, input));
            } catch (DelimitedCut cut) {
                int savedIndex = index;
                throw cut.append(frame, (saved, input, ambient, outer) -> resumeArgument(saved, slots, offset, closure, values, savedIndex, input.get()));
            }
        }
        TupleDispatch child = dispatch;
        if (child == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            child = atomic((Callable<TupleDispatch>) () -> {
                if (dispatch == null) {
                    TupleDispatch inserted = insert(new TupleDispatch(new AstTupleDestination(shape, slots, offset), metrics, arguments.length, tail, inputLayout));
                    destinationSlots = slots; destinationOffset = offset; dispatch = inserted;
                }
                return dispatch;
            });
        }
        if (destinationSlots != slots || destinationOffset != offset) throw new IllegalStateException("Check failed.");
        child.execute(frame, closure, values);
        return null;
    }
    private Object resumeArgument(VirtualFrame frame, int[] slots, int offset, Closure closure, Object[] prefix, int index, Object input) {
        Object[] values = prefix.clone(); // Per-invocation transport scratch; guest references remain shared.
        if (inputLayout == null || !inputLayout.isEmpty(index)) values[ArgumentLayout.offset(inputLayout, index)] = input;
        return executeArguments(frame, slots, offset, closure, values, index + 1);
    }
}
