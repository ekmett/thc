// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

/** GHC 9.14.1: a levity-polymorphic reference and State produce bare State. */
public final class CoreTouch {
    private CoreTouch() {}
    private static final Set<String> KEYS = Set.of("kind", "primReps", "evaluated");
    private static final List<String> LIFTED = List.of("BoxedRep (Just Lifted)");
    private static final List<String> UNLIFTED = List.of("BoxedRep (Just Unlifted)");
    private static Map<?, ?> scalar(Object value) {
        return value instanceof Map<?, ?> map && map.keySet().equals(KEYS)
            && map.get("evaluated") instanceof Boolean ? map : null;
    }
    private static boolean state(Object value) {
        Map<?, ?> map = scalar(value);
        return map != null && "void".equals(map.get("kind")) && List.of().equals(map.get("primReps"));
    }
    public static void validateRaw(List<?> arguments, List<?> flags, Object result) {
        Map<?, ?> kept = scalar(arguments.isEmpty() ? null : arguments.getFirst());
        if (arguments.size() != 2 || kept == null ||
            !("object".equals(kept.get("kind")) || "data".equals(kept.get("kind")) || "closure".equals(kept.get("kind"))) ||
            !(LIFTED.equals(kept.get("primReps")) || UNLIFTED.equals(kept.get("primReps"))) ||
            !state(arguments.get(1)) || !state(result) ||
            !flags.equals(List.of(LIFTED.equals(kept.get("primReps")), false)))
            throw new RuntimeFault("touch#: exact reference, State input and bare State result required");
    }
    private static Object raw(CoreRepresentation value) {
        if (!value.getPresent() || value.isAggregate() || value.isVector()) return null;
        var map = new HashMap<String, Object>();
        map.put("kind", value.getKind().name().toLowerCase(java.util.Locale.ROOT));
        map.put("primReps", value.getPrimReps()); map.put("evaluated", value.getEvaluated());
        return map;
    }
    /** Recheck refined lexical proofs; occurrence metadata cannot disguise a scalar. */
    public static void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        validateRaw(arguments.stream().map(CoreTouch::raw).toList(), flags, raw(result));
    }
}
