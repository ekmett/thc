// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;
import jdk.incubator.vector.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VectorMemoryValidationOrderTest {
    @Test void copiedVectorMemoryPreservesTypedBitsOrderAndSnapshots() {
        // The fallback must not use Vector API segment access, including the
        // JDK's non-intrinsic reinterpret fallback. Use a separate byte model.
        var vectors = List.<Vector<?>>of(
            ByteVector.fromArray(ByteVector.SPECIES_128, new byte[]{0, 1, -1, -128, 127, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}, 0),
            ShortVector.fromArray(ShortVector.SPECIES_128, new short[]{0, 1, -1, -32768, 32767, 3, 4, 5}, 0),
            IntVector.fromArray(IntVector.SPECIES_128, new int[]{0, 1, -1, Integer.MIN_VALUE}, 0),
            LongVector.fromArray(LongVector.SPECIES_128, new long[]{Long.MIN_VALUE, 0x0123456789abcdefL}, 0),
            FloatVector.fromArray(FloatVector.SPECIES_128, new float[]{-0.0f, Float.intBitsToFloat(0x7fc12345), Float.POSITIVE_INFINITY, Float.MIN_VALUE}, 0),
            DoubleVector.fromArray(DoubleVector.SPECIES_128, new double[]{-0.0, Double.longBitsToDouble(0x7ff8123456789abcL)}, 0));
        for (var vector : vectors) for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            byte[] bytes = new byte[34];
            Arrays.fill(bytes, (byte) 0x5a);
            byte[] expected = bytes.clone();
            var model = ByteBuffer.wrap(expected).order(order);
            model.position(1);
            Object lanes = vector.toArray();
            if (lanes instanceof byte[] values) model.put(values);
            else if (lanes instanceof short[] values) for (short value : values) model.putShort(value);
            else if (lanes instanceof int[] values) for (int value : values) model.putInt(value);
            else if (lanes instanceof long[] values) for (long value : values) model.putLong(value);
            else if (lanes instanceof float[] values) for (float value : values) model.putInt(Float.floatToRawIntBits(value));
            else if (lanes instanceof double[] values) for (double value : values) model.putLong(Double.doubleToRawLongBits(value));
            var segment = MemorySegment.ofArray(bytes);
            VectorMemory.copyWrite(vector, segment, 1, order);
            assertArrayEquals(expected, bytes);
            var snapshot = VectorMemory.copyRead(vector.species(), segment, 1, order);
            assertEquals(vector.species(), snapshot.species());
            Arrays.fill(bytes, 1, 17, (byte) 0);
            VectorMemory.copyWrite(snapshot, segment, 2, order);
            assertArrayEquals(Arrays.copyOfRange(expected, 1, 17), Arrays.copyOfRange(bytes, 2, 18));
            var raw = VectorMemory.copyReinterpret(vector, ByteVector.SPECIES_128);
            var little = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            // The JDK bit reinterpretation contract uses little-endian lanes,
            // independently of native byte order and the memory operation above.
            if (order == ByteOrder.LITTLE_ENDIAN)
                assertArrayEquals(Arrays.copyOfRange(expected, 1, 17), ((ByteVector) raw).toArray());
            var restored = VectorMemory.copyReinterpret(raw, vector.species());
            VectorMemory.copyWrite(restored, MemorySegment.ofArray(little.array()), 0, order);
            assertArrayEquals(Arrays.copyOfRange(expected, 1, 17), little.array());
        }
    }

    @Test void copiedVectorMemoryRejectsRangesWithoutPartialWritesAndKeepsSharedLifetime() throws Exception {
        var vector = IntVector.fromArray(IntVector.SPECIES_128, new int[]{1, 2, 3, 4}, 0);
        byte[] bytes = new byte[17];
        Arrays.fill(bytes, (byte) 0x5a);
        byte[] before = bytes.clone();
        var segment = MemorySegment.ofArray(bytes);
        assertThrows(IndexOutOfBoundsException.class, () -> VectorMemory.copyWrite(vector, segment, 2, ByteOrder.nativeOrder()));
        assertArrayEquals(before, bytes);
        assertThrows(IndexOutOfBoundsException.class, () -> VectorMemory.copyRead(IntVector.SPECIES_128, segment, Long.MAX_VALUE, ByteOrder.nativeOrder()));
        assertThrows(IllegalArgumentException.class, () -> VectorMemory.copyWrite(vector, segment.asReadOnly(), 0, ByteOrder.nativeOrder()));
        assertArrayEquals(before, bytes);
        assertThrows(IllegalArgumentException.class, () -> VectorMemory.copyReinterpret(vector, ByteVector.SPECIES_256));
        var arena = Arena.ofShared();
        var shared = arena.allocate(32, 8);
        var failure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                VectorMemory.copyWrite(vector, shared, 1, ByteOrder.nativeOrder());
                assertArrayEquals(new int[]{1, 2, 3, 4}, ((IntVector) VectorMemory.copyRead(IntVector.SPECIES_128, shared, 1, ByteOrder.nativeOrder())).toArray());
                arena.close();
            } catch (Throwable error) { failure.set(error); }
        });
        worker.join();
        try {
            assertNull(failure.get());
            assertFalse(shared.scope().isAlive());
            assertThrows(IllegalStateException.class, () -> VectorMemory.copyRead(IntVector.SPECIES_128, shared, 0, ByteOrder.nativeOrder()));
        } finally { if (shared.scope().isAlive()) arena.close(); }
    }

    @Test void floatCarrierIsCheckedBeforeTheDestinationRange() {
        byte[] bytes = new byte[1];
        var wrongShape = FloatVector.zero(FloatVector.SPECIES_64);
        var failure = assertThrows(RuntimeFault.class,
            () -> VectorMemory.writeFloatVectorArray(bytes, Long.MAX_VALUE, wrongShape, false));
        assertEquals("Unexpected vector species", failure.getMessage());
        assertArrayEquals(new byte[1], bytes);
    }

    @Test void readArgumentShapesAreCheckedBeforeAnyRepresentation() {
        var malformed = List.of("var", "array", Map.of("rep", Map.of("kind", "bogus")));
        var app = List.of("app", List.of("prim", "readFloatX4Array#"), List.of(malformed, "not-an-expression"));
        var failure = assertThrows(RuntimeFault.class, () -> CoreVectorMemory.readCase(List.of("case", app), Map.of()));
        assertEquals("Invalid local vector read argument", failure.getMessage());
    }

    @Test void readFlagsAreCheckedBeforeArgumentRepresentations() {
        var malformed = List.of("var", "array", Map.of("rep", Map.of("kind", "bogus")));
        var app = List.of("app", List.of("prim", "readFloatX4Array#"), List.of(malformed));
        var failure = assertThrows(RuntimeFault.class, () -> CoreVectorMemory.readCase(List.of("case", app), Map.of()));
        assertEquals("Missing local vector read flags", failure.getMessage());
    }

    @Test void allFlagsAreCheckedBeforeAnyArgumentProof() {
        var failure = assertThrows(RuntimeFault.class, () -> VectorMemoryOp.INDEX_FLOAT.validateArguments(
            Arrays.asList(null, CoreVectorMemory.indexProof), List.of(false, true)));
        assertEquals("Vector memory primitive argument representation mismatch: indexFloatX4Array#", failure.getMessage());
    }
}
