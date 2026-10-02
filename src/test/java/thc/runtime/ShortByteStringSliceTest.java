// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class ShortByteStringSliceTest {
    private final File root = new File(System.getProperty("thc.projectRoot")),
                       directory = new File(root, "build/short-bytes-slices");
    private final List<String> entries = List.of("takeCase", "dropCase", "splitCase");
    private Context context() {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .build();
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().retainedReferences());
    }
    private record Row(String name, long seed, long count, long side, long selector, long expected) {}
    private List<Long> payload(long seed) {
        int length = (int) Math.abs(seed % 17);
        var result = new ArrayList<Long>();
        for (int i = 0; i < length; i++) result.add((seed + i * 73L) & 255L);
        return result;
    }
    private List<Long> value(String name, long seed, long count, long side) {
        var data = payload(seed);
        int cut = (int) Math.max(0, Math.min(count, data.size()));
        return new ArrayList<>(name.equals("takeCase") || name.equals("splitCase") && side == 0L
                ? data.subList(0, cut)
                : data.subList(cut, data.size()));
    }
    private long observe(List<Long> data, long selector) {
        if (selector == -2L) {
            long result = 0;
            for (long b : data) result = result * 33 + b;
            return result;
        }
        if (selector == -1L)
            return data.size();
        return selector >= 0 && selector < data.size() ? data.get((int) selector) : -1L;
    }
    private Map<String, Object> manifest() throws Exception {
        var manifest =
            (Map<String, Object>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath()));
        assertEquals(entries, manifest.get("entries"));
        return manifest;
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths)
            modules.add(thc.CoreCbdFixtures.read(new File(root, path).toPath()));
        return CoreModules.merge(modules);
    }
    private void check(Row row, Value function, Language language, String label) {
        assertEquals(row.expected(), function.execute(row.seed(), row.count(), row.side(), row.selector()).asLong(),
            label + "/" + row.seed() + "/" + row.count() + "/" + row.side() + "/" + row.selector());
        released(language);
    }
    @Test
    void publicSlicesMatchNativeBeforeAndAfterCompilation() throws Exception {
        var manifest = manifest();
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(directory, "oracle.tsv").toPath())) {
            var p = line.split("\t", -1);
            rows.add(new Row(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]),
                Long.parseLong(p[4]), Long.parseLong(p[5])));
        }
        var seeds = new ArrayList<Long>();
        for (var n : (List<Number>) manifest.get("seeds")) seeds.add(n.longValue());
        assertEquals(265, seeds.size());
        assertTrue(seeds.containsAll(List.of(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 255L)));
        var expected = new ArrayList<Row>();
        for (var name : entries)
            for (long seed : seeds) {
                long n = payload(seed).size();
                for (long count :
                    new TreeSet<>(List.of(Long.MIN_VALUE, -1L, 0L, 1L, n / 2, n - 1, n, n + 1, Long.MAX_VALUE)))
                    for (long side : name.equals("splitCase") ? List.of(0L, 1L) : List.of(0L)) {
                        var data = value(name, seed, count, side);
                        var selectors = new ArrayList<>(List.of(Long.MIN_VALUE, -2L, -1L));
                        for (long i = 0; i <= data.size(); i++) selectors.add(i);
                        selectors.add(Long.MAX_VALUE);
                        for (long selector : selectors)
                            expected.add(new Row(name, seed, count, side, selector, observe(data, selector)));
                    }
            }
        assertEquals(81312, rows.size());
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rows.size());
        assertEquals(
            expected, rows, "Native oracle includes every result byte, length, checksum and boundary sentinel");
        var stages = (Map<String, List<String>>) manifest.get("stages");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stageEntry : stages.entrySet()) {
            var stage = stageEntry.getKey();
            var module = merged(stageEntry.getValue());
            for (var name : entries) {
                var audit = (Map<String, Object>) Json.parse(
                    Files.readString(new File(directory, stage + "-" + name + ".audit.json").toPath()));
                assertEquals(true, audit.get("accepted"));
                assertEquals(List.of(), audit.get("issues"));
                assertEquals(List.of(), audit.get("missingGlobals"));
                var selected = new ArrayList<Row>();
                for (var row : rows)
                    if (row.name().equals(name))
                        selected.add(row);
                for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var instrumented = new LinkedHashMap<>(CoreModules.reachable(module, "main:ShortByteStringSliceAudit." + name));
                            instrumented.put("instrument", true);
                            ExecutableProgram program = backend.equals("ast")
                                ? new Program(language, instrumented)
                                : new BytecodeProgram(language, instrumented);
                            var function = context.asValue(new EntryValue(program, "main:ShortByteStringSliceAudit." + name, 4));
                            var label = stage + "/" + backend + "/" + name;
                            for (var row : selected) check(row, function, language, label);
                            long beforeInstallation = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertTrue(function.invokeMember("compile").asBoolean(), label + " installation");
                            assertEquals(beforeInstallation,
                                ((Number) program.diagnostics().get("compiledEntries")).longValue(),
                                label + " installation executes no guest work");
                            var diagnostics = (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString());
                            assertEquals(true, ((Map<?, ?>) diagnostics.get("explicitCompilation")).get("validLastTier"),
                                label + " guest entry and host bridge installed");
                            var installedRows = selected.reversed();
                            check(installedRows.getFirst(), function, language, label);
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > beforeInstallation,
                                label + " first installed call enters compiled guest code");
                            diagnostics = (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString());
                            assertEquals(true, ((Map<?, ?>) diagnostics.get("explicitCompilation")).get("validLastTier"),
                                label + " first installed call preserves the installed guest entry and host bridge");
                            for (var row : installedRows.subList(1, installedRows.size())) check(row, function, language, label);
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(),
                                    label + "/" + counter);
                        } finally {
                            context.leave();
                        }
                    }
            }
        }
    }
}
