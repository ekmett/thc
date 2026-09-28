// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;

/** Validates the Core forms with captured AST child edges. */
public final class AstAsyncAdmission {
    public static final AstAsyncAdmission INSTANCE = new AstAsyncAdmission();
    private AstAsyncAdmission() {}
    public static void validate(List<? extends Map<String, ?>> bindings) {
        for (Map<String, ?> binding : bindings) {
            if (!(binding.get("expr") instanceof List<?> expression))
                throw new RuntimeFault("AST async binding has no expression: " + binding.get("id"));
            expression(expression);
        }
    }
    private static void expression(List<?> expression) {
        Object tag = expression.isEmpty() ? null : expression.getFirst();
        if (!(tag instanceof String name)) throw new UnsupportedCore("AST async capture is not complete for " + tag);
        switch (name) {
            case "var", "lit", "void", "con", "prim" -> {}
            case "lam" -> expression((List<?>) expression.get(2));
            case "app" -> {
                expression((List<?>) expression.get(1));
                for (Object child : (List<?>) expression.get(2)) expression((List<?>) child);
            }
            case "case" -> {
                expression((List<?>) expression.get(1));
                for (Object arm : (List<?>) expression.get(3)) expression((List<?>) ((List<?>) arm).get(3));
            }
            case "let" -> {
                for (Object binding : (List<?>) expression.get(2)) expression((List<?>) ((Map<?, ?>) binding).get("expr"));
                expression((List<?>) expression.get(3));
            }
            default -> throw new UnsupportedCore("AST async capture is not complete for " + tag);
        }
    }
}
