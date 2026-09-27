// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Exact installed pointer declarations; ByteArray# variants are not admitted. */
final class CoreByteStringUtf8Foreign {
    private static final List<String> ARGUMENT_REPS = Arrays.asList("AddrRep", "Word64Rep", null);
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
            "arity", "suppliedArity", "argumentReps", "resultRep");

    private CoreByteStringUtf8Foreign() {}

    private static void requireProof(boolean valid, String detail) {
        if (!valid) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Invalid original UTF-8 validation call: " + detail);
        }
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
        return proof.keySet().equals(SCALAR_KEYS) && kind(rep).equals(proof.get("kind"))
                && (rep == null ? List.of() : List.of(rep)).equals(proof.get("primReps"))
                && proof.get("evaluated") instanceof Boolean
                && (evaluated == null || evaluated.equals(proof.get("evaluated")));
    }

    private static boolean result(Object raw, String rep, boolean declared) {
        if (!(raw instanceof Map<?, ?> proof) || !(proof.get("components") instanceof List<?> fields)) return false;
        return proof.keySet().equals(TUPLE_KEYS) && "unknown".equals(proof.get("kind"))
                && "unboxed-tuple".equals(proof.get("aggregate")) && List.of(rep).equals(proof.get("primReps"))
                && proof.get("evaluated") instanceof Boolean
                && (!declared || Boolean.FALSE.equals(proof.get("evaluated")))
                && fields.size() == 2 && scalar(fields.get(0), null, true) && scalar(fields.get(1), rep, true);
    }

    static Boolean validate(Object metadata, List<?> arguments, List<?> flags, Object resultProof) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> call)
                || !(call.get("target") instanceof Map<?, ?> target)) return null;
        Object unit = target.get("unit");
        if (!"bytestring_is_valid_utf8".equals(target.get("symbol"))) return null;
        requireProof(call.keySet().equals(DESCRIPTOR_KEYS) && exact(call.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction"))
                && "static".equals(target.get("kind")) && Boolean.TRUE.equals(target.get("isFunction"))
                && CoreMemorySearchForeign.isOriginalByteStringUnit(unit), "supported installed target");
        requireProof("ccall".equals(call.get("convention"))
                && ("safe".equals(call.get("safety")) || "unsafe".equals(call.get("safety")))
                && exact(call.get("arity"), 3) && exact(call.get("suppliedArity"), 3), "convention, safety or arity");
        List<?> declared = call.get("argumentReps") instanceof List<?> list ? list : null;
        boolean valid = declared != null && declared.size() == 3;
        for (int index = 0; valid && index < ARGUMENT_REPS.size(); index++)
            valid = scalar(declared.get(index), ARGUMENT_REPS.get(index), false);
        valid = valid && arguments.size() == 3;
        for (int index = 0; valid && index < ARGUMENT_REPS.size(); index++)
            valid = scalar(arguments.get(index), ARGUMENT_REPS.get(index), null);
        requireProof(valid && List.of(false, false, false).equals(flags), "address, CInt/CSize and State# operands");
        requireProof(result(call.get("resultRep"), "Int32Rep", true) && result(meta.get("rep"), "Int32Rep", false)
                && result(resultProof, "Int32Rep", false), "State#/result tuple");
        return "safe".equals(call.get("safety"));
    }

    static void validateHead(List<?> function, boolean defined) {
        requireProof(function.size() == 3 && "var".equals(function.get(0)) && function.get(1) instanceof String name
                && !name.isEmpty() && !defined, "unresolved original FCallId required");
        var metadata = CoreRepresentations.INSTANCE.metadata(function);
        requireProof(scalar(metadata == null ? null : metadata.get("rep"), "BoxedRep (Just Lifted)", true),
                "unresolved original FCallId required");
    }

    static void validateOperand(int index, CoreRepresentation lowered, CoreRepresentation stored) {
        String rep = ARGUMENT_REPS.get(index);
        List<String> reps = rep == null ? List.of() : List.of(rep);
        requireProof(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector()
                && lowered.getKind().name().toLowerCase(Locale.ROOT).equals(kind(rep))
                && reps.equals(lowered.getPrimReps()), "lowered operand " + index);
        if (stored != null && stored.getPresent()) {
            String storedKind = stored.getKind().name().toLowerCase(Locale.ROOT);
            requireProof(!stored.isAggregate() && !stored.isVector()
                    && (storedKind.equals(kind(rep)) || storedKind.equals("unknown"))
                    && (stored.getPrimReps() == null || reps.equals(stored.getPrimReps())), "stored operand " + index);
        }
    }
}
