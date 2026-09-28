// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;

/** GHC's Id arity may exceed the lambdas retained in an exported pre-Tidy RHS. */
public final class CoreApplicationCertificates {
    private CoreApplicationCertificates() {}
    public record Arity(int declared, int retained, int retainedValues) {}

    public static Arity binding(Map<String, ?> binding) {
        if (!(binding.get("arity") instanceof Number declared) || !(binding.get("expr") instanceof List<?> rhs)) return null;
        List<?> parameters = List.of();
        if (!rhs.isEmpty() && "lam".equals(rhs.getFirst())) {
            if (rhs.size() < 2 || !(rhs.get(1) instanceof List<?> values)) return null;
            parameters = values;
        }
        int retainedValues = 0;
        for (var parameter : parameters)
            if (!(parameter instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("coercion"))) retainedValues++;
        return new Arity(declared.intValue(), parameters.size(), retainedValues);
    }

    /** A certified partial application can still enter work when forced to WHNF. */
    public static boolean eagerApplication(List<?> expression, Arity head) {
        if (expression.isEmpty() || !"app".equals(expression.getFirst())) return false;
        Object flag = expression.size() > 5 ? expression.get(5) : null;
        boolean certified = flag instanceof Boolean value ? value
            : expression.size() > 4 && Boolean.TRUE.equals(expression.get(4));
        if (!certified || head == null || head.declared() <= head.retainedValues()) return certified;
        if (expression.size() <= 2 || !(expression.get(2) instanceof List<?> arguments)) return false;
        // Below the retained lambda prefix, PAP construction is inert. Beyond
        // it a case may run; erased coercion slots do not prove safe saturation.
        return arguments.size() < head.retained();
    }
}
