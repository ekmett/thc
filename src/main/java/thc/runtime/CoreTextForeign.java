// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class CoreTextForeign {
    private CoreTextForeign() {}
    // Installation hashes are provenance, not a different release or ABI.
    // Preserve the complete FCall unit instead of rewriting it to "inplace".
    private static final Pattern INSTALLED_UNIT = Pattern.compile("text-2\\.1\\.3-(?:inplace|[0-9a-f]+)");
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
        "arity", "suppliedArity", "argumentReps", "resultRep");

    public static boolean supportedUnit(Object unit) {
        return unit instanceof String value && INSTALLED_UNIT.matcher(value).matches();
    }

    private static void requireProof(boolean valid, String detail) {
        if (!valid) throw fault("Invalid original text call: " + detail);
    }

    private static boolean exact(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    private static String kind(String rep) {
        return switch (rep) {
            case null -> "void";
            case "BoxedRep (Just Unlifted)" -> "object";
            case "BoxedRep (Just Lifted)" -> "closure";
            default -> "long";
        };
    }

    private static boolean scalar(Object raw, String rep, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> proof)) return false;
        return proof.keySet().equals(SCALAR_KEYS) && kind(rep).equals(proof.get("kind")) &&
            (rep == null ? List.of() : List.of(rep)).equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean &&
            (evaluated == null || evaluated.equals(proof.get("evaluated")));
    }

    private static boolean result(Object raw, TextForeignOp operation, boolean declared) {
        if (!(raw instanceof Map<?, ?> proof) || !(proof.get("components") instanceof List<?> components)) return false;
        boolean reverse = operation == TextForeignOp.REVERSE;
        return proof.keySet().equals(TUPLE_KEYS) && "unknown".equals(proof.get("kind")) &&
            "unboxed-tuple".equals(proof.get("aggregate")) &&
            (reverse ? List.of() : List.of("Int64Rep")).equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(proof.get("evaluated"))) &&
            components.size() == (reverse ? 1 : 2) && scalar(components.get(0), null, true) &&
            (reverse || scalar(components.get(1), "Int64Rep", true));
    }

    public static TextForeignOp validate(Object metadata, List<?> arguments, List<?> flags, Object resultProof) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> call) ||
            !(call.get("target") instanceof Map<?, ?> target)) return null;
        TextForeignOp operation = null;
        for (var candidate : TextForeignOp.values()) {
            if (candidate.getSymbol().equals(target.get("symbol"))) { operation = candidate; break; }
        }
        if (operation == null) return null;
        requireProof(call.keySet().equals(DESCRIPTOR_KEYS) && exact(call.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && supportedUnit(target.get("unit")) &&
            Boolean.TRUE.equals(target.get("isFunction")), "exact original installed text target");
        requireProof("ccall".equals(call.get("convention")) && "unsafe".equals(call.get("safety")) &&
            exact(call.get("arity"), 5) && exact(call.get("suppliedArity"), 5), "convention, safety or arity");
        var declared = call.get("argumentReps") instanceof List<?> values ? values : null;
        boolean valid = declared != null && declared.size() == 5 && arguments.size() == 5 &&
            Collections.nCopies(5, false).equals(flags);
        var expected = operation.getArguments();
        for (int index = 0; valid && index < expected.size(); index++)
            valid = scalar(declared.get(index), expected.get(index), false) && scalar(arguments.get(index), expected.get(index), null);
        requireProof(valid, "ByteArray#/size/byte/State arguments");
        requireProof(result(call.get("resultRep"), operation, true) && result(meta.get("rep"), operation, false) &&
            result(resultProof, operation, false), "original State tuple result");
        return operation;
    }

    public static void validateHead(List<?> function, boolean defined) {
        requireProof(function.size() == 3 && "var".equals(function.get(0)) &&
            function.get(1) instanceof String name && !name.isEmpty() && !defined, "unresolved original FCallId required");
        var metadata = CoreRepresentations.INSTANCE.metadata(function);
        requireProof(scalar(metadata == null ? null : metadata.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original FCallId required");
    }

    public static void validateOperand(TextForeignOp operation, int index, CoreRepresentation lowered, CoreRepresentation stored) {
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
}
