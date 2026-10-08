// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.*;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.function.LongUnaryOperator;
import org.graalvm.polyglot.Context;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Scalar/value fixture comparisons and nullable metadata builders. */
final class ScalarValueTestSupport {
    private ScalarValueTestSupport() {}
    @SafeVarargs static <T> List<T> list(T... values) { return Arrays.asList(values); }
    static Map<String, Object> map(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    static Map<String, Object> with(Map<String, Object> source, Object... pairs) {
        var result = new LinkedHashMap<>(source);
        result.putAll(map(pairs));
        return result;
    }
    static Map<String, Object> without(Map<String, Object> source, String... keys) {
        var result = new LinkedHashMap<>(source);
        for (var key : keys) result.remove(key);
        return result;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") static List<Object> expression(Object value) { return (List<Object>) value; }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> objects(Object value) { return (List<Map<String, Object>>) value; }
    @FunctionalInterface interface CheckedConsumer<T> { void accept(T value) throws Exception; }
    @FunctionalInterface interface CheckedBiConsumer<T, U> { void accept(T first, U second) throws Exception; }
    @FunctionalInterface interface CheckedRunnable { void run() throws Exception; }
    /** Compare a small native oracle in both backends, with optional first-compiled-call coverage. */
    static void nativeValues(Map<String, Object> module, String entry, Map<List<Object>, Long> cases,
                             boolean compiled, LongUnaryOperator normalize) {
        assertFalse(cases.isEmpty(), entry);
        for (boolean ast : new boolean[]{true, false}) try (var context = Context.newBuilder("thc")
                .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var source = with(CoreModules.reachable(module, entry), "instrument", true);
                ExecutableProgram program = ast ? new Program(language, source) : new BytecodeProgram(language, source);
                var function = context.asValue(new EntryValue(program, entry, cases.keySet().iterator().next().size()));
                for (int phase = 0; phase < (compiled ? 2 : 1); phase++) {
                    if (phase == 1) {
                        var before = program.diagnostics().get("compiledEntries");
                        assertTrue(function.invokeMember("compile").asBoolean(), entry);
                        assertEquals(before, program.diagnostics().get("compiledEntries"), "Compilation executes no guest work");
                    }
                    boolean first = phase == 1;
                    for (var row : cases.entrySet()) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(normalize.applyAsLong(row.getValue()),
                            normalize.applyAsLong(function.execute(row.getKey().toArray()).asBigInteger().longValue()),
                            (ast ? "ast/" : "bytecode/") + entry + row.getKey());
                        if (first) {
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before,
                                "First installed call enters compiled guest code");
                            var diagnostics = object(Json.parse(function.getMember("diagnostics").asString()));
                            assertEquals(true, object(diagnostics.get("explicitCompilation")).get("validLastTier"));
                            first = false;
                        }
                    }
                }
            } finally { context.leave(); }
        }
    }
    static List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        var targets = new ArrayList<RootCallTarget>();
        visitTarget(entry, seen, targets);
        return targets;
    }
    private static void visitTarget(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> targets) {
        if (!seen.add(target)) return;
        var root = target.getRootNode();
        var nodes = new ArrayList<Node>();
        nodes.add(root);
        if (root instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions())
            for (var argument : instruction.getArguments()) if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                var cached = argument.asCachedNode();
                if (cached != null) nodes.add(cached);
            }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget active && active.getRootNode() instanceof GuestRoot)
                visitTarget(active, seen, targets);
        targets.add(target);
    }
}
