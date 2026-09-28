// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class ByteStringSortTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/bytestring-sort";
    private final boolean nativeAvailable = "Linux".equals(System.getProperty("os.name")) && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"));
    private Object json(String name) throws Exception { return Json.parse(Files.readString(root.resolve(prefix + "/" + name))); }
    private Map<String, Object> source(String stage) throws Exception { return object(json(stage + ".json")); }
    private ExecutableProgram program(Language language, String backend, Map<String, Object> module) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void inside(CheckedConsumer<Language> action) throws Exception {
        try (var context = Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void install(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target); valid(target);
    }
    private void released(Language language) {
        var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
    }
    private byte[] bytes(ManagedAddress address, int count) { var bytes = new byte[count]; for (int i = 0; i < count; i++) bytes[i] = (byte) address.readWord8(i); return bytes; }
    private List<Object> singleCall(Object value) {
        var calls = OriginalStdioChecks.foreignCalls(value); assertEquals(1, calls.size()); return calls.getFirst();
    }
    private List<Map<String, Object>> fixture() throws Exception {
        var manifest = object(json("manifest.json")); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(list("sortBytes"), manifest.get("entries"));
        assertTrue(CoreMemorySearchForeign.isOriginalByteStringUnit(manifest.get("bytestringUnit"))); assertEquals(false, manifest.get("installedArtifactsHashed"));
        assertTrue(((String) manifest.get("interface")).endsWith("/Data/ByteString/Internal/Type.hi"));
        OriginalStdioChecks.hashes(root.toFile(), manifest.get("inputHashes"), Set.of("compiler/test-fixtures/ByteStringSortAudit.hs", "test/haskell-fixtures/ByteStringSortFixtures.hs",
            "src/test/resources/core/original-bytestring-sort-descriptor.json", "src/main/java/thc/runtime/CoreByteStringSort.java",
            "src/main/java/thc/runtime/ByteStringSort.java", "src/main/java/thc/runtime/ByteStringSortExpression.java"), null);
        OriginalStdioChecks.hashes(root.toFile(), manifest.get("artifactHashes"), Set.of(prefix + "/pre.json", prefix + "/post.json", prefix + "/oracle.json",
            prefix + "/pre-sortBytes.audit.json", prefix + "/post-sortBytes.audit.json"), prefix + "/");
        var retained = object(object(Json.parse(Files.readString(root.resolve("src/test/resources/core/original-bytestring-sort-descriptor.json")))).get("fps_sort"));
        for (var stage : list("pre", "post")) {
            var call = singleCall(source(stage)); var metadata = object(call.get(6)); var actual = object(metadata.get("foreignCall")); var target = object(actual.get("target"));
            assertEquals(manifest.get("bytestringUnit"), target.get("unit")); assertEquals(without(retained, "target"), without(actual, "target"));
            assertEquals(without(object(retained.get("target")), "unit"), without(target, "unit"));
            var arguments = new ArrayList<Object>(); for (var argument : expression(call.get(2))) {
                var annotation = CoreRepresentations.metadata(expression(argument)); arguments.add(annotation == null ? null : annotation.get("rep"));
            }
            assertTrue(CoreByteStringSort.validate(metadata, arguments, expression(call.get(3)), metadata.get("rep")));
            var audit = object(json(stage + "-sortBytes.audit.json")); assertEquals(true, audit.get("accepted")); assertEquals(list(), audit.get("issues")); assertEquals(list(), audit.get("missingGlobals"));
            var proof = new ArrayCoreEvidence(source(stage), "sortBytes"); assertEquals(1, proof.getBindings().size()); assertEquals(1, proof.guestLambdas(proof.getRoot().get("expr")).size());
        }
        var rows = objects(json("oracle.json")); assertTrue(rows.stream().anyMatch(row -> Long.valueOf(0).equals(row.get("count"))));
        assertTrue(rows.stream().anyMatch(row -> (Long) row.get("count") >= 256L));
        for (var row : rows) {
            var input = HexFormat.of().parseHex((String) row.get("input")); int offset = ((Long) row.get("offset")).intValue(), count = ((Long) row.get("count")).intValue();
            var model = input.clone(); var slice = new int[count]; for (int i = 0; i < count; i++) slice[i] = input[offset + i] & 255;
            Arrays.sort(slice); for (int i = 0; i < count; i++) model[offset + i] = (byte) slice[i];
            assertArrayEquals(model, HexFormat.of().parseHex((String) row.get("bytes")), (String) row.get("case"));
        }
        return rows;
    }
    @Test void originalSortMatchesNativeAtFirstInstalledEntry() throws Exception {
        var rows = fixture();
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) inside(language -> {
            var p = program(language, backend, with(CoreModules.reachable(source(stage), "sortBytes", true), "instrument", true)); var target = p.entryTarget("sortBytes");
            CheckedConsumer<Boolean> exercise = compiled -> {
                for (int kind = 0; kind <= (nativeAvailable ? 3 : 2); kind++) for (var row : rows) {
                    var input = HexFormat.of().parseHex((String) row.get("input"));
                    var base = switch (kind) {
                        case 0 -> ManagedAddress.fromByteArray(new byte[input.length]);
                        case 3 -> Language.currentState().getNativeAllocations().malloc(input.length);
                        default -> ManagedAddress.fromAllocation(ManagedAllocation.mutable(input.length, 8, kind == 2));
                    };
                    try {
                        for (int i = 0; i < input.length; i++) base.writeWord8(i, input[i]);
                        long before = ((Number) p.diagnostics().get("compiledEntries")).longValue(); if (compiled) valid(target);
                        var result = Calls.target(target, new Object[]{0L, base.plus((Long) row.get("offset")), row.get("count")}); assertEquals(row.get("count"), result);
                        assertArrayEquals(HexFormat.of().parseHex((String) row.get("bytes")), bytes(base, input.length), stage + "/" + backend + "/" + kind + "/" + row.get("case"));
                        if (compiled) { assertEquals(before + 1, ((Number) p.diagnostics().get("compiledEntries")).longValue()); valid(target); }
                        released(language);
                    } finally { if (kind == 3) Language.currentState().getNativeAllocations().free(base); }
                }
            };
            exercise.accept(false); install(target); exercise.accept(true);
        });
    }
    @Test void boundsStorageAndLifetimesRejectBeforeMutation() throws Exception {
        fixture();
        for (var backend : list("ast", "bytecode")) inside(language -> {
            var target = program(language, backend, CoreModules.reachable(source("pre"), "sortBytes", true)).entryTarget("sortBytes");
            byte[] original = {9, 8, 7, 6}; var storage = ManagedAddress.fromByteArray(original.clone());
            record Range(ManagedAddress address, long count) {}
            for (var range : list(new Range(storage, -1L), new Range(storage, Long.MIN_VALUE), new Range(storage, Long.MAX_VALUE),
                new Range(storage.plus(-1), 1), new Range(storage.plus(4), 1), new Range(storage.plus(2), 3), new Range(ManagedAddress.nullAddress(), 1),
                new Range(ManagedAddress.unownedNumeric(1), 0), new Range(ManagedAddress.fromHex("09080706"), 4))) {
                assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, range.address(), range.count()})); assertArrayEquals(original, bytes(storage, 4));
            }
            assertEquals(0L, Calls.target(target, new Object[]{0L, ManagedAddress.nullAddress(), 0L}));
            assertEquals(0L, Calls.target(target, new Object[]{0L, storage.plus(4), 0L}));
            var cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8)); cells.writeAddressElementIndex(0, storage);
            assertThrows(RuntimeFault.class, () -> ByteStringSort.sort(cells, 8)); assertTrue(cells.readAddressElementIndex(0).sameLocation(storage));
            if (nativeAvailable) {
                var nativeAddress = Language.currentState().getNativeAllocations().malloc(4);
                inside(ignored -> assertThrows(RuntimeFault.class, () -> ByteStringSort.sort(nativeAddress, 4)));
                Language.currentState().getNativeAllocations().free(nativeAddress); assertThrows(RuntimeFault.class, () -> ByteStringSort.sort(nativeAddress, 4));
            }
            released(language);
        });
    }
    @Test void exactOriginalDescriptorAndVoidStateRemainRequired() throws Exception {
        fixture();
        for (var backend : list("ast", "bytecode")) inside(language -> {
            var original = singleCall(source("pre"));
            for (int variant = 0; variant <= 10; variant++) {
                var candidate = OriginalStdioChecks.rawModule(original, source("pre"), null); var call = singleCall(candidate);
                var descriptor = object(object(call.get(6)).get("foreignCall"));
                switch (variant) {
                    case 0 -> object(descriptor.get("target")).put("unit", "ghc-internal");
                    case 1 -> object(descriptor.get("target")).put("unit", "bytestring-0.12.1.0-inplace");
                    case 2 -> descriptor.put("safety", "safe"); case 3 -> descriptor.put("convention", "capi"); case 4 -> descriptor.put("arity", 2L);
                    case 5 -> objects(descriptor.get("argumentReps")).get(1).put("primReps", list("Int64Rep"));
                    case 6 -> expression(call.get(3)).set(0, true); case 7 -> expression(call.get(1)).set(1, "entry");
                    case 8 -> expression(object(descriptor.get("resultRep")).get("components")).clear();
                    case 9 -> object(descriptor.get("target")).put("unit", "bytestring-0.12.2.0-inplace:forged");
                    case 10 -> object(descriptor.get("target")).put("isFunction", false);
                }
                assertThrows(RuntimeFault.class, () -> program(language, backend, candidate), backend + "/variant=" + variant);
            }
            var raw = program(language, backend, OriginalStdioChecks.rawModule(original, source("pre"), null));
            var storage = ManagedAddress.fromByteArray(new byte[]{3, 2, 1});
            assertThrows(RuntimeFault.class, () -> Calls.target(raw.entryTarget("entry"), new Object[]{0L, storage, 3L, 7L}));
            assertArrayEquals(new byte[]{3, 2, 1}, bytes(storage, 3)); released(language);
        });
    }
}
