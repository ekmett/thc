// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.MemorySegment;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.runtime.CoreSources;
import static org.junit.jupiter.api.Assertions.*;

/** These same manually specified bytes are checked by the native producer.
 * They test framing/primitives, not a fabricated executable Core module. */
class CoreCompactGoldenTest {
    private final Path directory = Path.of("t/compact-core/golden");
    private byte[] hex(String text) { return HexFormat.of().parseHex(text.replaceAll("\\s", "")); }
    @Test void sharedUnsignedAndSignedVectorsDecodeIndependently() throws Exception {
        var vectors = (List<?>) Json.parse(Files.readString(directory.resolve("integers-v1.json")));
        assertEquals(16, vectors.size());
        for (var raw : vectors) {
            var vector = (Map<?, ?>) raw;
            var cursor = new CoreCompactCursor(MemorySegment.ofArray(hex((String) vector.get("hex"))));
            var expected = (String) vector.get("value");
            switch ((String) vector.get("encoding")) {
                case "uvar" -> assertEquals(Long.parseUnsignedLong(expected), cursor.unsignedBits());
                case "svar" -> assertEquals(Long.parseLong(expected), cursor.signed());
                default -> fail("Unknown shared primitive vector");
            }
            cursor.expectEnd();
        }
    }
    @Test void sharedCbdHeaderDescribesMemberRelativePayloadWithoutScanningIt() throws Exception {
        byte[] header = hex(Files.readString(directory.resolve("cbd-header-v1.hex")));
        assertEquals(32, header.length);
        var format = CoreCompactFormat.read(MemorySegment.ofArray(header), List.of(2L, 3L, 0L, 0L, 0L, 24L));
        assertEquals(new CoreCompactFormat.Span(32, 0), format.facts());
        assertEquals(new CoreCompactFormat.Span(0, 2), format.get(CoreCompactFormat.Segment.DATA));
        assertEquals(new CoreCompactFormat.Span(0, 3), format.get(CoreCompactFormat.Segment.STRINGS));
        assertEquals(new CoreCompactFormat.Span(0, 24), format.get(CoreCompactFormat.Segment.SYMBOLS));
        assertEquals(1L, format.bindingCount());
        assertEquals(10, format.summaries());
        assertEquals(0, format.debug());
    }
    @SuppressWarnings("unchecked")
    @Test void nativeProducerStoredDeflatedAndMixedGoldensKeepSelectedRecordsAndLazyDebug() throws Exception {
        var expected = (Map<String, Object>) Json.parse(Files.readString(directory.resolve("cbd-module-v1.json")));
        var expectedSection = Objects.requireNonNull(new CoreSources(expected).binding(
            ((List<Map<String, Object>>) expected.get("bindings")).getFirst(), null)).getSection();
        assertNotNull(expectedSection);
        for (String encoding : List.of("stored", "deflated", "mixed")) {
            var path = directory.resolve("cbd-module-v1-" + encoding + ".cbd");
            var identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            try (var mappings = new CoreFileMappings(0, 0); var slabs = new CoreCbdSlabs(0, 0);
                 var file = new CoreCompactFile(path, identity, false, mappings, slabs)) {
                var records = new CoreCompactRecords(file, identity);
                var facts = records.header();
                for (String key : List.of("schema", "ghc", "unit", "module", "boundary", "constructors"))
                    assertEquals(expected.get(key), facts.get(key), encoding + "/" + key);
                assertEquals(0L, file.getCounters().statistics().dataBytesRead());
                assertEquals(0L, file.getCounters().statistics().debugBytesRead());
                var answer = records.binding(Objects.requireNonNull(file.lookup("main:CBDGolden.answer")));
                assertEquals(List.of("lit", "int", "42"), ((List<?>) answer.get("expr")).subList(0, 3));
                var identityBinding = records.binding(Objects.requireNonNull(file.lookup("main:CBDGolden.identity")));
                var lambda = (List<?>) identityBinding.get("expr");
                assertEquals("lam", lambda.get(0));
                var formals = (List<?>) lambda.get(1);
                assertEquals(1, formals.size());
                var formal = (Map<?, ?>) formals.getFirst();
                assertEquals(List.of("var", formal.get("id")), ((List<?>) lambda.get(2)).subList(0, 2));
                assertEquals(0L, file.getCounters().statistics().debugBytesRead());
                assertEquals(0L, file.getCounters().statistics().hashBytesRead());
                if (encoding.equals("stored")) assertEquals(0L, file.getCounters().statistics().inflatedBytes());
                else assertTrue(file.getCounters().statistics().inflatedBytes() > 0);
                var origin = (CoreCompactRecords.Origin) answer.get("compactOrigin");
                assertEquals("answer", Objects.requireNonNull(origin.getDebug()).name(origin.getBindingOffset(), 0));
                var section = Objects.requireNonNull(origin.getDebug().location(origin.getDataOffset())).getSection();
                assertNotNull(section);
                assertEquals("CBDGolden.hs", section.getSource().getName());
                // This original model supplies GHC line/column coordinates,
                // not character offsets. Preserve the JSON route's exact
                // unavailable-text policy rather than inventing offsets.
                assertEquals(expectedSection.getSource().hasCharacters(), section.getSource().hasCharacters());
                assertEquals(expectedSection.getCharacters().toString(), section.getCharacters().toString());
                assertEquals(List.of(1, 1, 1, 11), List.of(section.getStartLine(), section.getStartColumn(), section.getEndLine(), section.getEndColumn()));
                assertTrue(file.getCounters().statistics().debugBytesRead() > 0);
            }
        }
    }
}
