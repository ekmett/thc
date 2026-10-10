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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Compilation, target discovery and handoff checks shared by runtime fixtures. */
public final class ThreadInventoryCoreEvidence {
    private ThreadInventoryCoreEvidence() {}
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
        for (var value : registry.snapshot()) if (value instanceof GuestThreadId identity && identity.getForked()) {
            var carrier = identity.getCarrier().get();
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
            for (var argument : arguments) if (argument.getKind() == Instruction.Argument.Kind.CONSTANT &&
                argument.asConstant() instanceof RootCallTarget constant && constant.getRootNode() instanceof GuestRoot)
                visit(constant, found, seen);
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
}
