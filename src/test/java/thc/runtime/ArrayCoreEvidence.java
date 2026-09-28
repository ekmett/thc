// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import thc.CoreModules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Structural evidence for the pinned array fixtures, not a Core evaluator. */
@SuppressWarnings("unchecked")
final class ArrayCoreEvidence {
    private final String name;
    private final Map<String, Map<String, Object>> allBindings = new LinkedHashMap<>();
    private final List<Map<String, Object>> bindings;
    private final Map<String, Object> root;
    private final Map<String, Integer> primitiveCounts = new LinkedHashMap<>();

    ArrayCoreEvidence(Map<String, Object> module, String name) {
        this.name = name;
        var supplied = (List<Map<String, Object>>) module.get("bindings");
        for (var binding : supplied) allBindings.put((String) binding.get("id"), binding);
        require(allBindings.size() == supplied.size(), name + ": duplicate binding");
        // Reuse the production lexical linker, including missing-global/constructor checks.
        bindings = (List<Map<String, Object>>) CoreModules.reachable(module, name, true).get("bindings");
        root = allBindings.containsKey(name) ? allBindings.get(name) : single(bindings, it -> Objects.equals(it.get("name"), name));
        for (var binding : bindings) for (var node : nodes(binding.get("expr")))
            if (Objects.equals(first(node), "prim")) primitiveCounts.merge((String) node.get(1), 1, Integer::sum);
    }
    public Map<String, Map<String, Object>> getAllBindings() { return allBindings; }
    public List<Map<String, Object>> getBindings() { return bindings; }
    public Map<String, Object> getRoot() { return root; }
    public Map<String, Integer> getPrimitiveCounts() { return primitiveCounts; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static Object at(List<?> values, int index) { return values != null && index >= 0 && index < values.size() ? values.get(index) : null; }
    private static Object first(List<?> values) { return at(values, 0); }
    private static Object last(List<?> values) { return values == null ? null : at(values, values.size() - 1); }
    private static List<Object> list(Object value) { return value instanceof List<?> it ? (List<Object>) it : null; }
    private static Map<Object, Object> map(Object value) { return value instanceof Map<?, ?> it ? (Map<Object, Object>) it : null; }
    private static Object field(Object value, Object key) { var map = map(value); return map == null ? null : map.get(key); }
    private static Object only(List<?> values) { return values != null && values.size() == 1 ? values.getFirst() : null; }
    private static <T> T single(List<T> values, Predicate<T> predicate) {
        T found = null; boolean matched = false;
        for (var value : values) if (predicate.test(value)) {
            require(!matched, "Collection contains more than one matching element."); found = value; matched = true;
        }
        if (!matched) throw new java.util.NoSuchElementException("Collection contains no element matching the predicate.");
        return found;
    }
    public List<List<Object>> nodes(Object value) {
        var result = new ArrayList<List<Object>>(); visit(value, result); return result;
    }
    private static void visit(Object value, List<List<Object>> result) {
        if (value instanceof List<?> values) { result.add((List<Object>) values); for (var child : values) visit(child, result); }
        else if (value instanceof Map<?, ?> values) for (var child : values.values()) visit(child, result);
    }
    public List<String> globalReferences(Object expr) {
        var result = new ArrayList<String>();
        for (var node : nodes(expr)) if (Objects.equals(first(node), "var") && allBindings.containsKey(at(node, 1))) result.add((String) node.get(1));
        return result;
    }
    public List<List<Object>> guestLambdas(Object expr) {
        var all = nodes(expr);
        var prefixes = Collections.newSetFromMap(new IdentityHashMap<List<Object>, Boolean>());
        for (var node : all) if (Objects.equals(first(node), "let")) {
            for (var binding : (List<Map<String, Object>>) node.get(2)) {
                var arity = binding.get("joinValueArity"); var rhs = list(binding.get("expr"));
                if (rhs == null) continue;
                if ((arity instanceof Integer || arity instanceof Long) && ((Number) arity).longValue() > 0 &&
                    Objects.equals(first(rhs), "lam") && ((Number) arity).longValue() == ((List<?>) rhs.get(1)).size()) {
                    var result = map(binding.get("joinResultRep"));
                    require(result != null && Objects.equals(result.get("primReps"), List.of("IntRep")) &&
                        Objects.equals(result.get("kind"), "long") && Objects.equals(result, field(last(rhs), "resultRep")),
                        name + ": complete join prefix lost its scalar Int# result proof");
                    prefixes.add(rhs);
                }
            }
        }
        // Do not skip a join's body: a function returned or hidden inside it is a guest root.
        var result = new ArrayList<List<Object>>();
        for (var node : all) if (Objects.equals(first(node), "lam") && !prefixes.contains(node)) result.add(node);
        return result;
    }
    public List<Object> stateLambda(Object expr) {
        var outer = list(expr);
        require(Objects.equals(first(outer), "lam"), name + ": entry lost its lambda");
        var input = map(only(list(at(outer, 1))));
        require(Objects.equals(field(input == null ? null : input.get("rep"), "primReps"), List.of("IntRep")), name + ": expected unary Int# entry");
        var state = immediateStateLambda(at(outer, 2)); var lambdas = guestLambdas(outer);
        require(lambdas.size() == 2 && lambdas.get(0) == outer && lambdas.get(1) == state, name + ": unexpected additional guest lambda");
        return state;
    }
    /** The exact source redex, also used by multiargument and helper fixtures. */
    public List<Object> immediateStateLambda(Object expression) {
        var call = list(expression);
        require(Objects.equals(first(call), "app"), name + ": State# lambda must be called immediately");
        var state = list(at(call, 1));
        require(Objects.equals(first(state), "lam"), name + ": missing immediate State# lambda");
        var formal = map(only(list(at(state, 1))));
        var voidRep = Map.of("primReps", List.of(), "kind", "void", "evaluated", true);
        require(formal != null && Objects.equals(formal.get("type"), "State# RealWorld") && Objects.equals(formal.get("rep"), voidRep) &&
            Objects.equals(formal.get("lifted"), false) && Objects.equals(formal.get("coercion"), false), name + ": expected actual zero-slot State# formal");
        var argument = list(only(list(at(call, 2))));
        require(Objects.equals(first(argument), "void") && Objects.equals(field(last(argument), "rep"), voidRep), name + ": expected one void State# argument");
        require(call.subList(Math.min(3, call.size()), Math.min(6, call.size())).equals(List.of(List.of(false), false, false)), name + ": State# call flags changed");
        return state;
    }
    /** Keep helpers and returned/argument lambdas; exclude only checked direct
     * State# applications. This reads Core, never runtime target observations. */
    public List<List<Object>> loweredGuestLambdas(Object expr) {
        var inFrame = new ArrayList<List<Object>>();
        for (var node : nodes(expr)) if (Objects.equals(first(node), "app") && Objects.equals(first(list(at(node, 1))), "lam")) inFrame.add(immediateStateLambda(node));
        var result = new ArrayList<List<Object>>();
        outer: for (var lambda : guestLambdas(expr)) {
            for (var state : inFrame) if (state == lambda) continue outer;
            result.add(lambda);
        }
        return result;
    }
    public List<String> loweredStateFunctionPath(String callee) { return loweredStateFunctionPath(callee, (String) root.get("name"), List.of(2)); }
    public List<String> loweredStateFunctionPath(String callee, String stateOwner) { return loweredStateFunctionPath(callee, stateOwner, List.of(2)); }
    /** Bounded two-function fixture path with one source State# redex. Keep its
     * exported lambda inventory distinct from executable roots; never infer an
     * expected count from observed targets or call the production rewriter. */
    public List<String> loweredStateFunctionPath(String callee, String stateOwner, List<Integer> statePath) {
        var functions = List.of(root, single(bindings, it -> Objects.equals(it.get("name"), callee)));
        var ids = List.of((String) root.get("id"), (String) functions.get(1).get("id"));
        require(!ids.get(0).equals(ids.get(1)), name + ": expected two distinct global functions");
        var owner = single(functions, it -> Objects.equals(it.get("name"), stateOwner));
        Object application = owner.get("expr");
        for (int index : statePath) application = ((List<?>) application).get(index);
        var state = immediateStateLambda(application);
        var exported = new ArrayList<List<Object>>();
        for (var binding : bindings) exported.addAll(guestLambdas(binding.get("expr")));
        require(exported.size() == 3 && identityContains(exported, state) && identityContains(exported, functions.get(0).get("expr")) &&
            identityContains(exported, functions.get(1).get("expr")), name + ": expected two global lambdas and the exact local State# lambda");
        var retained = new ArrayList<List<Object>>();
        for (var binding : bindings) retained.addAll(loweredGuestLambdas(binding.get("expr")));
        require(retained.size() == 2 && identityContains(retained, functions.get(0).get("expr")) && identityContains(retained, functions.get(1).get("expr")),
            name + ": unexpected retained callback, helper or missing global root");
        var rootRefs = new ArrayList<String>(); var helperRefs = new ArrayList<String>();
        for (var id : globalReferences(root.get("expr"))) if (ids.contains(id)) rootRefs.add(id);
        for (var id : globalReferences(functions.get(1).get("expr"))) if (ids.contains(id)) helperRefs.add(id);
        require(rootRefs.equals(List.of(ids.get(1))) && helperRefs.isEmpty(), name + ": two-function call path or multiplicity changed");
        return ids;
    }
    private static boolean identityContains(List<?> values, Object value) { for (var item : values) if (item == value) return true; return false; }
    public int immediateStateCalls() {
        require(bindings.size() == 1 && globalReferences(root.get("expr")).isEmpty(), name + ": global closure changed");
        stateLambda(root.get("expr")); return guestLambdas(root.get("expr")).size();
    }
    /** Exclude only the independently checked runRW State# beta-redex.
     * Keep the exported lambda inventory above for structural evidence. */
    public List<List<Object>> loweredStateLambdas(Object expr) {
        var state = stateLambda(expr); var result = new ArrayList<List<Object>>();
        for (var lambda : guestLambdas(expr)) if (lambda != state) result.add(lambda);
        return result;
    }
    public int loweredImmediateStateCalls() {
        immediateStateCalls(); // Preserve the closed-global and exact-source checks.
        return loweredStateLambdas(root.get("expr")).size();
    }
}
