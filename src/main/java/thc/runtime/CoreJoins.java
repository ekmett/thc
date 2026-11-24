// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Join annotations refer to retained value/coercion arguments, not pre-erasure arity. */
@SuppressWarnings("unchecked")
public final class CoreJoins {
    private CoreJoins() {}

    public static List<CoreJoinDefinition> definitions(List<? extends Map<String, Object>> group) {
        var arities = new ArrayList<Integer>(group.size());
        for (var binding : group) arities.add(CoreRepresentations.joinArity(binding));
        boolean allAbsent = true;
        for (Integer arity : arities) if (arity != null) { allAbsent = false; break; }
        if (allAbsent) return null;
        for (Integer arity : arities) if (arity == null) throw new UnsupportedCore("Mixed local join and ordinary binding group");
        var definitions = new ArrayList<CoreJoinDefinition>(group.size());
        for (int index = 0; index < group.size(); index++) {
            var binding = group.get(index);
            int arity = arities.get(index);
            if (!(binding.get("expr") instanceof List<?> rawRhs)) throw new RuntimeFault("Join binding has no expression");
            var rhs = (List<Object>) rawRhs;
            List<Map<String, Object>> parameters;
            List<Object> body;
            if (arity == 0) { parameters = List.of(); body = rhs; }
            else {
                if (!"lam".equals(first(rhs))) throw new RuntimeFault("Join value arity exceeds its lambda prefix");
                var all = (List<Map<String, Object>>) rhs.get(1);
                if (arity > all.size()) throw new RuntimeFault("Join value arity exceeds its lambda prefix");
                if (arity == all.size()) {
                    var result = CoreRepresentations.joinResult(binding);
                    TupleShape.requireCompatible(result, CoreRepresentations.lambdaResult(rhs));
                }
                parameters = new ArrayList<>(all.subList(0, arity));
                if (arity == all.size()) body = (List<Object>) rhs.get(2);
                else {
                    var metadata = CoreRepresentations.metadata(rhs);
                    var meta = metadata == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(metadata);
                    Object joinResult = binding.get("joinResultRep");
                    if (joinResult != null) meta.put("rep", joinResult);
                    if (meta.containsKey("entryStrict")) {
                        boolean[] strict = CoreEntries.lambda(rhs);
                        var remaining = new ArrayList<Boolean>(Math.max(0, strict.length - arity));
                        for (int i = arity; i < strict.length; i++) remaining.add(strict[i]);
                        meta.put("entryStrict", remaining);
                    }
                    body = new ArrayList<>(4);
                    body.add("lam"); body.add(new ArrayList<>(all.subList(arity, all.size())));
                    body.add(rhs.get(2)); body.add(meta);
                }
            }
            // A join returns into the exact tag/payload layout without a closure boundary.
            definitions.add(new CoreJoinDefinition(binding, (String) binding.get("id"), parameters, body,
                CoreRepresentations.joinResult(binding)));
        }
        return definitions;
    }

    public static void validate(List<? extends Map<String, Object>> group, List<?> body, boolean recursive) {
        var joins = definitions(group);
        if (joins == null) return;
        var arities = new LinkedHashMap<String, Integer>();
        for (var join : joins) arities.put(join.id(), join.parameters().size());
        var validator = new Validator(arities);
        for (var join : joins) {
            int mark = validator.mark();
            if (!recursive) for (var id : arities.keySet()) validator.bind(id);
            validator.bindParameters(join.parameters());
            validator.visit(join.body(), true);
            validator.restore(mark);
        }
        validator.visit(body, true);
    }

    private static Object first(List<?> values) { return values.isEmpty() ? null : values.getFirst(); }

    /** Only shadowing of this group's joins affects its validation. Keep one
     * lexical stack instead of copying all enclosing binders at each node. */
    private static final class Validator {
        private final Map<String, Integer> arities;
        private final Set<String> shadowed = new HashSet<>();
        private final ArrayList<String> scope = new ArrayList<>();

        Validator(Map<String, Integer> arities) { this.arities = arities; }
        private int mark() { return scope.size(); }
        private void bind(String id) {
            if (arities.containsKey(id) && shadowed.add(id)) scope.add(id);
        }
        private void bindParameters(List<Map<String, Object>> parameters) {
            for (var parameter : parameters) bind((String) parameter.get("id"));
        }
        private void restore(int mark) {
            while (scope.size() > mark) shadowed.remove(scope.removeLast());
        }
        private boolean isJoin(String id) { return arities.containsKey(id) && !shadowed.contains(id); }
        private void visit(List<?> expr, boolean tail) {
            var inlined = CoreStateApplications.inline(expr);
            if (inlined != null) { visit(inlined, tail); return; }
            if (!(first(expr) instanceof String tag)) return;
            switch (tag) {
                case "var" -> {
                    var id = (String) expr.get(1);
                    if (isJoin(id) && (!tail || arities.get(id) != 0))
                        throw new RuntimeFault("Local join escapes or is not exactly saturated: " + id);
                }
                case "app" -> {
                    var function = (List<?>) expr.get(1);
                    var args = (List<List<?>>) expr.get(2);
                    var id = "var".equals(first(function)) ? (String) function.get(1) : null;
                    if (id != null && isJoin(id)) {
                        if (!tail || args.size() != arities.get(id))
                            throw new RuntimeFault("Local join must be exactly saturated in tail position: " + id);
                    } else visit(function, false);
                    for (var arg : args) visit(arg, false);
                }
                case "lam" -> {
                    int mark = mark();
                    bindParameters((List<Map<String, Object>>) expr.get(1));
                    visit((List<?>) expr.get(2), false);
                    restore(mark);
                }
                case "let" -> {
                    var nested = (List<Map<String, Object>>) expr.get(2);
                    int mark = mark();
                    boolean recursive = Boolean.TRUE.equals(expr.get(1));
                    if (recursive) bindParameters(nested);
                    var joins = definitions(nested);
                    if (joins != null) for (var join : joins) {
                        int rhsMark = mark();
                        bindParameters(join.parameters());
                        visit(join.body(), tail);
                        restore(rhsMark);
                    } else for (var binding : nested) visit((List<?>) binding.get("expr"), false);
                    if (!recursive) bindParameters(nested);
                    visit((List<?>) expr.get(3), tail);
                    restore(mark);
                }
                case "case" -> {
                    visit((List<?>) expr.get(1), false);
                    int mark = mark();
                    bind((String) expr.get(2));
                    for (var alternative : (List<List<?>>) expr.get(3)) {
                        int alternativeMark = mark();
                        for (var id : (List<String>) alternative.get(2)) bind(id);
                        visit((List<?>) alternative.get(3), tail);
                        restore(alternativeMark);
                    }
                    restore(mark);
                }
                default -> {}
            }
        }
    }
}
