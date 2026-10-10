// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import static thc.runtime.Alternative.*;
import static thc.runtime.RuntimeFault.fault;

class Case extends Expr {
    protected final int binderSlot;
    @Children protected Alternative[] alternatives;
    @Child private LocalBinding scrutinee;
    private final boolean delimited;
    @CompilationFinal private boolean unusedBinder;
    @CompilationFinal private LiteralIndex literalIndex;

    /** Prepare only label selection; arm bodies and their activation stay here. */
    final void prepareLiteralIndex(CoreRepresentation binderProof) {
        literalIndex = LiteralIndex.prepare(alternatives, binderProof);
    }
    protected final int indexedLiteral(long value) { return literalIndex.find(value); }
    protected int literalChoice(VirtualFrame frame) {
        if (literalIndex.integer) {
            if (frame.isInt(binderSlot)) return indexedLiteral(frame.getInt(binderSlot));
        } else if (frame.isLong(binderSlot)) return indexedLiteral(frame.getLong(binderSlot));
        if (frame.isObject(binderSlot)) {
            Object value = frame.getObject(binderSlot);
            if (literalIndex.integer) return value instanceof Integer number ? indexedLiteral(number.intValue()) : -1;
            return value instanceof Long number ? indexedLiteral(number.longValue()) : -1;
        }
        if (frame.isInt(binderSlot) || frame.isLong(binderSlot) || frame.isFloat(binderSlot) ||
                frame.isDouble(binderSlot) || frame.isBoolean(binderSlot)) return -1;
        throw fault("Unsupported runtime frame slot tag");
    }
    private static final class LiteralIndex {
        private record Label(long value, int alternative) {}
        final boolean integer;
        @CompilationFinal(dimensions = 1) final long[] keys;
        @CompilationFinal(dimensions = 1) final int[] alternatives;
        private LiteralIndex(boolean integer, long[] keys, int[] alternatives) {
            this.integer = integer; this.keys = keys; this.alternatives = alternatives;
        }
        static LiteralIndex prepare(Alternative[] alternatives, CoreRepresentation proof) {
            if (!proof.isInt() && !proof.isLong()) return null;
            boolean integer = proof.isInt();
            var labels = new ArrayList<Label>();
            for (int i = 0; i < alternatives.length; i++) {
                Alternative arm = alternatives[i];
                if (arm.getKind() == DEFAULT_ALTERNATIVE) continue;
                if (arm.getKind() != LITERAL_ALTERNATIVE ||
                        (integer ? !(arm.getValue() instanceof Integer) : !(arm.getValue() instanceof Long))) return null;
                long value = integer ? ((Integer) arm.getValue()).longValue() : ((Long) arm.getValue()).longValue();
                labels.add(new Label(value, i));
            }
            // Match the existing scalar-case policy; duplicates keep source-order matching.
            if (labels.size() <= 8) return null;
            labels.sort(java.util.Comparator.comparingLong(Label::value));
            long[] keys = new long[labels.size()];
            int[] indices = new int[labels.size()];
            for (int i = 0; i < keys.length; i++) {
                Label label = labels.get(i);
                if (i != 0 && keys[i - 1] == label.value) return null;
                keys[i] = label.value; indices[i] = label.alternative;
            }
            return new LiteralIndex(integer, keys, indices);
        }
        int find(long value) {
            int from = 0, end = keys.length - 1;
            while (from <= end) {
                int middle = (from + end) >>> 1;
                long key = keys[middle];
                if (value < key) end = middle - 1;
                else if (value > key) from = middle + 1;
                else return alternatives[middle];
            }
            return -1;
        }
    }
    /** Assigned during lowering, before adoption; matching still needs the slot. */
    final Case discardUnusedBinder() { unusedBinder = true; return this; }
    Case(Expr scrutinee, int binderSlot, Alternative[] alternatives, Metrics metrics) {
        this(scrutinee, binderSlot, alternatives, metrics, null, false);
    }
    Case(Expr scrutinee, int binderSlot, Alternative[] alternatives, Metrics metrics,
         CoreRepresentation binderProof, boolean delimited) {
        this.binderSlot = binderSlot; this.alternatives = alternatives; this.delimited = delimited;
        var proofs = new ArrayList<CoreRepresentation>(alternatives.length);
        var kinds = new HashSet<CoreKind>();
        boolean evaluated = true, present = alternatives.length != 0, references = alternatives.length != 0;
        for (Alternative alternative : alternatives) {
            CoreRepresentation proof = alternative.getBody().getRepresentation();
            proofs.add(proof); kinds.add(proof.getKind());
            evaluated &= proof.getEvaluated(); present &= proof.getPresent();
            references &= proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.CLOSURE || proof.getKind() == CoreKind.OBJECT;
        }
        CoreKind kind = kinds.size() == 1 ? kinds.iterator().next() : references ? CoreKind.OBJECT : CoreKind.UNKNOWN;
        CoreRepresentation first = proofs.isEmpty() ? null : proofs.getFirst();
        boolean aggregate = first != null && first.isAggregate(), narrow = first != null && first.isInt();
        for (CoreRepresentation proof : proofs) {
            aggregate = aggregate && proof.isAggregate() && TupleShape.Companion.compatible(first, proof);
            narrow = narrow && Objects.equals(proof.getPrimReps(), first.getPrimReps());
        }
        CoreRepresentation selected = null;
        if (aggregate) selected = first;
        else {
            CoreRepresentation vector = CoreVectors.caseResult(proofs);
            if (vector != null) setRepresentation(vector);
            else if (narrow) selected = first;
            else setRepresentation(new CoreRepresentation(kind, evaluated, present, null, null, null, null, null, null));
        }
        if (selected != null) setRepresentation(selected.copy(selected.getKind(), evaluated, selected.getPresent(),
            selected.getPrimReps(), selected.getComponents(), selected.getVector(), selected.getAlternatives(),
            selected.getTagSlot(), selected.getAlternativeSlots()));
        Evaluate evaluatedScrutinee = new Evaluate(scrutinee, metrics);
        if (binderProof != null) evaluatedScrutinee.setRepresentation(evaluatedScrutinee.getRepresentation().refine(
            binderProof.copy(binderProof.getKind(), false, binderProof.getPresent(), binderProof.getPrimReps(),
                binderProof.getComponents(), binderProof.getVector(), binderProof.getAlternatives(),
                binderProof.getTagSlot(), binderProof.getAlternativeSlots())));
        this.scrutinee = new LocalBinding(binderSlot, evaluatedScrutinee, true);
    }
    @Override public void prepareTuple(int[] slots, int offset) {
        for (Alternative alternative : alternatives) alternative.getBody().prepareTuple(slots, offset);
    }
    protected final void prepare(VirtualFrame frame) { prepare(frame, null, 0); }
    protected final void prepare(VirtualFrame frame, int[] destination, int offset) {
        try { scrutinee.write(frame); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame frame, Object input) {
                    Expr branch = select(frame);
                    return destination == null ? branch.execute(frame) : branch.executeTuple(frame, destination, offset);
                }
            });
        } catch (DelimitedCut cut) {
            if (!delimited) throw cut;
            throw cut.append(frame, new DelimitedStep() {
                @Override public Object resume(MaterializedFrame frame, DelimitedResume input,
                                               MaskingState ambient, DelimitedStep outerMask) {
                    // LocalBinding's saved write already installed the scrutinee.
                    input.get();
                    Expr branch = select(frame);
                    return destination == null ? branch.execute(frame) : branch.executeTuple(frame, destination, offset);
                }
            });
        }
    }
    private Expr select(VirtualFrame frame) {
        int choice = literalIndex == null ? -1 : literalChoice(frame);
        Alternative fallback = null;
        for (int index = 0; index < alternatives.length; index++) {
            Alternative alt = alternatives[index];
            if (alt.getKind() == DEFAULT_ALTERNATIVE) { fallback = alt; continue; }
            if (literalIndex == null ? matches(frame, alt) : choice == index) return selected(frame, alt);
        }
        if (fallback == null) throw fault("Non-exhaustive Core case");
        return selected(frame, fallback);
    }
    protected boolean matches(VirtualFrame frame, Alternative alternative) { return alternative.matches(frame, binderSlot); }
    @ExplodeLoop @Override public Object execute(VirtualFrame frame) {
        prepare(frame);
        int choice = literalIndex == null ? -1 : literalChoice(frame);
        Alternative fallback = null;
        for (int index = 0; index < alternatives.length; index++) {
            Alternative alt = alternatives[index];
            if (alt.getKind() == DEFAULT_ALTERNATIVE) { fallback = alt; continue; }
            if (literalIndex == null ? matches(frame, alt) : choice == index) {
                return selected(frame, alt).execute(frame);
            }
        }
        if (fallback == null) throw fault("Non-exhaustive Core case");
        return selected(frame, fallback).execute(frame);
    }
    @ExplodeLoop @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame);
        int choice = literalIndex == null ? -1 : literalChoice(frame);
        Alternative fallback = null;
        for (int index = 0; index < alternatives.length; index++) {
            Alternative alt = alternatives[index];
            if (alt.getKind() == DEFAULT_ALTERNATIVE) { fallback = alt; continue; }
            if (literalIndex == null ? matches(frame, alt) : choice == index) {
                return selected(frame, alt).executeInt(frame);
            }
        }
        if (fallback == null) throw fault("Non-exhaustive Core case");
        return selected(frame, fallback).executeInt(frame);
    }
    @ExplodeLoop @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame);
        int choice = literalIndex == null ? -1 : literalChoice(frame);
        Alternative fallback = null;
        for (int index = 0; index < alternatives.length; index++) {
            Alternative alt = alternatives[index];
            if (alt.getKind() == DEFAULT_ALTERNATIVE) { fallback = alt; continue; }
            if (literalIndex == null ? matches(frame, alt) : choice == index) {
                return selected(frame, alt).executeLong(frame);
            }
        }
        if (fallback == null) throw fault("Non-exhaustive Core case");
        return selected(frame, fallback).executeLong(frame);
    }
    @ExplodeLoop @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame);
        int choice = literalIndex == null ? -1 : literalChoice(frame);
        Alternative fallback = null;
        for (int index = 0; index < alternatives.length; index++) {
            Alternative alt = alternatives[index];
            if (alt.getKind() == DEFAULT_ALTERNATIVE) { fallback = alt; continue; }
            if (literalIndex == null ? matches(frame, alt) : choice == index) {
                return selected(frame, alt).executeFloat(frame);
            }
        }
        if (fallback == null) throw fault("Non-exhaustive Core case");
        return selected(frame, fallback).executeFloat(frame);
    }
    @ExplodeLoop @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame);
        int choice = literalIndex == null ? -1 : literalChoice(frame);
        Alternative fallback = null;
        for (int index = 0; index < alternatives.length; index++) {
            Alternative alt = alternatives[index];
            if (alt.getKind() == DEFAULT_ALTERNATIVE) { fallback = alt; continue; }
            if (literalIndex == null ? matches(frame, alt) : choice == index) {
                return selected(frame, alt).executeDouble(frame);
            }
        }
        if (fallback == null) throw fault("Non-exhaustive Core case");
        return selected(frame, fallback).executeDouble(frame);
    }
    @ExplodeLoop @Override public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame);
        int choice = literalIndex == null ? -1 : literalChoice(frame);
        Alternative fallback = null;
        for (int index = 0; index < alternatives.length; index++) {
            Alternative alt = alternatives[index];
            if (alt.getKind() == DEFAULT_ALTERNATIVE) { fallback = alt; continue; }
            if (literalIndex == null ? matches(frame, alt) : choice == index) {
                return selected(frame, alt).executeClosure(frame);
            }
        }
        if (fallback == null) throw fault("Non-exhaustive Core case");
        return selected(frame, fallback).executeClosure(frame);
    }
    @ExplodeLoop @Override public DataValue executeDataValue(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame);
        int choice = literalIndex == null ? -1 : literalChoice(frame);
        Alternative fallback = null;
        for (int index = 0; index < alternatives.length; index++) {
            Alternative alt = alternatives[index];
            if (alt.getKind() == DEFAULT_ALTERNATIVE) { fallback = alt; continue; }
            if (literalIndex == null ? matches(frame, alt) : choice == index) {
                return selected(frame, alt).executeDataValue(frame);
            }
        }
        if (fallback == null) throw fault("Non-exhaustive Core case");
        return selected(frame, fallback).executeDataValue(frame);
    }
    @ExplodeLoop @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame);
        int choice = literalIndex == null ? -1 : literalChoice(frame);
        Alternative fallback = null;
        for (int index = 0; index < alternatives.length; index++) {
            Alternative alt = alternatives[index];
            if (alt.getKind() == DEFAULT_ALTERNATIVE) { fallback = alt; continue; }
            if (literalIndex == null ? matches(frame, alt) : choice == index) {
                return selected(frame, alt).executeAddress(frame);
            }
        }
        if (fallback == null) throw fault("Non-exhaustive Core case");
        return selected(frame, fallback).executeAddress(frame);
    }
    @ExplodeLoop @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        prepare(frame, slots, offset);
        int choice = literalIndex == null ? -1 : literalChoice(frame);
        Alternative fallback = null;
        for (int index = 0; index < alternatives.length; index++) {
            Alternative alt = alternatives[index];
            if (alt.getKind() == DEFAULT_ALTERNATIVE) { fallback = alt; continue; }
            if (literalIndex == null ? matches(frame, alt) : choice == index) {
                return selected(frame, alt).executeTuple(frame, slots, offset);
            }
        }
        if (fallback == null) throw fault("Non-exhaustive Core case");
        return selected(frame, fallback).executeTuple(frame, slots, offset);
    }
    @ExplodeLoop protected final Expr selected(VirtualFrame frame, Alternative alt) {
        if (alt.getKind() == DATA_ALTERNATIVE) {
            Object scrutinee = frame.getObject(binderSlot);
            if (!(scrutinee instanceof DataValue data)) throw fault("Invalid constructor case");
            for (int i = 0; i < alt.getFields().length; i++) {
                int[] lanes = i < alt.getVectorFields().length ? alt.getVectorFields()[i] : null;
                if (lanes == null) alt.restore(data, i, frame, alt.getFields()[i]);
                else alt.restoreVector(data, i, frame, lanes);
            }
        }
        alt.releaseUnusedFields(frame);
        // Selection and field restoration have consumed the scrutinee. Keep a
        // live/captured binder, but do not carry a dead value into the next loop.
        if (unusedBinder) frame.clear(binderSlot);
        return alt.getBody();
    }
}
