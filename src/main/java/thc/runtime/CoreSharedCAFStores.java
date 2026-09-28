// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static thc.runtime.RuntimeFault.fault;

public final class CoreSharedCAFStores {
    private CoreSharedCAFStores() {}
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep");
    private static void requireProof(boolean valid, String detail) {
        if (!valid) throw fault("Invalid original RTS shared-CAF call: " + detail);
    }
    private static boolean exactInteger(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == (long) expected;
    }
    private static boolean scalar(Object value, String kind, List<String> reps) { return scalar(value, kind, reps, false); }
    private static boolean scalar(Object value, String kind, List<String> reps, boolean declared) {
        return value instanceof Map<?, ?> proof && proof.keySet().equals(SCALAR_KEYS) && Objects.equals(proof.get("kind"), kind) &&
            Objects.equals(proof.get("primReps"), reps) && proof.get("evaluated") instanceof Boolean &&
            (!declared || Boolean.FALSE.equals(proof.get("evaluated")));
    }
    private static boolean result(Object value) { return result(value, false); }
    private static boolean result(Object value, boolean declared) {
        return value instanceof Map<?, ?> proof && proof.get("components") instanceof List<?> fields &&
            proof.keySet().equals(TUPLE_KEYS) && "unknown".equals(proof.get("kind")) && "unboxed-tuple".equals(proof.get("aggregate")) &&
            Objects.equals(proof.get("primReps"), List.of("AddrRep")) && proof.get("evaluated") instanceof Boolean &&
            (!declared || Boolean.FALSE.equals(proof.get("evaluated"))) && fields.size() == 2 &&
            scalar(fields.get(0), "void", List.of()) && Boolean.TRUE.equals(((Map<?, ?>) fields.get(0)).get("evaluated")) &&
            scalar(fields.get(1), "address", List.of("AddrRep")) && Boolean.TRUE.equals(((Map<?, ?>) fields.get(1)).get("evaluated"));
    }
    public static SharedCAFStore validate(Object metadata, List<?> arguments, List<?> flags, Object resultProof) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return null;
        var store = SharedCAFStore.named(target.get("symbol"));
        if (store == null) return null;
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && exactInteger(descriptor.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && Objects.equals(target.get("unit"), store.getUnit()) && Boolean.TRUE.equals(target.get("isFunction")),
            "exact installed GHC target");
        requireProof("ccall".equals(descriptor.get("convention")) && "unsafe".equals(descriptor.get("safety")) &&
            exactInteger(descriptor.get("arity"), 2) && exactInteger(descriptor.get("suppliedArity"), 2), "convention, safety or arity");
        var declared = descriptor.get("argumentReps") instanceof List<?> list ? list : null;
        requireProof(declared != null && declared.size() == 2 && scalar(declared.get(0), "address", List.of("AddrRep"), true) &&
            scalar(declared.get(1), "void", List.of(), true) && arguments.size() == 2 &&
            scalar(arguments.get(0), "address", List.of("AddrRep")) && scalar(arguments.get(1), "void", List.of()) &&
            flags.equals(List.of(false, false)), "Addr# and State# arguments");
        requireProof(result(descriptor.get("resultRep"), true) && result(meta.get("rep")) && result(resultProof), "State# and Addr# tuple result");
        return store;
    }
    public static void validateHead(List<?> function, boolean defined) {
        var metadata = CoreRepresentations.metadata(function);
        Object proof = metadata == null ? null : metadata.get("rep");
        requireProof(function.size() == 3 && "var".equals(function.get(0)) && function.get(1) instanceof String id &&
            !id.isEmpty() && !defined && scalar(proof, "closure", List.of("BoxedRep (Just Lifted)")) &&
            Boolean.TRUE.equals(((Map<?, ?>) proof).get("evaluated")), "unresolved declared foreign variable required");
    }
    public static void validateOperand(int index, CoreRepresentation lowered, CoreRepresentation stored) {
        var kind = index == 0 ? CoreKind.ADDRESS : CoreKind.VOID;
        List<String> reps = index == 0 ? List.of("AddrRep") : List.of();
        requireProof(index >= 0 && index <= 1 && lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() &&
            lowered.getKind() == kind && Objects.equals(lowered.getPrimReps(), reps), "lowered operand " + index);
        if (stored != null && stored.getPresent()) requireProof(!stored.isAggregate() && !stored.isVector() &&
            (stored.getKind() == kind || stored.getKind() == CoreKind.UNKNOWN) &&
            (stored.getPrimReps() == null || stored.getPrimReps().equals(reps)), "stored operand " + index);
    }
}
