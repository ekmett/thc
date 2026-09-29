// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Small native-oracle baseline for the two execution backends. */
class FastSmokeTest {
    private final Path project = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> modules = List.of("THC.Prim.Test", "Fixtures").stream()
        .map(name -> project.resolve("build/core/" + name + ".cbd").toString()).toList();
    private record Row(String entry, long input, long expected) {}
    private Map<String, List<Row>> nativeRows() throws Exception {
        Map<String, List<Row>> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(project.resolve("build/native/oracle.tsv"))) {
            if (line.isBlank()) continue;
            var fields = line.split("\t", -1);
            if (fields.length != 3) throw new IllegalArgumentException("Malformed native oracle row: " + line);
            var row = new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2]));
            if (row.entry.equals("under") || row.entry.equals("sumLoop"))
                result.computeIfAbsent(row.entry, ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }
    private Map<?, ?> diagnostics(Value value) { return (Map<?, ?>) Json.parse(value.getMember("diagnostics").asString()); }
    private long count(Value value, String key) { return ((Number) diagnostics(value).get(key)).longValue(); }
    @Test void nativePartialApplicationAndTailLoopRunThroughInstalledCode() throws Exception {
        var rows = nativeRows();
        var sizes = new LinkedHashMap<String, Integer>();
        rows.forEach((key, value) -> sizes.put(key, value.size()));
        assertEquals(Map.of("under", 7, "sumLoop", 7), sizes, "The native GHC oracle must cover both smoke entries");
        for (String entry : List.of("under", "sumLoop")) {
            Set<Long> inputs = new HashSet<>();
            for (Row row : rows.get(entry)) inputs.add(row.input);
            assertEquals(Set.of(-3L, 0L, 1L, 2L, 7L, 10L, 20L), inputs, entry + " native oracle inputs");
        }
        for (String backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            for (String entry : List.of("under", "sumLoop")) {
                var function = Main.loadEntry(context, modules, "main:Fixtures." + entry, true, backend);
                assertEquals(backend, diagnostics(function).get("backend"));
                for (Row row : rows.get(entry)) assertEquals(row.expected, function.execute(row.input).asLong(),
                    backend + "/" + entry + "(" + row.input + ") interpreted");
                assertTrue(function.invokeMember("compile").asBoolean(), backend + "/" + entry + " compilation");
                long before = count(function, "compiledEntries");
                for (Row row : rows.get(entry)) assertEquals(row.expected, function.execute(row.input).asLong(),
                    backend + "/" + entry + "(" + row.input + ") compiled");
                assertTrue(count(function, "compiledEntries") > before, backend + "/" + entry + " must execute installed guest code");
                assertEquals(0L, count(function, "unsupportedTraps"), backend + "/" + entry);
            }
        }
    }
}
