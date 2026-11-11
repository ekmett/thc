// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import java.util.ArrayDeque;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.Applications.requireClosure;

public final class AstTypedApplication extends Expr {
    private final boolean tail, selfTransfer;
    private final Metrics metrics;
    private final TupleShape shape;
    @Child private Evaluate function;
    @Child private AstInputOperands operands;
    @Child private AstSelfTarget selfTarget;
    @Child private volatile InputDispatch dispatch;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private int[] destinationSlots;
    @CompilerDirectives.CompilationFinal private int destinationOffset = -1;
    private final VectorLayout vector;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] vectorSlots;
    public AstTypedApplication(Expr function, Expr[] arguments, FrameLayout frameLayout, boolean tail, Metrics metrics) {
        this(function, arguments, frameLayout, tail, metrics, null, false);
    }
    public AstTypedApplication(Expr function, Expr[] arguments, FrameLayout frameLayout, boolean tail, Metrics metrics, TupleShape shape) {
        this(function, arguments, frameLayout, tail, metrics, shape, false);
    }
    public AstTypedApplication(Expr function, Expr[] arguments, FrameLayout frameLayout, boolean tail, Metrics metrics, TupleShape shape, boolean selfTransfer) {
        this.tail = tail; this.metrics = metrics; this.shape = shape; this.selfTransfer = selfTransfer;
        this.function = new Evaluate(function, metrics); operands = new AstInputOperands(arguments, frameLayout);
        if (selfTransfer) selfTarget = new AstSelfTarget();
        if (shape == null) dispatch = new InputDispatch(operands.getSource(), arguments.length, tail, metrics);
        vector = shape != null && shape.getProof().isVector() ? new VectorLayout(shape.getProof()) : null;
        if (vector != null) {
            vectorSlots = new int[vector.getWidth()];
            for (int i = 0; i < vectorSlots.length; i++) vectorSlots[i] = frameLayout.bind("<vector result " + i + ">", com.oracle.truffle.api.frame.FrameSlotKind.Object);
        } else vectorSlots = null;
        if (vector != null) prepareTuple(vectorSlots, 0);
        CoreRepresentation proof = shape == null ? CoreRepresentation.UNKNOWN : shape.getProof();
        setRepresentation(proof.copy(proof.getKind(), shape != null, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(),
            proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public void prepareTuple(int[] slots, int offset) {
        if (shape == null) return;
        if (vector != null) { slots = vectorSlots; offset = 0; }
        if (destinationSlots != null) {
            if (destinationSlots != slots || destinationOffset != offset) throw new IllegalStateException("Conflicting typed destination");
            return;
        }
        destinationSlots = slots; destinationOffset = offset;
        if (metrics == null) dispatch = new InputDispatch(operands.getSource(), operands.getLayout().getLogicalArity(), tail, null,
            new AstTupleDestination(shape, slots, offset));
    }
    /** Adopt only the aggregate destination already determined during lowering. */
    void prepareForAOT() {
        if (dispatch == null && destinationSlots != null) atomic(() -> {
            if (dispatch == null) dispatch = insert(new InputDispatch(operands.getSource(), operands.getLayout().getLogicalArity(), tail, metrics,
                new AstTupleDestination(shape, destinationSlots, destinationOffset)));
            return null;
        });
    }
    @Override public Object execute(VirtualFrame frame) {
        if (vector != null) {
            try { executeInto(frame, vectorSlots, 0); }
            catch (AstCapture cut) {
                throw cut.append((saved, input) -> {
                    if (input != null) throw fault("Invalid vector application continuation");
                    return vector.read(saved, vectorSlots, 0);
                });
            } catch (DelimitedCut cut) {
                throw cut.append(frame, (saved, input, ambient, outer) -> {
                    if (input.get() != null) throw fault("Invalid vector application continuation");
                    return vector.read(saved, vectorSlots, 0);
                });
            }
            return vector.read(frame, vectorSlots, 0);
        }
        if (shape != null) throw fault("Aggregate value requires a typed destination");
        if (AstControl.captures(this)) return executeAsync(frame, null, 0);
        Closure closure = function.executeRequiredClosure(frame);
        try {
            operands.evaluate(frame); transferSelf(frame, closure);
            return dispatch.execute(frame, closure);
        } finally { operands.getSource().clear(frame); }
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (vector == null) return executeInto(frame, slots, offset);
        try { executeInto(frame, vectorSlots, 0); }
        catch (AstCapture cut) {
            throw cut.append((saved, input) -> {
                if (input != null) throw fault("Invalid vector application continuation");
                vector.copy(saved, vectorSlots, 0, slots, offset); return null;
            });
        } catch (DelimitedCut cut) {
            throw cut.append(frame, (saved, input, ambient, outer) -> {
                if (input.get() != null) throw fault("Invalid vector application continuation");
                vector.copy(saved, vectorSlots, 0, slots, offset); return null;
            });
        }
        vector.copy(frame, vectorSlots, 0, slots, offset); return null;
    }
    private Object executeInto(VirtualFrame frame, int[] slots, int offset) {
        TupleShape tuple = shape;
        if (tuple == null) throw fault("Scalar application has no aggregate destination");
        if (AstControl.captures(this)) return executeAsync(frame, slots, offset);
        Closure closure = function.executeRequiredClosure(frame);
        try {
            operands.evaluate(frame); transferSelf(frame, closure);
            InputDispatch child = destination(tuple, slots, offset);
            child.execute(frame, closure); return null;
        } finally { operands.getSource().clear(frame); }
    }
    private InputDispatch destination(TupleShape tuple, int[] slots, int offset) {
        InputDispatch child = dispatch;
        if (child == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            child = atomic(() -> {
                if (dispatch == null) {
                    InputDispatch inserted = insert(new InputDispatch(operands.getSource(), operands.getLayout().getLogicalArity(), tail, metrics,
                        new AstTupleDestination(tuple, slots, offset)));
                    destinationSlots = slots; destinationOffset = offset; dispatch = inserted;
                }
                return dispatch;
            });
        }
        if (destinationSlots != slots || destinationOffset != offset) throw new IllegalStateException("Check failed.");
        return child;
    }
    private Object executeAsync(VirtualFrame frame, int[] slots, int offset) {
        Closure closure;
        try { closure = function.executeRequiredClosure(frame); }
        catch (AstCapture cut) { throw cut.append((saved, input) -> invokeAsync(saved, requireClosure(input), slots, offset)); }
        catch (DelimitedCut cut) { throw cut.append(frame, (saved, input, ambient, outer) -> invokeAsync(saved, requireClosure(input.get()), slots, offset)); }
        return invokeAsync(frame, closure, slots, offset);
    }
    private Object invokeAsync(VirtualFrame frame, Closure closure, int[] slots, int offset) {
        boolean suspended = false;
        try {
            try { operands.evaluate(frame); }
            catch (AstCapture cut) {
                throw cut.append((saved, input) -> {
                    if (input != thc.runtime.Unit.INSTANCE) throw fault("Invalid typed operands continuation");
                    return dispatchAsync(saved, closure, slots, offset);
                });
            } catch (DelimitedCut cut) {
                throw cut.append(frame, (saved, input, ambient, outer) -> {
                    if (input.get() != thc.runtime.Unit.INSTANCE) throw fault("Invalid typed operands continuation");
                    return dispatchAsync(saved, closure, slots, offset);
                });
            }
            return dispatchAsync(frame, closure, slots, offset);
        } catch (AstCapture cut) {
            suspended = true;
            throw cut.enclose(steps -> new Cleanup(this, steps));
        } catch (DelimitedCut cut) {
            suspended = true;
            throw cut.append(frame, new Cleanup(this));
        } finally { if (!suspended) operands.getSource().clear(frame); }
    }
    private Object dispatchAsync(VirtualFrame frame, Closure closure, int[] slots, int offset) {
        try {
            transferSelf(frame, closure);
            if (slots == null) return dispatch.execute(frame, closure);
            if (shape == null) throw fault("Scalar application has no aggregate destination");
            destination(shape, slots, offset).execute(frame, closure); return null;
        } catch (AstSelfCall | TailCall transfer) {
            // Saved delimited transfers bypass the separate cleanup suffix.
            operands.getSource().clear(frame);
            throw transfer;
        }
    }
    private static final class Cleanup implements AstResumeStep, DelimitedStep {
        private final AstTypedApplication owner;
        private final ArrayDeque<AstResumeStep> steps;
        @CompilerDirectives.TruffleBoundary Cleanup(AstTypedApplication owner) { this(owner, new ArrayDeque<>()); }
        Cleanup(AstTypedApplication owner, ArrayDeque<AstResumeStep> steps) { this.owner = owner; this.steps = steps; }
        @Override public Object resume(VirtualFrame frame, Object input) {
            boolean suspended = false;
            try { return AstContinuations.resumeAstSteps(frame, steps, input); }
            catch (AstCapture cut) {
                suspended = true; throw cut.enclose(remaining -> new Cleanup(owner, remaining));
            } catch (DelimitedCut cut) {
                suspended = true; throw cut.append(frame, new Cleanup(owner));
            } finally { if (!suspended) owner.operands.getSource().clear(frame); }
        }
        @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
            try { return input.get(); }
            finally { owner.operands.getSource().clear(frame); }
        }
    }
    private void transferSelf(VirtualFrame frame, Closure function) {
        // Delimited images retain a separate cleanup suffix around pending operands.
        if (selfTransfer && !DelimitedControl.enabled(this) &&
            ((FunctionRoot) getRootNode()).getRole$org_intelligence_thc() != FunctionRootRole.PASS_THROUGH &&
            function.arity == operands.getLayout().getLogicalArity() && function.suppliedCount == 0 &&
            function.supplied.length == 0 && function.typedSupplied == null && selfTarget.matches(function.target)) {
            ((FunctionRoot) getRootNode()).transferTypedSelf(frame, function, operands.getSource(), this);
            throw AstSelfCall.INSTANCE;
        }
    }
}
