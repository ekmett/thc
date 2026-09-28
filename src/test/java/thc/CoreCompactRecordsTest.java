// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.CoreFloatingLiteral;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreCompactRecordsTest {
    @TempDir Path directory;
    private byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
    private final byte[] id = "unit:M.f".getBytes(StandardCharsets.UTF_8);
    @FunctionalInterface private interface Action { void run(CoreCompactRecords records, CoreCompactFile file) throws Exception; }
    private void module(byte[] data, Action action) throws Exception {
        module(data, id, action);
    }
    private void module(byte[] data, byte[] strings, Action action) throws Exception {
        module(data, strings, new byte[0], action);
    }
    private void module(byte[] data, byte[] strings, byte[] facts, Action action) throws Exception {
        module(data, strings, facts, 0, action);
    }
    private void module(byte[] data, byte[] strings, byte[] facts, int flags, Action action) throws Exception {
        var symbols = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .put(MessageDigest.getInstance("MD5").digest(id)).putLong(0).array();
        var encoded = CoreCbdTestSupport.archive(CoreCbdTestSupport.header(facts, 1, flags, 0),
            List.of(data, strings, new byte[0], new byte[0], new byte[0], symbols), Set.of(), false);
        var path = directory.resolve("module.cbd");
        Files.write(path, encoded);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        try (var cache = new CoreFileMappings(1024 * 1024, 1); var file = new CoreCompactFile(path, sha, false, cache)) {
            action.run(new CoreCompactRecords(file, sha), file);
        }
    }
    private byte[] concat(byte[]... inputs) {
        var out = new ByteArrayOutputStream();
        for (var input : inputs) out.writeBytes(input);
        return out.toByteArray();
    }
    private byte[] literal(int kind, byte[] payload) { return concat(bytes(2), new byte[10], bytes(kind), payload); }
    private byte[] binding(byte[] expression) { return concat(bytes(0, 0, id.length, 0, 2, 1, 0, 0, 0, 0, 0, 0, 0), expression); }
    private record LiteralCase(int kind, byte[] payload, String tag, Object expected) {}
    @Test void nominalHostSignatureExtensionNeedsItsFeatureBitAndKeepsOldRecords() throws Exception {
        // Independent shape encoding: object, exact unlifted boxed reference, evaluated.
        byte[] rep = bytes(0, 7, 2, 1, 15, 0, 0, 0, 0, 0, 0, 2, 1);
        byte[] plain = binding(literal(0, bytes(84)));
        byte[] extended = concat(bytes(2, 2, 1), rep, bytes(1, 1), rep, bytes(1, 2), plain);
        module(plain, (records, file) -> assertFalse(records.binding(0).containsKey("hostSignature")));
        module(extended, id, new byte[0], 16, (records, file) -> {
            assertTrue(file.header().getContainsHostSignatures());
            var signature = (Map<?, ?>) records.binding(0).get("hostSignature");
            var input = (Map<?, ?>) ((List<?>) signature.get("inputs")).getFirst();
            assertEquals(List.of("object"), input.get("carriers"));
            assertEquals(List.of("BoxedRep (Just Unlifted)"), ((Map<?, ?>) input.get("rep")).get("primReps"));
            assertEquals(List.of("interop-library"), ((Map<?, ?>) signature.get("result")).get("carriers"));
        });
        module(extended, (records, file) -> assertThrows(IllegalArgumentException.class, () -> records.binding(0)));
        module(concat(bytes(2, 0), plain), id, new byte[0], 16, (records, file) ->
            assertEquals("Missing compact host signature extension",
                assertThrows(IllegalArgumentException.class, () -> records.binding(0)).getMessage()));
        module(concat(bytes(2, 2, 0), rep, bytes(1, 3), plain), id, new byte[0], 16, (records, file) ->
            assertEquals("Invalid compact host carrier",
                assertThrows(IllegalStateException.class, () -> records.binding(0)).getMessage()));
    }
    @Test void nativeCompanionAndDataEntriesDecodeWithoutReadingBodies() throws Exception {
        byte[] text = bytes(0, id.length);
        byte[] extendedInputs = concat(bytes(3, 0, 0, 0, 1), text, bytes(1), text, text, text, bytes(1), text,
            bytes(2, 1), text, bytes(2), text, bytes(2), text, bytes(2, 1, 1), text, bytes(0, 0));
        for (int schema : List.of(1, 2)) for (byte[] inputs : List.of(bytes(0), extendedInputs)) {
            byte[] prefix = concat(bytes(1), text, text, text, text, new byte[12], bytes(2, schema),
                text, text, text, text, text, text, bytes(0, 0), inputs);
            byte[] suffix = schema == 2 ? bytes(0, 0) : bytes(0);
            byte[] extras = concat(bytes(3, 2), text, bytes(4, 0, 255, 66, 127, 2, 1), text);
            module(new byte[0], id, concat(prefix, extras, suffix), (records, file) -> {
                var link = (Map<?, ?>) records.header().get("packageNativeLink");
                assertEquals(Map.of("sha256", "unit:M.f", "hex", "00ff427f"), link.get("nativeLibrary"));
                assertEquals(List.of("unit:M.f"), link.get("dataSymbols"));
                assertFalse(link.containsKey("availableEntries"));
                if (inputs.length > 1) {
                    var build = (Map<?, ?>) link.get("buildInputs");
                    var receipt = (Map<?, ?>) ((List<?>) build.get("nativeLibraries")).getFirst();
                    assertEquals(List.of("unit:M.f"), receipt.get("dependencyArguments"));
                    assertEquals("unit:M.f", receipt.get("objcopy"));
                    assertEquals("unit:M.f", receipt.get("objcopySha256"));
                    assertEquals(List.of(List.of("unit:M.f")), receipt.get("objcopyArguments"));
                }
            });
            module(new byte[0], id, concat(prefix, bytes(0), suffix), (records, file) -> {
                var link = (Map<?, ?>) records.header().get("packageNativeLink");
                assertFalse(link.containsKey("nativeLibrary"));
                assertFalse(link.containsKey("dataSymbols"));
            });
            module(new byte[0], id, concat(prefix, bytes(2), suffix), (records, file) ->
                assertThrows(IllegalStateException.class, records::header));
        }
    }
    @Test void schemaTwoForeignArgumentsKeepArrayIdentityAndScalarNullPositions() throws Exception {
        byte[] immutable = "ByteArray#".getBytes(StandardCharsets.UTF_8);
        byte[] mutable = "MutableByteArray#".getBytes(StandardCharsets.UTF_8);
        // Void expression with only Meta.foreignCall present. Shapes are
        // deliberately unevidenced; this is a codec control, not call admission.
        var expression = concat(bytes(8), new byte[5], bytes(2, 2, 1, 0, 0, 3, 3, 3),
            new byte[40], bytes(0, 0, 2, 3, 2, id.length, immutable.length, 1,
                2, id.length + immutable.length, mutable.length), new byte[4]);
        module(binding(expression), concat(id, immutable, mutable), (records, file) -> {
            var body = (List<?>) records.binding(0).get("expr");
            var call = (Map<?, ?>) ((Map<?, ?>) body.getLast()).get("foreignCall");
            assertEquals(2L, call.get("schema"));
            assertEquals(Arrays.asList("ByteArray#", null, "MutableByteArray#"), call.get("argumentTypes"));
            assertEquals(3, ((List<?>) call.get("argumentReps")).size());
        });
    }
    @Test void explicitLiteralRecordsPreserveRawBytesIntegersAndIeeeBits() throws Exception {
        var cases = List.of(
            new LiteralCase(0, bytes(83), "int", "-42"),
            new LiteralCase(9, bytes(255, 255, 255, 255, 255, 255, 255, 255, 255, 1), "word64", "18446744073709551615"),
            new LiteralCase(10, bytes(3, 0, 0, 1), "bignat", "65536"),
            new LiteralCase(12, bytes(4, 0, 255, 192, 128), "string-bytes", "00ffc080"),
            new LiteralCase(13, bytes(0x34, 0x12, 0xc0, 0x7f), "float", new CoreFloatingLiteral.Single(0x7fc01234)),
            new LiteralCase(14, bytes(0, 0, 0, 0, 0, 0, 0, 128), "double", new CoreFloatingLiteral.Double(Long.MIN_VALUE)));
        for (var c : cases) module(binding(literal(c.kind(), c.payload())), (records, file) -> {
            var selected = records.binding(0);
            assertEquals("unit:M.f", selected.get("id"));
            assertEquals(List.of("lit", c.tag(), c.expected()), ((List<?>) selected.get("expr")).subList(0, 3));
            assertEquals(0L, file.getCounters().statistics().debugBytesRead());
            assertInstanceOf(CoreCompactRecords.Origin.class, selected.get("compactOrigin"));
        });
    }
    @Test void exactShapeReferencesDoNotShareOccurrenceEvaluatedness() throws Exception {
        var body = new ByteArrayOutputStream();
        body.writeBytes(bytes(0, 0, id.length, 0, 2, 1, 0, 2)); // Binding with known rep.
        int definition = body.size();
        body.writeBytes(bytes(0, 0, 2, 1, 0, 0, 0, 0, 0, 0, 0)); // ShapeUse inline; long/[IntRep].
        body.writeBytes(bytes(2, 0)); // First occurrence is not evaluated.
        body.writeBytes(new byte[5]); // Remaining binding optional fields.
        body.writeBytes(bytes(2, 2, 1, definition, 2, 1)); // lit Meta.rep uses same shape, evaluated.
        body.writeBytes(new byte[9]);
        body.writeBytes(bytes(0, 84)); // int42.
        module(body.toByteArray(), (records, file) -> {
            var selected = records.binding(0);
            var bindingRep = (Map<?, ?>) selected.get("rep");
            var expression = (List<?>) selected.get("expr");
            var occurrenceRep = (Map<?, ?>) ((Map<?, ?>) expression.getLast()).get("rep");
            assertEquals(false, bindingRep.get("evaluated"));
            assertEquals(true, occurrenceRep.get("evaluated"));
            assertEquals(without(bindingRep, "evaluated"), without(occurrenceRep, "evaluated"));
        });
    }
    @Test void malformedLiteralBoundariesAndUnknownTypedTagsRejectLocally() throws Exception {
        for (byte[] expression : List.of(literal(2, bytes(128, 2)), literal(6, bytes(128, 2)),
                literal(10, bytes(1, 0)), literal(12, bytes(127)), literal(19, new byte[0]), concat(bytes(255), new byte[10]))) {
            module(binding(expression), (records, file) -> assertThrows(RuntimeException.class, () -> records.binding(0)));
        }
    }
    private Map<String, Object> leaf(boolean evaluated) { return map("kind", "long", "primReps", List.of("IntRep"), "evaluated", evaluated); }
    @SafeVarargs private final Map<String, Object> tuple(boolean evaluated, Map<String, Object>... children) {
        return map("kind", "unknown", "primReps", Collections.nCopies(children.length, "IntRep"),
            "aggregate", "unboxed-tuple", "components", Arrays.asList(children), "evaluated", evaluated);
    }
    @Test void sharedManualNestedShapesKeepEveryChildOccurrenceEvaluationDistinct() throws Exception {
        String hex = Files.readString(Path.of("t/compact-core/golden/nested-shared-rep-v1.hex"));
        byte[] bytes = HexFormat.of().parseHex(hex.replaceAll("\\s", ""));
        assertEquals(62, bytes.length);
        var firstExpected = tuple(false, tuple(true, leaf(false)), tuple(false, leaf(true)));
        var secondExpected = tuple(true, tuple(false, leaf(true)), tuple(true, leaf(false)));
        module(bytes, (records, file) -> {
            var first = records.representation(0);
            assertEquals(firstExpected, first);
            assertEquals(50L, file.getCounters().statistics().dataBytesRead());
            var second = records.representation(50);
            assertEquals(secondExpected, second);
            assertEquals(62L, file.getCounters().statistics().dataBytesRead(), "The prior shape must be reused, not decoded again");
            assertSame(first.get("primReps"), second.get("primReps"));
            assertNotSame(first.get("components"), second.get("components"));
            var children = (List<?>) first.get("components");
            assertNotSame(children.get(0), children.get(1));
            assertEquals(firstExpected, first, "Decoding the second occurrence must not alter the first tree");
            assertEquals(0L, file.getCounters().statistics().debugBytesRead());
        });
    }
}
