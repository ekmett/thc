// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static thc.runtime.ThreadInventoryCoreEvidence.*;

@SuppressWarnings("unchecked")
class ScalarMemoryUtilitiesTest {
    private final File root = new File(System.getProperty("thc.projectRoot")),
                       directory = new File(root, "build/scalar-memory-utilities");
    private final List<String> names = List.of("memoryCase", "pinCase", "thawCase", "shrinkCase", "differenceCase",
        "remainderCase", "numericDifference", "numericRemainder");
    private final Set<String> primitives = Set.of("copyAddrToAddr#", "setAddrRange#", "minusAddr#", "remAddr#",
        "isByteArrayPinned#", "isMutableByteArrayPinned#", "isByteArrayWeaklyPinned#",
        "isMutableByteArrayWeaklyPinned#", "unsafeThawByteArray#", "shrinkSmallMutableArray#");
    private Map<String, Object> json(File file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(file.toPath()));
    }
    private Context context(boolean nativeAccess) {
        return Context.newBuilder("thc")
            .allowNativeAccess(nativeAccess)
            .allowExperimentalOptions(true)
            .option("compiler.Inlining", "false")
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .build();
    }
    private record Row(String entry, List<Long> args, long answer) {}
    private List<Row> rows() {
        var result = new ArrayList<Row>();
        for (var range : new int[][] {{0, 4, 16}, {4, 0, 16}, {8, 16, 8}, {0, 0, 32}, {32, 32, 0}})
            for (long value : new long[] {-257L, -1L, 0L, 1L, 256L, 511L})
                for (int selected : new int[] {0, 8, 15, 23, 31}) {
                    int from = range[0], to = range[1], count = range[2];
                    var bytes = new ArrayList<Long>(Collections.nCopies(32, value & 255L));
                    bytes.set(8, 99L);
                    var snapshot = new ArrayList<>(bytes.subList(from, from + count));
                    for (int i = 0; i < snapshot.size(); i++) bytes.set(to + i, snapshot.get(i));
                    result.add(new Row("memoryCase",
                        List.of((long) from, (long) to, (long) count, value, (long) selected), bytes.get(selected)));
                }
        for (long mode = 0; mode <= 2; mode++) result.add(new Row("pinCase", List.of(mode), mode == 0L ? 0 : 15));
        for (long value : new long[] {-257L, -1L, 0L, 1L, 127L, 255L, 256L, 511L})
            result.add(new Row("thawCase", List.of(value), (value & 255L) + 256L * ((value + 1) & 255L)));
        for (long size = 0; size <= 8; size++)
            result.add(new Row("shrinkCase", List.of(size), size == 0L ? 0 : size * 100 + 77));
        for (long left : new long[] {0L, 1L, 31L, 64L})
            for (long right : new long[] {0L, 1L, 31L, 64L})
                result.add(new Row("differenceCase", List.of(left, right), left - right));
        for (long offset : new long[] {0L, 1L, 7L, 31L, 64L})
            for (long divisor : new long[] {1L, 2L, 8L, 16L, 64L})
                result.add(new Row("remainderCase", List.of(offset, divisor), offset % divisor));
        for (long left : new long[] {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE})
            for (long right : new long[] {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE})
                result.add(new Row("numericDifference", List.of(left, right), left - right));
        for (long bits : new long[] {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE})
            for (long divisor : new long[] {Long.MIN_VALUE, -7L, -1L, 1L, 2L, 7L, Long.MAX_VALUE})
                result.add(new Row("numericRemainder", List.of(bits, divisor), Long.remainderUnsigned(bits, divisor)));
        return result;
    }
    private long count(ExecutableProgram program) {
        return ((Number) program.diagnostics().get("compiledEntries")).longValue();
    }
    private void call(Row row, RootCallTarget entry, HandoffState handoff, String label) {
        var args = new Object[row.args().size() + 1];
        args[0] = 0L;
        for (int i = 0; i < row.args().size(); i++) args[i + 1] = row.args().get(i);
        try {
            assertEquals(row.answer(), Calls.target(entry, args), label + "/" + row);
        } finally {
            assertEquals(0, handoff.getArguments().getDepth());
            assertEquals(0, handoff.getResults().getDepth());
            assertEquals(0, handoff.getArguments().retainedReferences());
            assertEquals(0, handoff.getResults().retainedReferences());
            assertNull(handoff.getPending());
        }
    }
    @Test
    void nativeModelsAndBothBackendsIncludeTheFirstInstalledEntry() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(names, manifest.get("entries"));
        assertEquals(271L, manifest.get("nativeRows"));
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    Files.readAllBytes(new File(root, item.getKey()).toPath())));
                assertEquals(item.getValue(), digest, "Stale scalar-memory input/artifact: " + item.getKey());
            }
        var expected = rows();
        var oracle = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(directory, "oracle.tsv").toPath())) {
            var fields = line.split(" ", -1);
            var args = new ArrayList<Long>();
            for (int i = 1; i < fields.length - 1; i++) args.add(Long.parseLong(fields[i]));
            oracle.add(new Row(fields[0], args, Long.parseLong(fields[fields.length - 1])));
        }
        assertEquals(expected, oracle, "Independent Java memory/arithmetic model");
        for (var stage : List.of("pre", "post")) {
            var audit = json(new File(directory, stage + "/audit.json"));
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("issues"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            var actualPrimitives = new ArrayList<Object>();
            for (var p : (List<Map<String, Object>>) audit.get("primitives")) actualPrimitives.add(p.get("name"));
            assertTrue(actualPrimitives.containsAll(primitives));
            var module = thc.CoreCbdFixtures.read(new File(directory, stage + "/core/ScalarMemoryUtilities.cbd").toPath());
            // Native pointer remainder observes the actual aligned allocation address; all other cases retain the
            // native-access-denied context.
            for (var backend : List.of("ast", "bytecode"))
                for (var name : names) try (var context = context(name.equals("remainderCase"))) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var configured = System.getenv("THC_EXPECT_HANDOFF_MODE");
                            boolean requestedMode;
                            if (configured == null)
                                requestedMode = Boolean.getBoolean(Handoff.HANDOFF_PROPERTY);
                            else if (configured.equals("true"))
                                requestedMode = true;
                            else if (configured.equals("false"))
                                requestedMode = false;
                            else
                                throw new IllegalArgumentException(
                                    "The string doesn't represent a boolean value: " + configured);
                            assertEquals(requestedMode, Boolean.getBoolean(Handoff.HANDOFF_PROPERTY));
                            assertEquals(requestedMode, language.getHandoffLayouts().getEnabled());
                            var linked = new LinkedHashMap<>(CoreModules.reachable(module, "main:ScalarMemoryUtilities." + name));
                            linked.put("instrument", true);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked)
                                                                              : new BytecodeProgram(language, linked);
                            var entry = program.entryTarget("main:ScalarMemoryUtilities." + name);
                            var corpus = new ArrayList<Row>();
                            for (var row : expected)
                                if (row.entry().equals(name))
                                    corpus.add(row);
                            var handoff = language.getHandoffState().get();
                            var label = stage + "/" + backend;
                            for (var row : corpus) call(row, entry, handoff, label);
                            // Compile the discovered targets without prescribing GHC's lambda or
                            // root layout. Installation must not execute the guest or settle calls.
                            var active = targets(entry);
                            var interpreted = interpretedCalls(active);
                            long beforeCompilation = count(program);
                            install(active);
                            assertEquals(beforeCompilation, count(program));
                            assertEquals(interpreted, interpretedCalls(active));
                            for (var row : corpus.reversed()) {
                                long before = count(program);
                                call(row, entry, handoff, label);
                                assertTrue(count(program) > before,
                                    label + "/" + name + " enters compiled code on the first installed call");
                                assertEquals(interpreted, interpretedCalls(active), "No interpreted settling call");
                                assertTrue(valid(entry), "Installed entry remains valid");
                            }
                            assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        } finally {
                            context.leave();
                        }
                    }
        }
    }
    @Test
    void addressRangesAndPointerCellsAreCheckedBeforeEffects() {
        var allocation = PinnedMemory.allocate(32, 8);
        assertTrue(allocation.isPinned());
        assertFalse(allocation.resized(40).isPinned(), "GHC resize growth returns ordinary unpinned storage");
        assertFalse(ManagedAllocation.mutable(32, 8).isPinned());
        assertSame(allocation, ManagedByteArray.freezeGuest(allocation));
        var base = ManagedAddress.fromAllocation(allocation);
        base.fill(32, 0x1ff);
        var bytes = new ArrayList<Long>();
        for (long i = 0; i < 32; i++) bytes.add(allocation.readByte(i));
        assertEquals(Collections.nCopies(32, 255L), bytes);
        for (long count : new long[] {-1L, Long.MIN_VALUE, Long.MAX_VALUE, 33L}) {
            assertThrows(RuntimeFault.class, () -> base.fill(count, 0));
            assertThrows(RuntimeFault.class, () -> base.moveTo(base, count));
        }
        base.plus(32).fill(0, 0);
        assertSame(base, base.moveTo(base, 32));
        var pointer = base.plus(24);
        allocation.writeAddressByteOffset(0, pointer);
        base.moveTo(base.plus(8), 8);
        assertSame(pointer, allocation.readAddressByteOffset(8));
        assertThrows(RuntimeFault.class, () -> base.plus(9).fill(2, 0));
        assertSame(pointer, allocation.readAddressByteOffset(8));
        base.plus(8).fill(8, 0);
        assertSame(pointer, allocation.readAddressByteOffset(0));
        assertThrows(RuntimeFault.class, () -> allocation.readAddressByteOffset(8));
        var immutable = ManagedAddress.fromHex("010203");
        assertThrows(RuntimeFault.class, () -> immutable.fill(0, 0));
        assertThrows(RuntimeFault.class, () -> base.moveTo(immutable, 1));
        assertThrows(RuntimeFault.class, () -> base.remainder(0));
        assertEquals(-31L, base.difference(base.plus(31)));
        var raw = ManagedAddress.fromByteArray(new byte[4]);
        assertThrows(RuntimeFault.class, () -> raw.difference(base));
    }
    @Test
    void shrinkPreservesAliasesAndDropsTruncatedLazyReferences() {
        var marker = new Object();
        var storage = ManagedSmallArray.allocate(8, marker);
        var frozen = ManagedSmallArray.freeze(storage);
        var backing = storage.getElements();
        for (long size : new long[] {-1L, 9L, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> storage.shrink(size));
        boolean all = true;
        for (var item : backing) all &= item == marker;
        assertTrue(all);
        storage.shrink(3);
        assertSame(backing, storage.getElements());
        assertSame(storage, frozen);
        assertEquals(3L, ManagedSmallArray.size(frozen));
        assertSame(marker, ManagedSmallArray.read(frozen, 2));
        all = true;
        for (int i = 3; i < backing.length; i++) all &= backing[i] == null;
        assertTrue(all);
        assertThrows(RuntimeFault.class, () -> ManagedSmallArray.read(storage, 3));
        assertThrows(RuntimeFault.class, () -> ManagedSmallArray.write(storage, 3, marker));
        assertThrows(RuntimeFault.class, () -> ManagedSmallArray.slice(storage, 2, 2));
        storage.shrink(0);
        all = true;
        for (var item : backing) all &= item == null;
        assertTrue(all);
        assertEquals(0L, ManagedSmallArray.size(storage));
    }
    @Test
    void nativeRemainderRequiresPermissionWhileManagedOffsetsDoNot() {
        for (boolean nativeAccess : new boolean[] {false, true}) try (var context = context(nativeAccess)) {
                context.initialize("thc");
                context.enter();
                try {
                    var pinned = ManagedAddress.fromAllocation(PinnedMemory.allocate(64, 64)).plus(31);
                    if (nativeAccess)
                        assertEquals(31L, pinned.remainder(64));
                    else
                        assertEquals("Native address projection requires native access",
                            assertThrows(RuntimeFault.class, () -> pinned.remainder(64)).getMessage());
                    var managed = ManagedAddress.fromByteArray(new byte[64]).plus(31);
                    assertEquals(31L, managed.remainder(64));
                    assertEquals(3L, managed.remainder(7));
                    assertThrows(RuntimeFault.class, () -> managed.remainder(0));
                } finally {
                    context.leave();
                }
            }
    }
    @Test
    void nativeFillAndMoveKeepBorrowLifetimeAndContextChecks() {
        assumeTrue(System.getProperty("os.name").equals("Linux")
            && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        try (var context = context(true)) {
            context.initialize("thc");
            context.enter();
            try {
                var registry = ManagedNativeAllocations.current(null);
                var base = registry.malloc(32);
                try {
                    base.fill(32, 511);
                    base.writeWord8(0, 7);
                    base.moveTo(base.plus(1), 31);
                    assertEquals(7L, base.readWord8(1));
                    assertEquals(255L, base.readWord8(31));
                    assertEquals(31L, base.plus(31).difference(base));
                    assertThrows(RuntimeFault.class, () -> base.fill(33, 0));
                    try (var other = context(true)) {
                        other.initialize("thc");
                        other.enter();
                        try {
                            assertThrows(RuntimeFault.class, () -> base.fill(1, 0));
                        } finally {
                            other.leave();
                        }
                    }
                } finally {
                    registry.free(base);
                }
                assertThrows(RuntimeFault.class, () -> base.fill(0, 0));
                assertThrows(RuntimeFault.class, () -> base.difference(base));
            } finally {
                context.leave();
            }
        }
    }
}
