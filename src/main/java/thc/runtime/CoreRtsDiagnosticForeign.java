// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Exact admission of original RTS diagnostics before any output occurs. */
public final class CoreRtsDiagnosticForeign {
    private CoreRtsDiagnosticForeign() {}
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep");

    private static void requireProof(boolean condition, String detail) {
        if (!condition) throw fault("Invalid original RTS diagnostic call: " + detail);
    }
    private static boolean exactInteger(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }
    private static CoreKind kind(String primitive) {
        return primitive == null ? CoreKind.VOID : "AddrRep".equals(primitive) ? CoreKind.ADDRESS : CoreKind.OBJECT;
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
        for (int i = 0; i < components.size(); i++) {
            if (!scalar(components.get(i), i == 0 ? null : primitive, false) ||
                !Boolean.TRUE.equals(((Map<?, ?>) components.get(i)).get("evaluated"))) return false;
        }
        return true;
    }

    public static void validateHead(List<?> function, boolean defined) {
        var metadata = CoreRepresentations.metadata(function);
        Map<?, ?> proof = metadata != null && metadata.get("rep") instanceof Map<?, ?> value ? value : null;
        requireProof(function.size() == 3 && "var".equals(function.get(0)) && function.get(1) instanceof String name &&
            !name.isEmpty() && !defined && proof != null && proof.keySet().equals(SCALAR_KEYS) &&
            "closure".equals(proof.get("kind")) && List.of("BoxedRep (Just Lifted)").equals(proof.get("primReps")) &&
            Boolean.TRUE.equals(proof.get("evaluated")), "unresolved declared foreign variable required");
    }

    public static void validateHeads(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var child : map.values()) validateHeads(child);
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.get(0))) {
                Map<?, ?> meta = list.size() > 6 && list.get(6) instanceof Map<?, ?> item ? item : null;
                Map<?, ?> descriptor = meta != null && meta.get("foreignCall") instanceof Map<?, ?> item ? item : null;
                Map<?, ?> target = descriptor != null && descriptor.get("target") instanceof Map<?, ?> item ? item : null;
                for (var operation : RtsDiagnosticOp.values()) {
                    if (target != null && operation.getSymbol().equals(target.get("symbol"))) {
                        if (list.size() <= 1 || !(list.get(1) instanceof List<?> function))
                            throw fault("Invalid original RTS diagnostic call: missing variable head");
                        validateHead(function, false);
                        break;
                    }
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    public static void validateOperand(RtsDiagnosticOp operation, int index,
                                       CoreRepresentation lowered, CoreRepresentation stored) {
        String primitive = operation.getArguments().get(index);
        CoreKind kind = kind(primitive);
        var reps = primitive == null ? List.of() : List.of(primitive);
        requireProof(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() &&
            lowered.getKind() == kind && reps.equals(lowered.getPrimReps()), "lowered operand " + index);
        if (stored != null && stored.getPresent())
            requireProof(!stored.isAggregate() && !stored.isVector() &&
                (stored.getKind() == kind || stored.getKind() == CoreKind.UNKNOWN) &&
                (stored.getPrimReps() == null || reps.equals(stored.getPrimReps())), "stored operand " + index);
    }

    public static RtsDiagnosticOp validate(Object metadata, List<?> argumentReps, List<?> flags, Object resultRep) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return null;
        RtsDiagnosticOp operation = null;
        for (var candidate : RtsDiagnosticOp.values()) {
            if (candidate.getSymbol().equals(target.get("symbol"))) { operation = candidate; break; }
        }
        if (operation == null) return null;
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && exactInteger(descriptor.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && "ghc-internal".equals(target.get("unit")) &&
            Boolean.TRUE.equals(target.get("isFunction")), "static ghc-internal function target");
        requireProof("ccall".equals(descriptor.get("convention")) && "unsafe".equals(descriptor.get("safety")), "calling convention/safety");
        var expected = operation.getArguments();
        requireProof(exactInteger(descriptor.get("arity"), expected.size()) &&
            exactInteger(descriptor.get("suppliedArity"), expected.size()), "saturated arity");
        List<?> declared = descriptor.get("argumentReps") instanceof List<?> values ? values : null;
        boolean valid = declared != null && declared.size() == expected.size();
        for (int i = 0; valid && i < expected.size(); i++) valid = scalar(declared.get(i), expected.get(i), true);
        requireProof(valid, "declared argument representations");
        valid = argumentReps.size() == expected.size();
        for (int i = 0; valid && i < expected.size(); i++) valid = scalar(argumentReps.get(i), expected.get(i), false);
        requireProof(valid, "actual argument representations");
        valid = flags.size() == expected.size();
        for (var flag : flags) if (!Boolean.FALSE.equals(flag)) { valid = false; break; }
        requireProof(valid, "unlifted argument flags");
        requireProof(result(descriptor.get("resultRep"), null, true) && result(meta.get("rep"), null, false) &&
            result(resultRep, null, false), "exact State/result tuple");
        return operation;
    }
}
