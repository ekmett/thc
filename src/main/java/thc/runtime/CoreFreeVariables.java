// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lexical captures of one demanded expression, without global lookup or body loading. */
public final class CoreFreeVariables {
    private CoreFreeVariables() {}

    public static Set<String> coreFreeVariables(List<?> expression) {
        if (!(expression.getFirst() instanceof String tag)) return Set.of();
        return switch (tag) {
            case "var" -> Set.of((String) expression.get(1));
            case "lam", "app", "let", "case" -> new Collector().collect(expression);
            default -> Set.of();
        };
    }

    private static final class Collector {
        private final Set<String> free = new LinkedHashSet<>();
        private final Set<String> bound = new HashSet<>();
        private final ArrayList<String> scope = new ArrayList<>();

        Set<String> collect(List<?> expression) { visit(expression); return free; }

        private void bind(String id) {
            // Shadowing must not remove an outer binding when this scope ends.
            if (bound.add(id)) scope.add(id);
        }

        private void restore(int mark) {
            while (scope.size() > mark) bound.remove(scope.removeLast());
        }

        private void visit(List<?> expression) {
            if (!(expression.getFirst() instanceof String tag)) return;
            switch (tag) {
                case "var" -> {
                    String id = (String) expression.get(1);
                    if (!bound.contains(id)) free.add(id);
                }
                case "lam" -> {
                    int mark = scope.size();
                    for (var parameter : (List<?>) expression.get(1)) bind((String) ((Map<?, ?>) parameter).get("id"));
                    visit((List<?>) expression.get(2));
                    restore(mark);
                }
                case "app" -> {
                    visit((List<?>) expression.get(1));
                    for (var argument : (List<?>) expression.get(2)) visit((List<?>) argument);
                }
                case "let" -> {
                    int mark = scope.size();
                    var group = (List<?>) expression.get(2);
                    boolean recursive = Boolean.TRUE.equals(expression.get(1));
                    if (recursive) for (var binding : group) bind((String) ((Map<?, ?>) binding).get("id"));
                    for (var binding : group) visit((List<?>) ((Map<?, ?>) binding).get("expr"));
                    if (!recursive) for (var binding : group) bind((String) ((Map<?, ?>) binding).get("id"));
                    visit((List<?>) expression.get(3));
                    restore(mark);
                }
                case "case" -> {
                    visit((List<?>) expression.get(1));
                    int mark = scope.size();
                    bind((String) expression.get(2));
                    for (var item : (List<?>) expression.get(3)) {
                        var alternative = (List<?>) item;
                        int alternativeMark = scope.size();
                        for (var id : (List<?>) alternative.get(2)) bind((String) id);
                        visit((List<?>) alternative.get(3));
                        restore(alternativeMark);
                    }
                    restore(mark);
                }
                default -> { }
            }
        }
    }
}
