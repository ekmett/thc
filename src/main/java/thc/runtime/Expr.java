// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.SourceSection;

@TypeSystemReference(RuntimeTypes.class)
public abstract class Expr extends Node {
    // Assigned during lowering, before adoption.
    @CompilationFinal private CoreRepresentation representation = CoreRepresentation.Companion.getUNKNOWN();
    @CompilationFinal private VectorLayout vectorLayout;
    @CompilationFinal private CoreSourceLocation coreSourceLocation;

    public final CoreRepresentation getRepresentation() { return representation; }
    public final void setRepresentation(CoreRepresentation value) {
        representation = value;
        vectorLayout = value.isVector() ? new VectorLayout(value) : null;
    }
    protected final VectorLayout getTypedVectorLayout() { return vectorLayout; }
    public final CoreSourceLocation getCoreSourceLocation() { return coreSourceLocation; }
    public final void setCoreSourceLocation(CoreSourceLocation value) { coreSourceLocation = value; }
    public final Expr located(CoreSourceLocation location) { coreSourceLocation = location; return this; }
    public final Expr proven(CoreRepresentation proof) { setRepresentation(proof); return this; }
    @Override public SourceSection getSourceSection() {
        if (coreSourceLocation != null) return coreSourceLocation.getSection();
        Node parent = getParent();
        return parent == null ? null : parent.getEncapsulatingSourceSection();
    }
    public final Object executeTuple(VirtualFrame frame, int[] slots) {
        return executeTuple(frame, slots, 0);
    }
    public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        VectorLayout layout = vectorLayout;
        if (layout != null) {
            layout.write(frame, slots, offset, execute(frame));
            return null;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Expression does not produce a tuple");
    }
    public abstract Object execute(VirtualFrame frame);

    public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        return RuntimeTypesGen.expectInteger(execute(frame));
    }
    public final int executeRequiredInt(VirtualFrame frame) {
        try { return executeInt(frame); }
        catch (UnexpectedResultException failure) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Expected primitive Int");
        }
    }

    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        return RuntimeTypesGen.expectLong(execute(frame));
    }
    public final long executeRequiredLong(VirtualFrame frame) {
        try { return executeLong(frame); }
        catch (UnexpectedResultException failure) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Expected primitive Long");
        }
    }

    public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        return RuntimeTypesGen.expectFloat(execute(frame));
    }
    public final float executeRequiredFloat(VirtualFrame frame) {
        try { return executeFloat(frame); }
        catch (UnexpectedResultException failure) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Expected primitive Float");
        }
    }

    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return RuntimeTypesGen.expectDouble(execute(frame));
    }
    public final double executeRequiredDouble(VirtualFrame frame) {
        try { return executeDouble(frame); }
        catch (UnexpectedResultException failure) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Expected primitive Double");
        }
    }

    public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException {
        return RuntimeTypesGen.expectClosure(execute(frame));
    }
    public final Closure executeRequiredClosure(VirtualFrame frame) {
        try { return executeClosure(frame); }
        catch (UnexpectedResultException failure) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Application of a non-function");
        }
    }

    public DataValue executeDataValue(VirtualFrame frame) throws UnexpectedResultException {
        return RuntimeTypesGen.expectDataValue(execute(frame));
    }
    public final DataValue executeRequiredDataValue(VirtualFrame frame) {
        try { return executeDataValue(frame); }
        catch (UnexpectedResultException failure) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Expected constructor value");
        }
    }

    public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException {
        return RuntimeTypesGen.expectManagedAddress(execute(frame));
    }
    public final ManagedAddress executeRequiredAddress(VirtualFrame frame) {
        try { return executeAddress(frame); }
        catch (UnexpectedResultException failure) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Expected a managed literal Addr#");
        }
    }
}
