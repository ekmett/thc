// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.runtime.BytecodeRoot;

import static org.junit.jupiter.api.Assertions.*;

/** Actual GHC primops, unsigned mathematical results and installed guest code. */
@SuppressWarnings("unchecked")
public final class IntegerPrimopsTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));

    @Test void bytecodeSignedRemainderPreservesLongBoundaries() {
        long[] values = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -37, -10, -2, -1,
            0, 1, 2, 10, 37, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        for (long left : values) for (long right : values) {
            if (right == 0) assertThrows(ArithmeticException.class,
                () -> BytecodeRoot.Remainder.apply(left, right));
            else assertEquals(left % right, BytecodeRoot.Remainder.apply(left, right),
                "remainder(" + left + ", " + right + ")");
        }
    }

    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.INSTANCE.parse(Files.readString(root.resolve("build/integer-primops/manifest.json")));
    }

    private long count(Value function, String key) {
        return ((Number) ((Map<String, Object>) Json.INSTANCE.parse(
            function.getMember("diagnostics").asString())).get(key)).longValue();
    }

    private void verifyHashes(Map<String, Object> manifest) throws Exception {
        for (String kind : List.of("inputHashes", "artifactHashes")) {
            for (var entry : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(root.resolve(entry.getKey()))));
                assertEquals(entry.getValue(), actual,
                    "Stale integer primop fixture: " + entry.getKey() + "; rerun prepare-tests.sh");
            }
        }
    }

    private String operation(String name) {
        int end = name.indexOf("Word");
        return end < 0 ? name : name.substring(0, end);
    }

    private void check(Value function, String backend, Map<String, Object> entry, long[] row) {
        long selector = ((Number) entry.get("selector")).longValue();
        assertEquals(row[2], function.execute(selector, row[0], row[1]).asLong(),
            backend + " " + entry.get("name") + "(" + row[0] + ", " + row[1] + ")");
    }

    @Test void realCoreAgreesWithNativeAndUnsignedModelBeforeAndAfterCompilation() throws Exception {
        var manifest = manifest();
        verifyHashes(manifest);
        var entries = (List<Map<String, Object>>) manifest.get("entries");
        assertEquals(50, entries.size());
        var composite = (Map<String, Object>) manifest.get("composite");
        assertEquals("composite", composite.get("name"));
        assertEquals(3, ((Number) composite.get("arity")).intValue());
        assertEquals(0, ((Number) composite.get("selectorArgument")).intValue());
        assertEquals(entries.stream().map(e -> e.get("name")).toList(), composite.get("selectorOrder"));
        assertEquals(IntStream.range(0, entries.size()).boxed().toList(),
            entries.stream().map(e -> ((Number) e.get("selector")).intValue()).toList());
        List<Map<String, Object>> modules = new ArrayList<>();
        for (String path : (List<String>) manifest.get("modules"))
            modules.add((Map<String, Object>) Json.INSTANCE.parse(Files.readString(root.resolve(path))));
        var merged = CoreModules.INSTANCE.merge(modules);
        var compositeCalls = NumericPrimopCoreEvidence.calls(merged, (String) composite.get("name"), true);
        for (var entry : entries) {
            String name = (String) entry.get("name");
            String operation = operation(name);
            String word = Set.of("pdep", "pext").contains(operation)
                ? (name.endsWith("Word64") ? "Word64Rep" : "WordRep")
                : name.endsWith("Word") ? "WordRep" : "Word" + ((Number) entry.get("width")).intValue() + "Rep";
            List<String> arguments = ((Number) entry.get("arity")).intValue() == 1 ? List.of(word)
                : List.of(word, operation.startsWith("uncheckedShift") ? "IntRep" : word);
            String result = Set.of("eq", "ne", "gt", "ge").contains(operation) ? "IntRep" : word;
            String primitive = (String) entry.get("primitive");
            NumericPrimopCoreEvidence.assertCall(NumericPrimopCoreEvidence.calls(merged, name),
                primitive, arguments, result, name);
            NumericPrimopCoreEvidence.assertCall(compositeCalls, primitive, arguments, result, "composite/" + name);
        }
        Map<String, List<long[]>> casesByName = new LinkedHashMap<>();
        for (String line : Files.readAllLines(root.resolve("build/integer-primops/oracle.tsv"))) {
            String[] row = line.split("\t");
            casesByName.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(new long[] {
                Long.parseLong(row[1]), Long.parseLong(row[2]), Long.parseLong(row[3])});
        }
        assertEquals(entries.stream().map(e -> e.get("name")).collect(Collectors.toSet()), casesByName.keySet());
        for (var entry : entries) {
            String name = (String) entry.get("name");
            int width = ((Number) entry.get("width")).intValue();
            for (long[] row : casesByName.get(name))
                assertEquals(ScalarPrimopModel.scalar(operation(name), width, true, row[0], row[1]), row[2],
                    "Native " + name + "(" + row[0] + ", " + row[1] + ")");
        }
        for (String backend : List.of("ast", "bytecode")) try (Context context = PrimopTestContextKt.primopTestContext()) {
            NumericPrimopCoreEvidence.assertLoadableWrappers(context, merged,
                entries.stream().map(e -> (String) e.get("name")).toList(), backend);
            Value function = context.eval("thc", Json.INSTANCE.stringify(Map.of("modules", modules,
                "entry", composite.get("name"), "backend", backend, "instrument", true)));
            // Warm every selector and native row, then repeat each through installed code.
            for (var entry : entries) for (long[] row : casesByName.get(entry.get("name")))
                check(function, backend, entry, row);
            assertEquals(0L, count(function, "compiledEntries"), backend + " warm phase");
            assertTrue(function.invokeMember("compile").asBoolean(), backend + " composite installation");
            for (var entry : entries) {
                String name = (String) entry.get("name");
                var cases = casesByName.get(name);
                long before = count(function, "compiledEntries");
                for (long[] row : cases.reversed()) check(function, backend, entry, row);
                assertEquals((long) cases.size(), count(function, "compiledEntries") - before,
                    backend + " " + name + " every native row must enter compiled code");
                assertEquals(0L, count(function, "unsupportedTraps"), backend + " " + name);
            }
        }
    }

    @Test void everyAddedPrimitiveRejectsWrongAritiesEvenInDiagnosticMode() throws Exception {
        var entries = (List<Map<String, Object>>) manifest().get("entries");
        for (String backend : List.of("ast", "bytecode")) for (boolean diagnostic : new boolean[] {false, true}) {
            try (Context context = MainKt.executionContext(false)) {
                for (var entry : entries) {
                    String name = (String) entry.get("primitive");
                    int arity = ((Number) entry.get("arity")).intValue();
                    for (int supplied : new int[] {arity - 1, arity + 1}) {
                        var body = List.of("app", List.of("prim", name),
                            Collections.nCopies(supplied, List.of("lit", "word", "1")), Collections.nCopies(supplied, false));
                        var module = Map.of("schema", 1, "ghc", "9.14.1", "module", "Malformed.IntegerPrimop",
                            "constructors", List.of(), "bindings", List.of(Map.of("id", "entry", "name", "entry",
                                "lifted", true, "arity", 0, "expr", body)));
                        var error = assertThrows(PolyglotException.class, () -> context.eval("thc", Json.INSTANCE.stringify(
                            Map.of("entry", "entry", "backend", backend, "diagnosticUnsupported", diagnostic, "modules", List.of(module)))));
                        assertTrue(error.getMessage() != null && error.getMessage().contains("Primitive arity mismatch: " + name), error.getMessage());
                    }
                }
            }
        }
    }
}
