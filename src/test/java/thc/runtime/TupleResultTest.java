// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
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
class TupleResultTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private static void compile(RootCallTarget target, String label) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        type.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target), label);
    }
    private static void valid(RootCallTarget target, String label) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target), label);
    }
    private static void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
    }
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private List<String[]> rows(String path) throws Exception {
        var rows = new ArrayList<String[]>();
        for (var line : Files.readAllLines(root.resolve(path))) rows.add(line.split("\t", -1));
        return rows;
    }
    private static List<Map<String, Object>> bindings(Map<String, Object> module) { return (List<Map<String, Object>>) module.get("bindings"); }
    private static String entryId(List<Map<String, Object>> bindings, String name) {
        Map<String, Object> found = null;
        for (var binding : bindings) if (name.equals(binding.get("name"))) {
            if (found != null) throw new IllegalArgumentException("Collection contains more than one matching element.");
            found = binding;
        }
        if (found == null) throw new NoSuchElementException("Collection contains no element matching the predicate.");
        return (String) found.get("id");
    }
    private static long count(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private static void checkRows(List<String[]> rows, ExecutableProgram program, Language language, String stage, String backend, String entry) {
        for (var row : rows) {
            if (entry != null && !row[0].equals(entry)) continue;
            var name = entry == null ? row[0] : entry;
            var result = Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue(name), new Object[]{Long.parseLong(row[1])}});
            assertEquals(Long.parseLong(row[2]), result, stage + "/" + backend + "/" + name + "/" + row[1]);
            released(language);
        }
    }
    @Test void nativeProducersForwardersAndOutstandingResultsRunWithoutInlining() throws Exception { checkNative(false); }
    @Test void inlinedNativeResultsRemainVirtual() throws Exception { checkNative(true); }
    @Test void resultOnlyNativeBoundariesWithoutInlining() throws Exception { checkReturnAudit(false); }
    @Test void resultOnlyNativeBoundariesWithInlining() throws Exception { checkReturnAudit(true); }
    private void checkNative(boolean inlining) throws Exception {
        var rows = rows("build/aggregate-native/oracle.tsv");
        for (var stage : List.of("aggregate-core", "aggregate-post-core")) {
            var module = (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/" + stage + "/AggregateFrontier.json")));
            for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var entry : List.of("tupleOutstanding", "tupleZeroLazy", "coldTuple")) {
                        var linked = CoreModules.reachable(module, entry);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                        checkRows(rows, program, language, stage, backend, entry);
                        if (entry.equals("coldTuple")) assertEquals(540820L, Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue(entry), new Object[]{31337L}}));
                        var functions = new ArrayList<Map<String, Object>>();
                        for (var binding : bindings(linked)) if (((List<?>) binding.get("expr")).get(0).equals("lam")) functions.add(binding);
                        for (var function : functions) compile(program.entryTarget((String) function.get("id")), stage + "/" + backend + "/" + entry + "/" + function.get("id"));
                        long before = count(program);
                        checkRows(rows, program, language, stage, backend, entry);
                        assertTrue(count(program) > before);
                        valid(program.entryTarget(entryId(bindings(linked), entry)), stage + "/" + backend + "/" + entry + " after compiled execution");
                        long allocations = language.getHandoffState().get().getResults().getAllocations();
                        checkRows(rows, program, language, stage, backend, entry);
                        assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations());
                    }
                } finally { context.leave(); }
            }
        }
    }
    private void checkReturnAudit(boolean inlining) throws Exception {
        var rows = rows("build/tuple-return/oracle.tsv");
        for (var stage : List.of("pre-core", "post-core")) {
            var module = (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/tuple-return/" + stage + "/TupleReturnAudit.json")));
            for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var names = new LinkedHashSet<String>();
                    for (var row : rows) names.add(row[0]);
                    var reached = new LinkedHashSet<Object>();
                    for (var name : names) for (var binding : bindings(CoreModules.reachable(module, name))) reached.add(binding.get("id"));
                    var bindings = new ArrayList<Map<String, Object>>();
                    for (var binding : bindings(module)) if (reached.contains(binding.get("id"))) bindings.add(binding);
                    var linked = new LinkedHashMap<>(module); linked.put("bindings", bindings);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                    checkRows(rows, program, language, stage, backend, null);
                    // The shared host PIC becomes generic across these thirteen entries.
                    // Warm every root through that settled ABI before compiling profiles.
                    checkRows(rows, program, language, stage, backend, null);
                    for (var binding : bindings) if (((List<?>) binding.get("expr")).get(0).equals("lam"))
                        compile(program.entryTarget((String) binding.get("id")), stage + "/" + backend + "/" + binding.get("name"));
                    long before = count(program);
                    checkRows(rows, program, language, stage, backend, null);
                    assertTrue(count(program) > before);
                    for (var name : names) valid(program.entryTarget(entryId(bindings, name)), stage + "/" + backend + "/" + name + " after compiled execution");
                    long allocations = language.getHandoffState().get().getResults().getAllocations();
                    checkRows(rows, program, language, stage, backend, null);
                    assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations());
                } finally { context.leave(); }
            }
        }
    }
}
