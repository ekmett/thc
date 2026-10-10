// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Logical aggregate identity is independent of physical positions; vectors remain atomic. */
public final class ArgumentLayout {
    public static final int[] EMPTY_TUPLE_SLOTS = new int[0];
    @CompilationFinal(dimensions = 1) private final CoreRepresentation[] proofs;
    @CompilationFinal(dimensions = 1) private final int[] offsets;
    @CompilationFinal(dimensions = 1) private final CoreRepresentation[] physicalProofs;
    @CompilationFinal(dimensions = 1) private final String[] tupleKeys;
    private final boolean requiresTyped;
    private final List<String> physicalStorageReps;
    private ArgumentLayout(CoreRepresentation[] proofs, int[] offsets, CoreRepresentation[] physicalProofs) {
        this.proofs = proofs; this.offsets = offsets; this.physicalProofs = physicalProofs;
        tupleKeys = new String[proofs.length];
        boolean typed = false;
        ArrayList<String> reps = new ArrayList<>();
        for (int i = 0; i < proofs.length; i++) {
            CoreRepresentation proof = proofs[i];
            typed |= typedScalar(proof) || proof.isTypedTransport() && !proof.isEmptyTuple();
            if (proof.isTypedTransport()) {
                reps.addAll(VectorLayout.storageReps(proof));
                tupleKeys[i] = TupleShape.Companion.compatibilityKey(proof);
            } else reps.add(proof.isInt() ? proof.getNarrowInteger().getRep() : proof.isLong() ? "IntRep" :
                proof.isFloat() ? "FloatRep" : proof.isDouble() ? "DoubleRep" :
                proof.getKind() == CoreKind.ADDRESS ? "AddrRep" : "BoxedRep (Just Lifted)");
        }
        requiresTyped = typed;
        physicalStorageReps = reps;
    }
    /** Complete scalar transport/demand shape for preparation caches, independent of allocation identity. */
    Object scalarKey() {
        if (requiresTyped) throw new IllegalArgumentException("Scalar application requires scalar array transport");
        record Proof(CoreKind kind, boolean evaluated, boolean present, CoreRepresentation.HostCarrier host,
                     String representation, int from, int to) {}
        var result = new ArrayList<Proof>(proofs.length);
        for (int i = 0; i < proofs.length; i++) {
            var proof = proofs[i];
            result.add(new Proof(proof.getKind(), proof.getEvaluated(), proof.getPresent(), proof.getHostCarrier(),
                TupleShape.compatibilityKey(proof), offsets[i], offsets[i + 1]));
        }
        return List.copyOf(result);
    }
    public int getLogicalArity() { return proofs.length; }
    public int getPhysicalArity() { return offsets[offsets.length - 1]; }
    public CoreRepresentation[] getPhysicalProofs() { return physicalProofs; }
    public boolean getRequiresTyped() { return requiresTyped; }
    public List<String> getPhysicalStorageReps() { return physicalStorageReps; }
    public boolean isEmpty(int index) { return proofs[index].isEmptyTuple(); }
    public boolean isTuple(int index) { return proofs[index].isTuple(); }
    public boolean isVector(int index) { return proofs[index].isVector(); }
    public boolean isTyped(int index) { return proofs[index].isTypedTransport(); }
    public CoreRepresentation proof(int index) { return proofs[index]; }
    public int offset(int index) { return offsets[index]; }
    public ArgumentLayout suffix(int index) {
        if (index < 0) throw new IllegalArgumentException("Requested element count " + index + " is less than zero.");
        return fromProofs(Arrays.asList(proofs).subList(Math.min(index, proofs.length), proofs.length));
    }
    public static ArgumentLayout fromProofs(List<CoreRepresentation> proofs) {
        boolean needed = false;
        for (CoreRepresentation proof : proofs) needed |= proof.isTypedTransport() || typedScalar(proof);
        if (!needed) return null;
        for (CoreRepresentation proof : proofs) CoreRepresentations.requireInput(proof);
        int[] offsets = new int[proofs.size() + 1];
        ArrayList<CoreRepresentation> physical = new ArrayList<>();
        for (int i = 0; i < proofs.size(); i++) {
            physical.addAll(leaves(proofs.get(i)));
            offsets[i + 1] = physical.size();
        }
        return new ArgumentLayout(proofs.toArray(CoreRepresentation[]::new), offsets, physical.toArray(CoreRepresentation[]::new));
    }
    private static boolean typedScalar(CoreRepresentation proof) {
        return proof.isInt() || proof.isFloat() || proof.isDouble() || proof.getKind() == CoreKind.ADDRESS;
    }
    /** A scalar State# retains its token; State# nested in a tuple is erased. */
    public static List<CoreRepresentation> leaves(CoreRepresentation proof) {
        return proof.isSum() ? SumShape.INSTANCE.storage(proof) :
            proof.isTuple() || proof.isVector() ? TupleShape.Companion.flatten(proof) : List.of(proof);
    }
    public static int offset(ArgumentLayout layout, int index) { return layout == null ? index : layout.offset(index); }
    public static int width(ArgumentLayout layout, int count) { return offset(layout, count); }
    public static void validate(Closure function, ArgumentLayout supplied, int offset, int count) {
        validate(function.target.getRootNode() instanceof GuestRoot root ? root.getInputLayout() : null,
            function.suppliedCount, supplied, offset, count);
    }
    /** Logical shapes remain mandatory; an unknown boxed levity can refine to either exact levity. */
    public static void validate(ArgumentLayout formal, int prefixCount, ArgumentLayout supplied, int offset, int count) {
        if (formal == null && supplied == null) return;
        for (int i = 0; i < count; i++) {
            String expected = formal == null ? null : formal.tupleKeys[prefixCount + i];
            String actual = supplied == null ? null : supplied.tupleKeys[offset + i];
            if (expected != actual && (expected == null || actual == null ||
                !TupleShape.compatible(formal.proofs[prefixCount + i], supplied.proofs[offset + i])))
                throw fault("Conflicting logical tuple argument representation");
        }
    }
}
