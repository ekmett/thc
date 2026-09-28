// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Public THC v1 queries, with ordinary C fallback symbols for native GHC. */
public final class CoreCpuAffinity {
    private CoreCpuAffinity() {}
    public static final String SUPPORT = "thc_cpu_affinity_v1_support";
    public static final String APPLIED = "thc_cpu_affinity_v1_applied";

    private static void requireProof(boolean valid, String detail) {
        if (!valid) throw fault("Invalid THC CPU-affinity query: " + detail);
    }

    private static boolean scalar(CoreRepresentation proof, String primitive) {
        return proof.getPresent() && !proof.isAggregate() && !proof.isVector() &&
            proof.getKind() == (primitive == null ? CoreKind.VOID : CoreKind.LONG) &&
            (primitive == null ? List.of() : List.of(primitive)).equals(proof.getPrimReps());
    }

    private static boolean tuple(CoreRepresentation proof) {
        var fields = proof.getComponents();
        return fields != null && proof.isTuple() && !proof.isSum() && !proof.isVector() && fields.size() == 2 &&
            scalar(fields.get(0), null) && scalar(fields.get(1), "Int32Rep") && List.of("Int32Rep").equals(proof.getPrimReps());
    }

    private static boolean one(Object value) {
        return Integer.valueOf(1).equals(value) || Long.valueOf(1).equals(value);
    }

    public static Boolean validate(List<?> expr, boolean defined) {
        var metadata = CoreRepresentations.metadata(expr);
        if (metadata == null || !(metadata.get("foreignCall") instanceof Map<?, ?> call) ||
            !(call.get("target") instanceof Map<?, ?> target)) return null;
        var symbol = target.get("symbol");
        if (!SUPPORT.equals(symbol) && !APPLIED.equals(symbol)) return null;
        var function = expr.size() > 1 && expr.get(1) instanceof List<?> list ? list : null;
        requireProof(!expr.isEmpty() && "app".equals(expr.get(0)) && function != null && function.size() == 3 &&
            "var".equals(function.get(0)) && function.get(1) instanceof String name && !name.isEmpty() && !defined,
            "unresolved foreign identifier");
        CoreBoundThreadForeign.validateHead(function, defined);
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) &&
            "static".equals(target.get("kind")) && Boolean.TRUE.equals(target.get("isFunction")) &&
            (target.get("unit") == null || target.get("unit") instanceof String), "static function target");
        requireProof(call.keySet().equals(Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity",
            "argumentReps", "resultRep")) && one(call.get("schema")) && "ccall".equals(call.get("convention")) &&
            "unsafe".equals(call.get("safety")) && one(call.get("arity")) && one(call.get("suppliedArity")), "exact v1 C ABI");
        var arguments = expr.size() > 2 && expr.get(2) instanceof List<?> list ? list : null;
        var declared = call.get("argumentReps") instanceof List<?> list ? list : null;
        requireProof(arguments != null && arguments.size() == 1 && declared != null && declared.size() == 1 &&
            expr.size() > 3 && List.of(false).equals(expr.get(3)) &&
            scalar(CoreRepresentations.parse(declared.get(0)), null) &&
            scalar(CoreRepresentations.expression(arguments.get(0) instanceof List<?> list ? list : List.of()), null),
            "State# argument");
        requireProof(tuple(CoreRepresentations.parse(call.get("resultRep"))) &&
            tuple(CoreRepresentations.expression(expr)), "State#/CInt result");
        return APPLIED.equals(symbol);
    }
}
