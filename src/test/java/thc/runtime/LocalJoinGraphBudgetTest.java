// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedOSRLoopNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Real Core lowering: an async local-join backedge and returning case side paths. */
class LocalJoinGraphBudgetTest {
    private static final Map<String,Object> LONG = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private static final Map<String,Object> CLOSURE = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private static List<Object> node(Object... fields) { return Arrays.asList(fields); }
    private static List<Object> variable(String id) { return node("var", id, Map.of("rep", LONG)); }
    private static List<Object> integer(long value) { return node("lit", "int", Long.toString(value), Map.of("rep", LONG)); }
    private static Map<String,Object> parameter(String id) { return Map.of("id", id, "name", id, "lifted", false, "rep", LONG); }
    private static List<Object> apply(List<Object> function, List<List<Object>> values) {
        return node("app", function, values, java.util.Collections.nCopies(values.size(), false), false, false, Map.of("rep", LONG));
    }
    private static List<Object> primitive(String name, List<Object> a, List<Object> b) {
        return apply(node("prim", name), List.of(a, b));
    }
    private static List<Object> lambda(List<String> names, List<Object> body) {
        var args = new ArrayList<Map<String,Object>>();
        for (String name : names) args.add(parameter(name));
        return node("lam", args, body, Map.of("rep", CLOSURE, "resultRep", LONG));
    }
    private static List<Object> choice(String binder, List<Object> value, List<List<Object>> arms) {
        return node("case", value, binder, arms, Map.of("rep", LONG, "binder", parameter(binder)));
    }
    private static List<Object> arithmetic(int depth, long index) {
        if (depth == 0) return primitive("xorI#", variable("key"), integer(index));
        return primitive("+#", arithmetic(depth - 1, index * 2), arithmetic(depth - 1, index * 2 + 1));
    }
    static Map<String,Object> module(int depth) {
        List<Object> work = integer(0);
        for (int arm = 0; arm < 8; arm++) work = primitive("+#", work,
                choice("selected" + arm, variable("key"), List.of(
                        node("default", null, List.of(), arithmetic(depth, arm + 1)))));
        var recurse = apply(node("var", "loop", Map.of("rep", CLOSURE)),
                List.of(primitive("-#", variable("remaining"), integer(1)), work));
        var loopBody = choice("done", variable("remaining"), List.of(
                node("lit", node("int", "0"), List.of(), variable("value")), node("default", null, List.of(), recurse)));
        var join = new LinkedHashMap<String,Object>();
        join.put("id", "loop"); join.put("name", "loop"); join.put("lifted", true); join.put("rep", CLOSURE);
        join.put("expr", lambda(List.of("remaining", "value"), loopBody));
        join.put("joinValueArity", 2); join.put("joinResultRep", LONG);
        var entry = apply(node("var", "loop", Map.of("rep", CLOSURE)), List.of(variable("count"), integer(10)));
        var region = node("let", true, List.of(join), entry, Map.of("rep", LONG));
        var body = primitive("+#", region, integer(17));
        return Map.of("instrument", true,
                "sourceFiles", List.of(Map.of("id", "control", "path", "LocalJoinGraphBudget.hs")),
                "sourceSpans", List.of(Map.of("id", "body", "file", "control", "startLine", 1,
                        "startColumn", 1, "endLine", 1, "endColumn", 2)),
                "bindings", List.of(Map.of("id", "entry", "name", "entry", "source", "body", "lifted", true,
                "rep", CLOSURE, "expr", lambda(List.of("count", "key"), body))));
    }
    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.OSRCompilationThreshold", "1024")
                .option("engine.CompilationFailureAction", "Throw")
                .option("compiler.MaximumGraalGraphSize", "10000").option("compiler.CompilationTimeout", "30").build();
    }
    @Test void asyncLocalJoinRetainsItsBackedgeAndReturnsAfterRealBudgetRecovery() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, module(4), true);
                var target = (OptimizedCallTarget) program.entryTarget("entry");
                assertEquals(0L, ((Number) program.diagnostics().get("localJoinTransfers")).longValue());
                // One real call crosses OSR; there is no training or replayed guest prefix.
                // key=0 sums integers [16..143] in every iteration, then adds 17 once.
                Object result;
                try { result = Calls.target(target, new Object[]{0L, 1500L, 0L}); }
                catch (Throwable failure) {
                    var field = AstSameFrameArm.class.getDeclaredField("targets"); field.setAccessible(true);
                    var failed = OptimizedCallTarget.class.getDeclaredField("compilationFailed"); failed.setAccessible(true);
                    var arms = NodeUtil.findAllNodeInstances(target.getRootNode(), AstSameFrameArm.class);
                    var states = new ArrayList<String>();
                    for (var arm : arms) {
                        var sides = (com.oracle.truffle.api.RootCallTarget[]) field.get(arm);
                        if (sides == null) states.add("inline");
                        else for (int mode = 0; mode < sides.length; mode++) {
                            var side = (OptimizedCallTarget) sides[mode];
                            if (failed.getBoolean(side)) states.add("failed-side-" + mode);
                        }
                    }
                    throw new AssertionError("real OSR failed at generation " + ((FunctionRoot) target.getRootNode()).getGraphBudgetGeneration() +
                            ", slots=" + target.getRootNode().getFrameDescriptor().getNumberOfSlots() +
                            ", arms=" + arms.size() + ", states=" + states, failure);
                }
                assertEquals(10193L, result);
                assertTrue(((FunctionRoot) target.getRootNode()).getGraphBudgetGeneration() > 0, "actual structural budget recovery");
                assertEquals(1501L, ((Number) program.diagnostics().get("localJoinTransfers")).longValue(), "no replayed join transfer");
                assertSame(target, program.entryTarget("entry"));
                var loops = NodeUtil.findAllNodeInstances(target.getRootNode(), OptimizedOSRLoopNode.class);
                assertTrue(loops.stream().anyMatch(loop -> loop.getCompiledOSRLoop() != null), "real local-join OSR target");
            } finally { context.leave(); }
        }
    }

    @Test void exhaustedLeafDoesNotRetryWithoutAnUnusedBoundary() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, module(7), true);
                var target = program.entryTarget("entry");
                // Separate this leaf without running or training the guest. An oversized
                // body with no remaining safe boundary must still report its real failure.
                var arm = NodeUtil.findAllNodeInstances(target.getRootNode(), AstSameFrameArm.class).stream()
                        .filter(candidate -> candidate.getParent() instanceof Alternative).findFirst().orElseThrow();
                assertTrue(AstSameFrameArm.extract(arm));
                var field = AstSameFrameArm.class.getDeclaredField("targets"); field.setAccessible(true);
                var sides = (com.oracle.truffle.api.RootCallTarget[]) field.get(arm);
                com.oracle.truffle.api.RootCallTarget rawSide = sides[2];
                var side = (OptimizedCallTarget) rawSide;
                var sideRoot = (AstSameFrameArm.ArmRoot) side.getRootNode();
                long generation = sideRoot.getGraphBudgetGeneration();
                var failure = assertThrows(com.oracle.truffle.api.OptimizationFailedException.class,
                        () -> ((OptimizedCallTarget) rawSide).compile(true));
                assertTrue(failure.toString().contains("GraphTooBigBailoutException"));
                assertEquals(generation, sideRoot.getGraphBudgetGeneration());
                assertEquals(generation, sideRoot.prepareGraphBudgetRetry(generation),
                        "no unbounded retry when no unused boundary remains");
                assertFalse(side.isValid());
                assertEquals(0L, ((Number) program.diagnostics().get("localJoinTransfers")).longValue());
            } finally { context.leave(); }
        }
    }
}
