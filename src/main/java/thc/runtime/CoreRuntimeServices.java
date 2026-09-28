// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Exact admission of the reserved runtime service ABI. */
public final class CoreRuntimeServices {
    private CoreRuntimeServices() {}

    private static boolean scalar(CoreRepresentation proof, String primitive) {
        CoreKind kind = primitive == null ? CoreKind.VOID : "AddrRep".equals(primitive) ? CoreKind.ADDRESS : CoreKind.LONG;
        return proof.getPresent() && !proof.isAggregate() && !proof.isVector() && proof.getKind() == kind &&
            (primitive == null ? List.of() : List.of(primitive)).equals(proof.getPrimReps());
    }

    private static boolean result(CoreRepresentation proof) {
        var fields = proof.getComponents();
        return fields != null && proof.isTuple() && !proof.isSum() && !proof.isVector() && fields.size() == 2 &&
            scalar(fields.get(0), null) && scalar(fields.get(1), "Int64Rep") && List.of("Int64Rep").equals(proof.getPrimReps());
    }

    private static void require(boolean valid, String detail) {
        if (!valid) throw fault("Invalid THC runtime service declaration: " + detail);
    }

    private static boolean number(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    public static RuntimeServiceCall validate(List<?> expr, boolean defined) {
        var metadata = CoreRepresentations.INSTANCE.metadata(expr);
        if (metadata == null || !(metadata.get("foreignCall") instanceof Map<?, ?> call) ||
            !(call.get("target") instanceof Map<?, ?> target)) return null;
        RuntimeServiceCall operation = null;
        for (var candidate : RuntimeServiceCall.values()) {
            if (candidate.getSymbol().equals(target.get("symbol"))) { operation = candidate; break; }
        }
        if (operation == null) return null;
        List<?> function = expr.size() > 1 && expr.get(1) instanceof List<?> value ? value : null;
        require(!expr.isEmpty() && "app".equals(expr.get(0)) && function != null && function.size() == 3 &&
            "var".equals(function.get(0)) && function.get(1) instanceof String name && !name.isEmpty() && !defined,
            "unresolved foreign identifier");
        CoreBoundThreadForeign.validateHead(function, defined);
        require(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) && "static".equals(target.get("kind")) &&
            Boolean.TRUE.equals(target.get("isFunction")) && (target.get("unit") == null || target.get("unit") instanceof String),
            "static function target");
        int arity = operation.getArguments().size();
        require(call.keySet().equals(Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")) &&
            number(call.get("schema"), 1) && "ccall".equals(call.get("convention")) &&
            (operation == RuntimeServiceCall.EXCEPTION_TEXT ? "safe" : "unsafe").equals(call.get("safety")) &&
            number(call.get("arity"), arity) && number(call.get("suppliedArity"), arity), "exact v1 C ABI");
        List<?> arguments = expr.size() > 2 && expr.get(2) instanceof List<?> value ? value : null;
        List<?> declared = call.get("argumentReps") instanceof List<?> value ? value : null;
        require(arguments != null && arguments.size() == arity && declared != null && declared.size() == arity &&
            expr.size() > 3 && Collections.nCopies(arity, false).equals(expr.get(3)), "argument count and representation flags");
        for (int index = 0; index < arity; index++) {
            String primitive = operation.getArguments().get(index);
            List<?> expression = arguments.get(index) instanceof List<?> value ? value : List.of();
            require(scalar(CoreRepresentations.INSTANCE.parse(declared.get(index)), primitive) &&
                scalar(CoreRepresentations.INSTANCE.expression(expression), primitive), "argument " + index);
        }
        require(result(CoreRepresentations.INSTANCE.parse(call.get("resultRep"))) &&
            result(CoreRepresentations.INSTANCE.expression(expr)), "State#/CLLong result");
        return operation;
    }

    /** Check actual lowered/binder shapes once at this external ABI boundary. */
    public static void validateOperand(RuntimeServiceCall operation, int index,
                                       CoreRepresentation lowered, CoreRepresentation bound) {
        String primitive = operation.getArguments().get(index);
        if ((lowered.getPresent() && !scalar(lowered, primitive)) ||
            (bound != null && bound.getPresent() && !scalar(bound, primitive)))
            throw fault("Runtime service lowered argument " + index + " contradicts its C ABI");
    }
}
