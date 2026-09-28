// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class ArrayCoreEvidenceTest {
    private final Map<String, Object> intRep =
        Map.of("primReps", List.of("IntRep"), "kind", "long", "evaluated", false);
    private final Map<String, Object> voidRep = Map.of("primReps", List.of(), "kind", "void", "evaluated", true);
    private final List<Object> leaf = List.of("lit", "int", "0");
    private Map<String, Object> formal(String id) {
        return Map.of("id", id, "rep", intRep);
    }
    private Map<String, Object> binding(String id, Object expr) {
        return Map.of("id", id, "name", id, "expr", expr);
    }
    @SafeVarargs
    private final Map<String, Object> module(Map<String, Object>... bindings) {
        return Map.of("bindings", Arrays.asList(bindings));
    }
    private Map<String, Object> root() {
        return root(leaf);
    }
    private Map<String, Object> root(Object body) {
        var state = Map.of("id", "s", "type", "State# RealWorld", "rep", voidRep, "lifted", false, "coercion", false);
        return binding("root",
            List.of("lam", List.of(formal("x")),
                List.of("app", List.of("lam", List.of(state), body), List.of(List.of("void", Map.of("rep", voidRep))),
                    List.of(false), false, false)));
    }
    private Map<String, Object> changedCall(Consumer<List<Object>> change) {
        return changedCall(leaf, change);
    }
    private Map<String, Object> changedCall(Object body, Consumer<List<Object>> change) {
        var root = new LinkedHashMap<>(root(body));
        var expr = new ArrayList<>((List<Object>) root.get("expr"));
        var call = new ArrayList<>((List<Object>) expr.get(2));
        change.accept(call);
        expr.set(2, call);
        root.put("expr", expr);
        return root;
    }
    @Test
    void actualClosureAndPrimitiveOccurrencesComeFromCore() {
        var helper = binding("helper",
            List.of("lam", List.of(formal("h")),
                List.of("app", List.of("prim", "+#"), List.of(List.of("var", "h"), leaf))));
        var root = binding("root",
            List.of(
                "lam", List.of(formal("x")), List.of("app", List.of("var", "helper"), List.of(List.of("var", "x")))));
        var ignored = binding("unused", List.of("prim", "indexIntArray#"));
        var proof = new ArrayCoreEvidence(module(root, helper, ignored), "root");
        var ids = new HashSet<>();
        for (var binding : proof.getBindings()) ids.add(binding.get("id"));
        assertEquals(java.util.Set.of("root", "helper"), ids);
        assertEquals(Map.of("+#", 1), proof.getPrimitiveCounts());
        assertEquals(List.of("helper"), proof.globalReferences(root.get("expr")));
        assertThrows(IllegalArgumentException.class, () -> new ArrayCoreEvidence(module(root), "root"));
        assertThrows(IllegalArgumentException.class, () -> new ArrayCoreEvidence(module(root, root, helper), "root"));
        assertThrows(IllegalArgumentException.class, () -> new ArrayCoreEvidence(module(root, helper), "missing"));
    }
    @Test
    void immediateStateProofRejectsChangedFormalsArgumentsFlagsAndControlFlow() {
        assertEquals(2, new ArrayCoreEvidence(module(root()), "root").immediateStateCalls());
        assertEquals(1, new ArrayCoreEvidence(module(root()), "root").loweredImmediateStateCalls());
        var badCalls = new ArrayList<Map<String, Object>>();
        for (var change :
            new Object[][] {{"type", "Proxy# RealWorld"}, {"rep", intRep}, {"lifted", true}, {"coercion", true}}) {
            badCalls.add(changedCall(call -> {
                var state = new ArrayList<>((List<Object>) call.get(1));
                var formal = new LinkedHashMap<>(((List<Map<String, Object>>) state.get(1)).getFirst());
                formal.put((String) change[0], change[1]);
                state.set(1, List.of(formal));
                call.set(1, state);
            }));
        }
        badCalls.add(changedCall(it -> it.set(2, List.of())));
        badCalls.add(changedCall(it -> it.set(2, List.of(leaf))));
        badCalls.add(changedCall(it -> it.set(2, List.of(List.of("void", Map.of("rep", intRep))))));
        badCalls.add(changedCall(call -> {
            var state = new ArrayList<>((List<Object>) call.get(1));
            var formals = new ArrayList<>((List<Object>) state.get(1));
            formals.add(formal("extra"));
            state.set(1, formals);
            call.set(1, state);
        }));
        badCalls.add(changedCall(it -> it.set(3, List.of(true))));
        badCalls.add(changedCall(it -> it.set(4, true)));
        badCalls.add(changedCall(it -> it.set(5, true)));
        badCalls.add(root(List.of("lam", List.of(formal("hidden")), leaf)));
        badCalls.add(root(List.of("var", "root")));
        var outer = (List<Object>) root().get("expr");
        badCalls.add(binding("root",
            List.of("lam", outer.get(1),
                List.of("case", leaf, "c", List.of(Arrays.asList("default", null, List.of(), outer.get(2)))))));
        for (int index = 0; index < badCalls.size(); index++) {
            var bad = badCalls.get(index);
            assertThrows(IllegalArgumentException.class,
                () -> new ArrayCoreEvidence(module(bad), "root").immediateStateCalls(), "mutation" + index);
            assertThrows(IllegalArgumentException.class,
                ()
                    -> new ArrayCoreEvidence(module(bad), "root").loweredImmediateStateCalls(),
                "lowered mutation" + index);
        }
    }
    @Test
    void inFrameStateProofKeepsReturnedLambdasAndValidatesMultiargumentRoots() {
        var nested = new LinkedHashMap<>(root(List.of("lam", List.of(formal("returned")), leaf)));
        var outer = new ArrayList<>((List<Object>) nested.get("expr"));
        outer.set(1, List.of(formal("x"), formal("y")));
        nested.put("expr", outer);
        var proof = new ArrayCoreEvidence(module(nested), "root");
        assertEquals(3, proof.guestLambdas(outer).size());
        var kept = proof.loweredGuestLambdas(outer);
        assertEquals(2, kept.size());
        assertSame(outer, kept.get(0));
        assertEquals("returned", ((Map<?, ?>) ((List<?>) kept.get(1).get(1)).getFirst()).get("id"));
        assertThrows(IllegalArgumentException.class, () -> proof.stateLambda(outer));
        for (var bad :
            List.of(changedCall(it -> it.set(2, List.of(leaf))), changedCall(it -> it.set(3, List.of(true))))) {
            var rejected = new ArrayCoreEvidence(module(bad), "root");
            assertThrows(IllegalArgumentException.class, () -> rejected.loweredGuestLambdas(bad.get("expr")));
        }
    }
    private ArrayCoreEvidence helperProof(Map<String, Object> entry, Map<String, Object> helper) {
        return new ArrayCoreEvidence(module(entry, helper), "root");
    }
    @Test
    void namedStatePathRequiresTheExactRedexAndPreservesCallbacksAndMultiplicity() {
        var call = List.of("app", List.of("var", "helper"), List.of(List.of("var", "x")));
        var helper = binding("helper", List.of("lam", List.of(formal("h")), leaf));
        var good = helperProof(root(call), helper);
        assertEquals(List.of("root", "helper"), good.loweredStateFunctionPath("helper"));
        int count = 0;
        for (var binding : good.getBindings()) count += good.guestLambdas(binding.get("expr")).size();
        assertEquals(3, count);
        assertThrows(IllegalArgumentException.class, () -> good.loweredStateFunctionPath("root"));
        assertThrows(IllegalArgumentException.class, () -> good.loweredStateFunctionPath("helper", "helper"));
        var direct = binding("root", List.of("lam", List.of(formal("x")), call));
        var stateHelper = new LinkedHashMap<>(root());
        stateHelper.put("id", "helper");
        stateHelper.put("name", "helper");
        assertEquals(List.of("root", "helper"),
            new ArrayCoreEvidence(module(direct, stateHelper), "root").loweredStateFunctionPath("helper", "helper"));
        var bad = List.of(changedCall(call, it -> it.set(3, List.of(true))),
            changedCall(call, it -> it.set(3, List.of("false"))),
            changedCall(call, it -> it.set(2, List.of(List.of("var", "x", Map.of("rep", voidRep))))),
            changedCall(call, it -> it.set(2, List.of(List.of("app", List.of("prim", "effect#"), List.of())))),
            changedCall(call,
                node -> {
                    var lambda = new ArrayList<>((List<Object>) node.get(1));
                    var arg = new LinkedHashMap<>(((List<Map<String, Object>>) lambda.get(1)).getFirst());
                    var rep = new LinkedHashMap<>(voidRep);
                    rep.put("aggregate", "unboxed-tuple");
                    rep.put("components", List.of());
                    arg.put("rep", rep);
                    lambda.set(1, List.of(arg));
                    node.set(1, lambda);
                }),
            root(List.of("lam", List.of(formal("callback")), call)),
            root(List.of("case", call, "v", List.of(Arrays.asList("default", null, List.of(), call)))));
        for (int index = 0; index < bad.size(); index++) {
            var entry = bad.get(index);
            assertThrows(IllegalArgumentException.class,
                () -> helperProof(entry, helper).loweredStateFunctionPath("helper"), "source path mutation " + index);
        }
        // A genuine callback with the same State# proof is not an immediate call.
        var outer = (List<Object>) root(call).get("expr");
        var stateCall = (List<Object>) outer.get(2);
        var callback = binding("root",
            List.of(
                "lam", outer.get(1), List.of("app", List.of("prim", "keepAlive#"), List.of(leaf, stateCall.get(1)))));
        assertThrows(
            IllegalArgumentException.class, () -> helperProof(callback, helper).loweredStateFunctionPath("helper"));
    }
    private Map<String, Object> join() {
        return new LinkedHashMap<>(Map.of("id", "j", "joinValueArity", 1L, "joinResultRep", intRep, "info",
            Map.of("joinArity", 1L), "expr", List.of("lam", List.of(formal("a")), leaf, Map.of("resultRep", intRep))));
    }
    private ArrayCoreEvidence joinProof(Map<String, Object> join) {
        return new ArrayCoreEvidence(
            module(root(List.of("let", false, List.of(join), List.of("app", List.of("var", "j"), List.of(leaf))))),
            "root");
    }
    @Test
    void onlyCompleteProvenJoinPrefixesAreExcludedAndTheirBodiesRemainVisible() {
        assertEquals(2, joinProof(join()).immediateStateCalls());
        List<Consumer<Map<String, Object>>> mutations = List.of(it
            -> it.remove("joinValueArity"),
            it
            -> it.put("joinValueArity", 0L),
            it
            -> it.put("joinValueArity", true),
            it
            -> it.put("joinValueArity", 1.0),
            it
            -> it.put("joinValueArity", 2L),
            it
            -> it.remove("joinResultRep"),
            it
            -> {
                it.put("joinResultRep", Map.of());
                it.put("expr", List.of("lam", List.of(formal("a")), leaf, Map.of("resultRep", Map.of())));
            },
            it
            -> {
                var rep = new LinkedHashMap<>(intRep);
                rep.put("primReps", List.of("WordRep"));
                it.put("joinResultRep", rep);
            },
            it
            -> it.put("expr", List.of("lam", List.of(formal("a"), formal("b")), leaf, Map.of("resultRep", intRep))),
            it
            -> it.put("expr",
                List.of("lam", List.of(formal("a")), List.of("lam", List.of(formal("hidden")), leaf),
                    Map.of("resultRep", intRep))));
        for (int index = 0; index < mutations.size(); index++) {
            var change = mutations.get(index);
            assertThrows(IllegalArgumentException.class, () -> {
                var join = join();
                change.accept(join);
                joinProof(join).immediateStateCalls();
            }, "join mutation" + index);
        }
    }
    @Test
    void traversalIncludesDictionaryHeldExpressionsWithoutCountingUnusedGlobals() {
        var local = Map.of("id", "a", "expr", List.of("app", List.of("prim", "+#"), List.of(leaf, leaf)));
        var proof =
            new ArrayCoreEvidence(module(root(List.of("let", false, List.of(local), List.of("var", "a")))), "root");
        assertEquals(Map.of("+#", 1), proof.getPrimitiveCounts());
        assertEquals(2, proof.immediateStateCalls());
        var hidden = new LinkedHashMap<>(local);
        hidden.put("expr", List.of("lam", List.of(formal("h")), leaf));
        assertThrows(IllegalArgumentException.class,
            ()
                -> new ArrayCoreEvidence(module(root(List.of("let", false, List.of(hidden), leaf))), "root")
                    .immediateStateCalls());
    }
}
