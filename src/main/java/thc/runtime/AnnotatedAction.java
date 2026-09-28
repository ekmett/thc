// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

/** The lexical return remains present even when its action occupies tail position. */
public final class AnnotatedAction extends Expr {
    private final TupleShape shape;
    @Child private Expr annotation, action, state;
    @Child private Force force;
    @Child private Dispatch dispatch;
    public AnnotatedAction(TupleShape shape, Expr annotation, Expr action, Expr state, Metrics metrics, boolean async) {
        this.shape = shape; this.annotation = annotation; this.action = action; this.state = state;
        force = new Force(metrics, async); dispatch = Dispatch.create(1, false, metrics);
        var proof = shape.getProof();
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("annotateStack# requires a tuple destination"); }
    private Object invoke(VirtualFrame frame, Object action) {
        Closure closure;
        try { closure = ApplicationKt.requireClosure(AstControl.force(frame, this, force, action)); }
        catch (AstCapture cut) { throw cut.append((resumed, input) -> call(resumed, ApplicationKt.requireClosure(input))); }
        return call(frame, closure);
    }
    private Object call(VirtualFrame frame, Closure closure) {
        Object result;
        try {
            Object returned = dispatch.execute(frame, closure, new Object[] {kotlin.Unit.INSTANCE});
            DelimitedControl.captureBytecode(returned, shape);
            result = AstControl.complete(this, returned, closure.target, shape);
        } catch (AstCapture cut) { throw cut.append((resumed, input) -> TupleResultsKt.ownedTupleResult(input, shape)); }
        return TupleResultsKt.ownedTupleResult(result, shape);
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object value;
        try { value = annotation.execute(frame); }
        catch (AstCapture cut) { throw cut.append((resumed, input) -> loadAction(resumed, input, slots, offset)); }
        return loadAction(frame, value, slots, offset);
    }
    private Object loadAction(VirtualFrame frame, Object value, int[] slots, int offset) {
        Object function;
        try { function = action.execute(frame); }
        catch (AstCapture cut) { throw cut.append((resumed, input) -> loadState(resumed, value, input, slots, offset)); }
        return loadState(frame, value, function, slots, offset);
    }
    private Object loadState(VirtualFrame frame, Object value, Object function, int[] slots, int offset) {
        Object token;
        try { token = state.execute(frame); }
        catch (AstCapture cut) { throw cut.append((resumed, input) -> {
            TupleResultsKt.requireVoidCarrier(input); return runAction(resumed, value, function, slots, offset);
        }); }
        TupleResultsKt.requireVoidCarrier(token);
        return runAction(frame, value, function, slots, offset);
    }
    private Object runAction(VirtualFrame frame, Object value, Object function, int[] slots, int offset) {
        StackAnnotationState prior = StackAnnotations.enter(this, value);
        try {
            Object result;
            try { result = invoke(frame, function); }
            catch (AstCapture cut) { throw cut.append((resumed, input) -> { shape.consume(resumed, input, slots, offset); return null; }); }
            catch (DelimitedCut cut) {
                if (!DelimitedControl.enabled(this)) throw cut;
                throw cut.append(frame, new DelimitedAnnotationStep(this, prior))
                    .append(frame, new DelimitedTupleStep(new AstTupleDestination(shape, slots, offset), this));
            }
            shape.consume(frame, result, slots, offset);
            return null;
        } catch (AstCapture cut) { throw cut.enclose(steps -> new AstAnnotationScope(this, prior, steps)); }
        finally { StackAnnotations.set(this, prior); }
    }
}
