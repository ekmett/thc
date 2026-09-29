// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** Differential check of an ordinary containers program against native GHC. */
public final class MapCheck {
    private record Row(long input, long expected) {}

    private static boolean blank(String line) {
        return line.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }
    private static long count(Value function, String key) {
        var diagnostics = (Map<?, ?>) Json.INSTANCE.parse(function.getMember("diagnostics").asString());
        return ((Number) diagnostics.get(key)).longValue();
    }
    private static void checkRows(Value function, List<Row> rows, String phase) {
        for (var row : rows) {
            long actual = function.execute(row.input()).asLong();
            if (actual != row.expected()) throw new IllegalStateException(
                phase + " mapAggregate(" + row.input() + "): " + actual + " != native " + row.expected());
            System.out.println("VERIFIED_MAP\t" + phase + "\t" + row.input() + "\t" + actual);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: map-check MODULES_FILE ORACLE_TSV");
        var modules = Files.readAllLines(Path.of(args[0])).stream().filter(line -> !blank(line)).toList();
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            if (blank(line)) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 3 || !fields[0].equals("mapAggregate")) throw new IllegalArgumentException("Failed requirement.");
            rows.add(new Row(Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Failed requirement.");
        try (Context context = Main.executionContext(false)) {
            var function = Main.loadEntry(context, modules, "main:MapWorkload.mapAggregate");
            checkRows(function, rows, "before-requested-compilation");
            for (int i = 0; i < 40; i++) function.execute(256L + (i & 15)).asLong();
            if (!function.invokeMember("compile").asBoolean()) throw new IllegalStateException("Check failed.");
            long before = count(function, "compiledEntries");
            checkRows(function, rows, "after-requested-compilation");
            if (count(function, "compiledEntries") <= before) throw new IllegalStateException("No installed guest code was executed");
            if (count(function, "unsupportedTraps") != 0) throw new IllegalStateException("An unsupported path was entered");
            System.out.println("MAP_DIAGNOSTICS " + function.getMember("diagnostics").asString());
        }
    }
}
