// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TupleResultsKt.requireVoidCarrier;
import static thc.runtime.ApplicationKt.requireClosure;
public final class MaskAction extends Expr {
    private final TupleShape shape;
    private final MaskingState target;
    private final Metrics metrics;
    @Child private Expr action, state;
    @Child private Force force;
    @Child private volatile TupleDispatch actionCall;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private int[] destinationSlots;
    @CompilerDirectives.CompilationFinal private int destinationOffset = -1;
    public MaskAction(TupleShape shape, MaskingState target, Expr action, Expr state, Metrics metrics) {
        this.shape = shape; this.target = target; this.action = action; this.state = state; this.metrics = metrics;
        force = new Force(metrics);
        CoreRepresentation proof = shape.getProof();
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(),
            proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("mask action requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (actionCall == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            atomic(() -> {
                if (actionCall == null) {
                    actionCall = insert(new TupleDispatch(new AstTupleDestination(shape, slots, offset), metrics, 1, false, null));
                    destinationSlots = slots; destinationOffset = offset;
                }
            });
        }
        if (destinationSlots != slots || destinationOffset != offset) throw new IllegalStateException("Check failed.");
        Object value;
        try { value = action.execute(frame); }
        catch (AstCapture cut) { throw cut.append((saved, input) -> loadState(saved, input)); }
        return loadState(frame, value);
    }
    private Object loadState(VirtualFrame frame, Object actionValue) {
        Object value;
        try { value = state.execute(frame); }
        catch (AstCapture cut) { throw cut.append((saved, input) -> { requireVoidCarrier(input); return runAction(saved, actionValue); }); }
        requireVoidCarrier(value);
        return runAction(frame, actionValue);
    }
    private Object runAction(VirtualFrame frame, Object actionValue) {
        MaskingState prior = SynchronousMasking.current(this);
        SynchronousMasking.set(this, target);
        try {
            Object closure;
            try { closure = AstControl.force(frame, this, force, actionValue); }
            catch (AstCapture cut) {
                throw cut.append((saved, input) -> { actionCall.execute(saved, requireClosure(input), new Object[] {thc.runtime.Unit.INSTANCE}); return null; });
            }
            actionCall.execute(frame, requireClosure(closure), new Object[] {thc.runtime.Unit.INSTANCE});
            return null;
        } catch (AstCapture cut) { throw cut.enclose(steps -> new AstMaskScope(this, prior, steps)); }
        finally { SynchronousMasking.set(this, prior); }
    }
}
