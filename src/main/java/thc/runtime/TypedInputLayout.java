// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;

import thc.Language;
import java.util.ArrayList;
import java.util.List;

public final class TypedInputLayout {
    private final Language language;
    private final ArgumentLayout logical;
    private final boolean hasEnvironment;
    private final int header;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final CoreRepresentation[] leaves;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final VectorLayout[] selfVectors;
    private final HandoffLayout packet;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final HandoffLayout[] prefixes;
    public TypedInputLayout(Language language, ArgumentLayout logical, boolean hasEnvironment) {
        this.language = language; this.logical = logical; this.hasEnvironment = hasEnvironment;
        header = hasEnvironment ? 2 : 1;
        leaves = logical.getPhysicalProofs();
        selfVectors = new VectorLayout[leaves.length];
        for (int i = 0; i < leaves.length; i++) if (leaves[i].isVector()) selfVectors[i] = new VectorLayout(leaves[i]);
        List<String> reps = logical.getPhysicalStorageReps();
        ArrayList<String> packetReps = new ArrayList<>();
        packetReps.add("WordRep");
        if (hasEnvironment) packetReps.add("BoxedRep (Just Unlifted)");
        packetReps.addAll(reps);
        packet = language.getHandoffLayouts().intern(packetReps);
        prefixes = new HandoffLayout[logical.getLogicalArity() + 1];
        for (int count = 0; count < prefixes.length; count++)
            prefixes[count] = language.getHandoffLayouts().intern(reps.subList(0, logical.offset(count)));
    }
    public Language getLanguage() { return language; }
    public ArgumentLayout getLogical() { return logical; }
    public boolean getHasEnvironment() { return hasEnvironment; }
    public int getHeader() { return header; }
    public CoreRepresentation[] getLeaves() { return leaves; }
    public HandoffLayout getPacket() { return packet; }
    public HandoffState state() { return language.getHandoffState().get(); }
    public HandoffLayout prefix(int count) { return prefixes[count]; }
    @ExplodeLoop public void validateSelfSource(InputSource source, VirtualFrame frame, Node node) {
        ArgumentLayout.validate(logical, 0, source.getLayout(), 0, logical.getLogicalArity());
        for (int i = 0; i < leaves.length; i++) {
            if (packet.isInt(header + i)) source.readInt(frame, node, null, i);
            else if (packet.isLong(header + i)) source.readLong(frame, node, null, i);
            else if (packet.isFloat(header + i)) source.readFloat(frame, node, null, i);
            else if (packet.isDouble(header + i)) source.readDouble(frame, node, null, i);
            else if (selfVectors[i] != null) source.setReference(frame, node, null, i, selfVectors[i].require(source.reference(frame, node, null, i)));
        }
    }
    public HandoffStorage take(Object[] arguments) {
        if (arguments.length != 1) throw fault("Tuple input entry requires one typed carrier");
        if (!(arguments[0] instanceof HandoffStorage input)) throw fault("Tuple input entry requires a typed carrier");
        validate(input);
        return input;
    }
    public void validate(HandoffStorage input) { validate(input, true); }
    public void validateTail(HandoffStorage input) {
        if (input.getInputMode() == 2) {
            releaseUnexpected(input);
            throw fault("Direct typed input cannot be restored from a tail transfer");
        }
        validate(input, false);
    }
    private void validate(HandoffStorage input, boolean direct) {
        int mode = input.getInputMode();
        if (input.getLayout() != packet || mode < 1 || mode > 3 || input.getLive() != (mode == 1)) {
            releaseUnexpected(input);
            throw fault("Conflicting typed input layout or ownership");
        }
        if (direct && CompilerDirectives.inCompiledCode() && !CompilerDirectives.inCompilationRoot() && mode == 2)
            CompilerDirectives.ensureVirtualizedHere(input);
    }
    public void release(HandoffStorage input) {
        if (input.getLayout() != packet) throw new IllegalStateException("Check failed.");
        switch (input.getInputMode()) {
            case 1 -> state().getArguments().release(input, packet);
            case 2, 3 -> packet.clearReferences(input);
            default -> throw fault("Typed input carrier was already consumed");
        }
        input.setInputMode(0);
    }
    public void releaseChecked(HandoffStorage input) {
        if (input.getInputMode() == 0) return;
        if (input.getLayout() == packet) release(input); else releaseUnexpected(input);
    }
    public void releaseIfOwned(HandoffStorage input, long generation) {
        if (input.getGeneration() == generation) releaseChecked(input);
    }
    private void releaseUnexpected(HandoffStorage input) { TypedInputs.discardTypedInput(language, input); }
    public static TypedInputLayout create(Language language, ArgumentLayout logical, boolean hasEnvironment) {
        return logical != null && logical.getRequiresTyped() ? new TypedInputLayout(language, logical, hasEnvironment) : null;
    }
}
