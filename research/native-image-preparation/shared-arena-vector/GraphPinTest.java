/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */

import java.nio.file.*;
import java.util.*;
import jdk.graal.compiler.graphio.parsing.*;
import jdk.graal.compiler.graphio.parsing.model.*;

/** Checks the actual optimized fixedAccess graph, not the Java wrapper template. */
public final class GraphPinTest {
    private static final Map<String, InputGraph> graphs = new LinkedHashMap<>();

    private static String property(InputNode node, String name) {
        return node.getProperties().getString(name, "");
    }

    private static boolean cas(InputNode node) {
        return property(node, "class").endsWith(".LogicCompareAndSwapNode");
    }

    private static List<InputEdge> inputs(InputGraph graph, InputNode node) {
        return graph.getEdges().stream().filter(edge -> edge.getTo() == node.getId()).toList();
    }

    private static InputNode input(InputGraph graph, InputNode node, String label) {
        return inputs(graph, node).stream().filter(edge -> edge.getLabel().equals(label))
                .map(edge -> graph.getNode(edge.getFrom())).findFirst().orElse(null);
    }

    private static boolean vectorMemory(InputGraph graph, InputNode node) {
        if (!property(node, "locationIdentity").equals("OFF_HEAP_LOCATION")) return false;
        if (property(node, "class").endsWith(".ReadNode")) return property(node, "stamp").startsWith("<");
        InputNode value = input(graph, node, "value");
        return property(node, "class").endsWith(".WriteNode") && value != null && property(value, "stamp").startsWith("<");
    }

    private static String snapshot(InputGraph graph, InputNode node) {
        return graph.getBlock(node).getName() + ":" + inputs(graph, node).stream()
                .map(edge -> edge.getLabel() + "=" + edge.getFrom()).sorted().toList();
    }

    private static int delta(InputGraph graph, InputNode node) {
        InputNode expected = input(graph, node, "expectedValue"), replacement = input(graph, node, "newValue");
        if (expected == null || replacement == null ||
                !property(expected, "field").equals("jdk.internal.foreign.MemorySessionImpl.acquireCount") ||
                !property(replacement, "class").endsWith(".AddNode") ||
                input(graph, replacement, "x").getId() != expected.getId())
            throw new AssertionError("CAS is not a real session count update: " + node);
        int delta = Integer.parseInt(String.valueOf(input(graph, replacement, "y").getProperties().get("rawvalue")));
        if (delta != 1 && delta != -1) throw new AssertionError("Unknown session count delta: " + delta);
        return delta;
    }

    private static Set<Integer> pins(InputGraph graph) {
        Set<Integer> result = new TreeSet<>();
        for (InputNode node : graph.getNodes()) if (cas(node)) {
            if (!property(node, "memoryOrder").equals("MemoryOrderMode.VOLATILE") ||
                    !property(node, "killedLocationIdentity").equals("ANY_LOCATION"))
                throw new AssertionError("Pin lost ordered ANY_LOCATION semantics: " + node);
            result.add(node.getId());
            delta(graph, node);
        }
        return result;
    }

    private static Set<Integer> memoryPins(InputGraph graph, InputNode node, Set<Integer> visited) {
        Set<Integer> result = new TreeSet<>();
        if (!visited.add(node.getId())) return result;
        if (cas(node)) result.add(node.getId());
        else for (InputEdge edge : inputs(graph, node)) {
            if (edge.getLabel().startsWith("values") || edge.getLabel().equals("lastLocationAccess"))
                result.addAll(memoryPins(graph, graph.getNode(edge.getFrom()), visited));
        }
        return result;
    }

    private static boolean reachable(InputBlock start, InputBlock target, InputBlock excluded) {
        Set<InputBlock> seen = new HashSet<>();
        ArrayDeque<InputBlock> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            InputBlock block = pending.remove();
            if (block == excluded || !seen.add(block)) continue;
            if (block == target) return true;
            pending.addAll(block.getSuccessors());
        }
        return false;
    }

    private static void checkStage(InputGraph graph) {
        List<InputNode> accesses = graph.getNodes().stream().filter(node -> vectorMemory(graph, node)).toList();
        if (accesses.size() < 2) throw new AssertionError("No real SIMD load/store witness in " + graph.getName());
        if (pins(graph).size() < 8) throw new AssertionError("Four vector-operation pins/releases missing");
        for (InputNode access : accesses) {
            if (!property(access, "category").equals("fixed") ||
                    (property(access, "class").endsWith(".ReadNode") && !property(access, "forceFixed").equals("true")))
                throw new AssertionError("Native SIMD memory access can float: " + access);
            Set<Integer> dependencies = memoryPins(graph, access, new HashSet<>());
            if (dependencies.isEmpty() && graph.getName().contains("After low tier")) {
                // Final scheduling clears memory-dependency edges. Check its
                // retained fixed control flow against the real pre-schedule
                // acquire dependencies rather than inventing new ones.
                InputGraph late = graphs.get("late");
                dependencies.addAll(memoryPins(late, late.getNode(access.getId()), new HashSet<>()));
            }
            // Memory phis can also carry the preceding operation's release.
            dependencies.removeIf(pin -> delta(graph, graph.getNode(pin)) != 1);
            if (dependencies.isEmpty()) throw new AssertionError("SIMD memory access lost acquire dependency: " + access);
            InputBlock block = graph.getBlock(access);
            for (int pin : dependencies) {
                InputBlock before = graph.getBlock(graph.getNode(pin));
                if (!reachable(before, block, null) || reachable(block, before, null))
                    throw new AssertionError("Acquire pin moved past native SIMD access: " + pin);
            }
            // Global/confined paths legitimately skip shared CAS. On the shared
            // branch, a later count CAS must still be dominated by this access.
            boolean after = graph.getNodes().stream().filter(GraphPinTest::cas).filter(node -> delta(graph, node) == -1)
                    .map(graph::getBlock).anyMatch(candidate -> candidate != block &&
                            reachable(block, candidate, null) && !reachable(candidate, block, null) &&
                            !reachable(graph.getBlock("0"), candidate, block));
            if (!after) throw new AssertionError("Shared release moved before SIMD memory access");
            System.out.println("  SIMD " + access.getId() + " block=" + block.getName() + " acquire-memory=" + dependencies);
        }
        System.out.println("PASS " + graph.getName() + ": " + accesses.size() + " fixed SIMD accesses, " + pins(graph).size() + " volatile pins");
    }

    public static void main(String[] args) throws Exception {
        var document = new GraphDocument();
        var builder = new ModelBuilder(document, null) {
            @Override public InputGraph endGraph() {
                InputGraph graph = super.endGraph();
                String name = graph.getName();
                String stage = name.contains("FrameStateAssignment") ? "before" :
                        name.contains("SubstrateOptimizeSharedArenaAccess") ? "after" :
                        name.contains("LowTierLowering") ? "late" : name.contains("After low tier") ? "schedule" : null;
                if (stage == null) graph.getParent().removeElement(graph);
                else graphs.put(stage, graph);
                return graph;
            }
        };
        try (var stream = new java.io.BufferedInputStream(Files.newInputStream(Path.of(args[0])))) {
            new BinaryReader(new StreamSource(stream), builder).parse();
        }
        if (!graphs.keySet().equals(Set.of("before", "after", "late", "schedule")))
            throw new AssertionError("Missing real phase/late/schedule graphs: " + graphs.keySet());
        InputGraph before = graphs.get("before"), after = graphs.get("after");
        Set<Integer> expected = pins(before);
        for (var entry : graphs.entrySet()) {
            if (!pins(entry.getValue()).equals(expected)) throw new AssertionError("Pin eliminated or introduced in " + entry.getKey());
            checkStage(entry.getValue());
        }
        for (InputNode node : before.getNodes()) if (cas(node) || vectorMemory(before, node)) {
            InputNode retained = after.getNode(node.getId());
            if (retained == null || !snapshot(before, node).equals(snapshot(after, retained)))
                throw new AssertionError("Scalar scope phase moved/rewired native access or pin: " + node.getId());
        }
        System.out.println("PASS unchanged CAS/native-access placement through scalar shared-arena optimization");
    }
}
