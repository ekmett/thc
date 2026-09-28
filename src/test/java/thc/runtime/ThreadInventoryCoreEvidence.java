// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Predicate;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Counts come from genuine Core and the independently retained snapshot population,
 * never from discovered targets, compilation counters, or an interpreted trial. */
@SuppressWarnings("unchecked")
public final class ThreadInventoryCoreEvidence {
    private final ArrayCoreEvidence evidence;
    private final String name;
    private final int scans;
    private final int originalOnce;
    private final int once;
    private final Set<String> labels;

    public ThreadInventoryCoreEvidence(Map<String, Object> module, String name) {
        this.name = name;
        evidence = new ArrayCoreEvidence(module, name);
        scans = switch (name) {
            case "selfInventory" -> 1;
            case "forkSnapshot" -> 3;
            case "boundQuery", "snapshotSize" -> 0;
            default -> throw new IllegalStateException("No source call-count proof for " + name);
        };
        originalOnce = name.equals("forkSnapshot") ? 3 : 2;
        once = originalOnce - 1; // Proven immediate State# wrapper is beta-reduced.
        var root = (List<Object>) evidence.getRoot().get("expr");
        var lambdas = evidence.guestLambdas(root);
        assertEquals(originalOnce, lambdas.size(), name + " public, immediate state, and optional child roots");
        assertEquals("lam", root.get(0));
        var immediate = (List<?>) root.get(2);
        assertEquals("app", immediate.get(0));
        assertSame(lambdas.get(1), immediate.get(1), name + " retains its immediately applied runRW# lambda");
        assertEquals(List.of("Int#"), types(root.get(1)));
        for (var lambda : lambdas.subList(1, lambdas.size())) assertEquals(List.of("State# RealWorld"), types(lambda.get(1)));
        var inlined = inlinedStateWrapper(evidence);
        var originalRoots = new ArrayList<List<?>>();
        for (var lambda : lambdas) if (lambda != inlined) originalRoots.add(lambda);
        if (scans == 0) {
            assertEquals(1, evidence.getBindings().size());
            assertEquals(List.of(), evidence.globalReferences(root));
        } else {
            var names = new LinkedHashSet<Object>();
            for (var binding : evidence.getBindings()) names.add(binding.get("name"));
            assertEquals(Set.of(name, "occurrences"), names);
            var recursive = single(evidence.getBindings(), item -> "occurrences".equals(item.get("name")));
            var id = (String) recursive.get("id");
            var loop = (List<Object>) recursive.get("expr");
            assertEquals("lam", loop.get(0));
            assertEquals(1, evidence.guestLambdas(loop).size());
            originalRoots.add(loop);
            var formals = (List<Map<String, Object>>) loop.get(1);
            assertEquals(List.of("ThreadId#", "Array# ThreadId#", "Int#"), types(formals));
            var wanted = formals.get(0).get("id");
            var array = formals.get(1).get("id");
            var index = formals.get(2).get("id");
            var body = (List<?>) loop.get(2);
            assertEquals("case", body.get(0));
            var condition = application(body.get(1), "<#");
            variable(condition.get(0), index);
            variable(single(application(condition.get(1), "sizeofArray#")), array);
            var alternatives = (List<List<?>>) body.get(3);
            assertEquals(2, alternatives.size());
            var stop = single(alternatives, item -> "lit".equals(item.get(0)));
            assertEquals(List.of("int", "0"), stop.get(1));
            literal(stop.get(3), "0");
            var next = (List<?>) single(alternatives, item -> "default".equals(item.get(0))).get(3);
            assertEquals("case", next.get(0));
            var read = application(next.get(1), "indexArray#");
            variable(read.get(0), array); variable(read.get(1), index);
            var recursiveCall = single(evidence.nodes(loop), item -> head(item, "app", "var", id));
            var arguments = (List<?>) recursiveCall.get(2);
            variable(arguments.get(0), wanted); variable(arguments.get(1), array);
            var increment = application(arguments.get(2), "+#");
            variable(increment.get(0), index); literal(increment.get(1), "1");
            assertEquals(List.of(id), evidence.globalReferences(loop), "Exactly one recursive call");
            var listing = single(evidence.nodes(root), item -> "case".equals(at(item, 0)) &&
                at(item, 1) instanceof List<?> call && at(call, 1) instanceof List<?> fn &&
                take(fn, 2).equals(List.of("prim", "listThreads#")));
            var snapshot = ((List<?>) ((List<?>) single((List<?>) listing.get(3))).get(2)).get(1);
            var calls = new ArrayList<List<Object>>();
            for (var item : evidence.nodes(root)) if (head(item, "app", "var", id)) calls.add(item);
            assertEquals(scans, calls.size());
            assertEquals(Collections.nCopies(scans, id), evidence.globalReferences(root));
            for (var call : calls) {
                var inputs = (List<?>) call.get(2);
                assertEquals(3, inputs.size());
                variable(inputs.get(1), snapshot); literal(inputs.get(2), "0");
            }
        }
        labels = new LinkedHashSet<>();
        for (var lambda : originalRoots) {
            var text = new StringBuilder("lambda ");
            boolean first = true;
            for (var parameter : (List<Map<String, Object>>) lambda.get(1)) {
                if (!first) text.append(", "); first = false;
                text.append(String.valueOf(parameter.get("name")));
            }
            labels.add(text.toString());
        }
        assertEquals(once + (scans == 0 ? 0 : 1), labels.size(), name + " distinct source roots");
    }
    public Set<String> getLabels() { return labels; }
    public long compiledCalls(int population) {
        if (population < 1) throw new IllegalArgumentException("Failed requirement.");
        // Includes terminal zero case; the child enters once, not on each resume.
        return (long) once + scans * ((long) population + 1);
    }
    public void assertRoots(List<RootCallTarget> targets) {
        var names = new LinkedHashSet<String>();
        for (var target : targets) names.add(target.getRootNode().getName());
        assertEquals(labels, names, name + " source-derived root labels");
        assertEquals(labels.size(), targets.size(), name + " exact original root inventory");
    }
    /** Prove the eliminated wrapper from original Core, never target discovery. */
    public static List<?> inlinedStateWrapper(ArrayCoreEvidence evidence) {
        var root = (List<?>) evidence.getRoot().get("expr");
        assertEquals("lam", root.get(0));
        var call = (List<?>) root.get(2);
        assertEquals("app", call.get(0));
        assertEquals(List.of(List.of(false), false, false), call.subList(3, 6));
        var lambda = (List<?>) call.get(1);
        assertEquals("lam", lambda.get(0));
        var parameter = (Map<?, ?>) single((List<?>) lambda.get(1));
        var voidRep = Map.of("primReps", List.of(), "kind", "void", "evaluated", true);
        assertEquals("State# RealWorld", parameter.get("type"));
        assertEquals(false, parameter.get("lifted"));
        assertEquals(false, parameter.get("coercion"));
        assertEquals(voidRep, parameter.get("rep"));
        var argument = (List<?>) single((List<?>) call.get(2));
        assertEquals("void", argument.get(0));
        assertEquals(voidRep, ((Map<?, ?>) argument.getLast()).get("rep"));
        var immediate = new ArrayList<List<Object>>();
        for (var binding : evidence.getBindings()) for (var node : evidence.nodes(binding.get("expr"))) {
            if ("app".equals(at(node, 0)) && at(node, 1) instanceof List<?> fn && "lam".equals(at(fn, 0))) immediate.add(node);
        }
        assertSame(call, single(immediate), "Exactly one immediate State# lambda can be eliminated");
        return lambda;
    }
    private static final Class<?> targetClass;
    static {
        try { targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); }
        catch (ClassNotFoundException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static boolean valid(RootCallTarget target) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(targetClass.getMethod("isValidLastTier").invoke(target));
    }
    public static void rawCompile(RootCallTarget target) throws ReflectiveOperationException {
        targetClass.getMethod("compile", boolean.class).invoke(target, true);
        assertTrue(valid(target), "Installed " + target.getRootNode().getName());
    }
    public static void restoreBoundary(RootCallTarget target) throws ReflectiveOperationException {
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", targetClass).invoke(runtime, target);
    }
    public static void install(List<RootCallTarget> targets) throws ReflectiveOperationException {
        for (var target : targets) { rawCompile(target); restoreBoundary(target); }
    }
    public static List<Integer> interpretedCalls(List<RootCallTarget> targets) throws ReflectiveOperationException {
        var counts = new ArrayList<Integer>();
        for (var target : targets) counts.add((Integer) targetClass.getMethod("getCallCount").invoke(target));
        return counts;
    }
    public static void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
        assertNull(state.getPending());
    }
    public static void joinCompletedForks(GuestThreads registry) throws InterruptedException {
        // Join only context-owned completed-child fixture forks, never host threads.
        for (var value : registry.snapshot()) if (value instanceof GuestThreadId identity && identity.getForked$org_intelligence_thc()) {
            var carrier = identity.getCarrier$org_intelligence_thc().get();
            if (carrier != null) {
                carrier.join(5000);
                assertFalse(carrier.isAlive(), "Completed guest child must terminate before context close");
            }
            assertEquals(GuestThreadStatus.FINISHED, registry.status(identity));
        }
    }
    public static List<RootCallTarget> targets(RootCallTarget entry) throws ReflectiveOperationException {
        var found = new ArrayList<RootCallTarget>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        visit(entry, found, seen);
        return found;
    }
    private static void visit(RootCallTarget target, List<RootCallTarget> found, Set<RootCallTarget> seen) throws ReflectiveOperationException {
        if (!seen.add(target)) return;
        var root = target.getRootNode();
        var nodes = new ArrayList<Node>();
        if (root instanceof BytecodeRoot bytecode) {
            var arguments = new ArrayList<Instruction.Argument>();
            for (var instruction : bytecode.getBytecodeNode().getInstructions()) arguments.addAll(instruction.getArguments());
            // Fork action's genuine closure belongs to its fresh thread root.
            for (var argument : arguments) if (argument.getKind() == Instruction.Argument.Kind.CONSTANT &&
                argument.asConstant() instanceof BytecodeRoot.ClosureTemplate closure) visit(closure.target, found, seen);
            nodes.add(root);
            for (var argument : arguments) if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                var node = argument.asCachedNode(); if (node != null) nodes.add(node);
            }
        } else nodes.add(root);
        var all = new ArrayList<Node>();
        for (var node : nodes) all.addAll(NodeUtil.findAllNodeInstances(node, Node.class));
        for (var node : all) {
            if (Set.of("MakeClosure", "Delay").contains(node.getClass().getSimpleName())) {
                var field = node.getClass().getDeclaredField("target"); field.setAccessible(true);
                visit((RootCallTarget) field.get(node), found, seen);
            }
            if (node instanceof DirectCallNode direct && direct.getCurrentCallTarget() instanceof RootCallTarget callee && callee.getRootNode() instanceof GuestRoot)
                visit(callee, found, seen);
        }
        found.add(target);
    }
    private static List<Object> types(Object formals) {
        var types = new ArrayList<Object>();
        for (var formal : (List<Map<?, ?>>) formals) types.add(formal.get("type"));
        return types;
    }
    private static Object at(List<?> list, int index) { return index < list.size() ? list.get(index) : null; }
    private static List<?> take(List<?> list, int size) { return list.subList(0, Math.min(size, list.size())); }
    private static boolean head(List<?> expression, String tag, String kind, Object id) {
        return tag.equals(at(expression, 0)) && at(expression, 1) instanceof List<?> fn && take(fn, 2).equals(Arrays.asList(kind, id));
    }
    private static void variable(Object value, Object expected) { assertEquals(Arrays.asList("var", expected), take((List<?>) value, 2)); }
    private static List<?> application(Object value, String operator) {
        var expression = (List<?>) value;
        assertEquals("app", expression.get(0));
        assertEquals(List.of("prim", operator), take((List<?>) expression.get(1), 2));
        return (List<?>) expression.get(2);
    }
    private static void literal(Object value, String expected) { assertEquals(List.of("lit", "int", expected), take((List<?>) value, 3)); }
    private static <T> T single(List<T> values) { return single(values, value -> true); }
    private static <T> T single(List<T> values, Predicate<T> predicate) {
        boolean found = false; T result = null;
        for (T value : values) if (predicate.test(value)) {
            if (found) throw new IllegalArgumentException("Collection contains more than one matching element.");
            found = true; result = value;
        }
        if (!found) throw new NoSuchElementException("Collection contains no element matching the predicate.");
        return result;
    }
}
