// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.CoreFreeVariables.coreFreeVariables;

@SuppressWarnings("unchecked")
class CoreFreeVariablesTest {
    private List<Object> variable(String id) { return List.of("var", id); }
    @SafeVarargs private final List<Object> apply(List<Object>... values) {
        return List.of("app", values[0], new ArrayList<>(Arrays.asList(values).subList(1, values.length)));
    }
    private List<Object> lambda(List<String> ids, List<Object> body) {
        var parameters = new ArrayList<Map<String, Object>>();
        for (var id : ids) parameters.add(Map.of("id", id));
        return List.of("lam", parameters, body);
    }
    private Map<String, Object> binding(String id, List<Object> body) { return Map.of("id", id, "expr", body); }
    private List<Object> local(boolean recursive, List<Map<String, Object>> bindings, List<Object> body) {
        return List.of("let", recursive, bindings, body);
    }
    private List<Object> alternative(List<String> ids, List<Object> body) { return Arrays.asList("default", null, ids, body); }
    @SafeVarargs private final List<Object> caseExpression(List<Object> scrutinee, String id, List<Object>... alternatives) {
        return List.of("case", scrutinee, id, Arrays.asList(alternatives));
    }
    @Test void lexicalScopesPreserveFirstFreeOccurrenceAndRestoreShadowedIdentities() {
        var expression = apply(variable("first"),
            lambda(List.of("x"), apply(variable("x"), lambda(List.of("x"), variable("x")), variable("inside"))),
            variable("x"), variable("first"), variable("last"));
        assertEquals(List.of("first", "inside", "x", "last"), new ArrayList<>(coreFreeVariables(expression)));
        var selected = caseExpression(variable("caseValue"), "caseValue",
            alternative(List.of("field"), apply(variable("caseValue"), variable("field"), variable("a"))),
            alternative(List.of(), apply(variable("field"), variable("b"))));
        assertEquals(List.of("caseValue", "a", "field", "b"), new ArrayList<>(coreFreeVariables(selected)));
    }
    @Test void recursiveAndNonrecursiveGroupsHaveDifferentRhsScopes() {
        var group = List.of(binding("a", apply(variable("a"), variable("outside"))),
            binding("b", apply(variable("a"), variable("b"), variable("second"))));
        var body = apply(variable("b"), variable("last"));
        assertEquals(List.of("outside", "second", "last"), new ArrayList<>(coreFreeVariables(local(true, group, body))));
        assertEquals(List.of("a", "outside", "b", "second", "last"), new ArrayList<>(coreFreeVariables(local(false, group, body))));
        var filtered = new ArrayList<String>();
        for (var id : coreFreeVariables(apply(variable("a"), local(true, group, variable("a"))))) if (id.equals("a")) filtered.add(id);
        assertEquals(List.of("a"), filtered);
    }
    @Test void terminalRecordsAndMetadataAreNotVisitedOrRetainedAcrossCalls() {
        for (var tag : List.of("prim", "lit", "con", "void"))
            assertTrue(coreFreeVariables(List.of(tag, variable("not an expression child"))).isEmpty());
        List<Object> expression = List.of("app", variable("real"), List.of(), Map.of("debug", variable("not free")));
        var first = coreFreeVariables(expression);
        assertEquals(Set.of("real"), first);
        assertEquals(Set.of("other"), coreFreeVariables(variable("other")));
        assertEquals(Set.of("real"), first);
    }
    /** Previous lowering algorithm, retained here as an independent traversal
     * oracle over generated lexical trees, not used by production. */
    private Set<String> previous(List<Object> expr) {
        var result = new LinkedHashSet<String>();
        switch ((String) expr.get(0)) {
            case "var" -> result.add((String) expr.get(1));
            case "lam" -> {
                result.addAll(previous((List<Object>) expr.get(2)));
                for (var binding : (List<Map<String, Object>>) expr.get(1)) result.remove((String) binding.get("id"));
            }
            case "app" -> {
                result.addAll(previous((List<Object>) expr.get(1)));
                for (var argument : (List<List<Object>>) expr.get(2)) result.addAll(previous(argument));
            }
            case "let" -> {
                var group = (List<Map<String, Object>>) expr.get(2);
                var ids = new LinkedHashSet<String>(); for (var binding : group) ids.add((String) binding.get("id"));
                for (var binding : group) result.addAll(previous((List<Object>) binding.get("expr")));
                if (Boolean.TRUE.equals(expr.get(1))) result.removeAll(ids);
                var body = new LinkedHashSet<>(previous((List<Object>) expr.get(3))); body.removeAll(ids); result.addAll(body);
            }
            case "case" -> {
                result.addAll(previous((List<Object>) expr.get(1)));
                for (var alternative : (List<List<Object>>) expr.get(3)) {
                    var body = new LinkedHashSet<>(previous((List<Object>) alternative.get(3)));
                    body.removeAll((List<String>) alternative.get(2)); body.remove((String) expr.get(2)); result.addAll(body);
                }
            }
            default -> {}
        }
        return result;
    }
    /** Test-local xorwow state preserves the original seeded tree inventory. */
    private static final class TreeRandom {
        private int x = 0x5eed, y, z, w, v = ~0x5eed, addend = 0x5eed << 10;
        TreeRandom() { for (int i = 0; i < 64; i++) word(); }
        private int word() {
            int t = x ^ (x >>> 2); x = y; y = z; z = w; w = v;
            v = t ^ (t << 1) ^ v ^ (v << 4); addend += 362437; return v + addend;
        }
        int nextInt(int bound) {
            if ((bound & (bound - 1)) == 0) return word() >>> (32 - Integer.numberOfTrailingZeros(bound));
            int bits, value;
            do { bits = word() >>> 1; value = bits % bound; } while (bits - value + (bound - 1) < 0);
            return value;
        }
        boolean nextBoolean() { return (word() >>> 31) != 0; }
    }
    private final class Trees {
        final TreeRandom random = new TreeRandom();
        String id() { return "v" + random.nextInt(8); }
        List<Object> expression(int depth) {
            if (depth == 0) return variable(id());
            return switch (random.nextInt(5)) {
                case 0 -> variable(id());
                case 1 -> {
                    int count = random.nextInt(3); var ids = new ArrayList<String>();
                    for (int i = 0; i < count; i++) ids.add(id());
                    yield lambda(ids, expression(depth - 1));
                }
                case 2 -> apply(expression(depth - 1), expression(depth - 1), expression(depth - 1));
                case 3 -> {
                    boolean recursive = random.nextBoolean(); int count = random.nextInt(3);
                    var bindings = new ArrayList<Map<String, Object>>();
                    for (int i = 0; i < count; i++) bindings.add(binding(id(), expression(depth - 1)));
                    yield local(recursive, bindings, expression(depth - 1));
                }
                default -> caseExpression(expression(depth - 1), id(),
                    alternative(List.of(id()), expression(depth - 1)),
                    alternative(List.of(id(), id()), expression(depth - 1)));
            };
        }
    }
    @Test void generatedLexicalTreesRetainExactOrderedCaptureSets() {
        var trees = new Trees();
        for (int repeat = 0; repeat < 1024; repeat++) {
            var tree = trees.expression(5);
            assertEquals(new ArrayList<>(previous(tree)), new ArrayList<>(coreFreeVariables(tree)));
        }
    }
}
