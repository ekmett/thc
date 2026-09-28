// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CoreMd5Foreign {
    private CoreMd5Foreign() {}

    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
        "arity", "suppliedArity", "argumentReps", "resultRep");

    private static void requireProof(boolean condition, String detail) {
        if (!condition) throw new RuntimeFault("Invalid MD5 foreign call: " + detail);
    }

    private static boolean exactInteger(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    private static CoreKind kind(String primitive) {
        return switch (primitive) {
            case "AddrRep" -> CoreKind.ADDRESS;
            case "Int32Rep" -> CoreKind.LONG;
            case null, default -> CoreKind.VOID;
        };
    }

    private static boolean scalar(Object raw, String primitive, boolean declared) {
        if (!(raw instanceof Map<?, ?> value)) return false;
        return value.keySet().equals(SCALAR_KEYS) && kind(primitive).name().toLowerCase(Locale.ROOT).equals(value.get("kind")) &&
            (primitive == null ? List.of() : List.of(primitive)).equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(value.get("evaluated")));
    }

    private static boolean result(Object raw, String primitive, boolean declared) {
        if (!(raw instanceof Map<?, ?> value) || !(value.get("components") instanceof List<?> components)) return false;
        if (!value.keySet().equals(TUPLE_KEYS) || !"unknown".equals(value.get("kind")) ||
            !"unboxed-tuple".equals(value.get("aggregate")) ||
            !(primitive == null ? List.of() : List.of(primitive)).equals(value.get("primReps")) ||
            !(value.get("evaluated") instanceof Boolean) || (declared && !Boolean.FALSE.equals(value.get("evaluated"))) ||
            components.size() != (primitive == null ? 1 : 2)) return false;
        for (int i = 0; i < components.size(); i++)
            if (!scalar(components.get(i), i == 0 ? null : primitive, false) ||
                !Boolean.TRUE.equals(((Map<?, ?>) components.get(i)).get("evaluated"))) return false;
        return true;
    }

    public static void validateHead(List<?> function, boolean defined) {
        var metadata = CoreRepresentations.metadata(function);
        var proof = metadata != null && metadata.get("rep") instanceof Map<?, ?> map ? map : null;
        requireProof(function.size() == 3 && "var".equals(function.get(0)) &&
            function.get(1) instanceof String name && !name.isEmpty() && !defined && proof != null &&
            proof.keySet().equals(SCALAR_KEYS) && "closure".equals(proof.get("kind")) &&
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
                for (var operation : Md5ForeignOp.values()) {
                    if (target != null && operation.getSymbol().equals(target.get("symbol"))) {
                        if (list.size() <= 1 || !(list.get(1) instanceof List<?> head))
                            throw new RuntimeFault("Invalid MD5 foreign call: missing variable head");
                        validateHead(head, false);
                        break;
                    }
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    public static Md5ForeignOp validate(Object metadata, List<?> argumentReps, List<?> flags, Object resultRep) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return null;
        Md5ForeignOp operation = null;
        for (var candidate : Md5ForeignOp.values()) if (candidate.getSymbol().equals(target.get("symbol"))) { operation = candidate; break; }
        if (operation == null) return null;
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && exactInteger(descriptor.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && "ghc-internal".equals(target.get("unit")) && Boolean.TRUE.equals(target.get("isFunction")),
            "static ghc-internal function target");
        requireProof("ccall".equals(descriptor.get("convention")) && "unsafe".equals(descriptor.get("safety")), "calling convention/safety");
        requireProof(exactInteger(descriptor.get("arity"), operation.getArity()) &&
            exactInteger(descriptor.get("suppliedArity"), operation.getArity()), "saturated arity");
        var expected = switch (operation) {
            case INIT -> java.util.Arrays.asList("AddrRep", null);
            case UPDATE -> java.util.Arrays.asList("AddrRep", "AddrRep", "Int32Rep", null);
            case FINAL -> java.util.Arrays.asList("AddrRep", "AddrRep", null);
        };
        var declared = descriptor.get("argumentReps") instanceof List<?> list ? list : null;
        requireProof(declared != null && declared.size() == expected.size(), "declared argument representations");
        for (int i = 0; i < expected.size(); i++)
            requireProof(scalar(declared.get(i), expected.get(i), true), "declared argument representations");
        requireProof(argumentReps.size() == expected.size(), "actual argument representations");
        for (int i = 0; i < expected.size(); i++)
            requireProof(scalar(argumentReps.get(i), expected.get(i), false), "actual argument representations");
        requireProof(flags.size() == expected.size(), "unlifted argument flags");
        for (var flag : flags) requireProof(Boolean.FALSE.equals(flag), "unlifted argument flags");
        requireProof(result(descriptor.get("resultRep"), null, true) &&
            result(meta.get("rep"), null, false) && result(resultRep, null, false), "singleton State tuple result");
        return operation;
    }
}
