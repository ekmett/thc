// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import thc.*;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class ShowWordListTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> entries = list("wordChecksum", "wordCharacter", "listChecksum", "listCharacter");
    private Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "initial installation"); }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
    }
    private record Row(String name, long input, long shape, long index, long expected) {}
    private String text(String name, long input, long shape) {
        if (name.startsWith("word")) return Long.toUnsignedString(input);
        List<Long> values;
        if (shape == 0) values = list();
        else if (shape == 1) values = list(input);
        else if (shape == 3) values = list(input, input + 1, -input);
        else throw new IllegalStateException("Unexpected list shape: " + shape);
        var joined = new StringJoiner(",", "[", "]"); for (long value : values) joined.add(Long.toString(value)); return joined.toString();
    }
    private void check(Value function, String name, Row row, String label, Language language) {
        long actual = switch (name) {
            case "wordChecksum" -> function.execute(row.input()).asLong();
            case "wordCharacter" -> function.execute(row.input(), row.index()).asLong();
            case "listChecksum" -> function.execute(row.input(), row.shape()).asLong();
            default -> function.execute(row.input(), row.shape(), row.index()).asLong();
        };
        assertEquals(row.expected(), actual, label + "/" + row.input() + "/" + row.shape() + "/" + row.index()); released(language);
    }
    @Test void publicWordAndListCharactersMatchNative() throws Exception {
        var manifest = object(Json.parse(Files.readString(root.resolve("build/show-word-list/manifest.json"))));
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(root.resolve("build/show-word-list/oracle.tsv"))) {
            var parts = line.split("\t", -1); rows.add(new Row(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]), Long.parseLong(parts[3]), Long.parseLong(parts[4])));
        }
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rows.size());
        var inputs = expression(manifest.get("inputs")).stream().map(value -> ((Number) value).longValue()).toList();
        var shapes = expression(manifest.get("listShapes")).stream().map(value -> ((Number) value).longValue()).toList();
        assertEquals(entries, manifest.get("entries")); assertEquals(list(0L, 1L, 3L), shapes);
        assertEquals(inputs.size(), new HashSet<>(inputs).size()); assertTrue(inputs.containsAll(list(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L)));
        for (long input = -20; input <= 20; input++) assertTrue(inputs.contains(input), "Missing signed small value: " + input);
        var expectedRows = new ArrayList<Row>();
        for (var name : entries) for (long input : inputs) for (long shape : name.startsWith("word") ? list(0L) : shapes) {
            var formatted = text(name, input, shape);
            if (name.endsWith("Character")) for (int index = -1; index <= formatted.length(); index++)
                expectedRows.add(new Row(name, input, shape, index, index >= 0 && index < formatted.length() ? formatted.charAt(index) : -1L));
            else {
                long checksum = 5381L; for (int index = 0; index < formatted.length(); index++) checksum = checksum * 33 + formatted.charAt(index);
                expectedRows.add(new Row(name, input, shape, 0, checksum));
            }
        }
        assertEquals(expectedRows, rows, "Every unsigned/list character, checksum, and end sentinel matches independently");
        for (var stage : object(manifest.get("stages")).entrySet()) {
            var modules = new ArrayList<Map<String, Object>>();
            for (var path : expression(stage.getValue())) modules.add(thc.CoreCbdFixtures.read(root.resolve((String) path)));
            var module = CoreModules.merge(modules);
            for (var name : entries) {
                var audit = object(Json.parse(Files.readString(root.resolve("build/show-word-list/" + stage.getKey() + "-" + name + ".audit.json"))));
                assertEquals(true, audit.get("accepted"));
                var selected = rows.stream().filter(row -> row.name().equals(name)).toList();
                for (var backend : list("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var instrumented = with(CoreModules.reachable(module, "main:ShowWordListAudit." + name), "instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, instrumented) : new BytecodeProgram(language, instrumented);
                        int arity = switch (name) { case "wordChecksum" -> 1; case "listCharacter" -> 3; default -> 2; };
                        var function = context.asValue(new EntryValue(program, "main:ShowWordListAudit." + name, arity)); var host = program.hostEntryTarget(arity);
                        var label = stage.getKey() + "/" + backend + "/" + name;
                        // Warm every retained row once; no extra settling, retries, or threshold changes.
                        for (var row : selected) check(function, name, row, label, language);
                        for (var target : activeTargets(host)) if (target != host) compile(target);
                        assertTrue(function.invokeMember("compile").asBoolean()); valid(host, label + " initial host installation");
                        for (int index = selected.size() - 1; index >= 0; index--) {
                            var row = selected.get(index); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            check(function, name, row, label, language); long after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertTrue(after > before, label + "/" + row.input() + "/" + row.shape() + "/" + row.index() + ": actual compiled guest entry");
                            valid(host, label + " installed host after call");
                        }
                        for (var counter : list("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(), label + "/" + counter);
                        System.out.println("ShowWordList PASS " + label + " rows=" + selected.size());
                    } finally { context.leave(); }
                }
            }
        }
    }
}
