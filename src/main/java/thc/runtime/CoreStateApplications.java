// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reduce the exported runRW# State# lambda in its existing binder/source scope. */
public final class CoreStateApplications {
    private CoreStateApplications() {}

    @SuppressWarnings("unchecked")
    public static List<Object> inline(List<?> expression) {
        if (expression.isEmpty() || !"app".equals(expression.getFirst()) || expression.size() <= 1 ||
                !(expression.get(1) instanceof List<?> function) || function.isEmpty() || !"lam".equals(function.getFirst())) return null;
        var parameters = (List<?>) function.get(1);
        var arguments = (List<?>) expression.get(2);
        if (parameters.size() != 1 || arguments.size() != 1 || expression.size() <= 3 || !List.of(false).equals(expression.get(3))) return null;
        var parameter = (Map<String, Object>) parameters.getFirst();
        var state = (List<?>) arguments.getFirst();
        if (!Boolean.FALSE.equals(parameter.get("lifted")) || Boolean.TRUE.equals(parameter.get("coercion")) ||
                state.isEmpty() || !"void".equals(state.getFirst())) return null;
        var formal = CoreRepresentations.binder(parameter);
        var actual = CoreRepresentations.expression(state);
        if (formal.getKind() != CoreKind.VOID || formal.isAggregate() || actual.getKind() != CoreKind.VOID || actual.isAggregate()) return null;
        Map<String, Object> metadata = new LinkedHashMap<>();
        var original = CoreRepresentations.metadata(expression);
        if (original != null) metadata.putAll(original);
        metadata.put("binder", parameter);
        return Arrays.asList("case", state, parameter.get("id"),
            List.of(Arrays.asList("default", null, List.of(), function.get(2))), metadata);
    }
}
