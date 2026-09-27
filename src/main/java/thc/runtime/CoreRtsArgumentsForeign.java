// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Exact admission of original program-arguments imports. */
public final class CoreRtsArgumentsForeign {
    private CoreRtsArgumentsForeign() {}
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
        "arity", "suppliedArity", "argumentReps", "resultRep");

    private static void requireProof(boolean valid, String detail) {
        if (!valid) throw fault("Invalid original program-arguments call: " + detail);
    }

    private static boolean exact(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    private static CoreKind kind(String primitive) {
        if (primitive == null) return CoreKind.VOID;
        if ("AddrRep".equals(primitive)) return CoreKind.ADDRESS;
        return CoreKind.LONG;
    }

    private static boolean scalar(Object raw, String primitive, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> value)) return false;
        return value.keySet().equals(SCALAR_KEYS) &&
            kind(primitive).name().toLowerCase(Locale.ROOT).equals(value.get("kind")) &&
            (primitive == null ? List.of() : List.of(primitive)).equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (evaluated == null || evaluated.equals(value.get("evaluated")));
    }

    private static boolean result(Object raw, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> value) || !(value.get("components") instanceof List<?> components)) return false;
        return value.keySet().equals(TUPLE_KEYS) && "unknown".equals(value.get("kind")) &&
            "unboxed-tuple".equals(value.get("aggregate")) && List.of().equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (evaluated == null || evaluated.equals(value.get("evaluated"))) &&
            components.size() == 1 && scalar(components.get(0), null, true);
    }

    public static void validateHead(List<?> function, boolean defined) {
        var metadata = CoreRepresentations.INSTANCE.metadata(function);
        var raw = metadata == null ? null : metadata.get("rep");
        var proof = raw instanceof Map<?, ?> value ? value : null;
        requireProof(function.size() == 3 && "var".equals(function.get(0)) &&
            function.get(1) instanceof String name && !name.isEmpty() && !defined &&
            proof != null && proof.keySet().equals(SCALAR_KEYS) && "closure".equals(proof.get("kind")) &&
            List.of("BoxedRep (Just Lifted)").equals(proof.get("primReps")) && Boolean.TRUE.equals(proof.get("evaluated")),
            "unresolved declared foreign variable required");
    }

    public static void validateHeads(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var child : map.values()) validateHeads(child);
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.get(0))) {
                var raw = list.size() > 6 ? list.get(6) : null;
                var descriptor = raw instanceof Map<?, ?> meta && meta.get("foreignCall") instanceof Map<?, ?> call ? call : null;
                var target = descriptor != null && descriptor.get("target") instanceof Map<?, ?> map ? map : null;
                for (var operation : RtsArgumentsOp.values()) {
                    if (operation.getSymbol().equals(target == null ? null : target.get("symbol"))) {
                        if (list.size() <= 1 || !(list.get(1) instanceof List<?> head))
                            throw fault("Invalid original program-arguments call: missing head");
                        validateHead(head, false);
                        break;
                    }
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    public static void validateOperand(RtsArgumentsOp operation, int index,
                                       CoreRepresentation lowered, CoreRepresentation stored) {
        var kind = kind(operation.getArguments().get(index));
        requireProof(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() && lowered.getKind() == kind,
            "lowered operand " + index);
        if (stored != null && stored.getPresent())
            requireProof(!stored.isAggregate() && !stored.isVector() &&
                (stored.getKind() == kind || stored.getKind() == CoreKind.UNKNOWN), "stored operand " + index);
    }

    public static RtsArgumentsOp validate(Object metadata, List<?> argumentReps, List<?> flags, Object resultRep) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return null;
        RtsArgumentsOp operation = null;
        for (var candidate : RtsArgumentsOp.values()) {
            if (candidate.getSymbol().equals(target.get("symbol"))) { operation = candidate; break; }
        }
        if (operation == null) return null;
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && exact(descriptor.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && "ghc-internal".equals(target.get("unit")) &&
            Boolean.TRUE.equals(target.get("isFunction")), "static ghc-internal function target");
        requireProof("ccall".equals(descriptor.get("convention")) && "unsafe".equals(descriptor.get("safety")), "convention/safety");
        int count = 3;
        requireProof(exact(descriptor.get("arity"), count) && exact(descriptor.get("suppliedArity"), count), "saturated arity");
        var declared = descriptor.get("argumentReps") instanceof List<?> values ? values : null;
        boolean valid = declared != null && declared.size() == count;
        for (int index = 0; valid && index < count; index++)
            valid = scalar(declared.get(index), operation.getArguments().get(index), false);
        requireProof(valid, "declared argument representations");
        valid = argumentReps.size() == count;
        for (int index = 0; valid && index < count; index++)
            valid = scalar(argumentReps.get(index), operation.getArguments().get(index), null);
        requireProof(valid, "actual argument representations");
        requireProof(Collections.nCopies(count, false).equals(flags), "unlifted argument flags");
        requireProof(result(descriptor.get("resultRep"), false) &&
            result(meta.get("rep"), null) &&
            result(resultRep, null), "State tuple");
        return operation;
    }
}
