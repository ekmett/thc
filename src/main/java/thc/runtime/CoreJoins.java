// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        for (var join : joins) {
            var shadowed = new LinkedHashSet<String>(recursive ? Set.of() : arities.keySet());
            addParameters(shadowed, join.parameters());
            visit(join.body(), true, shadowed, arities);
        }
        visit(body, true, Set.of(), arities);
    }

    private static Object first(List<?> values) { return values.isEmpty() ? null : values.getFirst(); }
    private static void addParameters(Set<String> ids, List<Map<String, Object>> parameters) {
        for (var parameter : parameters) ids.add((String) parameter.get("id"));
    }
    private static boolean isJoin(String id, Set<String> shadowed, Map<String, Integer> arities) {
        return arities.containsKey(id) && !shadowed.contains(id);
    }
    private static void visit(List<?> expr, boolean tail, Set<String> shadowed, Map<String, Integer> arities) {
        var inlined = CoreStateApplications.inline(expr);
        if (inlined != null) { visit(inlined, tail, shadowed, arities); return; }
        if (!(first(expr) instanceof String tag)) return;
        switch (tag) {
            case "var" -> {
                var id = (String) expr.get(1);
                if (isJoin(id, shadowed, arities) && (!tail || arities.get(id) != 0))
                    throw new RuntimeFault("Local join escapes or is not exactly saturated: " + id);
            }
            case "app" -> {
                var function = (List<?>) expr.get(1);
                var args = (List<List<?>>) expr.get(2);
                var id = "var".equals(first(function)) ? (String) function.get(1) : null;
                if (id != null && isJoin(id, shadowed, arities)) {
                    if (!tail || args.size() != arities.get(id))
                        throw new RuntimeFault("Local join must be exactly saturated in tail position: " + id);
                } else visit(function, false, shadowed, arities);
                for (var arg : args) visit(arg, false, shadowed, arities);
            }
            case "lam" -> {
                var scope = new LinkedHashSet<>(shadowed);
                addParameters(scope, (List<Map<String, Object>>) expr.get(1));
                visit((List<?>) expr.get(2), false, scope, arities);
            }
            case "let" -> {
                var nested = (List<Map<String, Object>>) expr.get(2);
                var ids = new LinkedHashSet<String>();
                addParameters(ids, nested);
                var bodyScope = new LinkedHashSet<>(shadowed); bodyScope.addAll(ids);
                Set<String> rhsScope = Boolean.TRUE.equals(expr.get(1)) ? bodyScope : shadowed;
                var joins = definitions(nested);
                if (joins != null) for (var join : joins) {
                    var scope = new LinkedHashSet<>(rhsScope); addParameters(scope, join.parameters());
                    visit(join.body(), tail, scope, arities);
                } else for (var binding : nested) visit((List<?>) binding.get("expr"), false, rhsScope, arities);
                visit((List<?>) expr.get(3), tail, bodyScope, arities);
            }
            case "case" -> {
                visit((List<?>) expr.get(1), false, shadowed, arities);
                for (var alternative : (List<List<?>>) expr.get(3)) {
                    var scope = new LinkedHashSet<>(shadowed);
                    scope.add((String) expr.get(2)); scope.addAll((List<String>) alternative.get(2));
                    visit((List<?>) alternative.get(3), tail, scope, arities);
                }
            }
            default -> {}
        }
    }
}
