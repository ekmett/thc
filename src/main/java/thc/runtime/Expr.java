// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.SourceSection;

@GenerateWrapper
@TypeSystemReference(RuntimeTypes.class)
public abstract class Expr extends Node implements InstrumentableNode {
    @Override public boolean isInstrumentable() { return coreSourceLocation != null && getSourceSection() != null; }
    @Override public WrapperNode createWrapper(ProbeNode probe) {
        var wrapper = new ExprWrapper(this, probe);
        wrapper.setRepresentation(representation);
        wrapper.setCoreSourceLocation(coreSourceLocation);
        return wrapper;
    }
    @Override public boolean hasTag(Class<? extends Tag> tag) {
        if (tag == StandardTags.StatementTag.class) return coreSourceLocation != null;
        Node parent = getParent();
        if (parent instanceof WrapperNode) parent = parent.getParent();
        if (parent instanceof Evaluate) parent = parent.getParent();
        if (parent instanceof FunctionBody) parent = parent.getParent();
        return parent instanceof GuestRoot &&
            (tag == StandardTags.RootTag.class || tag == StandardTags.RootBodyTag.class);
    }
    // Assigned during lowering, before adoption.
    @CompilationFinal private CoreRepresentation representation = CoreRepresentation.UNKNOWN;
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
        for (Node parent = getParent(); parent != null; parent = parent.getParent()) {
            // Read an Expr ancestor's own metadata without restarting its ancestor scan.
            SourceSection section = parent instanceof Expr expression ?
                    expression.coreSourceLocation == null ? null : expression.coreSourceLocation.getSection() :
                    parent.getSourceSection();
            if (section != null) return section;
        }
        return null;
    }
    /** Bind an existing typed destination while lowering, without executing the expression. */
    public void prepareTuple(int[] slots, int offset) {}
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

    /**
     * Whether execution needs call ancestry, can enter another guest activation,
     * demand a lazy value, or transfer a guest tail call. False covers every typed execution route;
     * returning a thunk without entering it is allowed. Composite proofs must
     * inspect their current children so replacement invalidates compiled proofs.
     * Concrete and conservative so instrumentation wrappers retain their own
     * potentially reentrant behavior instead of delegating the child's proof.
     */
    public boolean needsCallState() { return true; }

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
