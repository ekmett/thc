// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Independent host byte model; never invokes VectorMemory or guest operations. */
@SuppressWarnings("unchecked")
final class SimdByteArrayCorpus {
    record Input(String name, List<Long> arguments) {}
    final String family;
    final boolean floating;
    final boolean wide;
    final int lanes;
    final int rowCount;
    private final List<Long> weights;
    private final List<Long> seeds = List.of(Long.MIN_VALUE, -4294967297L, -2147483649L, -2147483648L, -2147483647L,
        -1L, 0L, 1L, 127L, 128L, 255L, 256L, 2147483646L, 2147483647L, 2147483648L, 4294967295L, 4294967296L, Long.MAX_VALUE);
    private final List<Long> edges;
    private final List<Long> sentinels;
    private final List<Long> rawBits;
    private final List<Long> signalingBits;
    private final List<String> offsetFamilies = List.of("vector", "scalar");
    private final List<String> operations;

    SimdByteArrayCorpus(String family) {
        this.family = family;
        floating = family.equals("floatx4") || family.equals("doublex2");
        wide = family.equals("doublex2");
        lanes = wide ? 2 : 4;
        rowCount = switch (family) { case "floatx4" -> 6720; case "doublex2" -> 4384; default -> 9666; };
        weights = List.of(3L, 5L, 7L, 11L).subList(0, lanes);
        edges = switch (family) {
            case "int32x4" -> List.of(-2147483648L, -2147483647L, -65537L, -1L, 0L, 1L, 65537L, 2147483646L, 2147483647L);
            case "word32x4" -> List.of(0L, 1L, 65535L, 65536L, 2147483647L, 2147483648L, 2147483649L, 4294967294L, 4294967295L);
            case "floatx4" -> List.of(-65536L, -257L, -1L, 0L, 1L, 255L, 256L, 65535L);
            case "doublex2" -> List.of(-(1L << 40), -(1L << 32) - 1, -1L, 0L, 1L, (1L << 32) - 1, (1L << 40) - 1, 1L << 40);
            default -> throw new IllegalStateException("Unknown SIMD memory family");
        };
        sentinels = switch (family) {
            case "int32x4" -> List.of(0x1234567L, -0x2345678L, 0x3456789L, -0x456789aL);
            case "word32x4" -> List.of(0x81234567L, 0x92345678L, 0xa3456789L, 0xb456789aL);
            default -> List.of(-31L, 127L, -1024L, 4096L).subList(0, lanes);
        };
        rawBits = wide ? List.of(0L, 0x8000000000000000L, 1L, 0x8000000000000001L,
            0x000fffffffffffffL, 0x800fffffffffffffL, 0x0010000000000000L, 0x8010000000000000L,
            0x3ff0000000000000L, 0xbff0000000000000L, 0x7fefffffffffffffL, 0xffefffffffffffffL,
            0x7ff0000000000000L, 0xfff0000000000000L, 0x7ff8123456789abcL, 0xfff8abcdef012345L)
            : List.of(0L, 0x80000000L, 1L, 0x80000001L, 0x007fffffL, 0x807fffffL, 0x00800000L, 0x80800000L,
                0x3f800000L, 0xbf800000L, 0x7f7fffffL, 0xff7fffffL, 0x7f800000L, 0xff800000L, 0x7fc12345L, 0xffc54321L);
        signalingBits = wide ? List.of(0x7ff0000000000001L, 0xfff0000000000001L, 0x7ff123456789abcdL, 0xfff543210fedcba9L)
            : List.of(0x7f800001L, 0xff800001L, 0x7fa12345L, 0xffa54321L);
        operations = floating ? List.of("Unit", "Index", "Read", "Write", "GraphIndex", "GraphStore")
            : List.of("Unit", "Index", "Read", "Write", "Store");
    }
    private String operation(String name) {
        String found = null;
        for (String operation : operations) {
            boolean matches = false;
            for (String offset : offsetFamilies) if (name.equals(offset + operation + "Case")) { matches = true; break; }
            if (matches) {
                if (found != null) throw new IllegalArgumentException("Unknown SIMD memory entry");
                found = operation;
            }
        }
        if (found == null) throw new IllegalArgumentException("Unknown SIMD memory entry");
        return found;
    }
    private int scale(String name) { return name.startsWith("vector") ? 16 : 16 / lanes; }
    private long narrow(long value) { return wide ? value : family.equals("int32x4") ? (long) (int) value : value & 0xffffffffL; }
    private long score(List<Long> values) {
        long result = 0;
        for (int i = 0; i < values.size(); i++) result += (floating ? values.get(i) : narrow(values.get(i))) * weights.get(i);
        return result;
    }
    private long bits(long value) { return wide ? Double.doubleToRawLongBits(value) : Float.floatToRawIntBits(value) & 0xffffffffL; }
    private byte[] initial() {
        var bytes = new byte[64];
        for (int b = 0; b < bytes.length; b++) bytes[b] = (byte) (((family.equals("int32x4") ? 0x10203040L : 0x89abcdefL)
            + b / 4 * 0x01030507L) >>> (8 * (b % 4)));
        return bytes;
    }
    private byte[] moved(byte[] original, long address, List<Long> values) {
        require(values.size() == lanes && address >= 0 && address <= original.length - 16);
        var bytes = original.clone();
        for (int b = 0; b < 16; b++) bytes[(int) address + b] = (byte) (values.get(b / (16 / lanes)) >>> (8 * (b % (16 / lanes))));
        return bytes;
    }
    private List<Long> loaded(byte[] bytes, long address) {
        require(address >= 0 && address <= bytes.length - 16);
        var result = new ArrayList<Long>(lanes);
        for (int lane = 0; lane < lanes; lane++) {
            long value = 0;
            for (int b = 0; b < 16 / lanes; b++) value |= (bytes[(int) address + lane * (16 / lanes) + b] & 255L) << (8 * b);
            result.add(narrow(value));
        }
        return result;
    }
    private List<List<Long>> patterns(String offsetFamily) { return patterns(offsetFamily, false, false); }
    private List<List<Long>> patterns(String offsetFamily, boolean graph) { return patterns(offsetFamily, graph, false); }
    private List<List<Long>> patterns(String offsetFamily, boolean graph, boolean diagnostic) {
        require(offsetFamilies.contains(offsetFamily) && !(graph && diagnostic));
        var values = new ArrayList<List<Long>>();
        if (!floating || graph) {
            for (int lane = 0; lane < lanes; lane++) for (long edge : edges) {
                var row = new ArrayList<>(sentinels); row.set(lane, edge); values.add(row);
            }
        } else {
            var domain = diagnostic ? signalingBits : rawBits;
            for (int index = 0; index < domain.size(); index++) {
                var row = new ArrayList<Long>(lanes);
                for (int lane = 0; lane < lanes; lane++) row.add(domain.get((index + 5 * lane) % domain.size()));
                values.add(row);
            }
        }
        var result = new ArrayList<List<Long>>();
        for (int index = 0; index < values.size(); index++) {
            var row = new ArrayList<Long>();
            row.add((long) (index % (48 / scale(offsetFamily) + 1))); row.addAll(values.get(index)); result.add(row);
        }
        return result;
    }
    Map<String, List<List<Long>>> cases() {
        var result = new LinkedHashMap<String, List<List<Long>>>();
        if (!floating) for (String offsetFamily : offsetFamilies) {
            var rows = new ArrayList<List<Long>>();
            for (int offset = 0; offset <= 48 / scale(offsetFamily); offset++) for (long seed : seeds) rows.add(List.of((long) offset, seed));
            result.put(offsetFamily + "UnitCase", rows);
        }
        for (String offsetFamily : offsetFamilies) for (String operation : operations) if (floating || !operation.equals("Unit")) {
            int selectors = switch (operation) {
                case "Unit" -> 2 * lanes;
                case "Index", "Read" -> floating ? lanes : 0;
                case "Write", "Store", "GraphStore" -> 64;
                default -> 0;
            };
            var domain = patterns(offsetFamily, operation.startsWith("Graph"));
            var rows = new ArrayList<List<Long>>();
            if (selectors == 0) rows.addAll(domain);
            else for (var row : domain) for (int selector = 0; selector < selectors; selector++) rows.add(append(row, selector));
            result.put(offsetFamily + operation + "Case", rows);
        }
        return result;
    }
    long expected(String name, List<Long> input) {
        String operation = operation(name);
        boolean graph = operation.startsWith("Graph");
        int arity = !floating && operation.equals("Unit") ? 2 : lanes + 1
            + (operation.equals("GraphIndex") || !floating && (operation.equals("Index") || operation.equals("Read")) ? 0 : 1);
        require(input.size() == arity && input.getFirst() >= 0 && input.getFirst() <= 48 / scale(name));
        long address = input.getFirst() * scale(name);
        if (!floating && operation.equals("Unit")) {
            long seed = input.get(1);
            var before = new ArrayList<Long>();
            for (long value : new long[]{seed, seed + 17, seed * 3 - 29, seed ^ 0x55aa55aaL}) before.add(narrow(value));
            var after = new ArrayList<>(before);
            after.set(1, narrow(name.startsWith("vector") ? seed ^ 0x80000000L : (before.get(1) & 0xffffffL) | (((seed + 101) & 255L) << 24)));
            return score(before) + 15 * score(after);
        }
        var values = input.subList(1, lanes + 1);
        if (floating && !wide && !graph) for (long value : values) require(value >= 0 && value <= 0xffffffffL);
        var encoded = graph ? encoded(values) : values;
        var storage = moved(initial(), address, encoded);
        if (operation.equals("Unit")) {
            require(input.getLast() >= 0 && input.getLast() < 2L * lanes);
            return narrow(encoded.get((int) (input.getLast() % lanes))
                ^ (input.getLast() == lanes + 1L ? (wide ? Long.MIN_VALUE : 0x80000000L) : 0));
        }
        if (operation.equals("Index") || operation.equals("Read")) {
            if (!floating) return score(loaded(storage, address));
            require(input.getLast() >= 0 && input.getLast() < lanes);
            return loaded(storage, address).get(input.getLast().intValue());
        }
        if (operation.equals("GraphIndex")) return score(values);
        require(input.getLast() >= 0 && input.getLast() <= 63);
        long selected = storage[input.getLast().intValue()] & 255L;
        return wide && !graph ? values.get(0) ^ values.get(1) ^ selected : score(values) * 257 + selected;
    }
    Map<Input, Long> checkedRows(String text) {
        var domain = new ArrayList<Input>();
        for (var entry : cases().entrySet()) for (var row : entry.getValue()) domain.add(new Input(entry.getKey(), row));
        var lines = new ArrayList<>(Arrays.asList(text.split("\r\n|\n|\r", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        require(lines.size() == rowCount && domain.size() == rowCount);
        var result = new LinkedHashMap<Input, Long>();
        for (int index = 0; index < lines.size(); index++) {
            var parts = lines.get(index).split("\t", -1);
            var key = domain.get(index);
            var numbers = new ArrayList<Long>();
            for (int i = 1; i < parts.length; i++) {
                long number = Long.parseLong(parts[i]); require(parts[i].equals(Long.toString(number))); numbers.add(number);
            }
            require(parts[0].equals(key.name()) && numbers.size() == key.arguments().size() + 1
                && numbers.subList(0, numbers.size() - 1).equals(key.arguments()) && numbers.getLast() == expected(key.name(), key.arguments()));
            result.put(key, numbers.getLast());
        }
        require(result.size() == rowCount);
        return result;
    }
    void verify(File root, Map<String, Object> provenance) throws Exception {
        var directory = new File(root, "build/simd-" + family + "-bytearray");
        var expectedRows = checkedRows(Files.readString(new File(directory, "expected.tsv").toPath()));
        if (provenance.get("nativeRows") != null) assertEquals(expectedRows, checkedRows(Files.readString(new File(directory, "oracle.tsv").toPath())));
        var entries = (List<Map<String, Object>>) provenance.get("entries");
        var names = new ArrayList<Object>(); for (var entry : entries) names.add(entry.get("name"));
        assertEquals(new ArrayList<>(cases().keySet()), names);
        for (var entry : entries) {
            var rows = new ArrayList<List<Long>>();
            for (var raw : (List<List<Number>>) entry.get("cases")) {
                var row = new ArrayList<Long>(); for (var value : raw) row.add(value.longValue()); rows.add(row);
            }
            assertEquals(cases().get((String) entry.get("name")), rows);
            assertEquals((long) rows.getFirst().size(), entry.get("arity"));
        }
        var graphs = (List<Map<String, Object>>) provenance.get("graphEntries");
        assertEquals(4, graphs.size());
        var expectedNames = new ArrayList<String>();
        for (String offset : offsetFamilies) { expectedNames.add(offset + "Index" + (floating ? "Graph" : "Worker")); expectedNames.add(offset + "StoreGraph"); }
        names.clear(); for (var graph : graphs) names.add(graph.get("name"));
        assertEquals(expectedNames, names);
        for (var entry : graphs) {
            String name = (String) entry.get("name");
            boolean index = "index".equals(entry.get("operation"));
            String offsetFamily = name.startsWith("vector") ? "vector" : "scalar";
            assertEquals(index ? 2L : lanes + 3L, entry.get("arity"));
            assertEquals((long) scale(name), entry.get("offsetUnitBytes"));
            assertEquals(name.endsWith("StoreGraph") ? "store" : "index", entry.get("operation"));
            var domain = patterns(offsetFamily, floating);
            var graphCases = (List<Map<String, Object>>) entry.get("cases");
            assertEquals(domain.size(), graphCases.size());
            for (int position = 0; position < graphCases.size(); position++) {
                var row = graphCases.get(position);
                var input = domain.get(position);
                var values = input.subList(1, input.size());
                var before = initial();
                var after = moved(before, input.getFirst() * scale(name), floating ? encoded(values) : values);
                assertEquals(input.getFirst(), row.get("offset")); assertEquals(values, row.get("lanes"));
                assertEquals(score(values), row.get("expectedScalar"));
                assertEquals(unsignedBytes(after), row.get("expectedBytes"));
                assertEquals(unsignedBytes(index ? after : before), row.get("initialBytes"));
                String nativeEntry = offsetFamily + (floating ? "Graph" : "") + (index ? "Index" : "Store") + "Case";
                assertEquals(nativeEntry, row.get("nativeEntry")); assertEquals(input, row.get("nativeArguments"));
                if (index) assertEquals(score(values), expectedRows.get(new Input(nativeEntry, input)).longValue());
                else for (int b = 0; b < 64; b++) assertEquals(score(values) * 257 + (after[b] & 255L), expectedRows.get(new Input(nativeEntry, append(input, b))).longValue());
            }
        }
    }
    void controls(File root) throws Exception {
        String text = Files.readString(new File(root, "build/simd-" + family + "-bytearray/expected.tsv").toPath());
        var rows = checkedRows(text); assertEquals(rowCount, rows.size());
        var lines = new ArrayList<>(Arrays.asList(text.replaceFirst("\n+$", "").split("\r\n|\n|\r", -1)));
        var missing = new ArrayList<>(lines.subList(1, lines.size()));
        var duplicate = new ArrayList<>(lines); duplicate.add(lines.getFirst());
        var reversed = new ArrayList<>(lines); Collections.reverse(reversed);
        var replaced = new ArrayList<>(lines); replaced.set(0, lines.get(1));
        var unknown = new ArrayList<>(lines); unknown.set(0, "unknown\t0\t0");
        var wrongValue = new ArrayList<>(lines); wrongValue.set(0, lines.getFirst().substring(0, lines.getFirst().lastIndexOf('\t')) + "\t999");
        var blank = new ArrayList<>(lines); blank.add("");
        for (var bad : List.of(missing, duplicate, reversed, replaced, unknown, wrongValue, blank))
            assertThrows(IllegalArgumentException.class, () -> checkedRows(String.join("\n", bad) + "\n"));
        for (long address : List.of(-1L, 49L, 64L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException.class, () -> loaded(new byte[64], address));
            assertThrows(IllegalArgumentException.class, () -> moved(new byte[64], address, Collections.nCopies(lanes, 0L)));
        }
        var original = initial(); var copy = moved(original, 4, sentinels); var snapshot = loaded(copy, 4);
        Arrays.fill(copy, 4, 20, (byte) 0); assertArrayEquals(initial(), original);
        var narrowed = new ArrayList<Long>(); for (long value : sentinels) narrowed.add(narrow(value));
        assertEquals(narrowed, snapshot);
        for (String name : List.of("vectorIndexCase", "scalarReadCase", "vectorWriteCase")) {
            var good = cases().get(name).getFirst();
            var invalidAddress = new ArrayList<>(good); invalidAddress.set(0, Long.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> expected(name, invalidAddress));
            if (floating || name.endsWith("WriteCase")) {
                var invalidSelector = new ArrayList<>(good); invalidSelector.set(good.size() - 1, name.endsWith("WriteCase") ? 64L : (long) lanes);
                assertThrows(IllegalArgumentException.class, () -> expected(name, invalidSelector));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> expected("unknown", List.of()));
        for (String offsetFamily : offsetFamilies) {
            var domain = patterns(offsetFamily, floating);
            assertEquals(lanes * edges.size(), domain.size());
            var expectedOffsets = new HashSet<Long>(); for (long i = 0; i <= 48 / scale(offsetFamily); i++) expectedOffsets.add(i);
            var observedOffsets = new HashSet<Long>(); for (var row : domain) observedOffsets.add(row.getFirst());
            assertEquals(expectedOffsets, observedOffsets);
            for (int lane = 0; lane < lanes; lane++) for (long edge : edges) {
                var row = domain.get(lane * edges.size() + edges.indexOf(edge));
                assertEquals(edge, row.get(lane + 1).longValue());
                for (int other = 0; other < lanes; other++) if (other != lane) assertEquals(sentinels.get(other), row.get(other + 1));
            }
            for (var row : domain) if (floating) {
                var values = row.subList(1, row.size());
                long magnitude = 0;
                for (int i = 0; i < values.size(); i++) magnitude += Math.abs(values.get(i) * weights.get(i));
                assertTrue(magnitude < (1L << (wide ? 44 : 24)));
                double floatingScore;
                if (wide) {
                    floatingScore = 0;
                    for (int i = 0; i < values.size(); i++) floatingScore += values.get(i).doubleValue() * weights.get(i);
                } else {
                    float sum = 0;
                    for (int i = 0; i < values.size(); i++) sum += values.get(i).floatValue() * weights.get(i);
                    floatingScore = sum;
                }
                assertEquals((double) score(values), floatingScore);
                assertTrue(Math.abs(score(values)) * 257 + 255 < Long.MAX_VALUE);
            }
            for (var entry : cases().entrySet()) if (entry.getKey().startsWith(offsetFamily)) {
                String operation = operation(entry.getKey());
                int selectors = switch (operation) {
                    case "Unit" -> floating ? 2 * lanes : 0;
                    case "Index", "Read" -> floating ? lanes : 0;
                    case "Write", "Store", "GraphStore" -> 64;
                    default -> 0;
                };
                if (selectors > 0) {
                    var groups = new LinkedHashMap<List<Long>, List<List<Long>>>();
                    for (var input : entry.getValue()) groups.computeIfAbsent(input.subList(0, input.size() - 1), key -> new ArrayList<>()).add(input);
                    for (var group : groups.values()) {
                        var wanted = new HashSet<Long>(); for (long i = 0; i < selectors; i++) wanted.add(i);
                        var actual = new HashSet<Long>(); for (var input : group) actual.add(input.getLast());
                        assertEquals(wanted, actual);
                    }
                }
            }
            if (floating) for (var input : patterns(offsetFamily)) {
                var values = input.subList(1, input.size());
                for (int selector = 0; selector < 2 * lanes; selector++) assertEquals(
                    narrow(values.get(selector % lanes) ^ (selector == lanes + 1 ? (wide ? Long.MIN_VALUE : 0x80000000L) : 0)),
                    expected(offsetFamily + "UnitCase", append(input, selector)));
                for (int lane = 0; lane < values.size(); lane++) for (String operation : List.of("Index", "Read"))
                    assertEquals(values.get(lane).longValue(), expected(offsetFamily + operation + "Case", append(input, lane)));
                var bytes = moved(initial(), input.getFirst() * scale(offsetFamily), values);
                for (int b = 0; b < 64; b++) {
                    long answer = expected(offsetFamily + "WriteCase", append(input, b));
                    assertEquals(bytes[b] & 255L, wide ? answer ^ values.get(0) ^ values.get(1) : answer - score(values) * 257);
                }
            }
        }
        if (!floating) {
            var low = new HashSet<Long>(); var high = new HashSet<Long>();
            for (long i = 0; i <= 65535; i++) {
                long value = (((i * 40503 + 97) & 65535L) << 16) | i;
                low.add(value & 65535); high.add(value >>> 16);
                assertEquals(narrow(value), narrow(value + (1L << 32)));
                assertEquals(family.equals("int32x4") ? (long) (int) value : value, narrow(value));
            }
            assertEquals(65536, low.size()); assertEquals(65536, high.size());
        } else {
            for (long value = -65536; value <= 65536; value++) assertEquals(integerEncoding(value), bits(value));
            if (wide) for (int bit = 0; bit <= 40; bit++) for (long delta = -1; delta <= 1; delta++) for (long sign : new long[]{-1, 1}) {
                long value = ((1L << bit) + delta) * sign;
                if (Math.abs(value) <= (1L << 40)) assertEquals(integerEncoding(value), bits(value));
            }
            if (wide) for (int bit = 0; bit <= 63; bit++) for (long raw : new long[]{1L << bit, ~(1L << bit)}) for (int lane = 0; lane < lanes; lane++) {
                var values = new ArrayList<>(Collections.nCopies(lanes, 0L)); values.set(lane, raw);
                for (String offsetFamily : offsetFamilies) for (String operation : List.of("Index", "Read")) {
                    var input = new ArrayList<Long>(); input.add(0L); input.addAll(values); input.add((long) lane);
                    assertEquals(raw, expected(offsetFamily + operation + "Case", input));
                }
            }
            long magnitudeMask = wide ? Long.MAX_VALUE : 0x7fffffffL;
            long exponent = wide ? 0x7ff0000000000000L : 0x7f800000L;
            long quiet = wide ? 1L << 51 : 1L << 22;
            long minNormal = wide ? 1L << 52 : 1L << 23;
            int zeros = 0, subnormals = 0, infinities = 0, quietNaNs = 0;
            for (long raw : rawBits) {
                long magnitude = raw & magnitudeMask;
                if (magnitude == 0) zeros++;
                if (magnitude >= 1 && magnitude < minNormal) subnormals++;
                if (magnitude == exponent) infinities++;
                if (magnitude > exponent && (raw & quiet) != 0) quietNaNs++;
            }
            assertEquals(2, zeros); assertEquals(4, subnormals); assertEquals(2, infinities); assertEquals(2, quietNaNs);
            boolean signaling = true;
            for (long raw : signalingBits) signaling &= (raw & exponent) == exponent && (raw & (minNormal - 1)) != 0 && (raw & quiet) == 0;
            assertTrue(signaling);
            for (String offsetFamily : offsetFamilies) for (int lane = 0; lane < lanes; lane++) {
                var observed = new HashSet<Long>(); for (var row : patterns(offsetFamily)) observed.add(row.get(lane + 1));
                assertEquals(new HashSet<>(rawBits), observed);
            }
            var diagnosticKeys = new ArrayList<Input>();
            for (String offsetFamily : offsetFamilies) for (String operation : List.of("Unit", "Index", "Read", "Write")) {
                int count = operation.equals("Unit") ? 2 * lanes : operation.equals("Write") ? 64 : lanes;
                for (var input : patterns(offsetFamily, false, true)) for (int selector = 0; selector < count; selector++)
                    diagnosticKeys.add(new Input(offsetFamily + operation + "Case", append(input, selector)));
            }
            assertEquals(wide ? 576 : 640, diagnosticKeys.size());
            boolean none = true; for (var key : diagnosticKeys) if (rows.containsKey(key)) { none = false; break; }
            assertTrue(none);
            for (var key : diagnosticKeys) expected(key.name(), key.arguments());
        }
    }
    private long integerEncoding(long value) {
        if (value == 0) return 0;
        long magnitude = Math.abs(value);
        int power = 63 - Long.numberOfLeadingZeros(magnitude);
        int fraction = wide ? 52 : 23, bias = wide ? 1023 : 127;
        return (value < 0 ? (wide ? Long.MIN_VALUE : 1L << 31) : 0)
            | ((long) (power + bias) << fraction) | ((magnitude - (1L << power)) << (fraction - power));
    }
    private List<Long> encoded(List<Long> values) {
        var result = new ArrayList<Long>(); for (long value : values) result.add(bits(value)); return result;
    }
    private static List<Long> unsignedBytes(byte[] bytes) {
        var result = new ArrayList<Long>(); for (byte value : bytes) result.add(value & 255L); return result;
    }
    private static List<Long> append(List<Long> values, long value) {
        var result = new ArrayList<>(values); result.add(value); return result;
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Failed requirement."); }
}
