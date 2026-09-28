// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Original pinned POSIX imports, with their emitted GHC ABI checked once at admission. */
public final class CoreEnvironmentForeign {
    private CoreEnvironmentForeign() {}
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
        "arity", "suppliedArity", "argumentReps", "resultRep");

    private static void requireProof(boolean valid, String detail) {
        if (!valid) throw fault("Invalid original environment call: " + detail);
    }

    private static boolean exact(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    private static CoreKind kind(String primitive) {
        if (primitive == null) return CoreKind.VOID;
        if ("AddrRep".equals(primitive)) return CoreKind.ADDRESS;
        if ("BoxedRep (Just Lifted)".equals(primitive)) return CoreKind.CLOSURE;
        return CoreKind.LONG;
    }

    private static boolean scalar(Object raw, String primitive, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> value)) return false;
        return value.keySet().equals(SCALAR_KEYS) &&
            kind(primitive).name().toLowerCase(Locale.ROOT).equals(value.get("kind")) &&
            (primitive == null ? List.of() : List.of(primitive)).equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (evaluated == null || evaluated.equals(value.get("evaluated")));
    }

    private static boolean result(Object raw, String primitive, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> value) || !(value.get("components") instanceof List<?> components)) return false;
        return value.keySet().equals(TUPLE_KEYS) && "unknown".equals(value.get("kind")) &&
            "unboxed-tuple".equals(value.get("aggregate")) && List.of(primitive).equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (evaluated == null || evaluated.equals(value.get("evaluated"))) &&
            components.size() == 2 && scalar(components.get(0), null, true) && scalar(components.get(1), primitive, true);
    }

    public static void validateHead(List<?> function, boolean defined) {
        requireProof(function.size() == 3 && "var".equals(function.get(0)) &&
            function.get(1) instanceof String name && !name.isEmpty() && !defined,
            "unresolved declared foreign variable required");
        var metadata = CoreRepresentations.metadata(function);
        requireProof(scalar(metadata == null ? null : metadata.get("rep"), "BoxedRep (Just Lifted)", true),
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
                for (var operation : EnvironmentOp.values()) {
                    if (operation.getSymbol().equals(target == null ? null : target.get("symbol"))) {
                        if (list.size() <= 1 || !(list.get(1) instanceof List<?> head))
                            throw fault("Invalid original environment call: missing head");
                        validateHead(head, false);
                        break;
                    }
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    public static void validateOperand(EnvironmentOp operation, int index,
                                       CoreRepresentation lowered, CoreRepresentation stored) {
        var kind = kind(operation.getArguments().get(index));
        requireProof(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() && lowered.getKind() == kind,
            "lowered operand " + index);
        if (stored != null && stored.getPresent())
            requireProof(!stored.isAggregate() && !stored.isVector() &&
                (stored.getKind() == kind || stored.getKind() == CoreKind.UNKNOWN), "stored operand " + index);
    }

    public static EnvironmentOp validate(Object metadata, List<?> argumentReps, List<?> flags, Object resultRep) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return null;
        EnvironmentOp operation = null;
        for (var candidate : EnvironmentOp.values()) {
            if (candidate.getSymbol().equals(target.get("symbol"))) { operation = candidate; break; }
        }
        if (operation == null) return null;
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && exact(descriptor.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && ("ghc-internal".equals(target.get("unit")) ||
                operation == EnvironmentOp.GET && CoreOriginalStdio.isOriginalUnixUnit(target.get("unit"))) &&
            Boolean.TRUE.equals(target.get("isFunction")), "static supported installed-library function target");
        requireProof("ccall".equals(descriptor.get("convention")) && "unsafe".equals(descriptor.get("safety")), "convention/safety");
        int count = operation.getArguments().size();
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
        requireProof(result(descriptor.get("resultRep"), operation.getResult(), false) &&
            result(meta.get("rep"), operation.getResult(), null) &&
            result(resultRep, operation.getResult(), null), "State/result tuple");
        return operation;
    }
}
