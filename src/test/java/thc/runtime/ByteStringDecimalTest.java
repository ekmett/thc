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
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.runtime.ScalarValueTestSupport.*;

class ByteStringDecimalTest {
    private Map<String,Object> cbd(String path) throws Exception { return CoreCbdFixtures.read(root.resolve(prefix + "/" + path)); }
    private String entryId(String name) { return "main:ByteStringDecimalAudit." + name; }
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/bytestring-decimal";
    private final List<String> entries = list("decimal", "padded18");
    private final boolean nativeAvailable = "Linux".equals(System.getProperty("os.name")) && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"));
    private Object json(String name) throws Exception { return Json.parse(Files.readString(root.resolve(prefix + "/" + name))); }
    private Map<String, Object> source(String stage) throws Exception { return object(cbd(stage + ".cbd")); }
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
    private byte[] bytes(ManagedAddress address) { return bytes(address, 48); }
    private byte[] bytes(ManagedAddress address, int count) { var bytes = new byte[count]; for (int i = 0; i < count; i++) bytes[i] = (byte) address.readWord8(i); return bytes; }
    private byte[] sentinel(int count) { var bytes = new byte[count]; Arrays.fill(bytes, (byte) 0xa5); return bytes; }
    @Test void directWritersKeepBytesAndReleaseNativeBorrowsAfterFailures() throws Exception {
        inside(language -> {
            for (boolean nativeStorage : nativeAvailable ? new boolean[]{false, true} : new boolean[]{false}) {
                var address = nativeStorage ? Language.currentState().getNativeAllocations().malloc(24)
                    : ManagedAddress.fromByteArray(sentinel(24));
                try {
                    for (int i = 0; i < 24; i++) address.writeWord8(i, 0xa5);
                    assertTrue(ByteStringDecimal.signed(Long.MIN_VALUE, address).sameLocation(address.plus(20)));
                    assertEquals("-9223372036854775808", new String(bytes(address, 20), StandardCharsets.US_ASCII));
                    assertEquals(0xa5L, address.readWord8(20));
                    ByteStringDecimal.padded18(17, address);
                    assertEquals("000000000000000017", new String(bytes(address, 18), StandardCharsets.US_ASCII));
                    var before = bytes(address, 24);
                    assertThrows(RuntimeFault.class, () -> ByteStringDecimal.padded18(-1, address));
                    assertThrows(RuntimeFault.class, () -> ByteStringDecimal.padded18(17, address.plus(7)));
                    assertThrows(RuntimeFault.class, () -> ByteStringDecimal.signed(Long.MIN_VALUE, address.plus(5)));
                    assertArrayEquals(before, bytes(address, 24));
                } finally {
                    // Free rejects this thread's live borrow: success proves exceptional cleanup released it.
                    if (nativeStorage) Language.currentState().getNativeAllocations().free(address);
                }
            }
        });
    }
    @Test void borrowCleanupRetainsPrimarySuppressedAndCloseOnlyFailures() throws Exception {
        if (!nativeAvailable) return;
        var close = ManagedNativeAllocations.Owner.Borrow.class.getDeclaredMethod("closeAfter", Throwable.class);
        close.setAccessible(true);
        inside(language -> {
            var allocation = Language.currentState().getNativeAllocations().malloc(24);
            var borrow = allocation.nativeAllocation().borrow();
            var primary = new IllegalStateException("original writer failure");
            var failure = new AtomicReference<Throwable>();
            try {
                var other = new Thread(() -> {
                    try { close.invoke(borrow, primary); }
                    catch (Throwable error) { failure.set(error); }
                });
                other.start(); other.join(); assertNull(failure.get());
                assertEquals(1, primary.getSuppressed().length);
                assertInstanceOf(RuntimeFault.class, primary.getSuppressed()[0]);
                assertEquals("Native allocation borrow belongs to another thread", primary.getSuppressed()[0].getMessage());
                assertThrows(RuntimeFault.class, () -> Language.currentState().getNativeAllocations().free(allocation));
                var closeOnly = new Thread(() -> {
                    try { close.invoke(borrow, (Object) null); }
                    catch (InvocationTargetException error) { failure.set(error.getCause()); }
                    catch (Throwable error) { failure.set(error); }
                });
                closeOnly.start(); closeOnly.join();
                assertInstanceOf(RuntimeFault.class, failure.get());
                assertEquals("Native allocation borrow belongs to another thread", failure.get().getMessage());
                close.invoke(borrow, primary);
                assertEquals(1, primary.getSuppressed().length, "Successful close does not alter the primary failure");
            } finally {
                borrow.close(); Language.currentState().getNativeAllocations().free(allocation);
            }
        });
    }
    private List<Map<String, Object>> fixture() throws Exception {
        var manifest = object(json("manifest.json")); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(entries, manifest.get("entries"));
        assertTrue(CoreMemorySearchForeign.isOriginalByteStringUnit(manifest.get("bytestringUnit"))); assertEquals(false, manifest.get("installedArtifactsHashed"));
        assertTrue(((String) manifest.get("interface")).endsWith("/Data/ByteString/Internal/Type.hi"));
        OriginalStdioChecks.hashes(root.toFile(), manifest.get("inputHashes"), Set.of("t/fixtures/compiler/ByteStringDecimalAudit.hs", "t/haskell-fixtures/ByteStringDecimalFixtures.hs",
            "src/test/resources/core/original-bytestring-decimal-descriptors.json", "src/main/java/thc/runtime/CoreByteStringDecimal.java", "src/main/java/thc/runtime/ByteStringDecimal.java",
            "src/main/java/thc/runtime/ByteStringDecimalOp.java", "src/main/java/thc/runtime/ByteStringDecimalExpression.java"), null);
        var artifacts = new HashSet<>(list(prefix + "/pre.cbd", prefix + "/post.cbd", prefix + "/oracle.json"));
        for (var stage : list("pre", "post")) for (var entry : entries) artifacts.add(prefix + "/" + stage + "-" + entry + ".audit.json");
        OriginalStdioChecks.hashes(root.toFile(), manifest.get("artifactHashes"), artifacts, prefix + "/");
        var retained = object(Json.parse(Files.readString(root.resolve("src/test/resources/core/original-bytestring-decimal-descriptors.json"))));
        for (var stage : list("pre", "post")) {
            var calls = OriginalStdioChecks.foreignCalls(source(stage)); assertEquals(2, calls.size());
            for (var call : calls) {
                var metadata = object(call.get(6)); var actual = object(metadata.get("foreignCall")); var target = object(actual.get("target"));
                assertEquals(manifest.get("bytestringUnit"), target.get("unit")); var expected = object(retained.get(target.get("symbol")));
                assertEquals(without(expected, "target"), without(actual, "target")); assertEquals(without(object(expected.get("target")), "unit"), without(target, "unit"));
                var arguments = new ArrayList<Object>(); for (var argument : expression(call.get(2))) {
                    var annotation = CoreRepresentations.metadata(expression(argument)); arguments.add(annotation == null ? null : annotation.get("rep"));
                }
                var operation = Objects.requireNonNull(CoreByteStringDecimal.validate(metadata, arguments, expression(call.get(3)), metadata.get("rep")));
                assertEquals(operation.getAddressResult() ? 2 : 1, expression(object(actual.get("resultRep")).get("components")).size());
            }
            for (var entry : entries) {
                var audit = object(json(stage + "-" + entry + ".audit.json")); assertEquals(true, audit.get("accepted")); assertEquals(list(), audit.get("issues")); assertEquals(list(), audit.get("missingGlobals"));
                var proof = new ArrayCoreEvidence(source(stage),entryId(entry)); assertEquals(1, proof.getBindings().size());
                assertEquals(1, proof.guestLambdas(proof.getRoot().get("expr")).size(), "One typed consumer root");
            }
        }
        var rows = objects(json("oracle.json")); var counts = new HashMap<Object, Integer>(); for (var row : rows) counts.merge(row.get("entry"), 1, Integer::sum);
        assertEquals(map("decimal", 19, "padded18", 9), counts);
        assertTrue(rows.stream().anyMatch(row -> "decimal".equals(row.get("entry")) && Long.valueOf(Long.MIN_VALUE).equals(row.get("input"))));
        assertTrue(rows.stream().anyMatch(row -> "decimal".equals(row.get("entry")) && Long.valueOf(Long.MAX_VALUE).equals(row.get("input"))));
        for (var row : rows) {
            long value = (Long) row.get("input"); var decimal = Long.toString(value);
            var expected = row.get("entry").equals("decimal") ? decimal : "0".repeat(Math.max(0, 18 - decimal.length())) + decimal;
            var nativeBytes = HexFormat.of().parseHex((String) row.get("bytes")); assertEquals((long) expected.length(), row.get("end"));
            assertEquals(expected, new String(nativeBytes, 7, expected.length(), StandardCharsets.US_ASCII));
            for (int i = 0; i < 7; i++) assertEquals((byte) 0xa5, nativeBytes[i]);
            for (int i = 7 + expected.length(); i < nativeBytes.length; i++) assertEquals((byte) 0xa5, nativeBytes[i], "No NUL or writes past end");
        }
        return rows;
    }
    @Test void genuineDecimalWritersMatchNativeOnFirstInstalledCalls() throws Exception {
        var rows = fixture();
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) inside(language -> {
            for (var entry : entries) {
                var p = program(language, backend, with(CoreModules.reachable(source(stage),entryId(entry), true), "instrument", true)); var target = p.entryTarget(entryId(entry));
                CheckedConsumer<Boolean> exercise = compiled -> {
                    for (int kind = 0; kind <= (nativeAvailable ? 3 : 2); kind++) for (var row : rows) if (entry.equals(row.get("entry"))) {
                        var base = switch (kind) {
                            case 0 -> ManagedAddress.fromByteArray(new byte[48]);
                            case 3 -> Language.currentState().getNativeAllocations().malloc(48);
                            default -> ManagedAddress.fromAllocation(ManagedAllocation.mutable(48, 8, kind == 2));
                        };
                        try {
                            for (int i = 0; i < 48; i++) base.writeWord8(i, 165);
                            var address = base.plus(7); long before = ((Number) p.diagnostics().get("compiledEntries")).longValue(); if (compiled) valid(target);
                            var result = callScalarTestTarget(target, new Object[]{0L, row.get("input"), address});
                            assertArrayEquals(HexFormat.of().parseHex((String) row.get("bytes")), bytes(base), stage + "/" + backend + "/" + entry + "/" + kind + "/" + row.get("input"));
                            if (entry.equals("decimal")) {
                                var end = (ManagedAddress) result; assertTrue(end.sameLocation(address.plus((Long) row.get("end")))); assertEquals(row.get("end"), end.difference(address));
                                end.writeWord8(0, 33); assertEquals(33L, base.readWord8(7 + (Long) row.get("end")), "End pointer retains the caller allocation");
                            } else assertEquals(18L, result);
                            if (compiled) { assertEquals(before + 1, ((Number) p.diagnostics().get("compiledEntries")).longValue()); valid(target); }
                            released(language);
                        } finally { if (kind == 3) Language.currentState().getNativeAllocations().free(base); }
                    }
                };
                exercise.accept(false); install(target); exercise.accept(true);
            }
        });
    }
    @Test void domainBoundsAndOwnershipFailBeforeWrites() throws Exception {
        fixture();
        for (var backend : list("ast", "bytecode")) inside(language -> {
            for (var entry : entries) {
                var p = program(language, backend, CoreModules.reachable(source("pre"),entryId(entry), true)); var target = p.entryTarget(entryId(entry));
                var writable = ManagedAddress.fromByteArray(sentinel(48));
                for (var address : list(writable.plus(48), writable.plus(-1), ManagedAddress.nullAddress(),
                    ManagedAddress.fromHex("a5".repeat(48)), ManagedAddress.unownedNumeric(1))) {
                    assertThrows(RuntimeFault.class, () -> callScalarTestTarget(target, new Object[]{0L, 10L, address})); assertArrayEquals(sentinel(48), bytes(writable));
                }
                int length = entry.equals("decimal") ? 19 : 17; var shortBuffer = ManagedAddress.fromByteArray(sentinel(length));
                assertThrows(RuntimeFault.class, () -> callScalarTestTarget(target, new Object[]{0L, entry.equals("decimal") ? Long.MIN_VALUE : 0L, shortBuffer}));
                for (byte value : bytes(shortBuffer, length)) assertEquals((byte) 0xa5, value);
                if (entry.equals("padded18")) for (long invalid : list(Long.MIN_VALUE, -1L, 1_000_000_000_000_000_000L, Long.MAX_VALUE)) {
                    var error = assertThrows(RuntimeFault.class, () -> callScalarTestTarget(target, new Object[]{0L, invalid, writable.plus(7)}));
                    assertTrue(Objects.requireNonNull(error.getMessage()).contains("0 <= value < 10^18")); assertArrayEquals(sentinel(48), bytes(writable));
                }
                released(language);
            }
            var cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(32, 8)); var referent = ManagedAddress.fromByteArray(new byte[]{1});
            cells.writeAddressElementIndex(0, referent); assertThrows(RuntimeFault.class, () -> ByteStringDecimal.signed(10, cells));
            assertTrue(cells.readAddressElementIndex(0).sameLocation(referent));
            if (nativeAvailable) {
                var allocation = Language.currentState().getNativeAllocations().malloc(32); var end = ByteStringDecimal.signed(7, allocation);
                inside(ignored -> assertThrows(RuntimeFault.class, () -> ByteStringDecimal.signed(8, end)));
                Language.currentState().getNativeAllocations().free(allocation); assertThrows(RuntimeFault.class, () -> ByteStringDecimal.signed(8, end));
            }
        });
    }
    @Test void exactOriginalAbiAndStateRemainRequired() throws Exception {
        fixture();
        for (var backend : list("ast", "bytecode")) inside(language -> {
            for (var original : OriginalStdioChecks.foreignCalls(source("pre"))) {
                for (int variant = 0; variant <= 8; variant++) {
                    var candidate = OriginalStdioChecks.rawModule(original, source("pre"), null); var calls = OriginalStdioChecks.foreignCalls(candidate);
                    assertEquals(1, calls.size()); var call = calls.getFirst(); var descriptor = object(object(call.get(6)).get("foreignCall"));
                    switch (variant) {
                        case 0 -> object(descriptor.get("target")).put("unit", "ghc-internal");
                        case 1 -> object(descriptor.get("target")).put("unit", "bytestring-0.12.1.0-inplace");
                        case 2 -> descriptor.put("safety", "safe"); case 3 -> descriptor.put("convention", "capi"); case 4 -> descriptor.put("arity", 2L);
                        case 5 -> objects(descriptor.get("argumentReps")).get(0).put("primReps", list("Word64Rep"));
                        case 6 -> expression(call.get(3)).set(0, true); case 7 -> expression(call.get(1)).set(1, "entry");
                        case 8 -> expression(object(descriptor.get("resultRep")).get("components")).remove(0);
                    }
                    assertThrows(RuntimeFault.class, () -> callScalarTestTarget(program(language, backend, candidate).entryTarget("entry"), new Object[]{0L, 1L, ManagedAddress.fromByteArray(sentinel(48)), Unit.INSTANCE}), backend + "/variant=" + variant);
                }
                var raw = program(language, backend, OriginalStdioChecks.rawModule(original, source("pre"), null));
                var address = ManagedAddress.fromByteArray(sentinel(48));
                assertThrows(RuntimeFault.class, () -> callScalarTestTarget(raw.entryTarget("entry"), new Object[]{0L, 1L, address, 7L}));
                assertArrayEquals(sentinel(48), bytes(address)); released(language);
            }
        });
    }
}
