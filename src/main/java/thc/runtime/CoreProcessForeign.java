// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class CoreProcessForeign {
    private CoreProcessForeign() {}
    private static final Pattern UNIT = Pattern.compile("process-1\\.6\\.26\\.1-(?:inplace|[0-9a-f]+)");
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety", "arity",
        "suppliedArity", "argumentReps", "resultRep");

    private static void check(boolean valid, String detail) {
        if (!valid) throw fault("Invalid original process call: " + detail);
    }

    private static boolean exact(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    private static CoreKind kind(String primitive) {
        return switch (primitive) {
            case null -> CoreKind.VOID;
            case "AddrRep" -> CoreKind.ADDRESS;
            case "BoxedRep (Just Lifted)" -> CoreKind.CLOSURE;
            default -> CoreKind.LONG;
        };
    }

    private static boolean scalar(Object raw, String primitive, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> value)) return false;
        return value.keySet().equals(SCALAR_KEYS) && kind(primitive).name().toLowerCase(Locale.ROOT).equals(value.get("kind")) &&
            (primitive == null ? List.of() : List.of(primitive)).equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (evaluated == null || evaluated.equals(value.get("evaluated")));
    }

    private static boolean result(Object raw, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> value) || !(value.get("components") instanceof List<?> components)) return false;
        return value.keySet().equals(TUPLE_KEYS) && "unknown".equals(value.get("kind")) &&
            "unboxed-tuple".equals(value.get("aggregate")) && List.of("Int32Rep").equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (evaluated == null || evaluated.equals(value.get("evaluated"))) &&
            components.size() == 2 && scalar(components.get(0), null, true) && scalar(components.get(1), "Int32Rep", true);
    }

    public static void validateHead(List<?> function, boolean defined) {
        check(function.size() == 3 && "var".equals(function.get(0)) &&
            function.get(1) instanceof String name && !name.isEmpty() && !defined, "unresolved declared foreign variable required");
        var metadata = CoreRepresentations.metadata(function);
        check(scalar(metadata == null ? null : metadata.get("rep"), "BoxedRep (Just Lifted)", true),
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
                for (var operation : ProcessOp.values()) {
                    if (target != null && operation.getSymbol().equals(target.get("symbol"))) {
                        if (list.size() <= 1 || !(list.get(1) instanceof List<?> head))
                            throw fault("Invalid original process call: missing head");
                        validateHead(head, false);
                        break;
                    }
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    public static void validateOperand(ProcessOp operation, int index, CoreRepresentation lowered, CoreRepresentation stored) {
        var primitive = operation.getArguments().get(index);
        var kind = kind(primitive);
        var reps = primitive == null ? List.of() : List.of(primitive);
        check(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() &&
            lowered.getKind() == kind && reps.equals(lowered.getPrimReps()), "lowered operand " + index);
        if (stored != null && stored.getPresent()) check(!stored.isAggregate() && !stored.isVector() &&
            (stored.getKind() == kind || stored.getKind() == CoreKind.UNKNOWN) &&
            (stored.getPrimReps() == null || reps.equals(stored.getPrimReps())), "stored operand " + index);
    }

    public static ProcessOp validate(Object metadata, List<?> argumentReps, List<?> flags, Object resultRep) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return null;
        ProcessOp operation = null;
        for (var candidate : ProcessOp.values()) if (candidate.getSymbol().equals(target.get("symbol"))) { operation = candidate; break; }
        if (operation == null) return null;
        check(descriptor.keySet().equals(DESCRIPTOR_KEYS) && exact(descriptor.get("schema"), 1), "descriptor schema");
        check(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && target.get("unit") instanceof String unit && UNIT.matcher(unit).matches() &&
            Boolean.TRUE.equals(target.get("isFunction")), "static supported installed-library function target");
        check("ccall".equals(descriptor.get("convention")) && operation.getSafety().equals(descriptor.get("safety")), "convention/safety");
        var expected = operation.getArguments();
        int count = expected.size();
        check(exact(descriptor.get("arity"), count) && exact(descriptor.get("suppliedArity"), count), "saturated arity");
        var declared = descriptor.get("argumentReps") instanceof List<?> list ? list : null;
        check(declared != null && declared.size() == count, "declared argument representations");
        for (int i = 0; i < count; i++) check(scalar(declared.get(i), expected.get(i), false), "declared argument representations");
        check(argumentReps.size() == count, "actual argument representations");
        for (int i = 0; i < count; i++) check(scalar(argumentReps.get(i), expected.get(i), null), "actual argument representations");
        check(flags.size() == count, "unlifted argument flags");
        for (var flag : flags) check(Boolean.FALSE.equals(flag), "unlifted argument flags");
        check(result(descriptor.get("resultRep"), false) && result(meta.get("rep"), null) && result(resultRep, null), "State/result tuple");
        return operation;
    }
}
