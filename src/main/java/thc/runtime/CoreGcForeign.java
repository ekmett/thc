// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Exact original GC/clock foreign declarations and lowered operand checks. */
public final class CoreGcForeign {
    private CoreGcForeign() {}
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep");

    private static void requireProof(boolean valid, String detail) {
        if (!valid) throw fault("Invalid original GC/clock call: " + detail);
    }
    private static boolean exact(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }
    private static String kind(String rep) {
        if (rep == null) return "void";
        return switch (rep) {
            case "AddrRep" -> "address";
            case "BoxedRep (Just Lifted)" -> "closure";
            default -> "long";
        };
    }
    private static boolean scalar(Object raw, String rep, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> proof)) return false;
        return proof.keySet().equals(SCALAR_KEYS) && kind(rep).equals(proof.get("kind")) &&
            (rep == null ? List.of() : List.of(rep)).equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean && (evaluated == null || evaluated.equals(proof.get("evaluated")));
    }
    private static boolean result(Object raw, String rep, boolean declared) {
        if (!(raw instanceof Map<?, ?> proof) || !(proof.get("components") instanceof List<?> fields)) return false;
        return proof.keySet().equals(TUPLE_KEYS) && "unknown".equals(proof.get("kind")) &&
            "unboxed-tuple".equals(proof.get("aggregate")) &&
            (rep == null ? List.of() : List.of(rep)).equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(proof.get("evaluated"))) &&
            fields.size() == (rep == null ? 1 : 2) && scalar(fields.get(0), null, true) &&
            (rep == null || scalar(fields.get(1), rep, true));
    }

    public static GcForeignOp validate(Object metadata, List<?> arguments, List<?> flags, Object resultProof) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> call) ||
            !(call.get("target") instanceof Map<?, ?> target)) return null;
        GcForeignOp op = null;
        for (var candidate : GcForeignOp.values()) {
            if (candidate.getSymbol().equals(target.get("symbol"))) { op = candidate; break; }
        }
        if (op == null) return null;
        requireProof(call.keySet().equals(DESCRIPTOR_KEYS) && exact(call.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && op.getUnit().equals(target.get("unit")) &&
            Boolean.TRUE.equals(target.get("isFunction")), "exact installed target");
        var expected = op.getArguments();
        requireProof("ccall".equals(call.get("convention")) && op.getSafety().equals(call.get("safety")) &&
            exact(call.get("arity"), expected.size()) && exact(call.get("suppliedArity"), expected.size()),
            "convention, safety or arity");
        List<?> declared = call.get("argumentReps") instanceof List<?> values ? values : null;
        boolean valid = declared != null && declared.size() == expected.size() && arguments.size() == expected.size();
        for (int i = 0; valid && i < expected.size(); i++) {
            valid = scalar(declared.get(i), expected.get(i), false) && scalar(arguments.get(i), expected.get(i), null);
        }
        requireProof(valid && Collections.nCopies(expected.size(), false).equals(flags), "address/State# operands");
        requireProof(result(call.get("resultRep"), op.getResult(), true) && result(meta.get("rep"), op.getResult(), false) &&
            result(resultProof, op.getResult(), false), "State/result tuple");
        return op;
    }

    public static void validateHead(List<?> function, boolean defined) {
        requireProof(function.size() == 3 && "var".equals(function.get(0)) && function.get(1) instanceof String name &&
            !name.isEmpty() && !defined, "unresolved original FCallId required");
        var metadata = CoreRepresentations.INSTANCE.metadata(function);
        requireProof(scalar(metadata == null ? null : metadata.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original FCallId required");
    }

    public static void validateOperand(GcForeignOp op, int index, CoreRepresentation lowered, CoreRepresentation stored) {
        String rep = op.getArguments().get(index);
        var reps = rep == null ? List.of() : List.of(rep);
        requireProof(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() &&
            lowered.getKind().name().toLowerCase(Locale.ROOT).equals(kind(rep)) && reps.equals(lowered.getPrimReps()),
            "lowered operand " + index);
        if (stored != null && stored.getPresent()) {
            String storedKind = stored.getKind().name().toLowerCase(Locale.ROOT);
            requireProof(!stored.isAggregate() && !stored.isVector() && (storedKind.equals(kind(rep)) || storedKind.equals("unknown")) &&
                (stored.getPrimReps() == null || reps.equals(stored.getPrimReps())), "stored operand " + index);
        }
    }
}
