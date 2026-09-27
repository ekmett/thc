// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CoreFloatForeign {
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
            "arity", "suppliedArity", "argumentReps", "resultRep");

    private CoreFloatForeign() {}

    private static void requireProof(boolean valid, String detail) {
        if (!valid) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Invalid original floating call: " + detail);
        }
    }

    private static boolean exact(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    private static String kind(String rep) {
        return switch (rep) {
            case null -> "void";
            case "FloatRep" -> "float";
            case "DoubleRep" -> "double";
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
        if (!(raw instanceof Map<?, ?> proof) || !(proof.get("components") instanceof List<?> components)) return false;
        return proof.keySet().equals(TUPLE_KEYS) && "unknown".equals(proof.get("kind"))
                && "unboxed-tuple".equals(proof.get("aggregate")) && List.of(rep).equals(proof.get("primReps"))
                && proof.get("evaluated") instanceof Boolean
                && (!declared || Boolean.FALSE.equals(proof.get("evaluated"))) && components.size() == 2
                && scalar(components.get(0), null, true) && scalar(components.get(1), rep, true);
    }

    static FloatForeignOp validate(Object metadata, List<?> arguments, List<?> flags, Object resultProof) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> call)
                || !(call.get("target") instanceof Map<?, ?> target)) return null;
        FloatForeignOp operation = FloatForeignOp.named(target.get("symbol"));
        if (operation == null) return null;
        requireProof(call.keySet().equals(DESCRIPTOR_KEYS) && exact(call.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction"))
                && "static".equals(target.get("kind")) && "ghc-internal".equals(target.get("unit"))
                && Boolean.TRUE.equals(target.get("isFunction")), "exact original installed target");
        requireProof("ccall".equals(call.get("convention")) && "unsafe".equals(call.get("safety"))
                && exact(call.get("arity"), 2) && exact(call.get("suppliedArity"), 2), "convention, safety or arity");
        requireProof(call.get("argumentReps") instanceof List<?> declared && declared.size() == 2
                && scalar(declared.get(0), operation.getArgumentRep(), false) && scalar(declared.get(1), null, false)
                && arguments.size() == 2 && scalar(arguments.get(0), operation.getArgumentRep(), null)
                && scalar(arguments.get(1), null, null) && List.of(false, false).equals(flags),
                "floating and State# arguments");
        requireProof(result(call.get("resultRep"), operation.getResultRep(), true)
                && result(meta.get("rep"), operation.getResultRep(), false)
                && result(resultProof, operation.getResultRep(), false), "State#/scalar tuple result");
        return operation;
    }

    static void validateHead(List<?> function, boolean defined) {
        requireProof(function.size() == 3 && "var".equals(function.get(0))
                && function.get(1) instanceof String name && !name.isEmpty() && !defined,
                "unresolved original FCallId required");
        Map<String, Object> metadata = CoreRepresentations.INSTANCE.metadata(function);
        requireProof(scalar(metadata == null ? null : metadata.get("rep"), "BoxedRep (Just Lifted)", true),
                "unresolved original FCallId required");
    }

    static void validateOperand(FloatForeignOp operation, int index, CoreRepresentation lowered, CoreRepresentation stored) {
        String rep = index == 0 ? operation.getArgumentRep() : null;
        CoreKind expected = index == 0 ? operation.getSingle() ? CoreKind.FLOAT : CoreKind.DOUBLE : CoreKind.VOID;
        List<String> reps = rep == null ? List.of() : List.of(rep);
        requireProof(index >= 0 && index <= 1 && lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector()
                && lowered.getKind() == expected && reps.equals(lowered.getPrimReps()), "lowered operand " + index);
        if (stored != null && stored.getPresent())
            requireProof(!stored.isAggregate() && !stored.isVector()
                    && (stored.getKind() == expected || stored.getKind() == CoreKind.UNKNOWN)
                    && (stored.getPrimReps() == null || reps.equals(stored.getPrimReps())), "stored operand " + index);
    }
}
