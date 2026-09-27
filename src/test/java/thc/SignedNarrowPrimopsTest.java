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

import static org.junit.jupiter.api.Assertions.*;

/** Native/model observations are machine-width; narrow guest computations use Int. */
@SuppressWarnings("unchecked")
public final class SignedNarrowPrimopsTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.INSTANCE.parse(Files.readString(root.resolve("build/signed-narrow-primops/manifest.json")));
    }
    private List<Map<String, Object>> entries() throws Exception {
        return (List<Map<String, Object>>) manifest().get("entries");
    }
    private long count(Value function, String key) {
        return ((Number) ((Map<String, Object>) Json.INSTANCE.parse(function.getMember("diagnostics").asString())).get(key)).longValue();
    }
    private String operation(String name) {
        int end = name.indexOf("Int");
        return end < 0 ? name : name.substring(0, end);
    }
    private long mathematical(String name, int width, long left, long right) {
        if (name.startsWith("uncheckedShiftRA")) return ScalarPrimopModel.scalar("shiftRA", width, false, left, right);
        if (name.startsWith("uncheckedShiftL")) return ScalarPrimopModel.scalar("shiftL", width, false, left, right);
        if (name.startsWith("int") && name.contains("ToWord")) return ScalarPrimopModel.scalar("identity", width, true, left, right);
        if (name.startsWith("word") && name.contains("ToInt")) return ScalarPrimopModel.scalar("identity", width, false, left, right);
        return ScalarPrimopModel.scalar(operation(name), width, false, left, right);
    }
    private void verifyHashes(Map<String, Object> manifest) throws Exception {
        for (String kind : List.of("inputHashes", "artifactHashes")) for (var entry : ((Map<String, String>) manifest.get(kind)).entrySet()) {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(entry.getKey()))));
            assertEquals(entry.getValue(), actual, "Stale signed narrow fixture: " + entry.getKey() + "; rerun prepare-tests.sh");
        }
    }
    private void check(Value function, String backend, Map<String, Object> entry, long[] row) {
        int selector = ((Number) entry.get("selector")).intValue();
        assertEquals(row[2], function.execute(selector, row[0], row[1]).asLong(),
            backend + " " + entry.get("name") + "(" + row[0] + ", " + row[1] + ")");
    }

    @Test void realCoreAgreesWithNativeAndSignedModelBeforeAndAfterCompilation() throws Exception {
        var manifest = manifest();
        verifyHashes(manifest);
        var entries = (List<Map<String, Object>>) manifest.get("entries");
        assertEquals(48, entries.size());
        assertEquals("signedNarrowDispatch", manifest.get("compositeEntry"));
        assertEquals(IntStream.range(0, entries.size()).boxed().toList(),
            entries.stream().map(e -> ((Number) e.get("selector")).intValue()).toList());
        List<Map<String, Object>> modules = new ArrayList<>();
        for (String path : (List<String>) manifest.get("modules"))
            modules.add((Map<String, Object>) Json.INSTANCE.parse(Files.readString(root.resolve(path))));
        var merged = CoreModules.INSTANCE.merge(modules);
        var compositeCalls = NumericPrimopCoreEvidence.calls(merged, (String) manifest.get("compositeEntry"), true);
        for (var entry : entries) {
            String name = (String) entry.get("name");
            String rep = "Int" + ((Number) entry.get("width")).intValue() + "Rep";
            String wordRep = "Word" + ((Number) entry.get("width")).intValue() + "Rep";
            List<String> arguments = new ArrayList<>();
            for (int i = 0; i < ((Number) entry.get("arity")).intValue(); i++)
                arguments.add(name.startsWith("word") && name.contains("ToInt") ? wordRep
                    : name.startsWith("uncheckedShift") && i == 1 ? "IntRep" : rep);
            String result = name.startsWith("int") && name.contains("ToWord") ? wordRep
                : Set.of("eq", "ne", "lt", "le", "gt", "ge").contains(operation(name)) ? "IntRep" : rep;
            String primitive = (String) entry.get("primitive");
            NumericPrimopCoreEvidence.assertCall(NumericPrimopCoreEvidence.calls(merged, name), primitive, arguments, result, name);
            NumericPrimopCoreEvidence.assertCall(compositeCalls, primitive, arguments, result, "signedNarrowDispatch/" + name);
        }
        Map<String, List<long[]>> cases = new LinkedHashMap<>();
        for (String line : Files.readAllLines(root.resolve("build/signed-narrow-primops/oracle.tsv"))) {
            String[] row = line.split("\t");
            cases.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(new long[] {
                Long.parseLong(row[1]), Long.parseLong(row[2]), Long.parseLong(row[3])});
        }
        assertEquals(entries.stream().map(e -> e.get("name")).collect(Collectors.toSet()), cases.keySet());
        for (var entry : entries) {
            String name = (String) entry.get("name");
            int width = ((Number) entry.get("width")).intValue();
            int arity = ((Number) entry.get("arity")).intValue();
            for (long[] row : cases.get(name)) {
                if (arity == 1) assertEquals(0L, row[1], "Unary native oracle row for " + name);
                assertEquals(mathematical(name, width, row[0], row[1]), row[2], "Native " + name + "(" + row[0] + ", " + row[1] + ")");
            }
        }
        long total = cases.values().stream().mapToLong(List::size).sum();
        assertEquals(((Number) manifest.get("nativeRows")).longValue(), total);
        for (String backend : List.of("ast", "bytecode")) try (Context context = PrimopTestContextKt.primopTestContext()) {
            NumericPrimopCoreEvidence.assertLoadableWrappers(context, merged, entries.stream().map(e -> (String) e.get("name")).toList(), backend);
            Value function = context.eval("thc", Json.INSTANCE.stringify(Map.of("modules", modules,
                "entry", manifest.get("compositeEntry"), "backend", backend, "instrument", true)));
            for (var entry : entries) for (long[] row : cases.get(entry.get("name"))) check(function, backend, entry, row);
            assertEquals(0L, count(function, "compiledEntries"), backend + " must remain interpreted before explicit compile");
            assertTrue(function.invokeMember("compile").asBoolean(), backend + " composite installation");
            long before = count(function, "compiledEntries");
            for (var entry : entries.reversed()) for (long[] row : cases.get(entry.get("name")).reversed()) check(function, backend, entry, row);
            assertEquals(total, count(function, "compiledEntries") - before, backend + " every native row must enter the installed composite guest root");
            assertEquals(0L, count(function, "unsupportedTraps"));
            assertEquals(0L, count(function, "blackholes"));
        }
    }

    private Map<String, Object> rawModule(String primitive, int width, int supplied) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        List<List<String>> operands = new ArrayList<>();
        for (int i = 0; i < supplied; i++) {
            String family = primitive.startsWith("word") && primitive.contains("ToInt") ? "Word" + width
                : primitive.startsWith("uncheckedShift") && i == 1 ? "Int" : "Int" + width;
            parameters.add(Map.of("id", "x" + i, "name", "x" + i, "lifted", false, "type", family + "#",
                "rep", Map.of("kind", "long", "primReps", List.of(family + "Rep"), "evaluated", true), "coercion", false));
            operands.add(List.of("var", "x" + i));
        }
        var body = List.of("app", List.of("prim", primitive), operands, Collections.nCopies(supplied, false));
        String resultRep = Set.of("eq", "ne", "lt", "le", "gt", "ge").contains(operation(primitive)) ? "IntRep"
            : primitive.startsWith("int") && primitive.contains("ToWord") ? "Word" + width + "Rep" : "Int" + width + "Rep";
        return Map.of("schema", 1, "ghc", "9.14.1", "module", "SignedNarrowCarrierControl", "constructors", List.of(),
            "bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true, "arity", supplied,
                "expr", List.of("lam", parameters, body, Map.of("resultRep", Map.of("kind", "long", "primReps", List.of(resultRep), "evaluated", true))))));
    }

    @Test void directResultsAreCanonicalWithoutAnIntNToIntConversionMaskingTheResult() throws Exception {
        for (var entry : entries()) {
            String name = (String) entry.get("name");
            int width = ((Number) entry.get("width")).intValue();
            int arity = ((Number) entry.get("arity")).intValue();
            long minimum = -(1L << (width - 1)), maximum = -minimum - 1;
            long[] values = {minimum, minimum + 1, -3, -1, 0, 1, 3, maximum - 1, maximum};
            List<long[]> pairs = new ArrayList<>();
            for (long x : values) {
                if (arity == 1) pairs.add(new long[] {x, 0});
                else if (name.startsWith("uncheckedShift")) {
                    for (int shift = 0; shift < width; shift++) pairs.add(new long[] {x, shift});
                } else for (long y : values) {
                    if ((!name.startsWith("quot") && !name.startsWith("rem")) || (y != 0 && (x != minimum || y != -1)))
                        pairs.add(new long[] {x, y});
                }
            }
            for (String backend : List.of("ast", "bytecode")) try (Context context = MainKt.executionContext(false)) {
                Value function = context.eval("thc", Json.INSTANCE.stringify(Map.of("modules", List.of(rawModule((String) entry.get("primitive"), width, arity)),
                    "entry", "entry", "backend", backend, "instrument", true)));
                for (int pass = 0; pass < 2; pass++) {
                    if (pass == 1) assertTrue(function.invokeMember("compile").asBoolean());
                    long before = count(function, "compiledEntries");
                    for (long[] pair : pairs) {
                        // Word-to-Int inputs use their unsigned public range; the model observes the same low bits.
                        long input = name.startsWith("word") && name.contains("ToInt") ? pair[0] & ((1L << width) - 1) : pair[0];
                        Object[] args = arity == 1 ? new Object[] {input} : new Object[] {input, pair[1]};
                        assertEquals(mathematical(name, width, pair[0], pair[1]), function.execute(args).asLong(),
                            backend + " raw " + name + "(" + pair[0] + ", " + pair[1] + "), pass " + pass);
                    }
                    if (pass == 1) assertEquals((long) pairs.size(), count(function, "compiledEntries") - before);
                }
            }
        }
    }

    @Test void everyAddedPrimitiveRejectsWrongAritiesEvenInDiagnosticMode() throws Exception {
        for (String backend : List.of("ast", "bytecode")) for (boolean diagnostic : new boolean[] {false, true}) {
            try (Context context = MainKt.executionContext(false)) {
                for (var entry : entries()) {
                    String name = (String) entry.get("primitive");
                    int arity = ((Number) entry.get("arity")).intValue(), width = ((Number) entry.get("width")).intValue();
                    for (int supplied : new int[] {arity - 1, arity + 1}) {
                        var error = assertThrows(PolyglotException.class, () -> context.eval("thc", Json.INSTANCE.stringify(Map.of(
                            "entry", "entry", "backend", backend, "diagnosticUnsupported", diagnostic, "modules", List.of(rawModule(name, width, supplied))))));
                        assertTrue(error.getMessage() != null && error.getMessage().contains("Primitive arity mismatch: " + name), error.getMessage());
                    }
                }
            }
        }
    }
}
