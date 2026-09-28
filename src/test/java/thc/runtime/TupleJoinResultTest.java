// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class TupleJoinResultTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> module(String stage) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/tuple-join/" + stage + "-core/TupleJoinAudit.json")));
    }
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private static void valid(RootCallTarget target) throws Exception {
        assertEquals(true, Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target));
    }
    private static void compile(RootCallTarget target) throws Exception {
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("compile", boolean.class).invoke(target, true); valid(target);
    }
    private static ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private static void released(Language language) {
        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
        assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
    }
    private static List<Map<String, Object>> bindings(Map<String, Object> module) { return (List<Map<String, Object>>) module.get("bindings"); }
    private static Map<String, Object> named(List<Map<String, Object>> bindings, String name) {
        Map<String, Object> found = null;
        for (var binding : bindings) if (name.equals(binding.get("name"))) {
            if (found != null) throw new IllegalArgumentException("Collection contains more than one matching element.");
            found = binding;
        }
        if (found == null) throw new NoSuchElementException("Collection contains no element matching the predicate.");
        return found;
    }
    private static Map<String, Object> plus(Map<String, Object> source, String key, Object value) {
        var result = new LinkedHashMap<>(source); result.put(key, value); return result;
    }
    private static long count(ExecutableProgram program, String key) { return ((Number) program.diagnostics().get(key)).longValue(); }
    @Test void genuineTupleJoinsRunWithResidualCalls() throws Exception { checkNative(false); }
    @Test void genuineTupleJoinsRunWithInlining() throws Exception { checkNative(true); }
    private static void checkRows(List<String[]> rows, ExecutableProgram program, Language language, String stage, String backend) {
        for (var row : rows) {
            var result = Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue(row[0]), new Object[]{Long.parseLong(row[1])}});
            assertEquals(Long.parseLong(row[2]), result, stage + "/" + backend + "/" + row[0] + "/" + row[1]); released(language);
        }
    }
    private void checkNative(boolean inlining) throws Exception {
        var rows = new ArrayList<String[]>();
        for (var line : Files.readAllLines(root.resolve("build/tuple-join/oracle.tsv"))) rows.add(line.split("\t", -1));
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = module(stage);
                var names = new LinkedHashSet<String>();
                for (var row : rows) names.add(row[0]);
                var reached = new LinkedHashSet<Object>();
                for (var name : names) for (var binding : bindings(CoreModules.reachable(module, name))) reached.add(binding.get("id"));
                var bindings = new ArrayList<Map<String, Object>>();
                for (var binding : bindings(module)) if (reached.contains(binding.get("id"))) bindings.add(binding);
                var program = program(language, plus(module, "bindings", bindings), backend);
                checkRows(rows, program, language, stage, backend); checkRows(rows, program, language, stage, backend);
                for (var binding : bindings) if (((List<?>) binding.get("expr")).get(0).equals("lam")) compile(program.entryTarget((String) binding.get("id")));
                checkRows(rows, program, language, stage, backend);
                for (var name : names) valid(program.entryTarget((String) named(bindings, name).get("id")));
                assertTrue(count(program, "localJoinTransfers") > 100_000);
                assertEquals(0L, count(program, "tailBounces")); assertEquals(0L, count(program, "unsupportedTraps"));
            } finally { context.leave(); }
        }
    }
    private static List<Map<String, Object>> walk(Object value) {
        var result = new ArrayList<Map<String, Object>>();
        if (value instanceof Map<?, ?> map) {
            result.add((Map<String, Object>) map);
            for (var child : map.values()) result.addAll(walk(child));
        } else if (value instanceof List<?> list) for (var child : list) result.addAll(walk(child));
        return result;
    }
    private static Map<String, Object> firstJoin(Object source) {
        for (var item : walk(source)) if (item.containsKey("joinValueArity")) return item;
        throw new NoSuchElementException("Collection contains no element matching the predicate.");
    }
    private static Map<String, Object> wrap(Map<String, Object> proof) {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", "unknown"); result.put("evaluated", true); result.put("aggregate", "unboxed-tuple");
        result.put("primReps", proof.get("primReps")); result.put("components", List.of(proof)); return result;
    }
    @Test void astTupleJoinScratchReferencesAreClearedAfterCopying() throws Exception {
        try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var leaf = new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
                var proof = new CoreRepresentation(CoreKind.UNKNOWN, true, true, leaf.getPrimReps(), List.of(leaf), null, null, null, null);
                var shape = new TupleShape(proof, language);
                var layout = new FrameLayout(); int source = layout.bind("private tuple result"), destination = layout.bind("caller result");
                int selector = layout.bind("selector"), unused = layout.bind("scalar result");
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
                var marker = new Object(); int[] slots = {source};
                var value = new Expr() { @Override public Object execute(VirtualFrame frame) { return marker; } };
                var region = new LocalJoinRegion(new Object(), selector, unused, new Expr[]{new TupleConstruct(shape, new Expr[]{value})}, proof, false, shape, slots);
                region.executeTuple(frame, new int[]{destination}, 0);
                assertSame(marker, frame.getObject(destination));
                assertFalse(frame.isObject(source), "Private reference result slot must be cleared");
                region.executeTuple(frame, slots, 0);
                assertSame(marker, frame.getObject(source), "An explicit aliased destination must survive cleanup");
            } finally { context.leave(); }
        }
    }
    @Test void tupleJoinProofsAndUnusedAggregateFormalsAreCheckedAtLoad() throws Exception {
        List<Consumer<Map<String, Object>>> mutations = List.of(
            join -> { var p = (Map<String, Object>) join.get("joinResultRep"); var c = (List<Object>) p.get("components"); c.set(0, wrap((Map<String, Object>) c.get(0))); },
            join -> { var rhs = (List<?>) join.get("expr"); var p = (Map<String, Object>) ((Map<String, Object>) rhs.get(3)).get("resultRep"); var c = (List<Object>) p.get("components"); c.set(0, wrap((Map<String, Object>) c.get(0))); },
            join -> { var p = ((List<Map<String, Object>>) ((List<?>) join.get("expr")).get(1)).get(0); p.put("rep", wrap((Map<String, Object>) p.get("rep"))); },
            join -> ((Map<String, Object>) join.get("joinResultRep")).put("components", null));
        for (var backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (int index = 0; index < mutations.size(); index++) {
                    var module = module("pre"); var forward = named(bindings(module), "forward");
                    mutations.get(index).accept(firstJoin(forward));
                    assertThrows(RuntimeFault.class, () -> program(language, CoreModules.reachable(module, "forwardCase"), backend), backend + " mutation " + index);
                }
            } finally { context.leave(); }
        }
    }
    @Test void recursiveJoinShadowsAnOuterTupleAndZeroArityJoinReadsItsFields() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean capture : List.of(false, true)) {
                    var module = module("pre"); var bindings = bindings(module); var producer = named(bindings, "recursive");
                    var rhs = (List<Object>) producer.get("expr"); var original = rhs.get(2);
                    var result = (Map<String, Object>) ((Map<String, Object>) rhs.get(3)).get("resultRep");
                    var join = firstJoin(original); var tupleId = (String) join.get("id");
                    var x = ((List<Map<String, Object>>) rhs.get(1)).getLast(); var forward = named(bindings, "forward");
                    var scrutinee = List.of("app", List.of("var", forward.get("id"), Map.of("rep", forward.get("rep"))),
                        List.of(List.of("var", x.get("id"), Map.of("rep", x.get("rep")))), List.of(false), false, false, Map.of("rep", result));
                    Object body = original;
                    if (capture) {
                        var zero = Map.of("id", "zero", "name", "zero", "lifted", false, "rep", result,
                            "joinValueArity", 0, "joinResultRep", result, "info", Map.of("joinArity", 0), "expr", List.of("var", tupleId, Map.of("rep", result)));
                        body = List.of("let", false, List.of(zero), List.of("var", "zero", Map.of("rep", result)), Map.of("rep", result));
                    }
                    rhs.set(2, List.of("case", scrutinee, tupleId, List.of(Arrays.asList("default", null, List.of(), body, Map.of("binders", List.of()))),
                        Map.of("rep", result, "binder", Map.of("id", tupleId, "lifted", false, "rep", plus(result, "evaluated", true)))));
                    var linked = CoreModules.reachable(module, "recursiveCase"); var program = program(language, linked, backend);
                    assertEquals(capture ? 4168L : 4123L, Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("recursiveCase"), new Object[]{4097L}}));
                    released(language);
                }
            } finally { context.leave(); }
        }
    }
}
