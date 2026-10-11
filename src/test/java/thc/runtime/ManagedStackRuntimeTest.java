// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.api.source.Source;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.lang.ref.WeakReference;
import java.nio.ByteOrder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Live Truffle frames with synthetic metadata/layouts, not native GHC frame equivalence. */
public class ManagedStackRuntimeTest {
    private final String platform = (Set.of("arm64", "aarch64").contains(System.getProperty("os.arch").toLowerCase(Locale.ROOT)) ? "aarch64" : "x86_64") + (System.getProperty("os.name").startsWith("Mac") ? "-osx" : System.getProperty("os.name").startsWith("Windows") ? "-windows" : "-linux");
    private final String endian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big";
    private TargetLayout layout() { return layout(Map.of(), "stack-service-test"); }
    private TargetLayout layout(Map<String, ?> changes) { return layout(changes, "stack-service-test"); }
    private TargetLayout layout(Map<String, ?> changes, String abi) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("schema", 1); fields.put("profiled", false); fields.put("wordBytes", 8); fields.put("targetPlatform", platform); fields.put("tablesNextToCode", true); fields.put("endianness", endian);
        Object[][] offsets = {
            {"infoTableBytes",16},{"infoTablePtrsOffset",0},{"infoTablePtrsBytes",4},{"infoTableNptrsOffset",4},{"infoTableNptrsBytes",4},
            {"infoTableTypeOffset",8},{"infoTableTypeBytes",4},{"infoTableSrtOffset",12},{"infoTableSrtBytes",4},
            // Deliberately unaligned byte offsets and padding, not primop element indices.
            {"infoProvEntBytes",91},{"infoProvBytes",64},{"infoProvEntInfoOffset",3},{"infoProvEntProvOffset",19},
            {"infoProvNameOffset",0},{"infoProvDescOffset",8},{"infoProvDescBytes",4},{"infoProvTyDescOffset",16},{"infoProvLabelOffset",24},{"infoProvUnitOffset",32},
            {"infoProvModuleOffset",40},{"infoProvFileOffset",48},{"infoProvSpanOffset",56},
            {"closureRetBco",29},{"closureRetSmall",30},{"closureRetBig",31},{"closureRetFun",32},{"closureUpdateFrame",33},{"closureCatchFrame",34},{"closureUnderflowFrame",35},{"closureStopFrame",36},
            {"closureStack",53},{"closureAtomicallyFrame",55},{"closureCatchRetryFrame",56},{"closureCatchStmFrame",57},{"closureAnnFrame",65},{"stackHeaderBytes",8},
            {"stackCatchHandlerBytes",8},{"stackCatchFrameBytes",16},{"stackCatchStmCodeBytes",8},{"stackCatchStmHandlerBytes",16},{"stackCatchStmFrameBytes",24},
            {"stackUpdateeBytes",8},{"stackUpdateFrameBytes",16},{"stackAtomicallyCodeBytes",8},{"stackAtomicallyResultBytes",16},{"stackAtomicallyFrameBytes",24},
            {"stackCatchRetryAltCodeBytes",8},{"stackCatchRetryFirstCodeBytes",16},{"stackCatchRetryAltBytes",24},{"stackCatchRetryFrameBytes",32},
            {"stackRetFunSizeBytes",8},{"stackRetFunFunBytes",16},{"stackRetFunPayloadBytes",24},{"stackRetFunFrameBytes",24},{"stackAnnPayloadBytes",8},{"stackAnnFrameBytes",16},{"stackClosurePayloadBytes",8}
        };
        for (var field : offsets) fields.put((String) field[0], field[1]); fields.putAll(changes);
        return TargetLayout.fromDocument(Map.of("format", "thc-target-layout", "schema", 1, "compiler", Map.of("id", "ghc-9.14.1", "abi", abi, "platform", platform, "way", platform.endsWith("-windows") ? "vanilla-nonprofiling" : "dynamic-nonprofiling"), "layout", fields));
    }
    private static final class Capture extends GuestRoot {
        @Child Expr body;
        Capture(Language language, CoreFunctionIdentity identity, CoreSourceLocation location) {
            super(language, new FrameLayout().build()); body = new Expr() { @Override public Object execute(VirtualFrame frame) { return ManagedStackSnapshot.capture(this); } }.located(location); configureCoreIdentity(identity);
        }
        @Override public Object execute(VirtualFrame frame) { return body.execute(frame); }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public String getName() { return "invented-unit:Not.Provenance.lambda"; }
    }
    private ManagedStackSnapshot capture(Language language) { return capture(language, true, "workλ"); }
    private ManagedStackSnapshot capture(Language language, boolean named, String occurrence) {
        var source = Source.newBuilder("thc", "", "Actual.hs").content(Source.CONTENT_NONE).build(); var section = source.createSection(7, 3, 8, 9);
        var location = named ? new CoreSourceLocation(section, List.of(new CoreSourceNote("real-note", section, "actual note label", 7, 3, 8, 10))) : null;
        var root = new Capture(language, named ? new CoreFunctionIdentity("real-unit:Actual.Module." + occurrence, "real-unit", "Actual.Module", occurrence) : null, location);
        var caller = new GuestRoot(language, new FrameLayout().build()) {
            @Child DirectCallNode call = DirectCallNode.create(root.getCallTarget());
            @Override public Object execute(VirtualFrame frame) { return Calls.direct(call, new Object[]{0L}); }
            @Override public long bloom(VirtualFrame frame) { return 0L; }
        }; return (ManagedStackSnapshot) Calls.target(caller.getCallTarget(), new Object[]{0L});
    }
    @FunctionalInterface private interface Action<T> { T run(Language language) throws Exception; }
    private <T> T context(Action<T> block) throws Exception { return context(false, block); }
    private <T> T context(boolean nativeAccess, Action<T> block) throws Exception {
        try (var context = Context.newBuilder("thc").allowNativeAccess(nativeAccess).build()) {
            context.initialize("thc"); context.enter();
            try { return block.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private long unsigned(ManagedAddress address, long offset, int width) {
        long value = 0; for (int i = 0; i < width; i++) value |= address.readWord8(offset + i) << (8 * (endian.equals("little") ? i : width - 1 - i)); return value;
    }
    private static ManagedAddress text(ManagedAllocation storage, long view, TargetLayout layout, String field) { return storage.readAddressByteOffset(view + layout.offset("infoProvEntProvOffset") + layout.offset("infoProv" + field + "Offset")); }
    private static int registrations() throws Exception {
        var field = ManagedStackRegistry.class.getDeclaredField("entries"); field.setAccessible(true); return ((ManagedAddress.WeakLocations<?>) field.get(Language.currentState().getStackSnapshots())).getSize();
    }
    private void reclaimed(List<WeakReference<?>> references, TargetLayout layout) throws Exception { reclaimed(references, layout, 0); }
    private void reclaimed(List<WeakReference<?>> references, TargetLayout layout, int remaining) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (true) {
            System.gc(); // Normal queue draining only; never clear private references.
            assertEquals(0L, ManagedStackRuntime.lookupIpe(ManagedAddress.nullAddress(), ManagedAddress.nullAddress(), layout));
            boolean gone = true; for (var reference : references) if (reference.get() != null) { gone = false; break; }
            if (gone && registrations() == remaining) return;
            assertTrue(System.nanoTime() < deadline, "Discarded snapshots, addresses or provenance were retained"); Thread.sleep(10);
        }
    }
    private record Alias(ManagedAddress address, List<WeakReference<?>> discarded, WeakReference<ManagedStackFrame> provenance) {}
    private Alias registeredAlias(Language language, TargetLayout layout) {
        var snapshot = capture(language); var pair = ManagedStackRuntime.frameInfo(snapshot, 0, layout); var output = ManagedAllocation.mutable(91, 8);
        // Cache must not introduce a strong registry -> image -> key -> allocation cycle.
        assertEquals(1L, ManagedStackRuntime.lookupIpe(pair.getKey(), ManagedAddress.fromAllocation(output), layout));
        return new Alias(pair.getStandard().plus(7), List.of(new WeakReference<>(snapshot), new WeakReference<>(pair.getStandard()), new WeakReference<>(pair.getKey()), new WeakReference<>(snapshot.getFrames().get(1))), new WeakReference<>(snapshot.getFrames().get(0)));
    }
    private List<WeakReference<?>> exerciseRetainedAlias(Language language, TargetLayout layout) throws Exception {
        var retained = registeredAlias(language, layout); reclaimed(retained.discarded(), layout, 1); assertNotNull(retained.provenance().get()); assertEquals(1, registrations());
        var output = ManagedAllocation.mutable(91, 8); var destination = ManagedAddress.fromAllocation(output);
        assertEquals(0L, ManagedStackRuntime.lookupIpe(retained.address(), destination, layout));
        var forged = ManagedAddress.fromAllocation(ManagedAllocation.immutable(new byte[16], 8)).plus(16); assertEquals(0L, ManagedStackRuntime.lookupIpe(forged, destination, layout));
        var key = retained.address().plus(9); assertEquals(1L, ManagedStackRuntime.lookupIpe(key, destination, layout)); var label = text(output, 0, layout, "Label"); assertEquals("workλ", label.utf8());
        assertEquals(1L, ManagedStackRuntime.lookupIpe(key, destination, layout)); assertSame(label, text(output, 0, layout, "Label"));
        var result = new ArrayList<>(retained.discarded()); result.add(retained.provenance()); result.add(new WeakReference<>(retained.address())); result.add(new WeakReference<>(key)); return result;
    }
    @Test void derivedAliasKeepsOnlyItsRegistrationAliveAfterSnapshotCollection() throws Exception {
        context(language -> { var layout = layout(); reclaimed(exerciseRetainedAlias(language, layout), layout); assertEquals(0, registrations()); return null; });
    }
    private record CopiedKey(ManagedAllocation output, WeakReference<ManagedStackSnapshot> snapshot) {}
    private CopiedKey copiedKey(Language language, TargetLayout layout) {
        var snapshot = capture(language); var key = ManagedStackRuntime.frameInfo(snapshot, 0, layout).getKey(); var output = ManagedAllocation.mutable(91, 8);
        assertEquals(1L, ManagedStackRuntime.lookupIpe(key, ManagedAddress.fromAllocation(output), layout)); return new CopiedKey(output, new WeakReference<>(snapshot));
    }
    private List<WeakReference<?>> exerciseCopiedKey(Language language, TargetLayout layout) throws Exception {
        var copied = copiedKey(language, layout); var output = copied.output(); var snapshot = copied.snapshot(); reclaimed(List.of(snapshot), layout, 1);
        var key = output.readAddressByteOffset(3); var label = text(output, 0, layout, "Label"); assertEquals(1L, ManagedStackRuntime.lookupIpe(key, ManagedAddress.fromAllocation(output), layout));
        assertSame(label, text(output, 0, layout, "Label")); return List.of(snapshot, new WeakReference<>(key), new WeakReference<>(output));
    }
    @Test void copiedOutputPointerRetainsIpeWithoutRetainingItsSnapshot() throws Exception {
        context(language -> { var layout = layout(); reclaimed(exerciseCopiedKey(language, layout), layout); assertEquals(0, registrations()); return null; });
    }
    private List<WeakReference<?>> discardedRegistrations(Language language, TargetLayout layout) {
        var result = new ArrayList<WeakReference<?>>();
        for (int i = 0; i < 32; i++) {
            var snapshot = capture(language); var key = ManagedStackRuntime.frameInfo(snapshot, 0, layout).getKey(); var output = ManagedAddress.fromAllocation(ManagedAllocation.mutable(91, 8));
            assertEquals(1L, ManagedStackRuntime.lookupIpe(key, output, layout)); result.add(new WeakReference<>(snapshot)); result.add(new WeakReference<>(key)); result.add(new WeakReference<>(snapshot.getFrames().get(0)));
        } return result;
    }
    @Test void repeatedCaptureAndLookupDoesNotRetainHistoricalFrames() throws Exception {
        context(language -> { var layout = layout(); for (int i = 0; i < 3; i++) { reclaimed(discardedRegistrations(language, layout), layout); assertEquals(0, registrations()); } return null; });
    }
    @Test void liveFramesHaveStableDistinctImmutableInfoImagesAndWordOffsets() throws Exception {
        context(language -> {
            var snapshot = capture(language); var layout = layout(); assertEquals(2, snapshot.getFrames().size()); assertSame(Language.currentState().getStackSnapshots().getToken(), snapshot.getOwnerToken());
            var stack = ManagedStackRuntime.stackInfo(snapshot, layout); assertSame(stack, ManagedStackRuntime.stackInfo(snapshot, layout())); assertEquals(53L, unsigned(stack, 8, 4));
            var pairs = new ArrayList<ManagedStackFrameInfo>(); for (int i = 0; i < snapshot.getFrames().size(); i++) pairs.add(ManagedStackRuntime.frameInfo(snapshot, i, layout));
            for (int i = 0; i < pairs.size(); i++) {
                var pair = pairs.get(i); assertSame(pair.getStandard(), ManagedStackRuntime.frameInfo(snapshot, i, layout).getStandard()); assertSame(pair.getKey(), ManagedStackRuntime.frameInfo(snapshot, i, layout).getKey());
                assertFalse(pair.getStandard().sameLocation(pair.getKey())); assertTrue(pair.getStandard().plus(16).sameLocation(pair.getKey())); assertEquals(30L, unsigned(pair.getStandard(), 8, 4)); assertEquals(0L, unsigned(pair.getStandard(), 0, 8));
                assertThrows(RuntimeFault.class, () -> pair.getStandard().writeWord8(0, 1));
            }
            assertFalse(pairs.get(0).getKey().sameLocation(pairs.get(1).getKey()));
            for (long offset : new long[]{-1, 2, Long.MIN_VALUE, Long.MAX_VALUE}) assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.frameInfo(snapshot, offset, layout)); return null;
        });
    }
    @Test void virtualZeroSlackFramesExposeEmptyBitmapAndExactTerminalTraversal() throws Exception {
        context(language -> {
            var snapshot = capture(language); var layout = layout(); assertEquals(2L, ManagedStackRuntime.stackFields(snapshot, layout));
            for (int offset = 0; offset < snapshot.getFrames().size(); offset++) {
                var bitmap = ManagedStackRuntime.smallBitmap(snapshot, offset, layout); assertEquals(0L, bitmap.getBitmap()); assertEquals(0L, bitmap.getSize()); var next = ManagedStackRuntime.advance(snapshot, offset, layout);
                if (offset + 1 < snapshot.getFrames().size()) { assertSame(snapshot, next.getSnapshot()); assertEquals(offset + 1L, next.getWordOffset()); assertEquals(1L, next.getHasNext()); }
                else { assertNull(next.getSnapshot()); assertEquals(0L, next.getWordOffset()); assertEquals(0L, next.getHasNext()); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.stackFields(next.getSnapshot(), layout)); }
            }
            for (long offset : new long[]{-1, 2, Long.MIN_VALUE, Long.MAX_VALUE}) { assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.smallBitmap(snapshot, offset, layout)); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.advance(snapshot, offset, layout)); }
            var changed = layout(Map.of(), "other-stack-geometry"); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.stackFields(snapshot, changed)); return null;
        });
    }
    @Test void nativePayloadAndOtherFrameKindGettersNeverInventDiagnosticContents() throws Exception {
        context(language -> {
            var snapshot = capture(language); var layout = layout();
            for (var operation : List.of(OriginalStackInfoOp.CLOSURE, OriginalStackInfoOp.LARGE_BITMAP, OriginalStackInfoOp.BCO_LARGE_BITMAP, OriginalStackInfoOp.RET_FUN_LARGE_BITMAP, OriginalStackInfoOp.RET_FUN_SMALL_BITMAP, OriginalStackInfoOp.RET_FUN_BIG, OriginalStackInfoOp.UNDERFLOW)) {
                var error = assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.incompatibleGetter(operation, snapshot, 0, layout));
                assertTrue(Objects.toString(error.getMessage(), "").contains(operation.getSymbol())); assertTrue(Objects.toString(error.getMessage(), "").contains("managed diagnostic RET_SMALL"));
                assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.incompatibleGetter(operation, snapshot, -1, layout));
            } return null;
        });
    }
    @Test void headerWordsUseOwnedInfoPointerBitsAndEnforceSnapshotDomain() throws Exception {
        var layout = layout();
        var snapshot = context(true, language -> {
            var captured = capture(language); var words = new ArrayList<Long>();
            for (int offset = 0; offset < captured.getFrames().size(); offset++) {
                var pair = ManagedStackRuntime.frameInfo(captured, offset, layout); var standard = pair.getStandard(); var key = pair.getKey();
                assertEquals((long) layout.offset("infoTableBytes"), standard.availableBytes()); assertFalse(standard.cbitsWritable()); assertThrows(RuntimeFault.class, () -> standard.writeWord8(0, 0));
                long word = ManagedStackRuntime.word(captured, offset, layout); assertNotEquals(0L, word); assertEquals(key.toNativeBits(), word); assertEquals(standard.toNativeBits() + layout.offset("infoTableBytes"), word);
                assertTrue(key.sameLocation(NativeAddresses.current(null).recover(word))); assertEquals(word, ManagedStackRuntime.word(captured, offset, layout)); words.add(word);
            }
            assertEquals(words.size(), new HashSet<>(words).size()); for (long offset : new long[]{-1, words.size(), Long.MIN_VALUE, Long.MAX_VALUE}) assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.word(captured, offset, layout));
            for (var forged : Arrays.asList(null, 0L, "snapshot", captured.getFrames())) assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.word(forged, 0, layout));
            assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.word(captured, 0, layout(Map.of(), "different-image")));
            for (var geometry : List.of(Map.of("stackClosurePayloadBytes", 16), Map.of("stackHeaderBytes", 16, "stackClosurePayloadBytes", 16))) {
                var incompatible = layout(geometry, "different-header"); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.stackInfo(captured, incompatible));
                assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.frameInfo(captured, 0, incompatible)); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.word(captured, 0, incompatible));
            } return captured;
        });
        context(true, language -> assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.word(snapshot, 0, layout)));
        context(language -> assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.word(capture(language), 0, layout)));
    }
    @Test void speculativeScalarGetterInterfacesPreserveValueAndEvaluateOperandOnce() throws Exception {
        context(language -> {
            var snapshot = capture(language); var layout = layout(); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], new FrameLayout().build());
            class Operands { int evaluations; Expr operand() { return new Expr() { @Override public Object execute(VirtualFrame frame) { evaluations++; return snapshot; } }; } }
            var operands = new Operands();
            var info = new OriginalStackInfoExpression(OriginalStackInfoOp.STACK_INFO, layout, new Expr[]{operands.operand()}, proof(CoreKind.ADDRESS, true, null));
            var expectedAddress = ManagedStackRuntime.stackInfo(snapshot, layout); var addressMiss = assertThrows(UnexpectedResultException.class, () -> info.executeLong(frame)); assertSame(expectedAddress, addressMiss.getResult()); assertEquals(1, operands.evaluations);
            var fields = new OriginalStackInfoExpression(OriginalStackInfoOp.STACK_FIELDS, layout, new Expr[]{operands.operand()}, proof(CoreKind.LONG, true, null));
            var longMiss = assertThrows(UnexpectedResultException.class, () -> fields.executeAddress(frame)); assertEquals(2, longMiss.getResult()); assertEquals(2, operands.evaluations); return null;
        });
    }
    private static CoreRepresentation proof(CoreKind kind, boolean evaluated, List<String> primReps) { return new CoreRepresentation(kind, evaluated, false, primReps, null, null, null, null, null); }
    @Test void boundLocalsPreserveNullWhileRecursiveCellsRequirePublication() {
        var layout = new FrameLayout(); var slot = layout.bind("nullable-snapshot"); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build()); FrameAccess.writeObject(frame, slot, null);
        var unlifted = proof(CoreKind.OBJECT, true, List.of("BoxedRep (Just Unlifted)")); assertNull(new LocalRead(slot, false).proven(unlifted).execute(frame));
        assertNull(new LocalRead(slot, false).execute(frame));
        assertThrows(RuntimeFault.class, () -> new LocalRead(slot).proven(unlifted).execute(frame));
        var recursive = new RecCell(); FrameAccess.writeObject(frame, slot, recursive); assertThrows(RuntimeFault.class, () -> new LocalRead(slot).proven(unlifted).execute(frame)); recursive.setValue(null); recursive.setInitialized(true); assertNull(new LocalRead(slot).proven(unlifted).execute(frame));
    }
    @Test void newGettersEnforceSnapshotContextAndRegistryLifetime() throws Exception {
        var layout = layout(); var snapshot = context(this::capture);
        context(language -> {
            assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.stackFields(snapshot, layout)); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.smallBitmap(snapshot, 0, layout)); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.advance(snapshot, 0, layout));
            for (var invalid : Arrays.asList(null, 0L, "snapshot")) assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.stackFields(invalid, layout)); return null;
        });
    }
    @Test void ipeUsesAbsoluteFieldBytesWithinDestinationViewAndKeepsImmutableStringsAlive() throws Exception {
        var layout = layout(); var storage = ManagedAllocation.mutable(110, 8); var destination = ManagedAddress.fromAllocation(storage).plus(7);
        record Retained(ManagedAddress key, ManagedStackSnapshot snapshot, ManagedAddress label) {}
        var retained = context(language -> {
            var snapshot = capture(language); var key = ManagedStackRuntime.frameInfo(snapshot, 0, layout).getKey(); storage.fill(0, storage.getSize(), 0x5a);
            assertEquals(1L, ManagedStackRuntime.lookupIpe(key.plus(-1).plus(1), destination, layout)); assertTrue(key.sameLocation(storage.readAddressByteOffset(10)));
            assertEquals("THC managed diagnostic frame", text(storage, 7, layout, "Name").utf8()); assertEquals("", text(storage, 7, layout, "TyDesc").utf8()); var oldLabel = text(storage, 7, layout, "Label"); assertEquals("workλ", oldLabel.utf8());
            assertEquals("real-unit", text(storage, 7, layout, "Unit").utf8()); assertEquals("Actual.Module", text(storage, 7, layout, "Module").utf8()); assertEquals("Actual.hs", text(storage, 7, layout, "File").utf8()); assertEquals("7:3-8:10 (end exclusive)", text(storage, 7, layout, "Span").utf8());
            assertEquals(30L, unsigned(destination, 27, 4)); assertThrows(RuntimeFault.class, () -> storage.readAddressByteOffset(34)); assertEquals(1L, ManagedStackRuntime.lookupIpe(key, destination, layout)); assertSame(oldLabel, text(storage, 7, layout, "Label")); assertThrows(RuntimeFault.class, () -> oldLabel.writeWord8(0, 0));
            for (long i = 0; i < 7; i++) assertEquals(0x5aL, storage.readByte(i)); for (long i = 98; i < 110; i++) assertEquals(0x5aL, storage.readByte(i)); return new Retained(key, snapshot, oldLabel);
        });
        assertEquals("workλ", retained.label().utf8()); assertEquals("Actual.Module", text(storage, 7, layout, "Module").utf8()); assertTrue(retained.key().sameLocation(storage.readAddressByteOffset(10))); assertTrue(retained.snapshot().renderLines().getFirst().contains("Actual.hs"));
        context(language -> { assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.stackInfo(retained.snapshot(), layout)); assertEquals(0L, ManagedStackRuntime.lookupIpe(retained.key(), destination, layout)); assertSame(retained.label(), text(storage, 7, layout, "Label")); return null; });
    }
    @Test void anonymousMissingMetadataNeverUsesDebugNamesAsBindingProvenance() throws Exception {
        context(language -> {
            var layout = layout(); var snapshot = capture(language, false, "workλ"); var storage = ManagedAllocation.mutable(91, 8); var key = ManagedStackRuntime.frameInfo(snapshot, 0, layout).getKey();
            assertEquals(1L, ManagedStackRuntime.lookupIpe(key, ManagedAddress.fromAllocation(storage), layout)); for (var field : List.of("Label", "Unit", "Module", "File", "Span", "TyDesc")) assertEquals("", text(storage, 0, layout, field).utf8(), field);
            var standalone = capture(null); assertNull(standalone.getOwnerToken()); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.stackInfo(standalone, layout)); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.stackInfo(42L, layout)); return null;
        });
    }
    @Test void rejectedDestinationsAndUnknownKeysNeverPartiallyWrite() throws Exception {
        context(language -> {
            var layout = layout(); var key = ManagedStackRuntime.frameInfo(capture(language), 0, layout).getKey();
            for (var exposure : List.of("raw", "native", "short", "width", "partial")) {
                var storage = ManagedAllocation.mutable(100, exposure.equals("width") ? 4 : 8); storage.fill(0, 100, 0x42); var old = ManagedAddress.fromHex("6f6c64"); if (exposure.equals("partial")) storage.writeAddressByteOffset(0, old);
                var raw = switch (exposure) { case "raw" -> storage.rawBytesIfPointerFree(); case "native" -> storage.exposeToNative(); default -> null; };
                var destination = ManagedAddress.fromAllocation(storage).plus(exposure.equals("short") ? 10 : 3); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.lookupIpe(key, destination, layout), exposure);
                if (raw != null) { boolean unchanged = true; for (byte value : raw) if (value != (byte) 0x42) { unchanged = false; break; } assertTrue(unchanged); }
                if (exposure.equals("partial")) assertSame(old, storage.readAddressByteOffset(0)); for (long i = exposure.equals("partial") ? 8 : 0; i < 100; i++) assertEquals(0x42L, storage.readByte(i), exposure);
            }
            for (var destination : List.of(ManagedAddress.nullAddress(), ManagedAddress.fromHex("00".repeat(100)), ManagedAddress.fromByteArray(new byte[100]), ManagedAddress.fromAllocation(ManagedAllocation.immutable(new byte[100], 8)))) assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.lookupIpe(key, destination, layout));
            assertEquals(0L, ManagedStackRuntime.lookupIpe(ManagedAddress.nullAddress(), ManagedAddress.nullAddress(), layout)); return null;
        });
    }
    @Test void layoutMismatchesAndMalformedIpeLayoutsFailBeforeAnyWrite() throws Exception {
        context(language -> {
            var snapshot = capture(language); var layout = layout(); var key = ManagedStackRuntime.frameInfo(snapshot, 0, layout).getKey(); var storage = ManagedAllocation.mutable(100, 8); storage.fill(0, 100, 0x33); var destination = ManagedAddress.fromAllocation(storage);
            assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.frameInfo(snapshot, 0, layout(Map.of(), "other"))); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.lookupIpe(key, destination, layout(Map.of(), "other")));
            for (var changes : List.of(Map.of("tablesNextToCode", false), Map.of("infoProvDescBytes", 8), Map.of("infoProvNameOffset", 8), Map.of("infoProvEntInfoOffset", 20))) {
                var invalid = layout(changes); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.stackInfo(snapshot, invalid)); assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.lookupIpe(key, destination, invalid));
            }
            boolean unchanged = true; for (byte value : storage.copyBytesOut(0, 100)) if (value != (byte) 0x33) { unchanged = false; break; } assertTrue(unchanged); return null;
        });
    }
    @Test void invalidProvenanceAndAllocationCopyRangesAreTransactional() throws Exception {
        context(language -> {
            var layout = layout(); var key = ManagedStackRuntime.frameInfo(capture(language, true, "bad\u0000name"), 0, layout).getKey(); var storage = ManagedAllocation.mutable(100, 8); storage.fill(0, 100, 0x77); var destination = ManagedAddress.fromAllocation(storage).plus(3);
            assertThrows(RuntimeFault.class, () -> ManagedStackRuntime.lookupIpe(key, destination, layout)); var source = ManagedAllocation.mutable(16, 8); source.writeAddressByteOffset(0, ManagedAddress.fromHex("61"));
            for (var range : new long[][]{{-1,1},{1,7},{0,-1},{0,17},{Long.MAX_VALUE,1}}) assertThrows(RuntimeFault.class, () -> destination.copyFromAllocationBytes(source, range[0], range[1]));
            boolean unchanged = true; for (byte value : storage.copyBytesOut(0, 100)) if (value != (byte) 0x77) { unchanged = false; break; } assertTrue(unchanged); return null;
        });
    }
}
