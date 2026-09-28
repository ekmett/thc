// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import java.io.File;
import java.lang.foreign.*;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

/** Real products and native observations; only the C caller destroys C-owned output. */
@SuppressWarnings("unchecked")
public class LibyamlNativeProductsTest {
    @TempDir public Path temporary;
    private String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private String digest(File file) throws Exception { return digest(Files.readAllBytes(file.toPath())); }
    private record Original(Map<String, Object> module, String hash) {}
    /** Decode the original two modules and their exact original binding order. */
    private List<Map<String, Object>> selectedModules(List<Original> originals) throws Exception {
        String path = System.getProperty("thc.libyamlPackages"); var originalModules = new ArrayList<Map<String, Object>>(); for (var original : originals) originalModules.add(original.module); if (path == null) return originalModules;
        String format = System.getProperty("thc.libyamlFormat"); if (!Objects.equals(format, "json") && !Objects.equals(format, "compact")) throw new IllegalArgumentException();
        var directory = Objects.requireNonNull(CoreUnitDirectory.read((Map<?, ?>) Json.parse(Files.readString(Path.of(path))))); var units = new LinkedHashSet<Object>(); var names = new HashSet<Object>(); for (var original : originals) { units.add(original.module.get("unit")); names.add(original.module.get("module")); } assertEquals(1, units.size()); var unit = units.getFirst(); var selected = new ArrayList<CoreUnitDirectory.ModuleRecord>(); var selectedNames = new HashSet<String>();
        for (var record : directory.getModules()) if (Objects.equals(record.getUnit(), unit)) { selected.add(record); selectedNames.add(record.getName()); } assertEquals(names, selectedNames);
        try (var sources = directory.open(false, false)) {
            var decoded = new ArrayList<Map<String, Object>>(); long count = 0;
            for (var original : originals) {
                CoreUnitDirectory.ModuleRecord record = null; for (var candidate : selected) if (Objects.equals(candidate.getName(), original.module.get("module"))) { assertNull(record); record = candidate; } Objects.requireNonNull(record);
                assertEquals(original.hash, record.getSha256(), "selected module must be the exact native fixture capture"); assertEquals(format.equals("compact"), record.getStorage() instanceof CoreUnitDirectory.CompactStorage); var ids = new ArrayList<String>(); var bindings = new ArrayList<Map<String, Object>>(); var observedIds = new ArrayList<Object>();
                for (var binding : (List<Map<String, Object>>) original.module.get("bindings")) ids.add((String) binding.get("id")); for (var id : ids) { var binding = Objects.requireNonNull(sources.binding(id), "Missing original binding " + id); bindings.add(binding); observedIds.add(binding.get("id")); } assertEquals(ids, observedIds); var module = new LinkedHashMap<>(sources.metadata(record)); module.put("bindings", bindings); decoded.add(module); count += bindings.size();
            }
            if (format.equals("compact")) { assertEquals(0, sources.counters().size(), "compact execution must not fall back to JSON"); var counters = sources.compactCounters(); assertEquals(2, counters.size(), "unrelated package modules stay unopened"); long bindings = 0, bytes = 0; for (var counter : counters) { var statistics = counter.statistics(); bindings += statistics.decodedBindings(); bytes += statistics.debugBytesRead() + statistics.hashBytesRead(); } assertEquals(count, bindings); assertEquals(0L, bytes); }
            else { assertEquals(0, sources.compactCounters().size()); var counters = sources.counters(); assertEquals(1, counters.size(), "unrelated package units stay unopened"); long bindings = 0, bytes = 0; for (var counter : counters) { var statistics = counter.statistics(); bindings += statistics.decodedBindings(); bytes += statistics.hashBytesScanned(); } assertEquals(count, bindings); assertEquals(0L, bytes); }
            return decoded;
        }
    }
    private static final class Entry extends RootNode {
        final PackageScalarCall call; final NarrowInteger narrow; @Child PackageScalarAccess access;
        Entry(Language language, PackageScalarCall call) { super(language); this.call = call; narrow = NarrowInteger.fromRep(call.getResult()); access = new PackageScalarAccess(call); }
        @Override public Object execute(VirtualFrame frame) {
            if (call.getResult().equals("void")) { access.executeVoid(frame.getArguments(), kotlin.Unit.INSTANCE); return kotlin.Unit.INSTANCE; }
            if (call.getResult().equals("AddrRep")) return access.executeAddress(frame.getArguments(), kotlin.Unit.INSTANCE);
            if (narrow != null) { int value = access.executeInt(frame.getArguments(), kotlin.Unit.INSTANCE); return narrow == NarrowInteger.WORD32 ? Integer.toUnsignedLong(value) : (long) value; }
            return access.executeLong(frame.getArguments(), kotlin.Unit.INSTANCE);
        }
    }
    @FunctionalInterface private interface ParserAction { void run(ManagedAddress parser, ManagedAddress event) throws Throwable; }
    private final class Session {
        final Language.State owner; final Map<String, RootCallTarget> entries = new LinkedHashMap<>(); final List<String> observed; int returnedReads;
        Session(Language language, Language.State owner, PackageScalarLink link, List<String> observed) { this.owner = owner; this.observed = observed; for (var signature : link.getAbi()) entries.put(signature.getSymbol(), new Entry(language, new PackageScalarCall(link, signature)).getCallTarget()); }
        Object call(String symbol, Object... args) { return entries.get(symbol).call(args); }
        void success(String symbol, Object... args) { assertEquals(1L, call(symbol, args), symbol); }
        String marks(ManagedAddress mark) { var values = new ArrayList<String>(); for (var name : List.of("get_mark_index", "get_mark_line", "get_mark_column")) values.add(call(name, mark).toString()); return String.join(",", values); }
        void withParser(ManagedAddress address, int inputSize, ParserAction action) throws Throwable {
            var parser = owner.getNativeAllocations().malloc(480); var event = owner.getNativeAllocations().malloc(104);
            try { success("yaml_parser_initialize", parser); try { call("yaml_parser_set_input_string", parser, address, (long) inputSize); action.run(parser, event); } finally { call("yaml_parser_delete", parser); } } finally { owner.getNativeAllocations().free(event); owner.getNativeAllocations().free(parser); }
        }
        byte[] inspect(ManagedAddress pointer, long count) {
            assertTrue(count >= 1 && count <= 100000); assertNotNull(pointer.returnedAddress$org_intelligence_thc()); assertNull(pointer.nativeAllocation$org_intelligence_thc(), "C retains allocation ownership"); assertThrows(RuntimeFault.class, pointer::availableBytes); var numeric = ManagedAddress.Companion.unownedNumeric$org_intelligence_thc(pointer.toNativeBits()); assertTrue(pointer.sameLocation(numeric)); assertTrue(numeric.sameLocation(pointer)); assertEquals(0L, pointer.difference(numeric)); assertEquals(0L, numeric.difference(pointer)); assertEquals(-1, pointer.compareWithinAllocation(numeric.plus(1))); assertEquals(1, numeric.plus(1).compareWithinAllocation(pointer)); assertThrows(RuntimeFault.class, () -> numeric.readWord8(0)); assertThrows(RuntimeFault.class, () -> numeric.writeWord8(0, 1)); assertThrows(RuntimeFault.class, () -> pointer.requireRange(0, -1, false)); assertThrows(RuntimeFault.class, () -> pointer.requireRange(Long.MAX_VALUE, 1, false)); assertTrue(pointer.sameLocation(pointer.plus(0))); assertEquals(1L, pointer.plus(1).difference(pointer)); var bytes = new byte[(int) count]; pointer.copyToByteArray(bytes, 0, count); assertEquals((long) bytes[0] & 255, pointer.readWord8(0)); returnedReads++; return bytes;
        }
        void parse(ManagedAddress parser, ManagedAddress event, int iteration) {
            observed.add("parse\t" + (iteration + 1)); long kind;
            do { success("yaml_parser_parse", parser, event); kind = (Long) call("get_event_type", event); try { var start = (ManagedAddress) call("get_start_mark", event); assertTrue(start.sameLocation(event.plus(56))); assertEquals(48L, start.availableBytes(), "known interior alias keeps its actual event allocation bound"); assertThrows(RuntimeFault.class, () -> start.readWord8(48)); assertEquals(call("get_mark_index", start), ManagedAddressRead.WORD64.read(start, 0)); observed.add("marks\t" + marks((ManagedAddress) call("get_start_mark", event)) + "\t" + marks((ManagedAddress) call("get_end_mark", event))); var bytes = kind != 6L ? new byte[0] : inspect((ManagedAddress) call("get_scalar_value", event), (Long) call("get_scalar_length", event)); observed.add(kind + "\t" + bytes.length + "\t" + HexFormat.of().formatHex(bytes)); if (kind == 6L) { var tag = (ManagedAddress) call("get_scalar_tag", event); assertEquals(0L, tag.cStringLength(), "implicit input tag is the helper's static empty C string"); } } finally { call("yaml_event_delete", event); } } while (kind != 2L);
        }
        void encode(ManagedAddress parser, ManagedAddress event) throws Throwable {
            var emitter = owner.getNativeAllocations().malloc(432); var buffer = owner.getNativeAllocations().malloc(16);
            try { success("yaml_emitter_initialize", emitter); call("buffer_init", buffer); try {
                try { call("my_emitter_set_output", emitter, buffer); long kind; do { success("yaml_parser_parse", parser, event); kind = (Long) call("get_event_type", event); success("yaml_emitter_emit", emitter, event); /* consumes event */ } while (kind != 2L); } finally { call("yaml_emitter_delete", emitter); }
                // Caller-owned output remains live after emitter destruction.
                long size = (Long) call("get_buffer_used", buffer); assertTrue(size > 4096, "exercise the original growing encoder buffer"); var pointer = (ManagedAddress) call("get_buffer_buff", buffer); var bytes = inspect(pointer, size);
                pointer.withNativeIOWindow$org_intelligence_thc(size, false, window -> { assertEquals(pointer.toNativeBits(), window.address()); assertEquals(size, window.byteSize(), "window is the IO request, not an allocation extent"); assertArrayEquals(bytes, window.toArray(ValueLayout.JAVA_BYTE)); return kotlin.Unit.INSTANCE; });
                var path = temporary.resolve("encoded.yaml"); var pathBytes = path.toString().getBytes(StandardCharsets.UTF_8); var filename = ManagedAddress.Companion.fromByteArray(Arrays.copyOf(pathBytes, pathBytes.length + 1)); long descriptor = owner.getFiles().open(filename, 3, ForeignSafety.UNSAFE); assertTrue(descriptor >= 3);
                try { assertTrue(pointer.hasNativeIOStorage$org_intelligence_thc()); assertEquals(size, owner.getFiles().write(descriptor, pointer, size, ForeignSafety.UNSAFE)); assertArrayEquals(bytes, Files.readAllBytes(path)); assertEquals(0L, owner.getFiles().seek(descriptor, 0, 0, ForeignSafety.UNSAFE)); pointer.writeWord8(0, 0); assertEquals(size, owner.getFiles().read(descriptor, pointer, size, ForeignSafety.UNSAFE)); assertEquals((long) bytes[0] & 255, pointer.readWord8(0)); } finally { assertEquals(0L, owner.getFiles().close(descriptor, ForeignSafety.UNSAFE)); }
                var managed = ManagedAllocation.mutable(size, 8, false); pointer.copyToByteArray(managed, 0, size); assertArrayEquals(bytes, managed.copyBytesOut(0, size)); pointer.copyFromByteArray(managed, 0, size); observed.add("encoded\t" + bytes.length + "\t" + HexFormat.of().formatHex(bytes));
            } finally { // Only this test owns this original helper allocation.
                var pointer = (ManagedAddress) call("get_buffer_buff", buffer); var linker = Linker.nativeLinker(); linker.downcallHandle(linker.defaultLookup().find("free").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)).invokeWithArguments(MemorySegment.ofAddress(pointer.toNativeBits()));
            } } finally { owner.getNativeAllocations().free(buffer); owner.getNativeAllocations().free(emitter); }
        }
    }
    @Test public void originalNativeParserEventsAndEncoderMatchThroughReturnedPointers() throws Throwable {
        var root = new File(System.getProperty("thc.projectRoot")); var directory = new File(System.getProperty("thc.libyamlFixture", new File(root, "build/libyaml-native").getPath())); var zip = new File(System.getProperty("thc.libyamlBundle")); var source = new File(root, "compiler/test-fixtures/OriginalLibyamlNative.hs"); String sourceHash = digest(source); assertEquals(sourceHash, Files.readString(new File(directory, "native-source.sha256").toPath()).split(" ", 2)[0]); var manifest = (Map<?, ?>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath())); assertEquals(sourceHash, manifest.get("sourceSha256")); assertEquals(digest(zip), manifest.get("bundleSha256")); assertEquals(digest(new File(directory, "native.tsv")), manifest.get("nativeSha256")); assertEquals(digest(new File(directory, "input.yaml")), manifest.get("inputSha256"));
        var originals = new ArrayList<Original>(); try (var archive = new ZipFile(zip)) { var entries = archive.entries(); while (entries.hasMoreElements()) { var entry = entries.nextElement(); if (entry.getName().startsWith("core/") && entry.getName().endsWith(".json")) try (var input = archive.getInputStream(entry)) { var bytes = input.readAllBytes(); originals.add(new Original((Map<String, Object>) Json.parse(new String(bytes, StandardCharsets.UTF_8)), digest(bytes))); } } }
        var modules = selectedModules(originals); var names = new HashSet<Object>(); for (var module : modules) names.add(module.get("module")); assertEquals(Set.of("Paths_libyaml", "Text.Libyaml"), names); var merged = CoreModules.merge(modules); var links = (List<PackageScalarLink>) merged.get("packageScalarLinks"); assertEquals(1, links.size()); var link = links.getFirst(); var originalModules = new ArrayList<Map<String, Object>>(); for (var original : originals) originalModules.add(original.module); var originalLinks = (List<PackageScalarLink>) CoreModules.merge(originalModules).get("packageScalarLinks"); assertEquals(1, originalLinks.size()); assertTrue(link.same(originalLinks.getFirst()), "execution must use the same original native component and declared ABI"); assertEquals("llvm-embedded-elf", link.getFormat()); assertEquals(50, link.getAbi().size());
        var expected = Files.readAllLines(new File(directory, "native.tsv").toPath()); var input = Files.readAllBytes(new File(directory, "input.yaml").toPath()); var observed = new ArrayList<String>(); int returnedReads;
        try (var context = NativeFileProvider.Companion.createContext$org_intelligence_thc(Set.of(), ContextProfile.SYNCHRONOUS_TEST, FfiMode.NATIVE, false)) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var owner = Language.currentState(null); owner.getPackageCbits().link(link); var session = new Session(language, owner, link, observed); var returnSlot = owner.getNativeAllocations().malloc(16);
            try { for (long value : new long[] {0L, 2147483647L, 2147483648L, 4294967295L}) { for (int i = 0; i <= 3; i++) returnSlot.writeWord8(12L + i, (value >>> (i * 8)) & 255); observed.add("unsigned-result\t" + session.call("get_buffer_used", returnSlot)); } } finally { owner.getNativeAllocations().free(returnSlot); }
            var markSlot = owner.getNativeAllocations().malloc(24); try { for (long value : new long[] {0L, 2147483647L, 2147483648L, 4294967295L, 4294967296L, -1L}) { for (int offset : new int[] {0, 8, 16}) for (int i = 0; i <= 7; i++) markSlot.writeWord8(offset + i, (value >>> (i * 8)) & 255); observed.add("mark-result\t" + session.marks(markSlot)); } } finally { owner.getNativeAllocations().free(markSlot); }
            // Pinned storage deliberately survives many native calls.
            var storage = PinnedMemory.INSTANCE.allocate(input.length, 8); var address = ManagedAddress.Companion.fromAllocation(storage); for (int i = 0; i < input.length; i++) address.writeWord8(i, (long) input[i] & 255);
            for (int i = 0; i < 3; i++) { final int iteration = i; session.withParser(address, input.length, (parser, event) -> session.parse(parser, event, iteration)); } assertEquals(expected.subList(0, expected.size() - 1), observed, "three complete parses before encoder execution"); session.withParser(address, input.length, session::encode); Reference.reachabilityFence(storage); returnedReads = session.returnedReads;
        } finally { context.leave(); } }
        assertEquals(expected, observed); assertTrue(returnedReads > 3, "exercise returned C storage across parser calls and encoder growth");
    }
}
