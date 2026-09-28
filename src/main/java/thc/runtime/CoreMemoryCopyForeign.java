// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Original ghc-internal/ram address copies and array's byte-array memcpy declaration. */
public enum CoreMemoryCopyForeign {
    MEMMOVE("memmove"), MEMCPY("memcpy");

    private final String symbol;
    private static final Pattern RAM_UNIT = Pattern.compile("ram-0\\.22\\.1(?:-[A-Za-z0-9]+)?");
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
        "arity", "suppliedArity", "argumentReps", "resultRep");
    private static final List<String> ARGUMENT_REPS = Arrays.asList("AddrRep", "AddrRep", "Word64Rep", null);
    private static final List<String> ARRAY_ARGUMENT_REPS =
        Arrays.asList("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "Word64Rep", null);

    CoreMemoryCopyForeign(String symbol) { this.symbol = symbol; }

    public boolean byteArrays(Object metadata) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return false;
        return this == MEMCPY && "array-0.5.8.0-inplace".equals(target.get("unit"));
    }

    private void requireProof(boolean valid, String detail) {
        if (!valid) throw fault("Invalid original " + symbol + " call: " + detail);
    }

    private static boolean exactInteger(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    private static String kind(String rep) {
        return switch (rep) {
            case null -> "void";
            case "AddrRep" -> "address";
            case "BoxedRep (Just Unlifted)" -> "object";
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

    private static boolean result(Object raw, boolean declared) {
        if (!(raw instanceof Map<?, ?> proof) || !(proof.get("components") instanceof List<?> fields)) return false;
        return proof.keySet().equals(TUPLE_KEYS) && "unknown".equals(proof.get("kind")) &&
            "unboxed-tuple".equals(proof.get("aggregate")) && List.of("AddrRep").equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(proof.get("evaluated"))) &&
            fields.size() == 2 && scalar(fields.get(0), null, true) && scalar(fields.get(1), "AddrRep", true);
    }

    public void validateHead(List<?> function, boolean defined) {
        var metadata = CoreRepresentations.metadata(function);
        var raw = metadata == null ? null : metadata.get("rep");
        var proof = raw instanceof Map<?, ?> value ? value : null;
        requireProof(function.size() == 3 && "var".equals(function.get(0)) &&
            function.get(1) instanceof String name && !name.isEmpty() && !defined &&
            proof != null && proof.keySet().equals(SCALAR_KEYS) && "closure".equals(proof.get("kind")) &&
            List.of("BoxedRep (Just Lifted)").equals(proof.get("primReps")) && Boolean.TRUE.equals(proof.get("evaluated")),
            "unresolved original foreign variable required");
    }

    public void validateHeads(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var child : map.values()) validateHeads(child);
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.get(0))) {
                var raw = list.size() > 6 ? list.get(6) : null;
                var descriptor = raw instanceof Map<?, ?> meta && meta.get("foreignCall") instanceof Map<?, ?> call ? call : null;
                var target = descriptor != null && descriptor.get("target") instanceof Map<?, ?> map ? map : null;
                if (target != null && symbol.equals(target.get("symbol"))) {
                    if (list.size() <= 1 || !(list.get(1) instanceof List<?> head))
                        throw fault("Invalid original " + symbol + " call: missing variable head");
                    validateHead(head, false);
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    public void validateOperand(int index, CoreRepresentation lowered, CoreRepresentation stored, boolean byteArrays) {
        var rep = (byteArrays ? ARRAY_ARGUMENT_REPS : ARGUMENT_REPS).get(index);
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

    public boolean validate(Object metadata, List<?> arguments, List<?> flags, Object resultProof) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return false;
        if (!symbol.equals(target.get("symbol"))) return false;
        boolean arrays = byteArrays(metadata);
        var expected = arrays ? ARRAY_ARGUMENT_REPS : ARGUMENT_REPS;
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && exactInteger(descriptor.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && ("ghc-internal".equals(target.get("unit")) || arrays ||
                this == MEMCPY && target.get("unit") instanceof String unit && RAM_UNIT.matcher(unit).matches()) &&
            Boolean.TRUE.equals(target.get("isFunction")), "supported installed-library target");
        requireProof("ccall".equals(descriptor.get("convention")) && "unsafe".equals(descriptor.get("safety")) &&
            exactInteger(descriptor.get("arity"), 4) && exactInteger(descriptor.get("suppliedArity"), 4),
            "convention, safety or arity");
        var declared = descriptor.get("argumentReps") instanceof List<?> values ? values : null;
        boolean valid = declared != null && declared.size() == 4;
        for (int index = 0; valid && index < expected.size(); index++)
            valid = scalar(declared.get(index), expected.get(index), false);
        valid = valid && arguments.size() == 4;
        for (int index = 0; valid && index < expected.size(); index++)
            valid = scalar(arguments.get(index), expected.get(index), null);
        requireProof(valid && List.of(false, false, false, false).equals(flags), "argument representations and flags");
        requireProof(result(descriptor.get("resultRep"), true) && result(meta.get("rep"), false) && result(resultProof, false),
            "State#/Addr# tuple result");
        return true;
    }
}
