// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.CoreCbdFixtures;
import thc.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class SumJoinResultTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> module(String stage) throws Exception {
        return CoreCbdFixtures.read(root.resolve("build/sum-join/" + stage + "/core/SumJoinAudit.cbd"));
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private static void compile(RootCallTarget target) throws Exception {
        var optimized = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        optimized.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, optimized.getMethod("isValidLastTier").invoke(target));
    }
    private static void released(Language language) {
        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
        assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
    }
    private static List<Map<String, Object>> bindings(Map<String, Object> module) { return (List<Map<String, Object>>) module.get("bindings"); }
    private static List<Map<String, Object>> walk(Object value) {
        var result = new ArrayList<Map<String, Object>>();
        if (value instanceof Map<?, ?> map) {
            result.add((Map<String, Object>) map);
            for (var child : map.values()) result.addAll(walk(child));
        } else if (value instanceof List<?> list) for (var child : list) result.addAll(walk(child));
        return result;
    }
    private static long count(ExecutableProgram program, String name) { return ((Number) program.diagnostics().get(name)).longValue(); }
    @Test void originalSumJoinsMatchNativeWithoutInlining() throws Exception { checkNative(false); }
    @Test void originalSumJoinsMatchNativeWithInlining() throws Exception { checkNative(true); }
    private void checkRows(List<String[]> rows, ExecutableProgram program, Language language, String stage, String backend, boolean inlining, boolean compiled) {
        for (var row : rows) {
            var name = row[0]; long x = Long.parseLong(row[1]);
            long model = switch (name) {
                case "forwardCase" -> x <= 0 ? -11L : (x + 18L) * 3L;
                case "recursiveCase" -> x == 0L ? -13L : (x < 0 ? -x : 2L * x) + 19L;
                case "nestedCase" -> x <= 0 ? -23L : (x + 7L) * 5L;
                case "stateForwardCase" -> x <= 0 ? -31L : (x + 30L) * 7L;
                case "stateRecursiveCase" -> x == 0L ? -37L : (x < 0 ? -x : 2L * x) + 41L;
                case "tupleForwardCase" -> x <= 0 ? 5L * x - 14L : 6L * x + 75L;
                case "sumForwardCase" -> x <= 0 ? -43L : (x + 20L) * 11L;
                default -> throw new IllegalStateException(name);
            };
            assertEquals(model, Long.parseLong(row[2]), "Independent native model: " + name + "/" + x);
            long before = count(program, "compiledEntries");
            assertEquals(model, Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("main:SumJoinAudit." + name), new Object[]{x}}), stage + "/" + backend + "/" + name + "/" + x);
            if (compiled) assertTrue(count(program, "compiledEntries") > before,
                "First installed compiled call: " + stage + "/" + backend + "/" + name + "/" + x + "/inlining=" + inlining);
            released(language);
        }
    }
    private void checkNative(boolean inlining) throws Exception {
        var rows = new ArrayList<String[]>();
        for (var line : Files.readAllLines(root.resolve("build/sum-join/oracle.tsv"))) rows.add(line.split("\t", -1));
        assertEquals(42, rows.size());
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = module(stage);
                var names = new LinkedHashSet<String>();
                for (var row : rows) names.add(row[0]);
                var ids = new LinkedHashSet<Object>();
                for (var name : names) for (var binding : bindings(CoreModules.reachable(module, "main:SumJoinAudit." + name))) ids.add(binding.get("id"));
                var selected = new ArrayList<Map<String, Object>>();
                for (var binding : bindings(module)) if (ids.contains(binding.get("id"))) selected.add(binding);
                var selectedModule = new LinkedHashMap<>(module); selectedModule.put("bindings", selected);
                var program = program(language, selectedModule, backend);
                checkRows(rows, program, language, stage, backend, inlining, false);
                for (var binding : selected) if (((List<?>) binding.get("expr")).get(0).equals("lam")) compile(program.entryTarget((String) binding.get("id")));
                checkRows(rows, program, language, stage, backend, inlining, true);
                assertTrue(count(program, "localJoinTransfers") > 40_000);
                assertEquals(0L, count(program, "unsupportedTraps")); assertEquals(0L, count(program, "blackholes"));
            } finally { context.leave(); }
        }
    }
    private static Map<String, Object> forward(Map<String, Object> module) {
        Map<String, Object> result = null;
        for (var binding : bindings(module)) if ("main:SumJoinAudit.forward".equals(binding.get("id"))) {
            if (result != null) throw new IllegalArgumentException("Collection contains more than one matching element.");
            result = binding;
        }
        if (result == null) throw new NoSuchElementException("Collection contains no element matching the predicate.");
        return result;
    }
    @Test void zeroAritySumJoinRetainsLazyIdentityAndClearsInactiveReferences() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = module("pre"); var source = forward(module);
                var lambda = new ArrayList<>((List<Object>) source.get("expr"));
                source.put("expr", lambda);
                var proof = (Map<String, Object>) ((Map<String, Object>) lambda.get(3)).get("resultRep");
                var zero = Map.of("id", "zero-sum", "name", "zeroSum", "lifted", false, "rep", proof,
                    "joinValueArity", 0, "joinResultRep", proof, "info", Map.of("joinArity", 0), "expr", lambda.get(2));
                lambda.set(2, List.of("let", false, List.of(zero), List.of("var", "zero-sum", Map.of("rep", proof)), Map.of("rep", proof)));
                var program = program(language, CoreModules.reachable(module, "main:SumJoinAudit.forwardCase"), backend);
                var target = program.entryTarget((String) source.get("id"));
                var shape = new TupleShape(CoreRepresentations.parse(proof), language);
                var layout = new FrameLayout();
                var slots = new int[shape.getWidth()];
                for (int i = 0; i < slots.length; i++) slots[i] = layout.bind("sum result " + i);
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
                class Invoke {
                    Object call(long x) throws Exception {
                        shape.consume(frame, Calls.target(target, new Object[]{0L, x}), slots, 0);
                        assertEquals(x <= 0 ? 1L : 2L, frame.getLong(slots[0]));
                        var ref = frame.getObject(slots[1]);
                        if (x <= 0) assertTrue(ref instanceof Thunk);
                        else { assertNull(ref); assertEquals(x + 18L, frame.getLong(slots[2])); }
                        released(language); return ref;
                    }
                }
                var invoke = new Invoke();
                var lazy = invoke.call(-1L); invoke.call(1L); compile(target);
                for (long x : List.of(-41L, 41L, -1L, 0L, 9L)) {
                    var value = invoke.call(x); if (x <= 0) assertSame(lazy, value);
                }
                assertEquals(0L, count(program, "blackholes"));
            } finally { context.leave(); }
        }
    }
    @Test void mismatchedJoinProjectionAndLambdaResultFailAtLoad() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean lambdaProof : List.of(false, true)) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = module("pre"); var source = forward(module);
                Map<String, Object> join = null;
                for (var item : walk(source)) if (item.containsKey("joinValueArity")) { join = item; break; }
                if (join == null) throw new NoSuchElementException("Collection contains no element matching the predicate.");
                var proof = (Map<String, Object>) (lambdaProof ? ((Map<String, Object>) ((List<?>) join.get("expr")).get(3)).get("resultRep") : join.get("joinResultRep"));
                proof.put("alternativeSlots", List.of(List.of(2L), List.of(1L)));
                assertThrows(RuntimeFault.class, () -> program(language, CoreModules.reachable(module, "main:SumJoinAudit.forwardCase"), backend));
            } finally { context.leave(); }
        }
    }
}
