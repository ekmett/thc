// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.StringJoiner;
import jdk.incubator.vector.DoubleVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.VectorMemory.*;

class DoubleVectorStorageTest {
    private int shift(int b) { return 8 * (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : 7 - b); }
    private long[] loadModel(byte[] bytes, int offset) {
        var bits = new long[2];
        for (int lane = 0; lane < 2; lane++) for (int b = 0; b < 8; b++)
            bits[lane] |= (bytes[offset + lane * 8 + b] & 255L) << shift(b);
        return bits;
    }
    private void storeModel(byte[] bytes, int offset, long[] bits) {
        for (int lane = 0; lane < 2; lane++) for (int b = 0; b < 8; b++)
            bytes[offset + lane * 8 + b] = (byte) (bits[lane] >>> shift(b));
    }
    private long[] lanes(DoubleVector value) {
        var bits = new long[2];
        for (int lane = 0; lane < 2; lane++) bits[lane] = Double.doubleToRawLongBits(value.lane(lane));
        return bits;
    }
    private DoubleVector packed(long[] bits) {
        return DoubleVector.broadcast(DoubleVector.SPECIES_128, Double.longBitsToDouble(bits[0]))
            .withLane(1, Double.longBitsToDouble(bits[1]));
    }
    private Integer offsetModel(int size, long index, boolean scalarOffset) {
        var offset = BigInteger.valueOf(index).multiply(BigInteger.valueOf(scalarOffset ? 8 : 16));
        return offset.signum() < 0 || offset.add(BigInteger.valueOf(16)).compareTo(BigInteger.valueOf(size)) > 0
            ? null : offset.intValueExact();
    }
    private byte[] finiteBytes(int size) {
        var bytes = new byte[size];
        for (int b = 0; b < size; b++) {
            long bits = (b / 8 * 0x0123456789abcdefL + 0x8123456789abcdefL) & 0xbfffffffffffffffL;
            bytes[b] = (byte) (bits >>> shift(b % 8));
        }
        return bytes;
    }
    private byte[] filled(int size, int value) {
        var bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
    private String hex(long[] values) {
        var joined = new StringJoiner(", ");
        for (long value : values) joined.add(Long.toUnsignedString(value, 16));
        return joined.toString();
    }

    @Test void fullWidthBoundsAndNoPartialEffectsMatchIndependentByteModels() {
        var samples = new ArrayList<Long>();
        for (long i = -2; i <= 12; i++) samples.add(i);
        samples.addAll(List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE,
            (long) Integer.MIN_VALUE, (long) Integer.MAX_VALUE - 1, (long) Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1,
            (1L << 32) - 1, 1L << 32, (1L << 32) + 1));
        for (int power : new int[]{60, 61, 62}) for (long delta = -1; delta <= 1; delta++) samples.add((1L << power) + delta);
        for (int stride : new int[]{8, 16}) for (long delta = -1; delta <= 1; delta++) {
            samples.add((long) Integer.MAX_VALUE / stride + delta);
            samples.add(Long.MAX_VALUE / stride + delta);
            samples.add(Long.MIN_VALUE / stride + delta);
        }
        var indices = new LinkedHashSet<>(samples);
        var values = new long[]{Long.MIN_VALUE, 0x7ff8123456789abcL};
        for (int size = 0; size <= 40; size++) for (boolean scalarOffset : new boolean[]{false, true}) for (long index : indices) {
            var bytes = finiteBytes(size);
            var before = bytes.clone();
            var offset = offsetModel(size, index, scalarOffset);
            var label = "size=" + size + "/index=" + index + "/scalar=" + scalarOffset;
            if (offset == null) {
                var read = assertThrows(RuntimeFault.class, () -> readDoubleVectorArray(bytes, index, scalarOffset, 16), label);
                assertEquals("DoubleX2 ByteArray# range outside its backing storage", read.getMessage(), label);
                assertArrayEquals(before, bytes, "failed read " + label);
                var write = assertThrows(RuntimeFault.class, () -> writeDoubleVectorArray(bytes, index, packed(values), scalarOffset, 16), label);
                assertEquals("DoubleX2 ByteArray# range outside its backing storage", write.getMessage(), label);
                assertArrayEquals(before, bytes, "no partial write " + label);
            } else {
                assertArrayEquals(loadModel(before, offset), lanes(readDoubleVectorArray(bytes, index, scalarOffset, 16)), label);
                assertArrayEquals(before, bytes, "read preserved storage " + label);
                var expected = before.clone();
                storeModel(expected, offset, values);
                writeDoubleVectorArray(bytes, index, packed(values), scalarOffset, 16);
                assertArrayEquals(expected, bytes, "whole vector and sentinel neighbours " + label);
            }
        }
    }

    @Test void immutableVectorRetainsFiniteSpecialAndQuietNaNBitsAtBothOffsetUnits() {
        var seeds = new long[]{0, Long.MIN_VALUE, 1, Long.MIN_VALUE + 1,
            0x000fffffffffffffL, 0x800fffffffffffffL, 0x0010000000000000L, 0x8010000000000000L,
            0x3ff0000000000000L, 0xbff0000000000000L, 0x4025000000000000L, 0xc02b000000000000L,
            0x7fefffffffffffffL, 0xffefffffffffffffL, 0x7ff0000000000000L, 0xfff0000000000000L,
            0x7ff8000000000001L, 0x7ff8123456789abcL, 0x7fffffffffffffffL,
            0xfff8000000000001L, 0xfff8123456789abcL, -1};
        boolean[] scalarOffsets = {false, false, false, true, true, true, true, true};
        long[] indices = {0, 1, 2, 0, 1, 2, 3, 4};
        for (int sample = 0; sample < indices.length; sample++) for (int rotation = 0; rotation < seeds.length; rotation++) {
            boolean scalarOffset = scalarOffsets[sample];
            long index = indices[sample];
            var values = new long[2];
            for (int lane = 0; lane < 2; lane++) values[lane] = seeds[(rotation + lane * 5) % seeds.length];
            int offset = offsetModel(48, index, scalarOffset);
            var source = filled(48, 0x5a);
            storeModel(source, offset, values);
            var before = source.clone();
            var loaded = readDoubleVectorArray(source, index, scalarOffset, 16);
            assertArrayEquals(values, lanes(loaded));
            assertArrayEquals(before, source, "load does not mutate source");
            var expected = filled(48, 0x5a);
            storeModel(expected, offset, values);
            var fromPack = filled(48, 0x5a);
            writeDoubleVectorArray(fromPack, index, packed(values), scalarOffset, 16);
            assertArrayEquals(expected, fromPack);
            var fromLoad = filled(48, 0x5a);
            writeDoubleVectorArray(fromLoad, index, loaded, scalarOffset, 16);
            assertArrayEquals(expected, fromLoad, "quiet NaN byte transport is not floating arithmetic");
        }
    }

    @Test void byteAliasesAndOverlappingStoresPreserveLoadedFloatingSnapshots() {
        var bytes = finiteBytes(48);
        var alias = bytes;
        var captured = readDoubleVectorArray(bytes, 1, true, 16);
        var original = loadModel(bytes, 8);
        assertArrayEquals(lanes(readDoubleVectorArray(alias, 1, false, 16)), lanes(readDoubleVectorArray(bytes, 2, true, 16)));
        for (int b = 0; b <= 15; b++) {
            // Flip one bit per byte; the cleared exponent bit in finiteBytes stays clear.
            alias[8 + b] = (byte) (alias[8 + b] ^ 1);
            assertArrayEquals(loadModel(bytes, 8), lanes(readDoubleVectorArray(alias, 1, true, 16)));
            assertArrayEquals(original, lanes(captured), "immutable snapshot after byte " + b + " mutation");
        }
        var expected = bytes.clone();
        storeModel(expected, 16, original);
        writeDoubleVectorArray(alias, 2, captured, true, 16);
        assertArrayEquals(expected, bytes, "overlap uses retained vector snapshot");
        for (long index = 0; index <= 4; index++)
            assertArrayEquals(loadModel(expected, (int) (index * 8)), lanes(readDoubleVectorArray(bytes, index, true, 16)));
        for (long index = 0; index <= 2; index++)
            assertArrayEquals(loadModel(expected, (int) (index * 16)), lanes(readDoubleVectorArray(alias, index, false, 16)));
    }

    @Test void selectedSignalingNaNByteMovementIsReportedAsPlatformQualifiedObservation() {
        // Never extract/pack scalar floating lanes: scalar copies may quiet sNaNs.
        // This diagnostic remains separate from finite/quiet-NaN assertions.
        var seeds = new long[]{0x7ff0000000000001L, 0x7ff123456789abcdL,
            0xfff0000000000001L, 0xfff123456789abcdL};
        boolean[] scalarOffsets = {false, true, true};
        long[] indices = {1, 1, 3};
        for (int sample = 0; sample < indices.length; sample++) for (int rotation = 0; rotation < seeds.length; rotation++) {
            boolean scalarOffset = scalarOffsets[sample];
            long index = indices[sample];
            int offset = offsetModel(40, index, scalarOffset);
            var source = filled(40, 0x35);
            var values = new long[2];
            for (int lane = 0; lane < 2; lane++) values[lane] = seeds[(rotation + lane) % seeds.length];
            storeModel(source, offset, values);
            var before = source.clone();
            var target = filled(40, 0x35);
            writeDoubleVectorArray(target, index, readDoubleVectorArray(source, index, scalarOffset, 16), scalarOffset, 16);
            assertArrayEquals(before, source);
            assertArrayEquals(Arrays.copyOfRange(before, 0, offset), Arrays.copyOfRange(target, 0, offset));
            assertArrayEquals(Arrays.copyOfRange(before, offset + 16, 40), Arrays.copyOfRange(target, offset + 16, 40));
            var output = loadModel(target, offset);
            boolean allNaNs = true;
            for (long bits : output) allNaNs &= (bits & 0x7ff0000000000000L) == 0x7ff0000000000000L && (bits & 0x000fffffffffffffL) != 0;
            assertTrue(allNaNs);
            System.out.println("DoubleX2 selected sNaN byte movement: arch=" + System.getProperty("os.arch")
                + ", java=" + System.getProperty("java.version") + ", nativeOrder=" + ByteOrder.nativeOrder()
                + ", scalar=" + scalarOffset + ", index=" + index + ", input=" + hex(loadModel(source, offset))
                + ", output=" + hex(output) + ", exact=" + Arrays.equals(source, target)
                + "; platform observation only, not a scalar pack/unpack or arithmetic guarantee");
        }
    }
}
