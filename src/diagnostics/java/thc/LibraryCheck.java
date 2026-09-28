// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/** Real containers/primitive oracle checks. Unsupported frontiers are never execution passes. */
@SuppressWarnings("unchecked")
public final class LibraryCheck {
    private record Row(long input, long expected) {}
    private static void require(boolean condition) { require(condition, "Failed requirement."); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void check(boolean condition) { check(condition, "Check failed."); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static String message(Throwable failure) { return failure.getMessage() == null ? "" : failure.getMessage(); }
    private static Map<String, Object> diagnostics(Value function) {
        return (Map<String, Object>) Json.INSTANCE.parse(function.getMember("diagnostics").asString());
    }
    private static long count(Value function, String name) { return ((Number) diagnostics(function).get(name)).longValue(); }
    private static List<Row> rows(Map<String, Object> entry, String key) {
        List<Row> result = new ArrayList<>();
        for (var row : (List<List<Number>>) entry.get(key)) {
            require(row.size() == 2);
            result.add(new Row(row.get(0).longValue(), row.get(1).longValue()));
        }
        return result;
    }
    private record EntryCheck(List<String> modules, String name, String backend, boolean diagnostic) {
        private Source request(boolean mode) {
            // Each fresh context owns its request; a cached Source could keep
            // the complete Core transport alive after context close.
            return Source.newBuilder("thc", CoreModules.request(modules, name, true, mode, backend, true), "library:" + name).cached(false).buildLiteral();
        }
        private void checkPolicy(Value function) {
            var data = diagnostics(function);
            check(backend.equals(data.get("backend")));
            check((diagnostic ? "diagnostic-traps" : "reject-at-load").equals(data.get("unsupportedPolicy")));
            var deferred = (List<?>) data.get("deferredUnsupported");
            check(!deferred.isEmpty() == diagnostic, "Unexpected runtime frontier for " + name + ": " + deferred);
            check(count(function, "unsupportedTraps") == 0, "Unsupported trap reached by " + name);
            check(count(function, "blackholes") == 0, "Unexpected blackhole in " + name);
        }
        private void checkRows(Value function, List<Row> rows, String phase, boolean compiled) {
            for (var row : rows) {
                long before = count(function, "compiledEntries");
                long actual = function.execute(row.input()).asLong();
                check(actual == row.expected(), backend + " " + phase + " " + name + "(" + row.input() + "): " + actual + " != native " + row.expected());
                if (compiled) check(count(function, "compiledEntries") > before,
                    backend + " " + phase + " " + name + "(" + row.input() + ") did not enter installed guest code");
                checkPolicy(function);
                System.out.println("VERIFIED_LIBRARY\t" + backend + "\t" + phase + "\t" + name + "\t" + row.input() + "\t" + actual);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2 && Set.of("ast", "bytecode").contains(args[1]), "Usage: library-check CASES_JSON ast|bytecode");
        String backend = args[1];
        var cases = (Map<String, Object>) Json.INSTANCE.parse(Files.readString(Path.of(args[0])));
        require(((Number) cases.get("schema")).intValue() == 1);
        for (String kind : List.of("inputHashes", "artifactHashes")) {
            var hashes = (Map<String, String>) cases.get(kind);
            require(!hashes.isEmpty());
            for (var entry : hashes.entrySet()) {
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(entry.getKey()))));
                check(actual.equals(entry.getValue()), "Stale library input/artifact: " + entry.getKey() + "; run bin/prepare-library-tests.py");
            }
        }
        var groups = (List<Map<String, Object>>) cases.get("groups");
        var expectedEntries = Map.of(
            "set", Set.of("setAggregate"), "intmap", Set.of("intMapAggregate"),
            "intmap-primops", Set.of("countLeadingZeros", "unsignedLessThanZero", "unsignedLessThanMaxSigned", "unsignedLessThanSignBit", "unsignedLessThanAllOnes"),
            "intset", Set.of("intSetAggregate"),
            "intset-primops", Set.of("populationCount", "countTrailingZeros", "unsignedLessEqualZero", "unsignedLessEqualMaxSigned", "unsignedLessEqualSignBit", "unsignedLessEqualAllOnes"),
            "sequence", Set.of("sequenceBuild", "sequenceEnds", "sequenceAppend", "sequenceSplit", "sequenceIndexUpdate", "sequenceAggregate", "sequenceLazyPayloads"));
        var supportedSequence = Set.of("sequenceBuild", "sequenceEnds", "sequenceAppend", "sequenceLazyPayloads");
        require(groups.size() == expectedEntries.size() && new HashSet<>(groups.stream().map(group -> group.get("id")).toList()).equals(expectedEntries.keySet()));
        List<String> manifestRows = new ArrayList<>();
        for (var group : groups) {
            var entries = (List<Map<String, Object>>) group.get("entries");
            var names = entries.stream().map(entry -> (String) entry.get("name")).toList();
            require(names.size() == new HashSet<>(names).size() && new HashSet<>(names).equals(expectedEntries.get(group.get("id"))));
            for (var entry : entries) for (String phase : List.of("warm", "cold")) for (var row : rows(entry, phase))
                manifestRows.add(entry.get("name") + "\t" + row.input() + "\t" + row.expected());
        }
        Path oracle = Path.of(args[0]).toAbsolutePath().getParent().resolve("oracle.tsv");
        require(((Map<String, String>) cases.get("artifactHashes")).containsKey(oracle.toString()));
        check(manifestRows.equals(Files.readAllLines(oracle)), "Library manifest rows disagree with the fingerprinted native oracle");
        for (var group : groups) {
            var modules = (List<String>) group.get("modules");
            require(!modules.isEmpty());
            var entries = (List<Map<String, Object>>) group.get("entries");
            require(!entries.isEmpty());
            for (var entry : entries) {
                String name = (String) entry.get("name");
                boolean sequence = "sequence".equals(group.get("id"));
                String execution = (String) (sequence ? entry.get("execution") : group.get("execution"));
                require(Set.of("supported", "diagnostic", "frontier").contains(execution));
                if (sequence) {
                    require(execution.equals(supportedSequence.contains(name) ? "supported" : "frontier"), "Unexpected Sequence support declaration for " + name);
                    require(entry.get("audit") instanceof String, "Sequence requires a strict per-entry audit");
                }
                boolean diagnostic = execution.equals("diagnostic");
                var audit = (Map<String, Object>) Json.INSTANCE.parse(Files.readString(Path.of((String) (sequence ? entry.get("audit") : group.get("audit")))));
                require(Boolean.valueOf(execution.equals("supported")).equals(audit.get("accepted")), "Static audit disagrees with the declared coverage frontier");
                var warm = rows(entry, "warm");
                var cold = rows(entry, "cold");
                require(!warm.isEmpty() && !cold.isEmpty());
                require(Collections.disjoint(warm.stream().map(Row::input).toList(), cold.stream().map(Row::input).toList()));
                List<Row> all = new ArrayList<>(warm);
                all.addAll(cold);
                require(new HashSet<>(all.stream().map(Row::input).toList()).size() == all.size());
                var control = new EntryCheck(modules, name, backend, diagnostic);
                if (execution.equals("frontier")) {
                    try (Context context = Main.executionContext(false)) {
                        PolyglotException failure;
                        try {
                            context.eval(control.request(false));
                            throw new IllegalStateException("Strict loading unexpectedly accepted unsupported entry " + name);
                        } catch (PolyglotException exception) { failure = exception; }
                        check(message(failure).contains("Unsupported") || message(failure).contains("Unresolved") || message(failure).contains("unsupported"), "Unexpected loader failure: " + failure);
                        if (sequence) {
                            var missing = ((List<Map<String, Object>>) audit.get("missingGlobals")).stream().map(item -> (String) item.get("id")).toList();
                            check(message(failure).contains("Unresolved external binding") && missing.stream().anyMatch(message(failure)::contains),
                                "Sequence loader rejection does not match its recorded missing-definition frontier: " + failure);
                        }
                        System.out.println("LIBRARY_UNSUPPORTED\t" + backend + "\t" + name + "\t" + failure.getMessage());
                    }
                    continue;
                }
                try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true).option("engine.Compilation", "false").build()) {
                    if (diagnostic) {
                        PolyglotException failure;
                        try {
                            context.eval(control.request(false));
                            throw new IllegalStateException("Strict loading unexpectedly accepted diagnostic entry " + name);
                        } catch (PolyglotException exception) { failure = exception; }
                        System.out.println("LIBRARY_STRICT_REJECTION\t" + backend + "\t" + name + "\t" + failure.getMessage());
                    }
                    var function = context.eval(control.request(diagnostic));
                    control.checkRows(function, all, "interpreted", false);
                    check(count(function, "compiledEntries") == 0);
                }
                // Fresh state makes cold inputs genuinely unseen by this compilation.
                try (Context context = Main.executionContext(false)) {
                    var function = context.eval(control.request(diagnostic));
                    for (int index = 0; index < 40; index++) {
                        var row = warm.get(index % warm.size());
                        check(function.execute(row.input()).asLong() == row.expected());
                    }
                    control.checkPolicy(function);
                    check(function.invokeMember("compile").asBoolean(), "Failed guest compilation: " + backend + " " + name);
                    control.checkRows(function, warm, "compiled-warm", true);
                    // Cold arms can invalidate: preserve the original separate
                    // cold check, all-input training and installed final replay.
                    control.checkRows(function, cold, "after-compilation-cold", false);
                    for (int index = 0; index < Math.max(40, all.size()); index++) {
                        var row = all.get(index % all.size());
                        check(function.execute(row.input()).asLong() == row.expected());
                    }
                    check(function.invokeMember("compile").asBoolean(), "Failed post-cold compilation request: " + backend + " " + name);
                    control.checkRows(function, all.reversed(), "post-cold-compiled", true);
                    System.out.println("LIBRARY_DIAGNOSTICS\t" + backend + "\t" + name + "\t" + function.getMember("diagnostics").asString());
                }
            }
        }
    }
}
