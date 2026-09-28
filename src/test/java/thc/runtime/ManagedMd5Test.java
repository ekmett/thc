// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.File;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

/** Direct checked C ABI tests, not public Haskell Fingerprint/FFI execution. */
@SuppressWarnings("unchecked")
public class ManagedMd5Test {
    private Context cbitsContext;
    @BeforeEach public void enterCbitsContext() { cbitsContext = Context.newBuilder("thc").allowNativeAccess(true).build(); cbitsContext.initialize("thc"); cbitsContext.enter(); }
    @AfterEach public void closeCbitsContext() { cbitsContext.leave(); cbitsContext.close(); }
    private String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private byte[] unhex(String value) { return HexFormat.of().parseHex(value); }
    private byte[] filled(int size, int value) { var bytes = new byte[size]; Arrays.fill(bytes, (byte) value); return bytes; }
    private ManagedAddress address(byte[] bytes) { return address(bytes, 0); }
    private ManagedAddress address(byte[] bytes, int offset) { return ManagedAddress.fromByteArray(bytes).plus(offset); }
    private void word(byte[] bytes, int offset, long value) {
        for (int b = 0; b <= 3; b++) { int shift = 8 * (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : 3 - b); bytes[offset + b] = (byte) (value >>> shift); }
    }
    private boolean all(byte[] bytes, int start, int end, int value) { for (int i = start; i < end; i++) if (bytes[i] != (byte) value) return false; return true; }
    @Test public void publishedDigestVectorsAndLiteralInputUseOnlyVisibleContext() {
        String[][] vectors = {{"", "d41d8cd98f00b204e9800998ecf8427e"}, {"a", "0cc175b9c0f1b6a831c399e269772661"}, {"abc", "900150983cd24fb0d6963f7d28e17f72"},
            {"message digest", "f96b697d7cb7938d525a2f31aaf161d0"}, {"abcdefghijklmnopqrstuvwxyz", "c3fcd3d76192e4007dfb496cca67e13b"},
            {"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", "d174ab98d277d9f5a5611c2c9f419d9f"},
            {"12345678901234567890123456789012345678901234567890123456789012345678901234567890", "57edf4a22be3c955ac49da2e2107b67a"}};
        for (var vector : vectors) { var text = vector[0]; var expected = vector[1]; for (int split = 0; split <= text.length(); split++) {
            var backing = filled(96, 0xa5); var output = filled(24, 0xd3); var context = address(backing, 4); ManagedMd5.init(context);
            assertArrayEquals(filled(64, 0xa5), Arrays.copyOfRange(backing, 28, 92));
            var input = ManagedAddress.fromHex(hex(text.getBytes(StandardCharsets.US_ASCII))); ManagedMd5.update(context, input, split);
            var resumed = backing.clone(); // All visible context bytes suffice to resume.
            ManagedMd5.update(address(resumed, 4), input.plus(split), text.length() - split); ManagedMd5.finish(address(output, 3), address(resumed, 4));
            assertEquals(expected, hex(Arrays.copyOfRange(output, 3, 19)), "text=" + text + "/split=" + split);
            assertArrayEquals(new byte[88], Arrays.copyOfRange(resumed, 4, 92));
            assertTrue(all(resumed, 0, 4, 0xa5)); assertTrue(all(resumed, 92, resumed.length, 0xa5));
            assertTrue(all(output, 0, 3, 0xd3)); assertTrue(all(output, 19, output.length, 0xd3));
        } }
    }
    private void unchanged(Executable action, byte[]... arrays) {
        var before = new ArrayList<byte[]>(); for (var array : arrays) before.add(array.clone());
        assertThrows(RuntimeFault.class, action);
        for (int index = 0; index < arrays.length; index++) assertArrayEquals(before.get(index), arrays[index], "failed call storage " + index);
    }
    @Test public void malformedLengthsRangesAndLiteralDestinationsFailBeforeAnyEffect() {
        var bytes = filled(88, 0xa5); var input = new byte[64]; for (int i = 0; i < input.length; i++) input[i] = (byte) (i * 73 + 128);
        var output = filled(16, 0xd3); var context = address(bytes);
        for (long length : new long[]{Long.MIN_VALUE, -1L, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE, 65L}) unchanged(() -> ManagedMd5.update(context, address(input), length), bytes, input);
        var shortBytes = filled(87, 0x6b);
        unchanged(() -> ManagedMd5.init(address(shortBytes)), shortBytes);
        unchanged(() -> ManagedMd5.update(address(shortBytes), address(input), 0), shortBytes, input);
        unchanged(() -> ManagedMd5.finish(address(output), address(shortBytes)), shortBytes, output);
        unchanged(() -> ManagedMd5.init(context.plus(1)), bytes);
        unchanged(() -> ManagedMd5.update(context.plus(1), address(input), 0), bytes, input);
        unchanged(() -> ManagedMd5.finish(address(output).plus(1), context), bytes, output);
        unchanged(() -> ManagedMd5.finish(address(output), context.plus(1)), bytes, output);
        var unaligned = filled(96, 0xa5);
        for (int offset : new int[]{1, 2, 3, 5}) {
            var bad = address(unaligned, offset);
            unchanged(() -> ManagedMd5.init(bad), unaligned); unchanged(() -> ManagedMd5.update(bad, address(input), 0), unaligned, input);
            unchanged(() -> ManagedMd5.finish(address(output), bad), unaligned, output);
        }
        var literal = ManagedAddress.fromHex("a5".repeat(88)); var literalBefore = new ArrayList<Long>(); for (long i = 0; i <= 88; i++) literalBefore.add(literal.readWord8(i));
        assertThrows(RuntimeFault.class, () -> ManagedMd5.init(literal)); unchanged(() -> ManagedMd5.update(literal, address(input), 0), input);
        unchanged(() -> ManagedMd5.finish(address(output), literal), output); unchanged(() -> ManagedMd5.finish(literal, context), bytes);
        var literalAfter = new ArrayList<Long>(); for (long i = 0; i <= 88; i++) literalAfter.add(literal.readWord8(i)); assertEquals(literalBefore, literalAfter);
        ManagedMd5.init(context); var before = bytes.clone(); ManagedMd5.update(context, address(input).plus(64), 0); assertArrayEquals(before, bytes, "zero-length one-past input");
        var empty = address(new byte[0]); ManagedMd5.update(context, empty, 0); assertArrayEquals(before, bytes, "zero-length empty input");
    }
    @Test public void everyMemcpyOverlapIsRejectedBeforeEvenEarlierDisjointCopies() {
        var backing = new byte[256]; for (int i = 0; i < backing.length; i++) backing[i] = (byte) (i * 17 + 53);
        var context = address(backing, 64); ManagedMd5.init(context);
        unchanged(() -> ManagedMd5.update(context, address(backing, 88), 1), backing);
        // First copy disjoint; second overlapping copy must reject before either.
        unchanged(() -> ManagedMd5.update(context, address(backing), 128), backing);
        for (int offset : new int[]{49, 56, 64, 65, 79}) unchanged(() -> ManagedMd5.finish(address(backing, offset), context), backing);
        ManagedMd5.update(context, address(backing, 88), 0);
    }
    @Test public void exactNativeContextSnapshotsAndDefinedAliasesMatchPinnedC() throws Exception {
        var root = new File(System.getProperty("thc.projectRoot")); var directory = new File(root, "build/managed-md5-native");
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "provenance.json").toPath()));
        assertEquals(1L, ((Number) manifest.get("schema")).longValue()); assertEquals(88L, ((Number) manifest.get("contextSize")).longValue()); assertEquals(4L, ((Number) manifest.get("contextAlignment")).longValue());
        var offsets = new ArrayList<Long>(); for (var value : (List<Number>) manifest.get("contextOffsets")) offsets.add(value.longValue()); assertEquals(List.of(0L, 16L, 24L), offsets);
        assertEquals(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big", manifest.get("byteOrder")); assertEquals(true, manifest.get("independentModelMatched"));
        assertEquals(Map.of("md5.c", "4fa83bda7aacc8a1656d7e2d78251bbe70a04b56", "md5.h", "a87296687a2f3dc6748264ff2a8a0c919518db55"), manifest.get("referenceGitBlobs"));
        var sources = (List<Map<String, String>>) manifest.get("sources"); boolean md5 = false, addresses = false;
        for (var source : sources) { md5 |= "src/main/java/thc/runtime/ManagedMd5.java".equals(source.get("path")); addresses |= "src/main/java/thc/runtime/ManagedAddress.java".equals(source.get("path")); }
        assertTrue(md5); assertTrue(addresses); assertEquals("9.14.1", manifest.get("ghc"));
        var records = new ArrayList<>(sources); records.addAll((List<Map<String, String>>) manifest.get("artifacts"));
        for (var record : records) {
            var path = new File(record.get("path")); var file = path.isAbsolute() ? path : new File(root, record.get("path"));
            assertEquals(record.get("sha256"), hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))), file.getPath());
        }
        var inventory = new ArrayList<long[]>();
        for (int length : new int[]{0, 1, 2, 7, 15, 16, 31, 55, 56, 57, 63, 64, 65, 95, 119, 120, 127, 128, 129, 255, 256, 257, 1024}) {
            var splits = new LinkedHashSet<Integer>(); for (int split : new int[]{0, 1, length / 2, 55, 56, 63, 64, length}) splits.add(Math.min(split, length));
            for (int split : splits) for (int seed : new int[]{0, 1, 127, 255}) inventory.add(new long[]{length, split, seed, 0, 0});
        }
        for (long high : new long[]{0L, 0xffffffffL}) for (long length : new long[]{17L, 64L, 129L}) for (long split : new long[]{0L, 8L, 16L}) inventory.add(new long[]{length, split, 127L, 0xfffffff0L, high});
        assertEquals((long) inventory.size(), ((Number) manifest.get("cases")).longValue()); assertEquals(4L * inventory.size(), ((Number) manifest.get("caseRows")).longValue());
        assertEquals((long) inventory.size() - 18, ((Number) manifest.get("hashlibCases")).longValue()); assertEquals(5L, ((Number) manifest.get("aliasCases")).longValue()); assertEquals(15L, ((Number) manifest.get("aliasRows")).longValue());
        var lines = Files.readAllLines(new File(directory, "native.stdout").toPath()); assertEquals(List.of("layout", "88", "0", "16", "24", "4", manifest.get("byteOrder")), Arrays.asList(lines.getFirst().split("\t", -1)));
        int cursor = 1;
        for (int id = 0; id < inventory.size(); id++) {
            var metadata = inventory.get(id); long length = metadata[0], split = metadata[1], seed = metadata[2], low = metadata[3], high = metadata[4];
            int co = id % 3 * 4, io = new int[]{0, 1, 7}[id % 3], oo = new int[]{0, 3, 11}[id % 3];
            var bytes = filled(104, 0xa5); var input = filled((int) length + 16, 0x6b); var output = filled(32, 0xd3);
            for (int i = 0; i < (int) length; i++) input[io + i] = (byte) (i * 73L + seed * 19 + (i >> 3));
            var before = input.clone(); var context = address(bytes, co);
            for (int phase = 0; phase <= 3; phase++) {
                switch (phase) { case 0 -> { ManagedMd5.init(context); word(bytes, co + 16, low); word(bytes, co + 20, high); }
                    case 1 -> ManagedMd5.update(context, address(input, io), split); case 2 -> ManagedMd5.update(context, address(input, io).plus(split), length - split); case 3 -> ManagedMd5.finish(address(output, oo), context); }
                var fields = Arrays.asList(lines.get(cursor++).split("\t", -1)); assertEquals(13, fields.size());
                var expected = new ArrayList<String>(); for (var value : List.of("case", id, length, split, seed, co, io, oo, low, high, phase)) expected.add(value.toString()); assertEquals(expected, fields.subList(0, 11));
                assertArrayEquals(unhex(fields.get(11)), bytes, "native context case=" + id + "/phase=" + phase); assertArrayEquals(unhex(fields.get(12)), output, "native output case=" + id + "/phase=" + phase);
                assertArrayEquals(before, input, "native input case=" + id + "/phase=" + phase);
            }
        }
        int[][] aliases = {{32, 16, 160}, {48, 8, 160}, {112, 8, 160}, {128, 65, 56}, {128, 65, 112}};
        for (int id = 0; id < aliases.length; id++) {
            var metadata = aliases[id]; int io = metadata[0], length = metadata[1], oo = metadata[2]; var bytes = new byte[256];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 17 + 0x35); var context = address(bytes, 32);
            for (int phase = 0; phase <= 2; phase++) {
                switch (phase) { case 0 -> ManagedMd5.init(context); case 1 -> ManagedMd5.update(context, address(bytes, io), length); case 2 -> ManagedMd5.finish(address(bytes, oo), context); }
                var fields = Arrays.asList(lines.get(cursor++).split("\t", -1)); assertEquals(7, fields.size()); var expected = new ArrayList<String>();
                for (var value : List.of("alias", id, io, length, oo, phase)) expected.add(value.toString()); assertEquals(expected, fields.subList(0, 6));
                assertArrayEquals(unhex(fields.get(6)), bytes, "native alias=" + id + "/phase=" + phase);
            }
        }
        assertEquals(lines.size(), cursor, "no omitted or surplus native rows");
    }
}
