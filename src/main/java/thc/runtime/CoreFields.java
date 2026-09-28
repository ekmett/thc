// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Constructor metadata describes representation after the worker's CBV obligations. */
public final class CoreFields {
    private final String[] storage;
    private final Class<?>[] referenceTypes;
    private final CoreRepresentation[] vectorProofs;
    @CompilationFinal(dimensions = 1) private final CoreRepresentation[] logicalProofs;
    @CompilationFinal(dimensions = 1) private final int[] offsets;
    public String[] getStorage() { return storage; }
    public Class<?>[] getReferenceTypes() { return referenceTypes; }
    public CoreRepresentation[] getVectorProofs() { return vectorProofs; }
    public CoreRepresentation[] getLogicalProofs() { return logicalProofs; }
    public int[] getOffsets() { return offsets; }
    public boolean getHasAggregates() {
        for (CoreRepresentation proof : logicalProofs) if (proof.isAggregate()) return true;
        return false;
    }
    public CoreFields(Map<String, ?> info) {
        Object id = info.get("id"), kind = info.get("kind");
        if (kind != null && !kind.equals("boxed")) throw new UnsupportedCore("Unsupported constructor representation " + kind + ": " + id);
        if (!(info.get("arity") instanceof Number arityValue)) throw new RuntimeFault("Missing constructor arity: " + id);
        int arity = arityValue.intValue();
        if (arity < 0 || arityValue.doubleValue() != (double) arity) throw new RuntimeFault("Invalid constructor arity: " + id);
        if (!(info.get("fieldReps") instanceof List<?> reps)) throw new RuntimeFault("Missing constructor primitive representations: " + id);
        if (reps.size() != arity) throw new RuntimeFault("Constructor representation count mismatch: " + id);
        List<?> types = info.get("fieldTypes") instanceof List<?> list ? list : null;
        if (types != null && types.size() != arity) throw new RuntimeFault("Constructor field type count mismatch: " + id);
        logicalProofs = new CoreRepresentation[arity];
        for (int i = 0; i < arity; i++) {
            Object raw = types == null ? null : types.get(i);
            logicalProofs[i] = raw == null ? CoreRepresentation.Companion.getUNKNOWN() : CoreRepresentations.parse(raw);
        }
        var leaves = new ArrayList<List<CoreRepresentation>>(arity);
        for (CoreRepresentation proof : logicalProofs) {
            List<CoreRepresentation> fields = proof.isSum() ? SumShape.INSTANCE.storage(proof) :
                proof.isTuple() ? TupleShape.Companion.flatten(proof) : List.of(proof);
            leaves.add(fields);
        }
        offsets = new int[arity + 1];
        for (int i = 0; i < arity; i++) offsets[i + 1] = offsets[i] + leaves.get(i).size();
        var physicalReps = new ArrayList<Object>();
        for (int i = 0; i < arity; i++) {
            if (logicalProofs[i].isAggregate()) for (CoreRepresentation field : leaves.get(i)) physicalReps.add(field.getPrimReps());
            else physicalReps.add(reps.get(i));
        }
        storage = new String[physicalReps.size()];
        for (int i = 0; i < storage.length; i++) {
            if (!(physicalReps.get(i) instanceof List<?> registers)) throw new UnsupportedCore("Unresolved constructor field representation: " + id);
            if (registers.isEmpty()) storage[i] = "VoidRep";
            else if (registers.size() == 1) {
                if (!(registers.getFirst() instanceof String rep)) throw new RuntimeFault("Invalid constructor field representation: " + id);
                storage[i] = switch (rep) {
                    case "BoxedRep (Just Lifted)" -> "LiftedRep";
                    case "BoxedRep (Just Unlifted)" -> "UnliftedRep";
                    case "BoxedRep Nothing" -> throw new UnsupportedCore("Unresolved constructor field levity: " + id);
                    default -> rep;
                };
            } else throw new UnsupportedCore("Multi-register constructor field unsupported: " + id);
        }
        referenceTypes = new Class<?>[storage.length];
        vectorProofs = new CoreRepresentation[storage.length];
        boolean hasVector = false;
        for (int i = 0; i < storage.length; i++) {
            if (storage[i].equals("AddrRep")) referenceTypes[i] = ManagedAddress.class;
            hasVector |= storage[i].startsWith("VecRep ");
        }
        if (hasVector && !info.containsKey("fieldTypes")) throw new UnsupportedCore("Vector constructor field requires exact logical metadata: " + id);
        for (int i = 0; i < arity; i++) if (List.of("AddrRep").equals(reps.get(i)) && !logicalProofs[i].isAggregate()) {
            if (info.containsKey("fieldLifted")) {
                if (!(info.get("fieldLifted") instanceof List<?> lifted)) throw new RuntimeFault("Invalid address constructor levity: " + id + " field " + i);
                if (lifted.size() != arity || !Boolean.FALSE.equals(lifted.get(i))) throw new RuntimeFault("Address constructor field must be unlifted: " + id + " field " + i);
            }
        }
        if (info.containsKey("fieldTypes")) {
            if (types == null) throw new RuntimeFault("Invalid constructor field types: " + id);
            if (!(info.get("strictFields") instanceof List<?> strict)) throw new RuntimeFault("Missing constructor strictness metadata: " + id);
            if (!(info.get("fieldLifted") instanceof List<?> lifted)) throw new RuntimeFault("Missing constructor representation metadata: " + id);
            if (types.size() != arity || strict.size() != arity || lifted.size() != arity) throw new RuntimeFault("Constructor field type count mismatch: " + id);
            for (int i = 0; i < arity; i++) {
                CoreRepresentation proof = logicalProofs[i];
                if (proof.isVector()) VectorLayout.validate(proof);
                else if (!proof.isAggregate()) CoreRepresentations.requireScalar(proof, "constructor field");
                if (!proof.getPresent() || !Objects.equals(proof.getPrimReps(), reps.get(i))) throw new RuntimeFault("Constructor field type disagrees with its primitive representation: " + id + " field " + i);
                if (!proof.isAggregate() && storage[offsets[i]].equals("AddrRep") && proof.getKind() != CoreKind.ADDRESS) throw new RuntimeFault("Address constructor field lacks its exact managed carrier: " + id + " field " + i);
                if (!(strict.get(i) instanceof Boolean strictField)) throw new RuntimeFault("Unknown constructor field strictness: " + id);
                boolean expectedLifted = !proof.isAggregate() && storage[offsets[i]].equals("LiftedRep");
                if (!Boolean.valueOf(expectedLifted).equals(lifted.get(i))) throw new RuntimeFault("Constructor field levity disagrees with its primitive representation: " + id + " field " + i);
                if (proof.getEvaluated() != (strictField || Boolean.FALSE.equals(lifted.get(i)))) throw new RuntimeFault("Constructor field evaluatedness lacks a worker obligation: " + id + " field " + i);
                List<CoreRepresentation> fields = leaves.get(i);
                for (int leaf = 0; leaf < fields.size(); leaf++) {
                    int physical = offsets[i] + leaf;
                    CoreRepresentation field = fields.get(leaf);
                    referenceTypes[physical] = field.referenceCarrier();
                    if (field.isVector()) vectorProofs[physical] = field;
                }
            }
        }
    }
}
