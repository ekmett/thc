// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only the original local clone FCall; no native stack layout or remote clone ABI. */
public final class CoreStackForeign {
    private CoreStackForeign() {}
    public static final String CLONE = "stg_cloneMyStackzh";
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
        "arity", "suppliedArity", "argumentReps", "resultRep");
    private static final List<String> SNAPSHOT_REPS = List.of("BoxedRep (Just Unlifted)");

    private static void requireProof(boolean condition, String detail) {
        if (!condition) throw new RuntimeFault("Invalid original stack clone: " + detail);
    }

    private static boolean one(Object value) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == 1;
    }

    private static boolean scalar(Object raw, String kind, List<String> reps, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> value)) return false;
        return value.keySet().equals(SCALAR_KEYS) && kind.equals(value.get("kind")) && reps.equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (evaluated == null || evaluated.equals(value.get("evaluated")));
    }

    private static boolean result(Object raw, boolean declared) {
        if (!(raw instanceof Map<?, ?> value) || !(value.get("components") instanceof List<?> components)) return false;
        return value.keySet().equals(TUPLE_KEYS) && "unknown".equals(value.get("kind")) &&
            "unboxed-tuple".equals(value.get("aggregate")) && SNAPSHOT_REPS.equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(value.get("evaluated"))) &&
            components.size() == 2 && scalar(components.get(0), "void", List.of(), true) &&
            scalar(components.get(1), "object", SNAPSHOT_REPS, true);
    }

    public static void validateHead(List<?> function, boolean defined) {
        requireProof(function.size() == 3 && "var".equals(function.get(0)) &&
            function.get(1) instanceof String name && !name.isEmpty() && !defined,
            "unresolved declared foreign variable required");
        var metadata = CoreRepresentations.metadata(function);
        requireProof(scalar(metadata == null ? null : metadata.get("rep"), "closure", List.of("BoxedRep (Just Lifted)"), true),
            "unresolved declared foreign variable required");
    }

    /** Before generic analyses cast variable IDs, reject malformed recognized heads. */
    public static void validateHeads(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var child : map.values()) validateHeads(child);
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.get(0))) {
                var raw = list.size() > 6 ? list.get(6) : null;
                var descriptor = raw instanceof Map<?, ?> meta && meta.get("foreignCall") instanceof Map<?, ?> call ? call : null;
                var target = descriptor != null && descriptor.get("target") instanceof Map<?, ?> map ? map : null;
                if (target != null && CLONE.equals(target.get("symbol"))) {
                    if (list.size() <= 1 || !(list.get(1) instanceof List<?> head))
                        throw new RuntimeFault("Invalid original stack clone: missing variable head");
                    validateHead(head, false);
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    public static boolean validate(Object metadata, List<?> argumentReps, List<?> flags) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return false;
        if (!CLONE.equals(target.get("symbol"))) return false;
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && one(descriptor.get("schema")), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && "ghc-internal".equals(target.get("unit")) &&
            Boolean.TRUE.equals(target.get("isFunction")), "static ghc-internal function target");
        requireProof("prim".equals(descriptor.get("convention")) && "safe".equals(descriptor.get("safety")), "calling convention/safety");
        requireProof(one(descriptor.get("arity")) && one(descriptor.get("suppliedArity")), "saturated arity");
        requireProof(descriptor.get("argumentReps") instanceof List<?> declared && declared.size() == 1 &&
            scalar(declared.get(0), "void", List.of(), false), "declared State argument");
        requireProof(argumentReps.size() == 1 && scalar(argumentReps.get(0), "void", List.of(), null), "actual State argument");
        requireProof(List.of(false).equals(flags), "unlifted State flag");
        requireProof(result(descriptor.get("resultRep"), true) && result(meta.get("rep"), false), "State/snapshot tuple result");
        return true;
    }

    /** Occurrence metadata may not relabel an existing scalar or boxed binding as State. */
    public static void validateState(CoreRepresentation proof) {
        requireProof(proof.getPresent() && proof.getKind() == CoreKind.VOID && List.of().equals(proof.getPrimReps()) &&
            !proof.isAggregate() && !proof.isVector(), "lowered State argument");
    }

    public static void validateBinding(CoreRepresentation proof) {
        if (proof != null && proof.getPresent()) requireProof(!proof.isAggregate() && !proof.isVector() &&
            (proof.getKind() == CoreKind.VOID || proof.getKind() == CoreKind.UNKNOWN) &&
            (proof.getPrimReps() == null || proof.getPrimReps().isEmpty()), "stored State argument");
    }
}
