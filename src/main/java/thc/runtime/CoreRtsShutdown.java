// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import thc.Language;

public final class CoreRtsShutdown {
    private CoreRtsShutdown() {}
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep");
    private static void requireProof(boolean condition, String detail) {
        if (!condition) throw RuntimeFault.fault("Invalid original shutdown call: " + detail);
    }
    private static boolean exactInteger(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }
    private static CoreKind kind(String primitive) { return primitive == null ? CoreKind.VOID : primitive.equals("AddrRep") ? CoreKind.ADDRESS : CoreKind.LONG; }
    private static List<String> reps(String primitive) { return primitive == null ? List.of() : List.of(primitive); }
    private static boolean scalar(Object raw, String primitive, boolean declared) {
        return raw instanceof Map<?, ?> value && value.keySet().equals(SCALAR_KEYS) &&
            kind(primitive).name().toLowerCase(Locale.ROOT).equals(value.get("kind")) && reps(primitive).equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(value.get("evaluated")));
    }
    private static boolean result(Object raw, String primitive, boolean declared) {
        if (!(raw instanceof Map<?, ?> value) || !(value.get("components") instanceof List<?> components)) return false;
        if (!value.keySet().equals(TUPLE_KEYS) || !"unknown".equals(value.get("kind")) || !"unboxed-tuple".equals(value.get("aggregate")) ||
            !reps(primitive).equals(value.get("primReps")) || !(value.get("evaluated") instanceof Boolean) ||
            (declared && !Boolean.FALSE.equals(value.get("evaluated"))) || components.size() != (primitive == null ? 1 : 2)) return false;
        for (int i = 0; i < components.size(); i++)
            if (!scalar(components.get(i), i == 0 ? null : primitive, false) || !Boolean.TRUE.equals(((Map<?, ?>) components.get(i)).get("evaluated"))) return false;
        return true;
    }
    public static void validateHead(List<?> function, boolean defined) {
        Map<?, ?> metadata = CoreRepresentations.metadata(function);
        Object raw = metadata == null ? null : metadata.get("rep");
        requireProof(function.size() == 3 && "var".equals(function.getFirst()) && function.get(1) instanceof String name && !name.isEmpty() &&
            !defined && raw instanceof Map<?, ?> proof && proof.keySet().equals(SCALAR_KEYS) && "closure".equals(proof.get("kind")) &&
            List.of("BoxedRep (Just Lifted)").equals(proof.get("primReps")) && Boolean.TRUE.equals(proof.get("evaluated")),
            "unresolved declared foreign variable required");
    }
    public static void validateHeads(Object value) {
        if (value instanceof Map<?, ?> map) for (Object child : map.values()) validateHeads(child);
        else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()) && list.size() > 6 && list.get(6) instanceof Map<?, ?> meta &&
                meta.get("foreignCall") instanceof Map<?, ?> descriptor && descriptor.get("target") instanceof Map<?, ?> target) {
                boolean shutdown = false;
                for (RtsShutdownOp operation : RtsShutdownOp.values()) if (operation.getSymbol().equals(target.get("symbol"))) { shutdown = true; break; }
                if (shutdown) {
                    if (list.size() <= 1 || !(list.get(1) instanceof List<?> function)) throw RuntimeFault.fault("Invalid original shutdown call: missing variable head");
                    validateHead(function, false);
                }
            }
            for (Object child : list) validateHeads(child);
        }
    }
    public static void validateOperand(RtsShutdownOp operation, int index, CoreRepresentation lowered, CoreRepresentation stored) {
        String primitive = operation.getArguments().get(index);
        CoreKind kind = kind(primitive);
        List<String> reps = reps(primitive);
        requireProof(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() && lowered.getKind() == kind && reps.equals(lowered.getPrimReps()), "lowered operand " + index);
        if (stored != null && stored.getPresent()) requireProof(!stored.isAggregate() && !stored.isVector() &&
            (stored.getKind() == kind || stored.getKind() == CoreKind.UNKNOWN) && (stored.getPrimReps() == null || reps.equals(stored.getPrimReps())), "stored operand " + index);
    }
    public static RtsShutdownOp validate(Object metadata, List<?> argumentReps, List<?> flags, Object resultRep) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target)) return null;
        RtsShutdownOp operation = null;
        for (RtsShutdownOp candidate : RtsShutdownOp.values()) if (candidate.getSymbol().equals(target.get("symbol"))) { operation = candidate; break; }
        if (operation == null) return null;
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && exactInteger(descriptor.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) && "static".equals(target.get("kind")) &&
            "ghc-internal".equals(target.get("unit")) && Boolean.TRUE.equals(target.get("isFunction")), "static ghc-internal function target");
        requireProof("ccall".equals(descriptor.get("convention")) && "safe".equals(descriptor.get("safety")), "calling convention/safety");
        int arity = operation.getArguments().size();
        requireProof(exactInteger(descriptor.get("arity"), arity) && exactInteger(descriptor.get("suppliedArity"), arity), "saturated arity");
        Object declaredValue = descriptor.get("argumentReps");
        boolean declaredValid = declaredValue instanceof List<?> list && list.size() == arity;
        if (declaredValid) for (int i = 0; i < arity; i++) if (!scalar(((List<?>) declaredValue).get(i), operation.getArguments().get(i), true)) { declaredValid = false; break; }
        requireProof(declaredValid, "declared argument representations");
        boolean argumentsValid = argumentReps.size() == arity;
        if (argumentsValid) for (int i = 0; i < arity; i++) if (!scalar(argumentReps.get(i), operation.getArguments().get(i), false)) { argumentsValid = false; break; }
        requireProof(argumentsValid, "actual argument representations");
        boolean flagsValid = flags.size() == arity;
        if (flagsValid) for (Object flag : flags) if (!Boolean.FALSE.equals(flag)) { flagsValid = false; break; }
        requireProof(flagsValid, "unlifted argument flags");
        requireProof(result(descriptor.get("resultRep"), operation.getResult(), true) && result(meta.get("rep"), operation.getResult(), false) &&
            result(resultRep, operation.getResult(), false), "exact State/result tuple");
        return operation;
    }
    /** Close only the guest context; an owning launcher interprets negative Unix signal codes. */
    @TruffleBoundary(transferToInterpreterOnException = false)
    public static Void shutdown(Node node, RtsShutdownOp operation, long code, long fast, Object state) {
        TupleResults.requireVoidCarrier(state);
        if (code != (long) (int) code || fast != (long) (int) fast) throw RuntimeFault.fault("Shutdown arguments require signed CInt carriers");
        int status = operation == RtsShutdownOp.EXIT ? (int) code & 255 : code >= 1 && code <= 64 ? -(int) code : 255;
        var context = Language.currentState(node);
        context.getShutdown().compareAndSet(null, new GuestShutdown(status, fast != 0));
        context.getEnv().getContext().closeExited(node, context.getShutdown().get().getStatus());
        throw new ThreadDeath(); // closeExited only returns during an already active unwind.
    }
}
