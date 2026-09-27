// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.Arrays;

/** Executable root and fixed calling convention shared by AST and bytecode. */
public abstract class GuestRoot extends RootNode {
    static {
        com.oracle.truffle.api.Truffle.getRuntime();
        if (com.oracle.truffle.runtime.OptimizedCallTarget.declaredReturnPolicyVersion() != 1)
            throw new LinkageError("THC requires the declared root completion runtime");
    }
    protected GuestRoot(TruffleLanguage<?> language, FrameDescriptor descriptor) { super(language, descriptor); }
    @CompilationFinal private boolean delimitedControlEnabled = false;
    public final boolean getDelimitedControlEnabled() { return delimitedControlEnabled; }
    public final boolean getDelimitedControlEnabled$org_intelligence_thc() { return delimitedControlEnabled; }
    @CompilationFinal private ForeignExceptionBridge foreignExceptionBridge = null;
    public final ForeignExceptionBridge getForeignExceptionBridge() { return foreignExceptionBridge; }
    public final ForeignExceptionBridge getForeignExceptionBridge$org_intelligence_thc() { return foreignExceptionBridge; }
    @CompilationFinal private CoreFunctionIdentity coreIdentity = null;
    public final CoreFunctionIdentity getCoreIdentity() { return coreIdentity; }
    public final CoreFunctionIdentity getCoreIdentity$org_intelligence_thc() { return coreIdentity; }
    @CompilationFinal(dimensions = 1) private boolean[] entryStrict = new boolean[0];
    public final boolean[] getEntryStrict() { return entryStrict; }
    public final boolean[] getEntryStrict$org_intelligence_thc() { return entryStrict; }
    @CompilationFinal private int entryArgumentOffset = 1;
    public final int getEntryArgumentOffset() { return entryArgumentOffset; }
    public final int getEntryArgumentOffset$org_intelligence_thc() { return entryArgumentOffset; }
    @CompilationFinal private ArgumentLayout inputLayout = null;
    public final ArgumentLayout getInputLayout() { return inputLayout; }
    public final ArgumentLayout getInputLayout$org_intelligence_thc() { return inputLayout; }
    @CompilationFinal(dimensions = 1) private int[] strictArgumentPositions = new int[0];
    public final int[] getStrictArgumentPositions() { return strictArgumentPositions; }
    public final int[] getStrictArgumentPositions$org_intelligence_thc() { return strictArgumentPositions; }
    @CompilationFinal private TypedInputLayout typedInput = null;
    public final TypedInputLayout getTypedInput() { return typedInput; }
    public final TypedInputLayout getTypedInput$org_intelligence_thc() { return typedInput; }
    @CompilationFinal private LeadingCaseReturn leadingCaseReturn = null;
    public final LeadingCaseReturn getLeadingCaseReturn() { return leadingCaseReturn; }
    public final LeadingCaseReturn getLeadingCaseReturn$org_intelligence_thc() { return leadingCaseReturn; }
    @CompilationFinal private CoreRepresentation scalarResultProof = CoreRepresentation.Companion.getUNKNOWN();
    public final CoreRepresentation getScalarResultProof() { return scalarResultProof; }
    public final CoreRepresentation getScalarResultProof$org_intelligence_thc() { return scalarResultProof; }
    @CompilationFinal private TupleShape tupleResult = null;
    public final TupleShape getTupleResult() { return tupleResult; }
    public final TupleShape getTupleResult$org_intelligence_thc() { return tupleResult; }
    @Override protected void prepareForCall() {
        super.prepareForCall();
        // Resolve concrete control policy before target publication and compilation.
        delimitedControlEnabled = DelimitedControl.INSTANCE.rootEnabled(this);
    }
    public final void configureInput(ArgumentLayout layout) { inputLayout = layout; configureStrictPositions(); }
    public final void configureEntry(boolean[] strict, boolean hasEnvironment) {
        entryStrict = strict.clone();
        entryArgumentOffset = hasEnvironment ? 2 : 1;
        configureStrictPositions();
    }
    private void configureStrictPositions() {
        int[] positions = new int[entryStrict.length];
        int count = 0;
        for (int i = 0; i < entryStrict.length; i++)
            if (entryStrict[i] && (inputLayout == null || !inputLayout.isTuple(i) && !inputLayout.isVector(i)))
                positions[count++] = ArgumentLayout.offset(inputLayout, i) + entryArgumentOffset;
        strictArgumentPositions = Arrays.copyOf(positions, count);
    }
    public final void configureForeignExceptionBridge(ForeignExceptionBridge value) { foreignExceptionBridge = value; }
    public final void configureForeignExceptionBridge$org_intelligence_thc(ForeignExceptionBridge value) { configureForeignExceptionBridge(value); }
    public final void configureCoreIdentity(CoreFunctionIdentity value) { coreIdentity = value; }
    public final void configureCoreIdentity$org_intelligence_thc(CoreFunctionIdentity value) { configureCoreIdentity(value); }
    public final void configureTypedInput(TypedInputLayout value) { typedInput = value; }
    public final void configureTypedInput$org_intelligence_thc(TypedInputLayout value) { configureTypedInput(value); }
    public final void configureLeadingCaseReturn(LeadingCaseReturn value) { leadingCaseReturn = value; }
    public final void configureLeadingCaseReturn$org_intelligence_thc(LeadingCaseReturn value) { configureLeadingCaseReturn(value); }
    public final void configureScalarResult(CoreRepresentation value) { scalarResultProof = value; }
    public final void configureScalarResult$org_intelligence_thc(CoreRepresentation value) { configureScalarResult(value); }
    public final void configureTupleResult(TupleShape value) { tupleResult = value; }
    public final void configureTupleResult$org_intelligence_thc(TupleShape value) { configureTupleResult(value); }
    public final void configureInput$org_intelligence_thc(ArgumentLayout value) { configureInput(value); }
    public final void configureEntry$org_intelligence_thc(boolean[] strict, boolean hasEnvironment) { configureEntry(strict, hasEnvironment); }
    public final boolean hasTupleResult(TupleShape shape) { return tupleResult != null && tupleResult.matches(shape); }
    // Cloning retains this exact identity, including self calls through cloned targets.
    private final Object bodyIdentity = new Object();
    public final long mask = bodyMask();
    private long bodyMask() {
        int h = System.identityHashCode(bodyIdentity);
        return (1L << (h & 63)) | (1L << ((h >>> 6) & 63)) | (1L << ((h >>> 12) & 63)) |
            (1L << ((h >>> 18) & 63)) | (1L << ((h >>> 24) & 63));
    }
    public final boolean isSelf(RootCallTarget target) {
        return target.getRootNode() instanceof GuestRoot root && root.bodyIdentity == bodyIdentity;
    }
    public abstract long bloom(VirtualFrame frame);
}
