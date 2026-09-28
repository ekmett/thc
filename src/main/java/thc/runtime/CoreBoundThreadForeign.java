// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Original GHC thread queries: capability support and the allocation counter. */
public final class CoreBoundThreadForeign {
    private CoreBoundThreadForeign() {}
    private static final String SYMBOL = "rtsSupportsBoundThreads";
    private static final String COUNTER = "stg_getThreadAllocationCounterzh";
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
        "arity", "suppliedArity", "argumentReps", "resultRep");

    private static void requireProof(boolean valid, String detail) {
        if (!valid) throw fault("Invalid original thread query: " + detail);
    }

    private static boolean one(Object value) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == 1;
    }

    private static boolean scalar(Object raw, String kind, List<String> reps, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> proof)) return false;
        return proof.keySet().equals(SCALAR_KEYS) && kind.equals(proof.get("kind")) && reps.equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean && (evaluated == null || evaluated.equals(proof.get("evaluated")));
    }

    private static boolean result(Object raw, boolean declared, String primitive) {
        if (!(raw instanceof Map<?, ?> proof) || !(proof.get("components") instanceof List<?> fields)) return false;
        return proof.keySet().equals(TUPLE_KEYS) && "unknown".equals(proof.get("kind")) &&
            "unboxed-tuple".equals(proof.get("aggregate")) && List.of(primitive).equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(proof.get("evaluated"))) &&
            fields.size() == 2 && scalar(fields.get(0), "void", List.of(), true) &&
            scalar(fields.get(1), "long", List.of(primitive), true);
    }

    public static void validateHead(List<?> function, boolean defined) {
        requireProof(function.size() == 3 && "var".equals(function.get(0)) &&
            function.get(1) instanceof String name && !name.isEmpty() && !defined,
            "unresolved original foreign variable required");
        var metadata = CoreRepresentations.INSTANCE.metadata(function);
        requireProof(scalar(metadata == null ? null : metadata.get("rep"), "closure", List.of("BoxedRep (Just Lifted)"), true),
            "unresolved original foreign variable required");
    }

    public static void validateHeads(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var child : map.values()) validateHeads(child);
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.get(0))) {
                var raw = list.size() > 6 ? list.get(6) : null;
                var descriptor = raw instanceof Map<?, ?> meta && meta.get("foreignCall") instanceof Map<?, ?> call ? call : null;
                var target = descriptor != null && descriptor.get("target") instanceof Map<?, ?> map ? map : null;
                if (target != null && (SYMBOL.equals(target.get("symbol")) || COUNTER.equals(target.get("symbol")))) {
                    if (list.size() <= 1 || !(list.get(1) instanceof List<?> head))
                        throw fault("Invalid original bound-thread support query: missing variable head");
                    validateHead(head, false);
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    public static boolean validate(Object metadata, List<?> arguments, List<?> flags, Object resultProof) {
        return validate(metadata, arguments, flags, resultProof, false);
    }

    public static boolean validate(Object metadata, List<?> arguments, List<?> flags, Object resultProof, boolean allocationCounter) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return false;
        if (!(allocationCounter ? COUNTER : SYMBOL).equals(target.get("symbol"))) return false;
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && one(descriptor.get("schema")), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && "ghc-internal".equals(target.get("unit")) &&
            Boolean.TRUE.equals(target.get("isFunction")), "exact installed GHC target");
        requireProof((allocationCounter ? "prim" : "ccall").equals(descriptor.get("convention")) &&
            (allocationCounter ? "safe" : "unsafe").equals(descriptor.get("safety")) &&
            one(descriptor.get("arity")) && one(descriptor.get("suppliedArity")), "convention, safety or arity");
        requireProof(descriptor.get("argumentReps") instanceof List<?> declared && declared.size() == 1 &&
            scalar(declared.get(0), "void", List.of(), false) && arguments.size() == 1 &&
            scalar(arguments.get(0), "void", List.of(), null) && List.of(false).equals(flags), "State# argument");
        var primitive = allocationCounter ? "Int64Rep" : "IntRep";
        requireProof(result(descriptor.get("resultRep"), true, primitive) && result(meta.get("rep"), false, primitive) &&
            result(resultProof, false, primitive), "State#/Int# tuple result (HsBool is StgInt, not CInt)");
        return true;
    }

    public static void validateOperand(CoreRepresentation lowered, CoreRepresentation stored) {
        requireProof(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() &&
            lowered.getKind() == CoreKind.VOID && List.of().equals(lowered.getPrimReps()), "lowered State#");
        if (stored != null && stored.getPresent()) requireProof(!stored.isAggregate() && !stored.isVector() &&
            (stored.getKind() == CoreKind.VOID || stored.getKind() == CoreKind.UNKNOWN) &&
            (stored.getPrimReps() == null || stored.getPrimReps().isEmpty()), "stored State#");
    }
}
