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
