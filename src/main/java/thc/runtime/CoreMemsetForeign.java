// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

/** The pinned ByteString CInt/CSize memset declaration, with checked writes. */
public final class CoreMemsetForeign {
    private CoreMemsetForeign() {}
    private static final List<String> ARGUMENTS = Arrays.asList("AddrRep", "Int32Rep", "Word64Rep", null);
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
        "arity", "suppliedArity", "argumentReps", "resultRep");

    private static void requireProof(boolean valid, String detail) {
        if (!valid) throw fault("Invalid original memset call: " + detail);
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
        if (!(raw instanceof Map<?, ?> proof)) return false;
        return proof.keySet().equals(SCALAR_KEYS) && kind(rep).equals(proof.get("kind")) &&
            (rep == null ? List.of() : List.of(rep)).equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean &&
            (evaluated == null || evaluated.equals(proof.get("evaluated")));
    }

    private static boolean result(Object raw, String rep, boolean declared) {
        if (!(raw instanceof Map<?, ?> proof) || !(proof.get("components") instanceof List<?> fields)) return false;
        return proof.keySet().equals(TUPLE_KEYS) && "unknown".equals(proof.get("kind")) &&
            "unboxed-tuple".equals(proof.get("aggregate")) && List.of(rep).equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(proof.get("evaluated"))) &&
            fields.size() == 2 && scalar(fields.get(0), null, true) && scalar(fields.get(1), rep, true);
    }

    public static boolean validate(Object metadata, List<?> operands, List<?> flags, Object resultProof) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> call) ||
            !(call.get("target") instanceof Map<?, ?> target)) return false;
        var unit = target.get("unit");
        if (!"memset".equals(target.get("symbol"))) return false;
        requireProof(call.keySet().equals(DESCRIPTOR_KEYS) && exact(call.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && Boolean.TRUE.equals(target.get("isFunction")) &&
            CoreMemorySearchForeign.isOriginalByteStringUnit(unit),
            "supported installed target");
        requireProof("ccall".equals(call.get("convention")) && "unsafe".equals(call.get("safety")) &&
            exact(call.get("arity"), 4) && exact(call.get("suppliedArity"), 4), "convention, safety or arity");
        var declared = call.get("argumentReps") instanceof List<?> values ? values : null;
        var expected = ARGUMENTS;
        boolean valid = declared != null && declared.size() == 4;
        for (int index = 0; valid && index < expected.size(); index++)
            valid = scalar(declared.get(index), expected.get(index), false);
        valid = valid && operands.size() == 4;
        for (int index = 0; valid && index < expected.size(); index++)
            valid = scalar(operands.get(index), expected.get(index), null);
        requireProof(valid && List.of(false, false, false, false).equals(flags), "address, CInt/CSize and State# operands");
        requireProof(result(call.get("resultRep"), "AddrRep", true) &&
            result(meta.get("rep"), "AddrRep", false) &&
            result(resultProof, "AddrRep", false), "State#/result tuple");
        return true;
    }

    public static void validateHead(List<?> function, boolean defined) {
        requireProof(function.size() == 3 && "var".equals(function.get(0)) &&
            function.get(1) instanceof String name && !name.isEmpty() && !defined,
            "unresolved original FCallId required");
        var metadata = CoreRepresentations.INSTANCE.metadata(function);
        requireProof(scalar(metadata == null ? null : metadata.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original FCallId required");
    }

    public static void validateOperand(int index, CoreRepresentation lowered, CoreRepresentation stored) {
        var rep = ARGUMENTS.get(index);
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
