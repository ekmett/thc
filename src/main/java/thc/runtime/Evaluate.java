// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/** Typed execution widens per kind and consumes each evaluated result once. */
public final class Evaluate extends Expr {
    @Child private Expr value;
    @Child private Force force;
    public Evaluate(Expr value, Metrics metrics) {
        this.value = value;
        force = new Force(metrics);
        CoreRepresentation proof = value.getRepresentation();
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(),
            proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
        setCoreSourceLocation(value.getCoreSourceLocation());
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        return value.executeTuple(frame, slots, offset);
    }
    private Object forceResult(VirtualFrame frame, Object original) {
        Object result = AstControl.INSTANCE.force(frame, this, force, original);
        // A failed force never writes; already evaluated values need no write.
        if (original instanceof Thunk thunk && value instanceof LocalRead local) local.writeForced(frame, thunk, result);
        return result;
    }
    @Override public Object execute(VirtualFrame frame) {
        if (AstControl.INSTANCE.captures(this)) return executeAsync(frame);
        CoreRepresentation proof = value.getRepresentation();
        if (proof.isInt()) return value.executeRequiredInt(frame);
        if (proof.isLong()) return value.executeRequiredLong(frame);
        if (proof.isFloat()) return value.executeRequiredFloat(frame);
        if (proof.isDouble()) return value.executeRequiredDouble(frame);
        if (proof.getEvaluated()) return value.execute(frame);
        return forceResult(frame, value.execute(frame));
    }
    private Object executeAsync(VirtualFrame frame) {
        Object original;
        try {
            CoreRepresentation proof = value.getRepresentation();
            if (proof.isInt()) original = value.executeRequiredInt(frame);
            else if (proof.isLong()) original = value.executeRequiredLong(frame);
            else if (proof.isFloat()) original = value.executeRequiredFloat(frame);
            else if (proof.isDouble()) original = value.executeRequiredDouble(frame);
            else original = value.execute(frame);
        } catch (AstCapture cut) {
            if (value.getRepresentation().getEvaluated()) throw cut;
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame frame, Object input) { return forceResult(frame, input); }
            });
        }
        return value.getRepresentation().getEvaluated() ? original : forceResult(frame, original);
    }
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        if (AstControl.INSTANCE.captures(this)) return RuntimeTypesGen.expectInteger(executeAsync(frame));
        if (value.getRepresentation().getEvaluated() || value.getRepresentation().isInt()) return value.executeInt(frame);
        return RuntimeTypesGen.expectInteger(forceResult(frame, value.execute(frame)));
    }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        if (AstControl.INSTANCE.captures(this)) return RuntimeTypesGen.expectFloat(executeAsync(frame));
        if (value.getRepresentation().getEvaluated() || value.getRepresentation().isFloat()) return value.executeFloat(frame);
        return RuntimeTypesGen.expectFloat(forceResult(frame, value.execute(frame)));
    }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        if (AstControl.INSTANCE.captures(this)) return RuntimeTypesGen.expectDouble(executeAsync(frame));
        if (value.getRepresentation().getEvaluated() || value.getRepresentation().isDouble()) return value.executeDouble(frame);
        return RuntimeTypesGen.expectDouble(forceResult(frame, value.execute(frame)));
    }
    @CompilationFinal private boolean genericLong;
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        if (AstControl.INSTANCE.captures(this)) return RuntimeTypesGen.expectLong(executeAsync(frame));
        if (value.getRepresentation().getEvaluated() || value.getRepresentation().isLong()) return value.executeLong(frame);
        if (genericLong)
            return RuntimeTypesGen.expectLong(forceResult(frame, value.execute(frame)));
        try { return value.executeLong(frame); }
        catch (UnexpectedResultException unexpected) {
            // The failed typed read already invalidated code. Never reevaluate its child.
            genericLong = true;
            return RuntimeTypesGen.expectLong(forceResult(frame, unexpected.getResult()));
        }
    }
    @CompilationFinal private boolean genericClosure;
    @Override public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException {
        if (AstControl.INSTANCE.captures(this)) return RuntimeTypesGen.expectClosure(executeAsync(frame));
        if (value.getRepresentation().getEvaluated() || value.getRepresentation().isLong()) return value.executeClosure(frame);
        if (value.getRepresentation().getKind() == CoreKind.CLOSURE || genericClosure)
            return RuntimeTypesGen.expectClosure(forceResult(frame, value.execute(frame)));
        try { return value.executeClosure(frame); }
        catch (UnexpectedResultException unexpected) {
            // The failed typed read already invalidated code. Never reevaluate its child.
            genericClosure = true;
            return RuntimeTypesGen.expectClosure(forceResult(frame, unexpected.getResult()));
        }
    }
    @CompilationFinal private boolean genericDataValue;
    @Override public DataValue executeDataValue(VirtualFrame frame) throws UnexpectedResultException {
        if (AstControl.INSTANCE.captures(this)) return RuntimeTypesGen.expectDataValue(executeAsync(frame));
        if (value.getRepresentation().getEvaluated() || value.getRepresentation().isLong()) return value.executeDataValue(frame);
        if (value.getRepresentation().getKind() == CoreKind.DATA || genericDataValue)
            return RuntimeTypesGen.expectDataValue(forceResult(frame, value.execute(frame)));
        try { return value.executeDataValue(frame); }
        catch (UnexpectedResultException unexpected) {
            // The failed typed read already invalidated code. Never reevaluate its child.
            genericDataValue = true;
            return RuntimeTypesGen.expectDataValue(forceResult(frame, unexpected.getResult()));
        }
    }
    @CompilationFinal private boolean genericAddress;
    @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException {
        if (AstControl.INSTANCE.captures(this)) return RuntimeTypesGen.expectManagedAddress(executeAsync(frame));
        if (value.getRepresentation().getEvaluated() || value.getRepresentation().isLong()) return value.executeAddress(frame);
        if (value.getRepresentation().getKind() == CoreKind.ADDRESS || genericAddress)
            return RuntimeTypesGen.expectManagedAddress(forceResult(frame, value.execute(frame)));
        try { return value.executeAddress(frame); }
        catch (UnexpectedResultException unexpected) {
            // The failed typed read already invalidated code. Never reevaluate its child.
            genericAddress = true;
            return RuntimeTypesGen.expectManagedAddress(forceResult(frame, unexpected.getResult()));
        }
    }
}
