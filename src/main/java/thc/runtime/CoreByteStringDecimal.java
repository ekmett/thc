// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The two original bytestring-0.12.2.0 signed decimal declarations. */
final class CoreByteStringDecimal {
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety",
            "arity", "suppliedArity", "argumentReps", "resultRep");

    private CoreByteStringDecimal() {}

    private static void check(boolean valid, String detail) {
        if (!valid) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Invalid original ByteString decimal call: " + detail);
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

    private static boolean scalar(Object raw, String primitive, Boolean evaluated) {
        if (!(raw instanceof Map<?, ?> value)) return false;
        return value.keySet().equals(SCALAR_KEYS) && kind(primitive).equals(value.get("kind"))
                && (primitive == null ? List.of() : List.of(primitive)).equals(value.get("primReps"))
                && value.get("evaluated") instanceof Boolean
                && (evaluated == null || evaluated.equals(value.get("evaluated")));
    }

    private static boolean result(Object raw, ByteStringDecimalOp operation, boolean declared) {
        if (!(raw instanceof Map<?, ?> value) || !(value.get("components") instanceof List<?> fields)) return false;
        return value.keySet().equals(TUPLE_KEYS) && "unboxed-tuple".equals(value.get("aggregate"))
                && "unknown".equals(value.get("kind"))
                && (operation.getAddressResult() ? List.of("AddrRep") : List.of()).equals(value.get("primReps"))
                && value.get("evaluated") instanceof Boolean
                && (!declared || Boolean.FALSE.equals(value.get("evaluated")))
                && fields.size() == (operation.getAddressResult() ? 2 : 1) && scalar(fields.get(0), null, true)
                && (!operation.getAddressResult() || scalar(fields.get(1), "AddrRep", true));
    }

    static ByteStringDecimalOp validate(Object metadata, List<?> arguments, List<?> flags, Object resultProof) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> call)
                || !(call.get("target") instanceof Map<?, ?> target)) return null;
        ByteStringDecimalOp operation;
        if ("_hs_bytestring_long_long_int_dec".equals(target.get("symbol"))) operation = ByteStringDecimalOp.SIGNED;
        else if ("_hs_bytestring_long_long_int_dec_padded18".equals(target.get("symbol"))) operation = ByteStringDecimalOp.PADDED18;
        else return null;
        check(call.keySet().equals(DESCRIPTOR_KEYS) && exact(call.get("schema"), 1), "descriptor schema");
        check(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) && "static".equals(target.get("kind"))
                && Boolean.TRUE.equals(target.get("isFunction"))
                && CoreMemorySearchForeignKt.isOriginalByteStringUnit(target.get("unit")), "pinned original ByteString target");
        check("ccall".equals(call.get("convention")) && "unsafe".equals(call.get("safety"))
                && exact(call.get("arity"), 3) && exact(call.get("suppliedArity"), 3), "convention, safety or arity");
        List<?> declared = call.get("argumentReps") instanceof List<?> list ? list : null;
        boolean valid = declared != null && declared.size() == 3 && arguments.size() == 3
                && List.of(false, false, false).equals(flags);
        for (int index = 0; valid && index < operation.getArguments().size(); index++)
            valid = scalar(declared.get(index), operation.getArguments().get(index), false)
                    && scalar(arguments.get(index), operation.getArguments().get(index), null);
        check(valid, "Int64#/Addr#/State# arguments");
        check(result(call.get("resultRep"), operation, true) && result(meta.get("rep"), operation, false)
                && result(resultProof, operation, false), "State#/address or singleton-State tuple result");
        return operation;
    }

    static void validateHead(List<?> function, boolean defined) {
        check(function.size() == 3 && "var".equals(function.get(0)) && function.get(1) instanceof String name
                && !name.isEmpty() && !defined, "unresolved original FCallId required");
        var metadata = CoreRepresentations.INSTANCE.metadata(function);
        check(scalar(metadata == null ? null : metadata.get("rep"), "BoxedRep (Just Lifted)", true),
                "unresolved original FCallId required");
    }

    static void validateOperand(ByteStringDecimalOp operation, int index, CoreRepresentation lowered, CoreRepresentation stored) {
        String primitive = operation.getArguments().get(index);
        List<String> reps = primitive == null ? List.of() : List.of(primitive);
        check(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector()
                && lowered.getKind().name().toLowerCase(Locale.ROOT).equals(kind(primitive))
                && reps.equals(lowered.getPrimReps()), "lowered operand " + index);
        if (stored != null && stored.getPresent()) {
            String storedKind = stored.getKind().name().toLowerCase(Locale.ROOT);
            check(!stored.isAggregate() && !stored.isVector()
                    && (storedKind.equals(kind(primitive)) || storedKind.equals("unknown"))
                    && (stored.getPrimReps() == null || reps.equals(stored.getPrimReps())), "stored operand " + index);
        }
    }
}
