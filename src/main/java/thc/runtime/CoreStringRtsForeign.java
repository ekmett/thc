// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class CoreStringRtsForeign {
    private CoreStringRtsForeign() {}
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
        "arity", "suppliedArity", "argumentReps", "resultRep");

    private static void requireProof(boolean valid, String detail) {
        if (!valid) throw fault("Invalid original Posix string/RTS call: " + detail);
    }

    private static boolean exact(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    private static String kind(String rep) {
        return switch (rep) {
            case null -> "void";
            case "AddrRep" -> "address";
            case "BoxedRep (Just Lifted)" -> "closure";
            default -> "long";
        };
    }

    private static boolean scalar(Object raw, String rep, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> value)) return false;
        return value.keySet().equals(SCALAR_KEYS) && kind(rep).equals(value.get("kind")) &&
            (rep == null ? List.of() : List.of(rep)).equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean &&
            (evaluated == null || evaluated.equals(value.get("evaluated")));
    }

    private static boolean result(Object raw, String primitive, boolean declared) {
        if (!(raw instanceof Map<?, ?> value) || !(value.get("components") instanceof List<?> fields)) return false;
        return value.keySet().equals(TUPLE_KEYS) && "unknown".equals(value.get("kind")) &&
            "unboxed-tuple".equals(value.get("aggregate")) && List.of(primitive).equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(value.get("evaluated"))) &&
            fields.size() == 2 && scalar(fields.get(0), null, true) && scalar(fields.get(1), primitive, true);
    }

    public static void validateHead(List<?> function, boolean defined) {
        requireProof(function.size() == 3 && "var".equals(function.get(0)) &&
            function.get(1) instanceof String name && !name.isEmpty() && !defined,
            "unresolved original foreign variable required");
        var metadata = CoreRepresentations.metadata(function);
        requireProof(scalar(metadata == null ? null : metadata.get("rep"), "BoxedRep (Just Lifted)", true),
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
                for (var operation : StringRtsOp.values()) {
                    if (target != null && operation.getSymbol().equals(target.get("symbol"))) {
                        if (list.size() <= 1 || !(list.get(1) instanceof List<?> head))
                            throw fault("Invalid original Posix string/RTS call: missing head");
                        validateHead(head, false);
                        break;
                    }
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    public static void validateOperand(StringRtsOp operation, int index, CoreRepresentation lowered, CoreRepresentation stored) {
        var rep = operation.getArguments().get(index);
        var reps = rep == null ? List.of() : List.of(rep);
        requireProof(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() &&
            lowered.getKind().name().toLowerCase(Locale.ROOT).equals(kind(rep)) && reps.equals(lowered.getPrimReps()),
            "lowered operand " + index);
        if (stored != null && stored.getPresent()) {
            var storedKind = stored.getKind().name().toLowerCase(Locale.ROOT);
            requireProof(!stored.isAggregate() && !stored.isVector() &&
                (storedKind.equals(kind(rep)) || storedKind.equals("unknown")) &&
                (stored.getPrimReps() == null || reps.equals(stored.getPrimReps())), "stored operand " + index);
        }
    }

    public static StringRtsOp validate(Object metadata, List<?> arguments, List<?> flags, Object resultProof) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return null;
        boolean recognized = false;
        StringRtsOp operation = null;
        for (var candidate : StringRtsOp.values()) {
            if (candidate.getSymbol().equals(target.get("symbol"))) {
                recognized = true;
                if (candidate.acceptsUnit(target.get("unit"))) { operation = candidate; break; }
            }
        }
        if (!recognized) return null;
        if (operation == null) throw fault("Invalid original Posix string/RTS call: exact installed GHC target");
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && exact(descriptor.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && Boolean.TRUE.equals(target.get("isFunction")), "exact installed GHC target");
        var expected = operation.getArguments();
        requireProof("ccall".equals(descriptor.get("convention")) && "unsafe".equals(descriptor.get("safety")) &&
            exact(descriptor.get("arity"), expected.size()) && exact(descriptor.get("suppliedArity"), expected.size()),
            "convention, safety or arity");
        var declared = descriptor.get("argumentReps") instanceof List<?> values ? values : null;
        boolean valid = declared != null && declared.size() == expected.size();
        for (int index = 0; valid && index < expected.size(); index++)
            valid = scalar(declared.get(index), expected.get(index), false);
        valid = valid && arguments.size() == expected.size();
        for (int index = 0; valid && index < expected.size(); index++)
            valid = scalar(arguments.get(index), expected.get(index), null);
        requireProof(valid && Collections.nCopies(expected.size(), false).equals(flags), "argument representations and flags");
        requireProof(result(descriptor.get("resultRep"), operation.getResult(), true) &&
            result(meta.get("rep"), operation.getResult(), false) && result(resultProof, operation.getResult(), false),
            "State#/length tuple result");
        return operation;
    }
}
