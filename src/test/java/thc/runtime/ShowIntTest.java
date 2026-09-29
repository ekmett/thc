// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import thc.*;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class ShowIntTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final String worker = "ghc-internal:GHC.Internal.Show.$fShowCallStack_itos'";
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "initial installation"); }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
    }
    private record Row(String name, long input, long index, long expected) {}
    @Test void publicShowAndEveryCharacterWithInlining() throws Exception { nativeShow(true); }
    @Test void publicShowAndEveryCharacterAcrossResidualCalls() throws Exception { nativeShow(false); }
    private void check(Value function, int arity, Row row, String label, Language language) {
        long actual = arity == 1 ? function.execute(row.input()).asLong() : function.execute(row.input(), row.index()).asLong();
        assertEquals(row.expected(), actual, label + "/" + row.input() + "/" + row.index()); released(language);
    }
    private void nativeShow(boolean inlining) throws Exception {
        var manifest = object(Json.parse(Files.readString(root.resolve("build/show-int/manifest.json"))));
        for (var kind : list("sources", "artifacts")) for (var item : objects(manifest.get(kind))) {
            var path = (String) item.get("path"); var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(path))));
            assertEquals(item.get("sha256"), hash, "Stale Show preparation: " + path);
        }
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(root.resolve("build/show-int/oracle.tsv"))) {
            var parts = line.split("\t", -1); rows.add(new Row(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]), Long.parseLong(parts[3])));
        }
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rows.size());
        var inputs = expression(manifest.get("inputs")).stream().map(value -> ((Number) value).longValue()).toList();
        assertEquals(list("showChecksum", "showCharacter"), manifest.get("entries"));
        assertEquals(inputs, rows.stream().filter(row -> row.name().equals("showChecksum")).map(Row::input).toList());
        for (long input : inputs) {
            var characters = rows.stream().filter(row -> row.name().equals("showCharacter") && row.input() == input).toList();
            var text = Long.toString(input); var indices = new ArrayList<Long>();
            for (int index = -1; index <= text.length(); index++) indices.add((long) index);
            assertEquals(indices, characters.stream().map(Row::index).toList());
            var actualText = new StringBuilder();
            for (var row : characters) if (row.index() >= 0 && row.index() < text.length()) actualText.append((char) row.expected());
            assertEquals(text, actualText.toString()); assertEquals(-1L, characters.getFirst().expected()); assertEquals(-1L, characters.getLast().expected());
            long checksum = 5381L; for (int index = 0; index < text.length(); index++) checksum = checksum * 33 + text.charAt(index);
            var sums = rows.stream().filter(row -> row.name().equals("showChecksum") && row.input() == input).toList(); assertEquals(1, sums.size());
            assertEquals(checksum, sums.getFirst().expected());
        }
        for (var stage : object(manifest.get("stages")).entrySet()) {
            var modules = new ArrayList<Map<String, Object>>();
            for (var path : expression(stage.getValue())) modules.add(CoreCbdFixtures.read(root.resolve((String) path)));
            var module = CoreModules.merge(modules);
            for (var name : list("showChecksum", "showCharacter")) {
                var audit = object(Json.parse(Files.readString(root.resolve("build/show-int/" + stage.getKey() + "-" + name + ".audit.json"))));
                assertEquals(true, audit.get("accepted")); assertTrue(objects(audit.get("reachableBindings")).stream().anyMatch(binding -> worker.equals(binding.get("id"))));
                var selected = rows.stream().filter(row -> row.name().equals(name)).toList();
                for (var backend : list("ast", "bytecode")) try (var context = context(inlining)) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var instrumented = with(CoreModules.reachable(module, name), "instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, instrumented) : new BytecodeProgram(language, instrumented);
                        int arity = name.equals("showChecksum") ? 1 : 2; var function = context.asValue(new EntryValue(program, name, arity));
                        var host = program.hostEntryTarget(arity); var original = program.entryTarget(name); var digitWorker = program.entryTarget(worker);
                        var label = stage.getKey() + "/" + backend + "/" + name + "/inlining=" + inlining;
                        // Warm the retained lengths, signs and selector exits once; no retry/recompile loop.
                        for (var row : selected) check(function, arity, row, label, language);
                        var targets = activeTargets(host); assertTrue(targets.size() > 1, label + " adopted guest call path");
                        for (var target : targets) if (target != host) compile(target);
                        compile(digitWorker); assertTrue(function.invokeMember("compile").asBoolean());
                        var state = language.getHandoffState().get(); long resultAllocations = state.getResults().getAllocations();
                        for (int index = selected.size() - 1; index >= 0; index--) {
                            var row = selected.get(index); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            check(function, arity, row, label, language); long after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertTrue(after > before, label + "/" + row.input() + "/" + row.index() + ": actual compiled guest entry");
                            assertEquals(targets, activeTargets(host), label + " active target identity");
                            valid(original, label + " original"); valid(digitWorker, label + " actual Show worker"); for (var target : targets) valid(target, label + " active");
                        }
                        assertEquals(resultAllocations, state.getResults().getAllocations(), label + " result slabs reused");
                        for (var counter : list("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(), label + "/" + counter);
                        System.out.println("ShowInt PASS " + label + " rows=" + selected.size() + " activeTargets=" + targets.size());
                    } finally { context.leave(); }
                }
            }
        }
    }
}
