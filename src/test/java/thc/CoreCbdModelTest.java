// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the test-model adapter against the production encoder and reader. */
class CoreCbdModelTest {
    @TempDir Path directory;

    @SuppressWarnings("unchecked")
    @Test void snapshotsPermitNegativeControlsWithoutChangingTheSource() {
        var source = Map.of("expr", List.of("app", List.of("prim", "word2Float#"), List.of("operand")));
        var copy = (Map<String, Object>) CoreCbdFixtures.snapshot(source);
        var expression = (List<Object>) copy.get("expr");
        ((List<Object>) expression.get(2)).clear();
        expression.set(1, List.of("prim", "word2Double#"));
        assertEquals(List.of("app", List.of("prim", "word2Float#"), List.of("operand")), source.get("expr"));
        assertEquals(List.of(), expression.get(2));
    }

    @SuppressWarnings("unchecked")
    @Test void decodedFloatingModelsPreserveEveryIeeeBit() throws Exception {
        var model = (Map<String, Object>) Json.parse(Files.readString(Path.of("t/compact-core/golden/cbd-module-v1.json")));
        var literals = List.of(new thc.runtime.CoreFloatingLiteral.Single(0x80000000),
            new thc.runtime.CoreFloatingLiteral.Single(0x7f800000), new thc.runtime.CoreFloatingLiteral.Single(0x7fc12345),
            new thc.runtime.CoreFloatingLiteral.Double(0x8000000000000000L),
            new thc.runtime.CoreFloatingLiteral.Double(0x7ff0000000000000L), new thc.runtime.CoreFloatingLiteral.Double(0x7ff8123456789abcL));
        var bindings = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < literals.size(); i++) {
            boolean single = literals.get(i) instanceof thc.runtime.CoreFloatingLiteral.Single;
            var rep = Map.of("kind", single ? "float" : "double", "primReps", List.of(single ? "FloatRep" : "DoubleRep"), "evaluated", true);
            bindings.add(Map.of("id", "main:CBDGolden.bits" + i, "arity", 0, "lifted", false, "rep", rep,
                "expr", List.of("lit", single ? "float" : "double", literals.get(i), Map.of("rep", rep))));
        }
        model.put("bindings", bindings);
        var decoded = (List<Map<String, Object>>) CoreCbdFixtures.read(CoreCbdFixtures.write(directory.resolve("ieee.cbd"), model)).get("bindings");
        assertEquals(literals.size(), decoded.size());
        var byId = new LinkedHashMap<Object, Map<String, Object>>();
        for (var binding : decoded) assertNull(byId.put(binding.get("id"), binding));
        assertEquals(new HashSet<>(bindings.stream().map(binding -> binding.get("id")).toList()), byId.keySet());
        for (int i = 0; i < literals.size(); i++) {
            var expression = (List<?>) byId.get(bindings.get(i).get("id")).get("expr");
            assertEquals(literals.get(i), expression.get(2));
            assertEquals(((List<?>) bindings.get(i).get("expr")).getLast(), expression.getLast());
        }
        var malformed = new LinkedHashMap<>(bindings.getFirst());
        malformed.put("expr", List.of("lit", "double", literals.getFirst()));
        model.put("bindings", List.of(malformed));
        assertThrows(java.io.IOException.class, () -> CoreCbdFixtures.write(directory.resolve("mismatched.cbd"), model));
    }

    @SuppressWarnings("unchecked")
    @Test void modelRoundTripPreservesBindingsAndLazyDebug() throws Exception {
        var model = (Map<String, Object>) Json.parse(Files.readString(
            Path.of("t/compact-core/golden/cbd-module-v1.json")));
        var path = CoreCbdTestSupport.writeModel(directory.resolve("model with spaces.cbd"), model);
        var identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        try (var mappings = new CoreFileMappings(0, 0); var slabs = new CoreCbdSlabs(0, 0);
             var file = new CoreCompactFile(path, identity, false, mappings, slabs)) {
            var records = new CoreCompactRecords(file, identity);
            assertEquals("CBDGolden", records.header().get("module"));
            assertEquals(0L, file.getCounters().statistics().dataBytesRead());
            var binding = records.binding(Objects.requireNonNull(file.lookup("main:CBDGolden.answer")));
            assertEquals(List.of("lit", "int", "42"), ((List<?>) binding.get("expr")).subList(0, 3));
            assertEquals(0L, file.getCounters().statistics().debugBytesRead());
            var origin = (CoreCompactRecords.Origin) binding.get("compactOrigin");
            assertEquals("answer", Objects.requireNonNull(origin.getDebug()).name(origin.getBindingOffset(), 0));
            assertEquals("CBDGolden.hs", Objects.requireNonNull(origin.getDebug().location(
                origin.getDataOffset())).getSection().getSource().getName());
        }
    }
}
