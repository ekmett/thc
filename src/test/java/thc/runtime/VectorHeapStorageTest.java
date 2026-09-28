// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.*;
import jdk.incubator.vector.Vector;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RawVectorTestValues.rawVectorTestValue;

/** Heap ownership is independent of transient carriers and reusable call storage. */
public class VectorHeapStorageTest {
    private List<CoreRepresentation> vectors() throws Exception {
        var catalog = (Map<?, ?>) Json.parse(Files.readString(new File(System.getProperty("thc.projectRoot"), "scripts/simd-families.json").toPath()));
        var result = new ArrayList<CoreRepresentation>();
        for (var raw : (List<?>) catalog.get("families")) {
            var shape = (Map<?, ?>) raw; result.add(CoreRepresentations.parse(Map.of("kind", "vector", "evaluated", true,
                "primReps", List.of("VecRep " + shape.get("lanes") + " " + shape.get("element")),
                "vector", Map.of("lanes", shape.get("lanes"), "element", shape.get("element")))));
        }
        assertEquals(30, result.size()); return result;
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void inLanguage(String strategy, Action action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", strategy).build()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private static final class Slots {
        final FrameLayout layout = new FrameLayout(); final int[] slots; final VirtualFrame frame;
        Slots(int count) { slots = new int[count]; for (int i = 0; i < count; i++) slots[i] = layout.bind("field lane " + i); frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build()); }
    }
    private void fill(CoreRepresentation proof, Slots target) { new VectorLayout(proof).write(target.frame, target.slots, 0, rawVectorTestValue(proof)); }
    private void compare(CoreRepresentation proof, Slots expected, Slots actual) {
        var vector = new VectorLayout(proof); var first = (Vector<?>) vector.read(expected.frame, expected.slots, 0); var second = (Vector<?>) vector.read(actual.frame, actual.slots, 0);
        assertArrayEquals(first.reinterpretAsBytes().toArray(), second.reinterpretAsBytes().toArray());
    }
    private Map<String, Object> metadata(CoreRepresentation proof) {
        return Map.of("id", "VectorBox", "name", "VectorBox", "kind", "boxed", "arity", 3,
            "fieldReps", List.of(proof.getPrimReps(), List.of("IntRep"), List.of("BoxedRep (Just Lifted)")),
            "fieldTypes", List.of(Map.of("kind", "vector", "evaluated", true, "primReps", proof.getPrimReps(),
                "vector", Map.of("lanes", proof.getVector().getLanes(), "element", proof.getVector().getElement())),
                Map.of("kind", "long", "evaluated", true, "primReps", List.of("IntRep")),
                Map.of("kind", "object", "evaluated", false, "primReps", List.of("BoxedRep (Just Lifted)"))),
            "strictFields", List.of(false, false, false), "fieldLifted", List.of(false, false, true));
    }

    @Test public void everyShapeOwnsFinalLanesAfterCallerReuseWithLazyReferencesIntact() throws Exception {
        for (var strategy : List.of("field-based", "array-based")) inLanguage(strategy, language -> {
            for (var proof : vectors()) {
                int lanes = proof.getVector().getLanes(); var source = new Slots(3); fill(proof, source); var expected = new Slots(1); fill(proof, expected);
                var cell = new RecCell(); var captures = CaptureLayout.withVectors(language, new CoreRepresentation[] {proof, null, null}, new boolean[] {false, true, false}, new boolean[] {false, true, false});
                assertEquals(3, captures.getStorageSize()); assertEquals(1, captures.fieldWidth(0)); assertEquals(1, captures.fieldWidth(1));
                assertEquals(proof, captures.vectorProof(0)); assertFalse(captures.isVector(1));
                FrameAccess.writeLong(source.frame, source.slots[1], Long.MIN_VALUE); FrameAccess.write(source.frame, source.slots[2], cell);
                var environment = captures.capture(source.frame, source.slots); var fields = new CoreFields(metadata(proof)); var constructor = DataLayout.fromFields(language, "VectorBox", "VectorBox", fields);
                assertEquals(3, constructor.getArity()); assertEquals(1, constructor.fieldWidth(0)); assertEquals(proof, constructor.vectorProof(0));
                var never = new RootNode(language) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Lazy heap reference was forced"); } }.getCallTarget();
                var thunk = new Thunk(never, null); var self = new Slots(3);
                var selfLayout = new AstSelfLayout(captures, self.slots, new int[0], new CoreRepresentation[0], new boolean[0], null, new int[][] {new int[] {self.slots[0]}});
                assertSame(AstSelfCall.INSTANCE, assertThrows(AstSelfCall.class, () -> selfLayout.transfer(self.frame, new Closure(environment, 0, never), new int[0])));
                compare(proof, expected, self); assertEquals(Long.MIN_VALUE, FrameAccess.read(self.frame, self.slots[1])); assertSame(cell, FrameAccess.read(self.frame, self.slots[2]));
                var value = constructor.allocate(); constructor.initializeVector(value, 0, source.frame, source.slots, 0); constructor.initializeLong(value, 1, Long.MAX_VALUE); constructor.initialize(value, 2, thunk);
                for (int slot : source.slots) source.frame.clear(slot); var restored = new Slots(1);
                for (int i = 0; i < 2; i++) {
                    captures.restoreVector(environment, 0, restored.frame, restored.slots, 0); compare(proof, expected, restored);
                    constructor.restoreVector(value, 0, restored.frame, restored.slots, 0); compare(proof, expected, restored);
                }
                assertSame(cell, captures.readObject(environment, 2)); assertFalse(cell.getInitialized()); assertEquals(Long.MIN_VALUE, captures.readLong(environment, 1));
                assertSame(thunk, constructor.read(value, 2)); assertEquals(0, thunk.getState()); assertEquals(Long.MAX_VALUE, constructor.readLong(value, 1));
                assertThrows(RuntimeFault.class, () -> captures.read(environment, 0)); assertThrows(RuntimeFault.class, () -> captures.readLong(environment, 0));
                assertThrows(RuntimeFault.class, () -> captures.readFloat(environment, 0)); assertThrows(RuntimeFault.class, () -> captures.readDouble(environment, 0)); assertThrows(RuntimeFault.class, () -> captures.readObject(environment, 0));
                assertThrows(IllegalStateException.class, () -> captures.captureValues(new Object[] {null, 0L, cell}));
                assertThrows(RuntimeFault.class, () -> constructor.create(new Object[] {null, 0L, thunk})); assertThrows(RuntimeFault.class, () -> constructor.read(value, 0));
                if (strategy.equals("field-based")) {
                    var owners = new Object[] {environment, value}; var prefixes = new String[] {"capture_0_lane_", "field_0_lane_"};
                    for (int i = 0; i < owners.length; i++) {
                        // StaticShape escapes underscores and may place generated fields in a base class.
                        var generatedPrefix = prefixes[i].replace("_", "__"); var properties = new ArrayList<java.lang.reflect.Field>();
                        for (Class<?> type = owners[i].getClass(); type != null; type = type.getSuperclass()) for (var property : type.getDeclaredFields()) if (property.getName().startsWith(generatedPrefix)) properties.add(property);
                        assertEquals(lanes, properties.size()); boolean primitiveFinal = true;
                        for (var property : properties) if (!property.getType().isPrimitive() || !Modifier.isFinal(property.getModifiers())) { primitiveFinal = false; break; } assertTrue(primitiveFinal);
                        Class<?> width = switch (proof.getVector().getElement()) {
                            case "Int8ElemRep", "Word8ElemRep" -> byte.class; case "Int16ElemRep", "Word16ElemRep" -> short.class;
                            case "Int32ElemRep", "Word32ElemRep" -> int.class; case "Int64ElemRep", "Word64ElemRep" -> long.class;
                            case "FloatElemRep" -> float.class; default -> double.class;
                        };
                        boolean sameWidth = true; for (var property : properties) if (property.getType() != width) { sameWidth = false; break; } assertTrue(sameWidth);
                    }
                }
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            }
        });
    }

    @Test public void bytecodePrimitiveLocalsPreserveAdaptiveCapturesBesideOwnedVectorLanes() throws Exception {
        for (var strategy : List.of("field-based", "array-based")) inLanguage(strategy, language -> {
            for (var proof : vectors()) {
                var vector = new VectorLayout(proof); var expected = new Slots(1); fill(proof, expected);
                var captures = CaptureLayout.withVectors(language, new CoreRepresentation[] {proof, null, null, null, null}, new boolean[] {false, true, true, true, false});
                var locals = new ArrayList<LocalAccessor>();
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); for (int i = 0; i < captures.getStorageSize(); i++) locals.add(LocalAccessor.constantOf(b.createLocal("source " + i, null)));
                    b.beginReturn(); b.emitLoadConstant(0L); b.endReturn(); b.endRoot();
                }).getNode(0);
                var bytecode = root.getBytecodeNode();
                // Actual generated descriptor/typed local APIs, without an AST slot shim.
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[] {0L}, root.getFrameDescriptor()); var slots = locals.toArray(LocalAccessor[]::new);
                vector.write(bytecode, frame, slots, 0, vector.read(expected.frame, expected.slots, 0));
                float floating = Float.intBitsToFloat(0x7fc01234); double precise = Double.longBitsToDouble(0x7ff8000000001234L); var cell = new RecCell();
                slots[1].setLong(bytecode, frame, Long.MIN_VALUE); slots[2].setFloat(bytecode, frame, floating); slots[3].setDouble(bytecode, frame, precise); slots[4].setObject(bytecode, frame, cell);
                // StoreLocal instructions, not these isolated direct writes, populate bytecode profiles.
                assertEquals(Long.MIN_VALUE, slots[1].getLong(bytecode, frame)); assertEquals(Float.floatToRawIntBits(floating), Float.floatToRawIntBits(slots[2].getFloat(bytecode, frame)));
                assertEquals(Double.doubleToRawLongBits(precise), Double.doubleToRawLongBits(slots[3].getDouble(bytecode, frame)));
                var environment = captures.captureLocals(bytecode, frame, slots); for (var slot : slots) slot.clear(bytecode, frame);
                boolean allCleared = true; for (var slot : slots) if (!slot.isCleared(bytecode, frame)) { allCleared = false; break; } assertTrue(allCleared);
                assertTrue(captures.isLong(environment, 1)); assertEquals(Long.MIN_VALUE, captures.readLong(environment, 1));
                assertTrue(captures.isObject(environment, 2)); assertTrue(captures.isObject(environment, 3));
                assertEquals(Float.floatToRawIntBits(floating), Float.floatToRawIntBits((Float) captures.readObject(environment, 2)));
                assertEquals(Double.doubleToRawLongBits(precise), Double.doubleToRawLongBits((Double) captures.readObject(environment, 3)));
                assertSame(cell, captures.readObject(environment, 4)); assertFalse(cell.getInitialized());
                captures.restoreVector(environment, 0, bytecode, frame, slots, 0); var restored = new Slots(1);
                vector.write(restored.frame, restored.slots, 0, vector.read(bytecode, frame, slots, 0)); compare(proof, expected, restored);
            }
        });
    }

    @Test public void bytecodeGenericFieldsBesideVectorsPreserveBoxedNumericCarriers() throws Exception {
        for (var strategy : List.of("field-based", "array-based")) inLanguage(strategy, language -> {
            var matches = new ArrayList<CoreRepresentation>(); for (var proof : vectors()) if (CoreVector.INT8X16.equals(proof.getVector())) matches.add(proof);
            assertEquals(1, matches.size()); var proof = matches.getFirst(); var expected = new Slots(1); fill(proof, expected);
            var layout = DataLayout.fromFields(language, "VectorBox", "VectorBox", new CoreFields(metadata(proof)));
            List<Object> numbers = List.of(Long.MIN_VALUE, Float.intBitsToFloat(0x7fc01234), Double.longBitsToDouble(0x7ff8000000001234L));
            for (var number : numbers) {
                // Fresh operation site per carrier: no earlier Object fallback can hide a missing guard.
                var target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); var lane = b.createLocal("vector 0", "object");
                    b.beginStoreLocal(lane); b.emitLoadConstant(expected.frame.getObject(expected.slots[0])); b.endStoreLocal();
                    var value = b.createLocal("constructed vector box", "object"); b.beginStoreLocal(value); b.emitAllocateData(layout); b.endStoreLocal();
                    b.beginTransferDataVector(new BytecodeRoot.DataVectorTransfer(layout, 0, new LocalAccessor[] {LocalAccessor.constantOf(lane)}, true)); b.emitLoadLocal(value); b.endTransferDataVector();
                    b.beginInitializeDataScalar(layout, 1); b.emitLoadLocal(value); b.emitLoadConstant(Long.MAX_VALUE); b.endInitializeDataScalar();
                    b.beginInitializeDataScalar(layout, 2); b.emitLoadLocal(value); b.emitLoadArgument(1); b.endInitializeDataScalar();
                    b.beginReturn(); b.emitLoadLocal(value); b.endReturn(); b.endRoot();
                }).getNode(0).getCallTarget();
                for (int i = 0; i < 2; i++) {
                    var value = (DataValue) Calls.target(target, new Object[] {0L, number}); var actual = layout.read(value, 2);
                    assertEquals(number.getClass(), actual == null ? null : actual.getClass(), strategy);
                    switch (number) {
                        case Long integer -> assertEquals(integer, actual);
                        case Float floating -> assertEquals(Float.floatToRawIntBits(floating), Float.floatToRawIntBits((Float) actual));
                        case Double precise -> assertEquals(Double.doubleToRawLongBits(precise), Double.doubleToRawLongBits((Double) actual));
                        default -> throw new IllegalStateException("Unexpected numeric test carrier");
                    }
                    assertEquals(Long.MAX_VALUE, layout.readLong(value, 1)); var restored = new Slots(1); layout.restoreVector(value, 0, restored.frame, restored.slots, 0); compare(proof, expected, restored);
                }
            }
        });
    }

    @Test public void vectorIdentityAndAllocationOwnershipRemainExact() throws Exception {
        for (var strategy : List.of("field-based", "array-based")) inLanguage(strategy, language -> {
            var signedMatches = new ArrayList<CoreRepresentation>(); var unsignedMatches = new ArrayList<CoreRepresentation>();
            for (var proof : vectors()) { if (CoreVector.INT8X16.equals(proof.getVector())) signedMatches.add(proof); if (CoreVector.WORD8X16.equals(proof.getVector())) unsignedMatches.add(proof); }
            assertEquals(1, signedMatches.size()); assertEquals(1, unsignedMatches.size()); var signed = signedMatches.getFirst(); var unsigned = unsignedMatches.getFirst();
            var first = DataLayout.fromFields(language, "A", "A", new CoreFields(metadata(signed))); var second = DataLayout.fromFields(language, "B", "B", new CoreFields(metadata(unsigned)));
            var source = new Slots(1); fill(signed, source); var value = first.allocate(); first.initializeVector(value, 0, source.frame, source.slots, 0);
            assertNotEquals(first.vectorProof(0), second.vectorProof(0)); assertThrows(RuntimeFault.class, () -> second.restoreVector(value, 0, source.frame, source.slots, 0)); assertThrows(RuntimeFault.class, () -> second.initializeVector(value, 0, source.frame, source.slots, 0));
            var a = CaptureLayout.withVectors(language, new CoreRepresentation[] {signed}, new boolean[] {false}); var b = CaptureLayout.withVectors(language, new CoreRepresentation[] {unsigned}, new boolean[] {false});
            var environment = a.capture(source.frame, source.slots); assertThrows(RuntimeFault.class, () -> b.restoreVector(environment, 0, source.frame, source.slots, 0));
            assertThrows(RuntimeFault.class, () -> new CapturedFrame(a, new Object())); assertThrows(RuntimeFault.class, () -> new DataValue(first, new Object()));
            assertThrows(IllegalArgumentException.class, () -> CaptureLayout.withVectors(language, new CoreRepresentation[] {signed}, new boolean[] {true}));
            var absent = new LinkedHashMap<>(metadata(signed)); absent.remove("fieldTypes"); assertThrows(UnsupportedCore.class, () -> new CoreFields(absent));
            var conflicting = new LinkedHashMap<>(metadata(signed)); conflicting.put("fieldTypes", metadata(unsigned).get("fieldTypes")); assertThrows(RuntimeFault.class, () -> new CoreFields(conflicting));
            var tuple = new CoreRepresentation(CoreKind.UNKNOWN, true, true, Collections.nCopies(16, "Int8Rep"), Collections.nCopies(16, VectorLayout.laneProof(signed.getVector())));
            assertThrows(RuntimeFault.class, () -> CaptureLayout.withVectors(language, new CoreRepresentation[] {tuple}, new boolean[] {false}));
        });
    }
}
