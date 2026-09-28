// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import thc.ContextProfile;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static thc.Main.withContextProfile;
import static thc.runtime.ScalarValueTestSupport.*;

class TextCbitsTest {
    @BeforeEach void supportedCbitsPlatform() {
        assumeTrue(System.getProperty("os.name").equals("Linux") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")), "Original text cbits currently require Linux x86_64");
    }
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/text-cbits");
    private Map<String, Object> json(String name) throws Exception { return object(Json.parse(Files.readString(new File(directory, name).toPath()))); }
    private Map<String, Object> module(String stage) throws Exception { return json(stage + "-core/TextCbitsAudit.json"); }
    private record Row(String name, byte[] bytes, long offset, long length, long count, String result) {}
    private List<Row> rows() throws Exception {
        var inputs = Files.readAllLines(new File(directory, "inputs.tsv").toPath()); var lines = Files.readAllLines(new File(directory, "oracle.tsv").toPath());
        assertEquals(520, lines.size()); assertEquals(inputs, lines.stream().map(line -> line.substring(0, line.indexOf('\t'))).toList());
        var rows = new ArrayList<Row>();
        for (var line : lines) {
            var pair = line.split("\t"); var fields = pair[0].split(" "); assertEquals(5, fields.length);
            var row = new Row(fields[0], HexFormat.of().parseHex(fields[1]), Long.parseLong(fields[2]), Long.parseLong(fields[3]), Long.parseUnsignedLong(fields[4]), pair[1]);
            var data = Arrays.copyOfRange(row.bytes, (int) row.offset, (int) (row.offset + row.length)); String expected;
            if (row.name.equals("reverse")) {
                var points = new String(data, StandardCharsets.UTF_8).codePoints().toArray(); var reversed = new StringBuilder();
                for (int i = points.length - 1; i >= 0; i--) reversed.appendCodePoint(points[i]); expected = hex(reversed.toString().getBytes(StandardCharsets.UTF_8));
            } else if (row.name.equals("memchr")) {
                int found = -1; for (int i = 0; i < data.length; i++) if (data[i] == (byte) row.count) { found = i; break; } expected = Integer.toString(found);
            } else {
                var points = new String(data, StandardCharsets.UTF_8).codePoints().toArray(); long result;
                if (Long.compareUnsigned(row.count, points.length) > 0) {
                    // C narrows the unsigned negated remainder to ssize_t before branching.
                    long remainder = points.length - row.count; result = remainder >= 0 ? row.length - remainder : -(long) points.length;
                } else {
                    int size = 0; for (int i = 0; i < (int) row.count; i++) size += new String(Character.toChars(points[i])).getBytes(StandardCharsets.UTF_8).length; result = size;
                }
                expected = Long.toString(result);
            }
            assertEquals(expected, row.result, "independent UTF-8/search model " + pair[0]); rows.add(row);
        }
        return rows;
    }
    private static String hex(byte[] bytes) { return bytes.length == 0 ? "-" : HexFormat.of().formatHex(bytes); }
    private static Context context() { return context(true, true); }
    private static Context context(boolean inlining, boolean nativeAccess) {
        return withContextProfile(Context.newBuilder("thc").allowNativeAccess(nativeAccess), ContextProfile.SYNCHRONOUS_TEST).option("compiler.Inlining", Boolean.toString(inlining)).build();
    }
    private static ExecutableProgram program(Language language, Map<String, Object> source, String backend) { return backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source); }
    private static void compiled(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), "installed code remains valid"); }
    @Test void originalInstalledCallsMatchNativeWithInlining() throws Exception { nativeResults(true); }
    @Test void originalInstalledCallsMatchNativeAcrossResidualCalls() throws Exception { nativeResults(false); }
    private void nativeResults(boolean inlining) throws Exception {
        originalProvenanceAndNativeCorpusRemainExact(); var rows = new LinkedHashMap<String, List<Row>>();
        for (var row : rows()) rows.computeIfAbsent(row.name, ignored -> new ArrayList<>()).add(row);
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context(inlining, true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var group : rows.entrySet()) {
                    var name = group.getKey(); var corpus = group.getValue(); var entryName = switch (name) { case "memchr" -> "textMemchr"; case "reverse" -> "textReverse"; default -> "textMeasure"; };
                    var source = with(CoreModules.reachable(module(stage), entryName), "instrument", true); var program = program(language, source, backend);
                    var entry = program.entryValue(entryName); var host = program.hostEntryTarget(name.equals("reverse") ? 3 : 4);
                    var targets = objects(source.get("bindings")).stream().map(binding -> program.entryTarget((String) binding.get("id"))).toList();
                    CheckedConsumer<Row> call = row -> {
                        Object[] arguments = name.equals("reverse") ? new Object[]{row.bytes, row.offset, row.length} : new Object[]{row.bytes, row.offset, row.length, row.count};
                        var result = Calls.target(host, new Object[]{entry, arguments}); String actual;
                        if (!name.equals("reverse")) actual = String.valueOf(result);
                        else if (result instanceof byte[] bytes) actual = hex(bytes);
                        else if (result instanceof ManagedAllocation allocation) actual = hex(bytes(allocation));
                        else throw new IllegalStateException("Expected original reverse ByteArray#, got " + result);
                        assertEquals(row.result, actual, stage + "/" + backend + "/" + name + "/inlining=" + inlining + "/" + row.offset + "/" + row.length + "/" + row.count);
                    };
                    for (var row : corpus) call.accept(row);
                    for (var target : targets.reversed()) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); compiled(target); }
                    for (var row : corpus.reversed()) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); call.accept(row);
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, "first and subsequent installed calls: " + stage + "/" + backend + "/" + name);
                        for (var target : targets) compiled(target);
                    }
                    assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                }
            } finally { context.leave(); }
        }
    }
    @Test void originalProvenanceAndNativeCorpusRemainExact() throws Exception {
        var manifest = json("manifest.json"); assertEquals(520L, manifest.get("nativeRows")); assertTrue(CoreTextForeign.supportedUnit(manifest.get("unit")));
        var registrations = Files.readAllLines(new File(directory, "logs/original-registration.stdout").toPath()).stream().filter(line -> line.startsWith("id:")).toList();
        assertEquals(1, registrations.size()); var recordedUnit = registrations.getFirst().substring(registrations.getFirst().indexOf(':') + 1).trim();
        assertEquals(recordedUnit, manifest.get("unit"), "Preserve the original installed package registration");
        assertEquals(Set.of("test/fixtures/compiler/TextCbitsAudit.hs", "test/fixtures/compiler/TextCbitsNative.hs", "test/haskell-fixtures/TextCbitsFixtures.hs", "bin/core_original_foreign.py",
            "bin/audit-core.py", "bin/core-capabilities.json", "third-party/pinned/text-2.1.3/cbits/utils.c", "third-party/pinned/text-2.1.3/cbits/measure_off.c",
            "third-party/pinned/text-2.1.3/cbits/reverse.c", "third-party/pinned/text-2.1.3/LICENSE", "third-party/pinned/openbsd-memchr-1.8.c", "src/main/c/text-api.c", "bin/build-cbits.py"), object(manifest.get("inputHashes")).keySet());
        for (var key : list("inputHashes", "artifactHashes")) {
            var hashes = object(manifest.get(key)); assertFalse(hashes.isEmpty());
            for (var hash : hashes.entrySet()) assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), hash.getKey());
        }
        rows();
        for (var stage : list("pre", "post")) {
            var audit = json(stage + "-audit.json"); assertEquals(true, audit.get("accepted")); assertEquals(list(), audit.get("issues")); assertEquals(list(), audit.get("missingGlobals"));
            var calls = foreignApps(module(stage));
            assertEquals(Set.of(recordedUnit), calls.stream().map(app -> target(app).get("unit")).collect(Collectors.toSet()));
            assertEquals(Set.of("_hs_text_memchr", "_hs_text_measure_off", "_hs_text_reverse"), calls.stream().map(app -> target(app).get("symbol")).collect(Collectors.toSet()));
        }
    }
    private static Map<String, Object> target(List<Object> app) { return object(object(object(app.get(6)).get("foreignCall")).get("target")); }
    private static List<List<Object>> foreignApps(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof Map<?, ?> fields) for (var item : fields.values()) result.addAll(foreignApps(item));
        else if (value instanceof List<?> items) {
            if (!items.isEmpty() && "app".equals(items.getFirst()) && items.size() > 6 && items.get(6) instanceof Map<?, ?> metadata && metadata.containsKey("foreignCall")) result.add(expression(items));
            for (var item : items) result.addAll(foreignApps(item));
        }
        return result;
    }
    @Test void installedTextReleaseIdentityHasABoundedSuffixGrammar() throws Exception {
        var accepted = list("text-2.1.3-inplace", "text-2.1.3-e182", "text-2.1.3-119b");
        var rejected = list(null, "text-2.1.3", "text-2.1.3-", "text-2.1.2-e182", "text-2.1.4-e182", "text-2.1.3-e182-extra", "text-2.1.3-e182\n", "text-2.1.3-e182 ", "other-text-2.1.3-e182", "text-2.1.3-foreign", "text-2.1.3-e182:forged");
        for (var unit : accepted) assertTrue(CoreTextForeign.supportedUnit(unit), unit); for (var unit : rejected) assertFalse(CoreTextForeign.supportedUnit(unit), unit);
        var units = new ArrayList<>(accepted); units.addAll(rejected);
        for (var unit : units) for (var backend : list("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var source = module("pre");
                for (var app : foreignApps(source)) target(app).put("unit", unit);
                if (accepted.contains(unit)) program(language, source, backend); else assertThrows(RuntimeFault.class, () -> program(language, source, backend));
            } finally { context.leave(); }
        }
    }
    @Test void exactInstalledIdentityStateAndArrayProofsAreRequired() throws Exception {
        for (var backend : list("ast", "bytecode")) for (var name : list("textMeasure", "textReverse")) for (var variant : list("unit", "safety", "arity", "array", "result")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var source = CoreModules.reachable(module("post"), name);
                var apps = foreignApps(source); assertEquals(1, apps.size()); var call = object(object(apps.getFirst().get(6)).get("foreignCall"));
                switch (variant) {
                    case "unit" -> object(call.get("target")).put("unit", "other-text");
                    case "safety" -> call.put("safety", "safe");
                    case "arity" -> call.put("suppliedArity", 4L);
                    case "array" -> object(expression(call.get("argumentReps")).getFirst()).put("primReps", list("AddrRep"));
                    case "result" -> object(call.get("resultRep")).put("primReps", list("Word64Rep"));
                }
                assertThrows(RuntimeFault.class, () -> program(language, source, backend));
            } finally { context.leave(); }
        }
    }
    @Test void originalHeapAndPinnedStorageRemainDirectReadOnlyViews() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                for (var allocation : list(ManagedAllocation.mutable(32, 8), PinnedMemory.allocate(32, 16))) {
                    var segment = allocation.nativeSegment(); for (long i = 0; i <= 31; i++) allocation.writeByte(i, 97); allocation.writeByte(7, 98);
                    assertEquals(3L, ManagedText.invoke(TextForeignOp.MEMCHR, allocation, 4, 10, 98));
                    assertEquals(5L, ManagedText.invoke(TextForeignOp.MEASURE, allocation, 4, 10, 5)); allocation.writeByte(7, 97);
                    assertEquals(-1L, ManagedText.invoke(TextForeignOp.MEMCHR, allocation, 4, 10, 98)); assertSame(segment, allocation.nativeSegment());
                    for (long i = 0; i <= 31; i++) assertEquals(97L, allocation.readByte(i));
                }
            } finally { context.leave(); }
        }
    }
    private static byte[] filled(int size, int value) { var bytes = new byte[size]; Arrays.fill(bytes, (byte) value); return bytes; }
    @Test void rangeCarrierPointerAndPermissionGuardsFailBeforeCallingC() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var bytes = filled(8, 97);
                for (var range : list(new long[]{-1, 0}, new long[]{0, -1}, new long[]{9, 0}, new long[]{7, 2}, new long[]{Long.MAX_VALUE, 1}))
                    assertThrows(RuntimeFault.class, () -> ManagedText.invoke(TextForeignOp.MEASURE, bytes, range[0], range[1], 0));
                assertEquals(0L, ManagedText.invoke(TextForeignOp.MEASURE, bytes, 8, 0, 0)); assertEquals(-1L, ManagedText.invoke(TextForeignOp.MEMCHR, bytes, 8, 0, 97));
                assertThrows(RuntimeFault.class, () -> ManagedText.invoke(TextForeignOp.MEMCHR, bytes, 0, 8, 256));
                assertThrows(RuntimeFault.class, () -> ManagedText.invoke(TextForeignOp.MEMCHR, ManagedAddress.fromByteArray(bytes), 0, 8, 97));
                var pointer = PinnedMemory.allocate(8, 8); pointer.writeAddressByteOffset(0, ManagedAddress.fromHex("00"));
                assertThrows(RuntimeFault.class, () -> ManagedText.invoke(TextForeignOp.MEASURE, pointer, 0, 0, 0)); assertArrayEquals(filled(8, 97), bytes);
            } finally { context.leave(); }
        }
        try (var context = context(true, false)) {
            context.initialize("thc"); context.enter();
            try { assertThrows(RuntimeFault.class, () -> ManagedText.invoke(TextForeignOp.MEMCHR, new byte[]{1}, 0, 1, 1)); } finally { context.leave(); }
        }
    }
    private static Object storage(byte[] bytes, int kind) {
        if (kind == 0) return bytes.clone();
        var allocation = kind == 1 ? ManagedAllocation.mutable(bytes.length, 8) : PinnedMemory.allocate(bytes.length, 16);
        for (int i = 0; i < bytes.length; i++) allocation.writeByte(i, bytes[i]); return allocation;
    }
    private static byte[] bytes(Object value) {
        if (value instanceof byte[] bytes) return bytes;
        if (value instanceof ManagedAllocation allocation) { var bytes = new byte[(int) allocation.getSize()]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) allocation.readByte(i); return bytes; }
        throw new IllegalStateException("storage");
    }
    @Test void reverseBorrowsDistinctWritableStorageAndPreservesBounds() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var input = "xA\u0000é中🙂z".getBytes(StandardCharsets.UTF_8); var expected = "🙂中é\u0000A".getBytes(StandardCharsets.UTF_8);
                var expectedWithTail = Arrays.copyOf(expected, expected.length + 3); Arrays.fill(expectedWithTail, expected.length, expectedWithTail.length, (byte) 0x55);
                for (int sourceKind = 0; sourceKind <= 2; sourceKind++) for (int destinationKind = 0; destinationKind <= 2; destinationKind++) {
                    var source = storage(input, sourceKind); var destination = storage(filled(expected.length + 3, 0x55), destinationKind);
                    ManagedText.reverse(destination, source, 1, expected.length); assertArrayEquals(input, bytes(source)); assertArrayEquals(expectedWithTail, bytes(destination));
                    ManagedText.reverse(destination, source, input.length, 0); assertArrayEquals(expectedWithTail, bytes(destination));
                }
                var output = filled(16, 0x55);
                for (var range : list(new long[]{-1, 1}, new long[]{0, -1}, new long[]{input.length, 1}, new long[]{Long.MAX_VALUE, 2}, new long[]{0, Long.MAX_VALUE})) assertThrows(RuntimeFault.class, () -> ManagedText.reverse(output, input, range[0], range[1]));
                assertThrows(RuntimeFault.class, () -> ManagedText.reverse(new byte[1], input, 0, 2)); assertThrows(RuntimeFault.class, () -> ManagedText.reverse(input, input, 0, 1));
                var allocation = ManagedAllocation.mutable(16, 8); var alias = allocation.rawBytesIfPointerFree();
                assertThrows(RuntimeFault.class, () -> ManagedText.reverse(allocation, alias, 0, 1)); assertThrows(RuntimeFault.class, () -> ManagedText.reverse(alias, allocation, 0, 1));
                allocation.shrink(2); assertThrows(RuntimeFault.class, () -> ManagedText.reverse(output, allocation, 1, 2)); assertThrows(RuntimeFault.class, () -> ManagedText.reverse(allocation, input, 0, 3));
                var immutable = ManagedAllocation.immutable(new byte[16], 8); assertThrows(RuntimeFault.class, () -> ManagedText.reverse(immutable, input, 0, 1));
                var pointers = ManagedAllocation.mutable(16, 8); pointers.writeAddressByteOffset(0, ManagedAddress.fromHex("00"));
                assertThrows(RuntimeFault.class, () -> ManagedText.reverse(output, pointers, 0, 1)); assertThrows(RuntimeFault.class, () -> ManagedText.reverse(pointers, input, 0, 1));
                assertThrows(RuntimeFault.class, () -> ManagedText.reverse(output, ManagedAddress.fromByteArray(input), 0, 1)); assertArrayEquals(filled(16, 0x55), output);
            } finally { context.leave(); }
        }
        try (var context = context(true, false)) {
            context.initialize("thc"); context.enter();
            try { assertThrows(RuntimeFault.class, () -> ManagedText.reverse(new byte[1], new byte[]{1}, 0, 1)); } finally { context.leave(); }
        }
    }
    @Test void nativeAddressesIncludingForeignOwnersAreNotByteArrayCarriers() {
        try (var first = context()) {
            first.initialize("thc"); first.enter(); ManagedAddress address;
            try { address = Language.currentState().getNativeAllocations().malloc(8); } finally { first.leave(); }
            try {
                try (var second = context()) {
                    second.initialize("thc"); second.enter();
                    try {
                        assertThrows(RuntimeFault.class, () -> ManagedText.invoke(TextForeignOp.MEMCHR, address, 0, 8, 0));
                        assertEquals(0L, ManagedText.invoke(TextForeignOp.MEMCHR, new byte[]{37}, 0, 1, 37));
                    } finally { second.leave(); }
                }
            } finally { first.enter(); try { Language.currentState().getNativeAllocations().free(address); } finally { first.leave(); } }
        }
    }
}
