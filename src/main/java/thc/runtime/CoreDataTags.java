// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A concrete GHC family, retained before type erasure, is required by both variants. */
final class CoreDataTags {
    static final Set<String> operations = new LinkedHashSet<>(List.of("dataToTagSmall#", "dataToTagLarge#"));

    private CoreDataTags() {}

    static List<String> validate(List<?> expression, CoreRepresentation operand,
            Map<String, ? extends Map<String, ?>> constructors) {
        if (expression.size() <= 1 || !(expression.get(1) instanceof List<?> function))
            throw bad("Missing primitive function");
        CoreRepresentations.expression(function);
        Object name = function.size() > 1 ? function.get(1) : null;
        if (!"dataToTagSmall#".equals(name) && !"dataToTagLarge#".equals(name))
            throw bad("Unknown primitive variant");
        if (expression.size() <= 2 || !(expression.get(2) instanceof List<?> args))
            throw bad("Missing operand");
        if (args.size() != 1) throw bad("Exactly one data operand required");
        boolean lifted;
        if (List.of("BoxedRep (Just Lifted)").equals(operand.getPrimReps())) lifted = true;
        else if (List.of("BoxedRep (Just Unlifted)").equals(operand.getPrimReps())) lifted = false;
        else throw bad("Exact known boxed levity required");
        if (!operand.getPresent() || operand.getKind() != CoreKind.DATA || operand.isAggregate() || operand.isVector())
            throw bad("Exact algebraic data operand required");
        if (expression.size() <= 3 || !List.of(lifted).equals(expression.get(3))) throw bad("Operand levity mismatch");
        CoreRepresentation result = CoreRepresentations.expression(expression);
        if (!result.getPresent() || result.getKind() != CoreKind.LONG
                || !List.of("IntRep").equals(result.getPrimReps()) || result.isAggregate() || result.isVector())
            throw bad("Exact IntRep result required");
        if (expression.size() <= 6 || !(expression.get(6) instanceof Map<?, ?> metadata))
            throw bad("Missing application metadata");
        if (!(metadata.get("dataToTagFamily") instanceof Map<?, ?> family)) throw bad("Missing concrete family");
        if (!family.keySet().equals(Set.of("typeConstructor", "constructors", "smallFamilyLimit", "smallFamily"))
                || !(family.get("typeConstructor") instanceof String typeName) || typeName.isEmpty())
            throw bad("Malformed family");
        if (!(family.get("constructors") instanceof List<?> ids)) throw bad("Missing ordered constructors");
        Set<String> seen = new HashSet<>();
        if (ids.isEmpty()) throw bad("Invalid ordered constructors");
        for (Object value : ids)
            if (!(value instanceof String id) || id.isEmpty() || !seen.add(id))
                throw bad("Invalid ordered constructors");
        // The pinned 64-bit GHC exporter records mAX_PTR_TAG and isSmallFamily.
        if (!exactInteger(family.get("smallFamilyLimit"), 7)
                || !Boolean.valueOf(ids.size() <= 7).equals(family.get("smallFamily")))
            throw bad("Invalid pinned target small-family proof");
        if ("dataToTagSmall#".equals(name) != Boolean.TRUE.equals(family.get("smallFamily")))
            throw bad("Primitive variant does not match family size");
        for (var entry : constructors.entrySet()) {
            if (entry.getValue().get("dataToTagFamily") instanceof Map<?, ?> declared
                    && Objects.equals(declared.get("typeConstructor"), family.get("typeConstructor"))
                    && (!declared.equals(family) || !seen.contains(entry.getKey())))
                throw bad("Contradictory supplied family record " + entry.getKey());
        }
        List<String> ordered = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            String id = (String) ids.get(index);
            Map<String, ?> con = constructors.get(id);
            if (con == null) throw bad("Missing family constructor " + id);
            Object arity = con.get("arity");
            boolean validArity = arity instanceof Integer intValue && intValue >= 0
                    || arity instanceof Long longValue && longValue >= 0 && longValue <= Integer.MAX_VALUE;
            if (!family.equals(con.get("dataToTagFamily")) || !"boxed".equals(con.get("kind"))
                    || !validArity || !exactInteger(con.get("tag"), index + 1))
                throw bad("Contradictory constructor " + id);
            new CoreFields(con);
            ordered.add(id);
        }
        return ordered;
    }

    private static boolean exactInteger(Object value, int expected) {
        return value instanceof Long longValue && longValue == (long) expected
                || value instanceof Integer intValue && intValue == expected;
    }

    private static RuntimeFault bad(String message) { return new RuntimeFault("dataToTag: " + message); }
}
