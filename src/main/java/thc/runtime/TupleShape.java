// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Logical aggregate/vector identity is independent of physical result fields. */
public final class TupleShape {
    private final CoreRepresentation proof;
    private final Language language;
    @CompilationFinal(dimensions = 1) private final CoreRepresentation[] components;
    @CompilationFinal(dimensions = 1) private final CoreRepresentation[] leaves;
    @CompilationFinal(dimensions = 1) private final VectorLayout[] vectors;
    private final HandoffLayout layout;
    @CompilationFinal(dimensions = 1) private final int[] offsets;
    private final String signature;
    public TupleShape(CoreRepresentation proof, Language language) {
        this.proof = proof; this.language = language;
        if (!proof.isTypedTransport()) throw fault("Typed result shape requires an aggregate or vector proof");
        components = proof.getComponents() == null ? new CoreRepresentation[0] : proof.getComponents().toArray(CoreRepresentation[]::new);
        leaves = flatten(proof).toArray(CoreRepresentation[]::new);
        vectors = new VectorLayout[leaves.length];
        for (int i = 0; i < leaves.length; i++) if (leaves[i].isVector()) vectors[i] = new VectorLayout(leaves[i]);
        layout = language.getHandoffLayouts().intern(VectorLayout.storageReps(proof));
        offsets = new int[components.length];
        int next = 0;
        for (int i = 0; i < components.length; i++) { offsets[i] = next; next += flatten(components[i]).size(); }
        signature = compatibilityKey(proof);
    }
    public CoreRepresentation getProof() { return proof; }
    public Language getLanguage() { return language; }
    public CoreRepresentation[] getComponents() { return components; }
    public CoreRepresentation[] getLeaves() { return leaves; }
    public HandoffLayout getLayout() { return layout; }
    public int[] getOffsets() { return offsets; }
    public int getWidth() { return leaves.length; }
    public boolean matches(TupleShape other) { return signature == other.signature || compatible(proof, other.proof); }
    @ExplodeLoop public void copyFrom(VirtualFrame frame, HandoffStorage storage, int[] slots, int offset) {
        if (storage.getLayout() != layout) throw new IllegalStateException("Check failed.");
        for (int i = 0; i < leaves.length; i++) {
            if (layout.isInt(i)) FrameAccess.writeInt(frame, slots[offset + i], layout.getInt(storage, i));
            else if (layout.isLong(i)) FrameAccess.writeLong(frame, slots[offset + i], layout.getLong(storage, i));
            else if (layout.isFloat(i)) FrameAccess.writeFloat(frame, slots[offset + i], layout.getFloat(storage, i));
            else if (layout.isDouble(i)) FrameAccess.writeDouble(frame, slots[offset + i], layout.getDouble(storage, i));
            else FrameAccess.write(frame, slots[offset + i], checkedReference(i, layout.getObject(storage, i)));
        }
    }
    @ExplodeLoop private void write(VirtualFrame frame, int[] slots, HandoffStorage storage) {
        for (int i = 0; i < leaves.length; i++) {
            if (layout.isInt(i)) layout.setInt(storage, i, frame.getInt(slots[i]));
            else if (layout.isLong(i)) layout.setLong(storage, i, frame.getLong(slots[i]));
            else if (layout.isFloat(i)) layout.setFloat(storage, i, frame.getFloat(slots[i]));
            else if (layout.isDouble(i)) layout.setDouble(storage, i, frame.getDouble(slots[i]));
            else layout.setObject(storage, i, checkedReference(i, frame.getObject(slots[i])));
        }
    }
    public Object checkedReference(int index, Object value) {
        if (vectors[index] != null) return vectors[index].require(value);
        if (leaves[index].getKind() == CoreKind.ADDRESS && !(value instanceof ManagedAddress)) throw fault("Expected a managed literal Addr# tuple field");
        return value;
    }
    public Object finish(VirtualFrame frame, int[] slots) { return finish(frame, slots, false); }
    public Object finish(VirtualFrame frame, int[] slots, boolean capturesFrame) {
        if (inlineResult()) {
            HandoffStorage virtual = layout.create(); write(frame, slots, virtual);
            if (capturesFrame) CompilerDirectives.ensureVirtualizedHere(virtual); else CompilerDirectives.ensureVirtualized(virtual);
            return virtual;
        }
        TupleResultPool pool = language.getHandoffState().get().getResults();
        HandoffStorage output = pool.acquire(layout);
        try { write(frame, slots, output); return pool.complete(output); }
        catch (Throwable failure) { pool.release(output, layout); throw failure; }
    }
    public void consume(VirtualFrame frame, Object result, int[] slots, int offset) {
        if (result == TupleComplete.INSTANCE) {
            TupleResultPool pool = language.getHandoffState().get().getResults();
            HandoffStorage output = pool.completed();
            try { copyFrom(frame, output, slots, offset); } finally { pool.releaseChecked(output, layout); }
        } else {
            if (!(result instanceof HandoffStorage storage)) throw fault("Invalid tuple result carrier");
            copyFrom(frame, storage, slots, offset);
        }
    }
    public boolean inlineResult() { return CompilerDirectives.inCompiledCode() && !CompilerDirectives.inCompilationRoot(); }
    public static List<CoreRepresentation> flatten(CoreRepresentation proof) {
        if (proof.getComponents() != null) {
            var result = new ArrayList<CoreRepresentation>();
            for (CoreRepresentation field : proof.getComponents()) result.addAll(flatten(field));
            return result;
        }
        if (proof.isSum()) return SumShape.storage(proof);
        return proof.getKind() == CoreKind.VOID ? List.of() : List.of(proof);
    }
    public static List<CoreRepresentation> logicalLeaves(CoreRepresentation proof) {
        if (proof.getComponents() != null) {
            var result = new ArrayList<CoreRepresentation>();
            for (CoreRepresentation field : proof.getComponents()) result.addAll(logicalLeaves(field));
            return result;
        }
        return proof.getKind() == CoreKind.VOID ? List.of() : List.of(proof);
    }
    // Equal interned signatures stay inline in matches; structural mismatches
    // must not recursively expand proof trees in the guest compilation graph.
    @TruffleBoundary public static boolean compatible(CoreRepresentation left, CoreRepresentation right) {
        if (left.isTuple() || right.isTuple())
            return left.isTuple() && right.isTuple() && compatibleChildren(left.getComponents(), right.getComponents());
        if (left.isSum() || right.isSum())
            return left.isSum() && right.isSum() && compatibleChildren(left.getAlternatives(), right.getAlternatives()) &&
                compatibleTransport(SumShape.transport(left), SumShape.transport(right));
        if (left.isVector() || right.isVector())
            return left.isVector() && right.isVector() && Objects.equals(left.getVector(), right.getVector()) &&
                Objects.equals(left.getPrimReps(), right.getPrimReps());
        if (left.hasUnknownBoxedLevity() && right.hasBoxedPointer() || right.hasUnknownBoxedLevity() && left.hasBoxedPointer()) return true;
        return Objects.equals(left.getPrimReps(), right.getPrimReps());
    }
    private static boolean compatibleChildren(List<CoreRepresentation> left, List<CoreRepresentation> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (!compatible(left.get(i), right.get(i))) return false;
        return true;
    }
    private static boolean compatibleTransport(SumShape.Transport left, SumShape.Transport right) {
        return left.getProjections().equals(right.getProjections()) && compatibleChildren(left.getFields(), right.getFields());
    }
    public static String compatibilityKey(CoreRepresentation proof) { return signature(proof).toString().intern(); }
    public static void requireCompatible(CoreRepresentation expected, CoreRepresentation actual) { requireCompatible(expected, actual, false); }
    public static void requireCompatible(CoreRepresentation expected, CoreRepresentation actual, boolean component) {
        if (actual.getPresent() && (component || expected.isTypedTransport() || actual.isTypedTransport()) && !compatible(expected, actual))
            throw new RuntimeFault("Conflicting logical tuple representation proofs");
    }
    private static Object signature(CoreRepresentation proof) {
        if (proof.getComponents() != null || proof.getAlternatives() != null) {
            var children = new ArrayList<Object>();
            for (CoreRepresentation child : proof.getComponents() != null ? proof.getComponents() : proof.getAlternatives()) children.add(signature(child));
            return proof.getComponents() != null ? List.of("tuple", children) :
                Arrays.asList("sum", children, proof.getPrimReps(), proof.getTagSlot(), proof.getAlternativeSlots());
        }
        return proof.isVector() ? Arrays.asList("vector", proof.getVector(), proof.getPrimReps()) :
            List.of("scalar", proof.getPrimReps() == null ? List.of("?") : proof.getPrimReps());
    }
    public static void validate(CoreRepresentation proof) {
        if (proof.getKind() != CoreKind.UNKNOWN) throw new RuntimeFault("Tuple proof must retain its aggregate kind");
        if (proof.getComponents() != null) for (CoreRepresentation field : proof.getComponents()) if (field.isTuple()) validate(field);
        List<CoreRepresentation> fields = flatten(proof);
        for (CoreRepresentation field : fields) if (field.isVector()) VectorLayout.validate(field);
        for (CoreRepresentation field : fields) if (!field.isVector()) {
            CoreKind kind = field.getKind();
            List<String> reps = field.getPrimReps();
            if ((kind != CoreKind.LONG && kind != CoreKind.FLOAT && kind != CoreKind.DOUBLE && kind != CoreKind.ADDRESS && kind != CoreKind.DATA && kind != CoreKind.CLOSURE && kind != CoreKind.OBJECT) ||
                reps == null || reps.size() != 1 || !HandoffLayout.supportsResult(reps.getFirst()))
                throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-tuple has unsupported fields");
        }
        for (CoreRepresentation field : fields) if (field.getKind() == CoreKind.ADDRESS && !field.getEvaluated())
            throw new UnsupportedCore("Unsupported Core aggregate representation: AddrRep tuple field needs an evaluated carrier");
        List<String> reps = new ArrayList<>();
        for (CoreRepresentation leaf : logicalLeaves(proof)) {
            if (leaf.getPrimReps() == null) reps = null;
            else if (reps != null) reps.addAll(leaf.getPrimReps());
        }
        if (!Objects.equals(proof.getPrimReps(), reps)) throw new RuntimeFault("Tuple components disagree with primitive representations");
    }
    /** Temporary mixed-source ABI; direct Java consumers use the static methods above. */
    public static final Companion Companion = new Companion();
    public static final class Companion {
        private Companion() {}
        public List<CoreRepresentation> flatten(CoreRepresentation proof) { return TupleShape.flatten(proof); }
        public List<CoreRepresentation> logicalLeaves(CoreRepresentation proof) { return TupleShape.logicalLeaves(proof); }
        public boolean compatible(CoreRepresentation left, CoreRepresentation right) { return TupleShape.compatible(left, right); }
        public String compatibilityKey(CoreRepresentation proof) { return TupleShape.compatibilityKey(proof); }
        public void requireCompatible(CoreRepresentation expected, CoreRepresentation actual, boolean component) { TupleShape.requireCompatible(expected, actual, component); }
        public void validate(CoreRepresentation proof) { TupleShape.validate(proof); }
    }
}
