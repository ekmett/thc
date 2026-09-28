// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.*;

/** Declared nominal host types, checked against the optimized worker's physical ABI. */
public final class CoreHostSignature {
    private CoreHostSignature() {}

    public static CoreFunctionSignature select(Map<String, Object> binding, List<Map<String, Object>> bindings) {
        var actual = CoreRepresentations.knownFunctionSignature((List<?>) binding.get("expr"), bindings);
        if (!binding.containsKey("hostSignature")) return actual;
        if (!(binding.get("hostSignature") instanceof Map<?, ?> signature) ||
                !signature.keySet().equals(Set.of("inputs", "result")) || !(signature.get("inputs") instanceof List<?> inputs) ||
                actual == null || inputs.size() != actual.inputs().size())
            throw new RuntimeFault("Invalid declared host signature");
        var declared = new ArrayList<CoreRepresentation>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) declared.add(type(inputs.get(i), actual.inputs().get(i)));
        return new CoreFunctionSignature(List.copyOf(declared), type(signature.get("result"), actual.result()));
    }

    private static CoreRepresentation type(Object value, CoreRepresentation actual) {
        if (!(value instanceof Map<?, ?> type) || !type.keySet().equals(Set.of("rep", "carriers")) ||
                !(type.get("carriers") instanceof List<?> carriers))
            throw new RuntimeFault("Invalid nominal host type");
        var declared = CoreRepresentations.parse(type.get("rep"));
        if (!TupleShape.compatible(declared, actual)) throw new RuntimeFault("Declared host signature disagrees with worker representation");
        // Retain the worker's evaluation facts; nominal types must not force lifted values.
        var cursor = carriers.iterator();
        var result = attach(declared.refine(actual), cursor);
        if (cursor.hasNext()) throw new RuntimeFault("Excess nominal host carrier leaves");
        return result;
    }

    private static CoreRepresentation attach(CoreRepresentation proof, Iterator<?> carriers) {
        if (proof.isAggregate()) {
            var children = proof.isTuple() ? proof.getComponents() : proof.getAlternatives();
            var annotated = new ArrayList<CoreRepresentation>(children.size());
            for (var child : children) annotated.add(attach(child, carriers));
            return proof.copy(proof.getKind(), proof.getEvaluated(), proof.getPresent(), proof.getPrimReps(),
                proof.isTuple() ? List.copyOf(annotated) : null, proof.getVector(),
                proof.isSum() ? List.copyOf(annotated) : null, proof.getTagSlot(), proof.getAlternativeSlots());
        }
        if (!carriers.hasNext()) throw new RuntimeFault("Missing nominal host carrier leaf");
        Object carrier = carriers.next();
        if (carrier == null) return proof;
        if (!(carrier instanceof String name)) throw new RuntimeFault("Invalid nominal host carrier");
        return proof.withHostCarrier(switch (name) {
            case "object" -> CoreRepresentation.HostCarrier.OBJECT;
            case "interop-library" -> CoreRepresentation.HostCarrier.INTEROP_LIBRARY;
            default -> throw new RuntimeFault("Unknown nominal host carrier");
        });
    }
}
