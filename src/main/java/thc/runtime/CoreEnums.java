// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The family is a compile-time proof, not a guess from the scalar reference carrier. */
final class CoreEnums {
    private CoreEnums() {}

    static List<String> validate(List<?> expression, CoreRepresentation operand,
            Map<String, ? extends Map<String, ?>> constructors) {
        // Special primitive lowering still validates supplied function-node metadata.
        if (expression.size() <= 1 || !(expression.get(1) instanceof List<?> function))
            throw bad("Missing primitive function");
        CoreRepresentations.expression(function);
        if (expression.size() <= 2 || !(expression.get(2) instanceof List<?> args))
            throw bad("Missing operands");
        if (args.size() != 1 || expression.size() <= 3 || !List.of(false).equals(expression.get(3)))
            throw bad("Exactly one unlifted Int# operand required");
        if (!operand.getPresent() || operand.getKind() != CoreKind.LONG
                || !List.of("IntRep").equals(operand.getPrimReps()) || operand.isAggregate() || operand.isVector())
            throw bad("Exact IntRep operand required");
        CoreRepresentation result = CoreRepresentations.expression(expression);
        // An erased newtype result cast may retain OBJECT; the family below
        // certifies the original enum operation, not the outer result type.
        if (!result.getPresent() || (result.getKind() != CoreKind.DATA && result.getKind() != CoreKind.OBJECT)
                || !List.of("BoxedRep (Just Lifted)").equals(result.getPrimReps())
                || result.isAggregate() || result.isVector())
            throw bad("Exact lifted data/object result required");
        if (expression.size() <= 6 || !(expression.get(6) instanceof Map<?, ?> metadata))
            throw bad("Missing application metadata");
        if (!(metadata.get("enumFamily") instanceof Map<?, ?> family))
            throw bad("Missing concrete enum family");
        if (!family.keySet().equals(Set.of("typeConstructor", "constructors"))
                || !(family.get("typeConstructor") instanceof String name) || name.isEmpty())
            throw bad("Malformed enum family");
        if (!(family.get("constructors") instanceof List<?> ids)) throw bad("Missing ordered constructors");
        Set<String> seen = new HashSet<>();
        if (ids.isEmpty()) throw bad("Invalid ordered constructors");
        for (Object value : ids)
            if (!(value instanceof String id) || id.isEmpty() || !seen.add(id))
                throw bad("Invalid ordered constructors");
        // A supplied record cannot contradict or extend the complete nominal family.
        for (var entry : constructors.entrySet()) {
            if (entry.getValue().get("enumFamily") instanceof Map<?, ?> declared
                    && Objects.equals(declared.get("typeConstructor"), family.get("typeConstructor"))
                    && (!declared.equals(family) || !seen.contains(entry.getKey())))
                throw bad("Contradictory family record " + entry.getKey());
        }
        List<String> ordered = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            String id = (String) ids.get(index);
            Map<String, ?> con = constructors.get(id);
            if (con == null) throw bad("Missing family constructor " + id);
            if (!family.equals(con.get("enumFamily")) || !"boxed".equals(con.get("kind"))
                    || !exactInteger(con.get("arity"), 0) || !exactInteger(con.get("tag"), index + 1))
                throw bad("Contradictory enum constructor " + id);
            for (String key : List.of("fieldReps", "fieldTypes", "fieldLifted", "strictFields"))
                if (!List.of().equals(con.get(key))) throw bad("Non-nullary enum constructor " + id);
            ordered.add(id);
        }
        return ordered;
    }

    private static boolean exactInteger(Object value, int expected) {
        return value instanceof Long longValue && longValue == (long) expected
                || value instanceof Integer intValue && intValue == expected;
    }

    private static RuntimeFault bad(String message) { return new RuntimeFault("tagToEnum#: " + message); }
}
