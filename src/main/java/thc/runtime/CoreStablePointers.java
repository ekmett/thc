// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Exact original stable-pointer release declaration. */
public final class CoreStablePointers {
    private CoreStablePointers() {}
    private static final String SYMBOL = "hs_free_stable_ptr";
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
        "arity", "suppliedArity", "argumentReps", "resultRep");
    private static final List<String> ADDRESS_REPS = List.of("AddrRep");

    private static void requireProof(boolean valid, String detail) {
        if (!valid) throw fault("Invalid original StablePtr free call: " + detail);
    }

    private static boolean exact(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    private static boolean scalar(Object raw, String kind, List<String> reps, boolean declared) {
        if (!(raw instanceof Map<?, ?> proof)) return false;
        return proof.keySet().equals(SCALAR_KEYS) && kind.equals(proof.get("kind")) && reps.equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(proof.get("evaluated")));
    }

    private static boolean result(Object raw, boolean declared) {
        if (!(raw instanceof Map<?, ?> proof) || !(proof.get("components") instanceof List<?> fields)) return false;
        return proof.keySet().equals(TUPLE_KEYS) && "unknown".equals(proof.get("kind")) &&
            "unboxed-tuple".equals(proof.get("aggregate")) && List.of().equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(proof.get("evaluated"))) &&
            fields.size() == 1 && scalar(fields.get(0), "void", List.of(), false) &&
            Boolean.TRUE.equals(((Map<?, ?>) fields.get(0)).get("evaluated"));
    }

    public static void validateHead(List<?> function, boolean defined) {
        var metadata = CoreRepresentations.metadata(function);
        var proof = metadata == null ? null : metadata.get("rep");
        requireProof(function.size() == 3 && "var".equals(function.get(0)) &&
            function.get(1) instanceof String name && !name.isEmpty() && !defined &&
            scalar(proof, "closure", List.of("BoxedRep (Just Lifted)"), false) &&
            Boolean.TRUE.equals(((Map<?, ?>) proof).get("evaluated")), "unresolved declared foreign variable required");
    }

    public static void validateHeads(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var child : map.values()) validateHeads(child);
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.get(0))) {
                var raw = list.size() > 6 ? list.get(6) : null;
                var descriptor = raw instanceof Map<?, ?> meta && meta.get("foreignCall") instanceof Map<?, ?> call ? call : null;
                var target = descriptor != null && descriptor.get("target") instanceof Map<?, ?> map ? map : null;
                if (target != null && SYMBOL.equals(target.get("symbol"))) {
                    if (list.size() <= 1 || !(list.get(1) instanceof List<?> head))
                        throw fault("Invalid original StablePtr free call: missing variable head");
                    validateHead(head, false);
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    public static boolean validate(Object metadata, List<?> arguments, List<?> flags, Object resultProof) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return false;
        if (!SYMBOL.equals(target.get("symbol"))) return false;
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && exact(descriptor.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && "ghc-internal".equals(target.get("unit")) &&
            Boolean.TRUE.equals(target.get("isFunction")), "exact installed GHC target");
        requireProof("ccall".equals(descriptor.get("convention")) && "unsafe".equals(descriptor.get("safety")) &&
            exact(descriptor.get("arity"), 2) && exact(descriptor.get("suppliedArity"), 2), "convention, safety or arity");
        requireProof(descriptor.get("argumentReps") instanceof List<?> declared && declared.size() == 2 &&
            scalar(declared.get(0), "address", ADDRESS_REPS, true) && scalar(declared.get(1), "void", List.of(), true) &&
            arguments.size() == 2 && scalar(arguments.get(0), "address", ADDRESS_REPS, false) &&
            scalar(arguments.get(1), "void", List.of(), false) && List.of(false, false).equals(flags),
            "address and State# arguments");
        requireProof(result(descriptor.get("resultRep"), true) && result(meta.get("rep"), false) && result(resultProof, false),
            "single-State# tuple result");
        return true;
    }

}
