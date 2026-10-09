// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeFault.fault;

/** Exact ABI admission for the pinned Windows scheduler boundaries. */
final class CoreWindowsIoForeign {
    private CoreWindowsIoForeign() {}
    private static void require(boolean condition, String detail) {
        if (!condition) throw fault("Invalid original Windows RTS call: " + detail);
    }
    private static boolean exact(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }
    private static String kind(String rep) {
        return rep == null ? "void" : "AddrRep".equals(rep) ? "address" : "BoxedRep (Just Lifted)".equals(rep) ? "closure" : "long";
    }
    private static boolean scalar(Object value, String rep, Boolean evaluated) {
        return value instanceof Map<?, ?> proof && proof.keySet().equals(Set.of("kind", "primReps", "evaluated")) &&
            kind(rep).equals(proof.get("kind")) && (rep == null ? List.of() : List.of(rep)).equals(proof.get("primReps")) &&
            proof.get("evaluated") instanceof Boolean && (evaluated == null || evaluated.equals(proof.get("evaluated")));
    }
    private static boolean tuple(Object value, List<String> reps, boolean declared) {
        if (!(value instanceof Map<?, ?> proof) || !(proof.get("components") instanceof List<?> fields)) return false;
        boolean valid = proof.keySet().equals(Set.of("kind", "primReps", "evaluated", "aggregate", "components")) &&
            "unknown".equals(proof.get("kind")) && "unboxed-tuple".equals(proof.get("aggregate")) &&
            reps.equals(proof.get("primReps")) && proof.get("evaluated") instanceof Boolean &&
            (!declared || Boolean.FALSE.equals(proof.get("evaluated"))) && fields.size() == reps.size() + 1 &&
            scalar(fields.getFirst(), null, true);
        for (int i = 0; valid && i < reps.size(); i++) valid = scalar(fields.get(i + 1), reps.get(i), true);
        return valid;
    }
    static WindowsIoOp validate(Object value, List<?> arguments, List<?> flags, Object result) {
        if (!(value instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> call) ||
                !(call.get("target") instanceof Map<?, ?> target)) return null;
        var op = WindowsIoOp.named(target.get("symbol"));
        if (op == null) return null;
        require(call.keySet().equals(Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")) &&
            exact(call.get("schema"), 1), "descriptor schema");
        require(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && "ghc-internal".equals(target.get("unit")) &&
            Boolean.TRUE.equals(target.get("isFunction")), "installed target");
        var expected = op.arguments();
        require(op.convention().equals(call.get("convention")) && op.safety().equals(call.get("safety")) &&
            exact(call.get("arity"), expected.size()) && exact(call.get("suppliedArity"), expected.size()), "convention, safety or arity");
        var declared = call.get("argumentReps") instanceof List<?> fields ? fields : null;
        boolean valid = declared != null && declared.size() == expected.size() && arguments.size() == expected.size();
        for (int i = 0; valid && i < expected.size(); i++)
            valid = scalar(declared.get(i), expected.get(i), false) && scalar(arguments.get(i), expected.get(i), null);
        require(valid && Collections.nCopies(expected.size(), false).equals(flags), "operands");
        require(tuple(call.get("resultRep"), op.results(), true) && tuple(meta.get("rep"), op.results(), false) &&
            tuple(result, op.results(), false), "State/result tuple");
        return op;
    }
    static void validateHead(List<?> head, boolean defined) {
        var meta = CoreRepresentations.metadata(head);
        require(head.size() == 3 && "var".equals(head.getFirst()) && head.get(1) instanceof String name && !name.isEmpty() &&
            !defined && scalar(meta == null ? null : meta.get("rep"), "BoxedRep (Just Lifted)", true), "unresolved original FCallId");
    }
    static void validateOperand(WindowsIoOp op, int index, CoreRepresentation lowered, CoreRepresentation stored) {
        String rep = op.arguments().get(index);
        var reps = rep == null ? List.of() : List.of(rep);
        require(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() &&
            kind(rep).equals(lowered.getKind().name().toLowerCase(Locale.ROOT)) && reps.equals(lowered.getPrimReps()), "lowered operand " + index);
        if (stored != null && stored.getPresent()) require(!stored.isAggregate() && !stored.isVector() &&
            (kind(rep).equals(stored.getKind().name().toLowerCase(Locale.ROOT)) || stored.getKind() == CoreKind.UNKNOWN) &&
            (stored.getPrimReps() == null || reps.equals(stored.getPrimReps())), "stored operand " + index);
    }
}
