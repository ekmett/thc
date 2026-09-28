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
import jdk.incubator.vector.FloatVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.VectorMemory.*;

class FloatVectorStorageTest {
    private int shift(int b) { return 8 * (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : 3 - b); }
    private int[] loadModel(byte[] bytes, int offset) {
        var bits = new int[4];
        for (int lane = 0; lane < 4; lane++) for (int b = 0; b < 4; b++)
            bits[lane] |= (bytes[offset + lane * 4 + b] & 255) << shift(b);
        return bits;
    }
    private void storeModel(byte[] bytes, int offset, int[] bits) {
        for (int lane = 0; lane < 4; lane++) for (int b = 0; b < 4; b++)
            bytes[offset + lane * 4 + b] = (byte) (bits[lane] >>> shift(b));
    }
    private int[] lanes(FloatVector value) {
        var bits = new int[4];
        for (int lane = 0; lane < 4; lane++) bits[lane] = Float.floatToRawIntBits(value.lane(lane));
        return bits;
    }
    private FloatVector packed(int[] bits) {
        return FloatVector.broadcast(FloatVector.SPECIES_128, Float.intBitsToFloat(bits[0]))
            .withLane(1, Float.intBitsToFloat(bits[1]))
            .withLane(2, Float.intBitsToFloat(bits[2]))
            .withLane(3, Float.intBitsToFloat(bits[3]));
    }
    private Integer offsetModel(int size, long index, boolean scalarOffset) {
        var offset = BigInteger.valueOf(index).multiply(BigInteger.valueOf(scalarOffset ? 4 : 16));
        return offset.signum() < 0 || offset.add(BigInteger.valueOf(16)).compareTo(BigInteger.valueOf(size)) > 0
            ? null : offset.intValueExact();
    }
    private byte[] finiteBytes(int size) {
        var bytes = new byte[size];
        for (int b = 0; b < size; b++) {
            int bits = (b / 4 * 0x1234567 + 0x81234567) & 0xbfffffff;
            bytes[b] = (byte) (bits >>> shift(b % 4));
        }
        return bytes;
    }
    private byte[] filled(int size, int value) {
        var bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
    private String hex(int[] values) {
        var joined = new StringJoiner(", ");
        for (int value : values) joined.add(Integer.toUnsignedString(value, 16));
        return joined.toString();
    }

    @Test void fullWidthBoundsAndNoPartialEffectsMatchIndependentByteModels() {
        var samples = new ArrayList<Long>();
        for (long i = -2; i <= 12; i++) samples.add(i);
        samples.addAll(List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE,
            (long) Integer.MIN_VALUE, (long) Integer.MAX_VALUE - 1, (long) Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1,
            (1L << 32) - 1, 1L << 32, (1L << 32) + 1));
        samples.addAll(List.of((1L << 60) - 1, 1L << 60, (1L << 60) + 1,
            (1L << 62) - 1, 1L << 62, (1L << 62) + 1));
        for (int stride : new int[]{4, 16}) for (long delta = -1; delta <= 1; delta++)
            samples.add((long) Integer.MAX_VALUE / stride + delta);
        var indices = samples;
        var values = new int[]{0x80000000, 0x00000001, 0x7fc12345, 0xff800000};
        for (int size = 0; size <= 40; size++) for (boolean scalarOffset : new boolean[]{false, true}) for (long index : indices) {
            var bytes = finiteBytes(size);
            var before = bytes.clone();
            var offset = offsetModel(size, index, scalarOffset);
            var label = "size=" + size + "/index=" + index + "/scalar=" + scalarOffset;
            if (offset == null) {
                var read = assertThrows(RuntimeFault.class, () -> readFloatVectorArray(bytes, index, scalarOffset, 16), label);
                assertEquals("FloatX4 ByteArray# range outside its backing storage", read.getMessage(), label);
                assertArrayEquals(before, bytes, "failed read " + label);
                var write = assertThrows(RuntimeFault.class, () -> writeFloatVectorArray(bytes, index, packed(values), scalarOffset, 16), label);
                assertEquals("FloatX4 ByteArray# range outside its backing storage", write.getMessage(), label);
                assertArrayEquals(before, bytes, "no partial write " + label);
            } else {
                assertArrayEquals(loadModel(before, offset), lanes(readFloatVectorArray(bytes, index, scalarOffset, 16)), label);
                assertArrayEquals(before, bytes, "read preserved storage " + label);
                var expected = before.clone();
                storeModel(expected, offset, values);
                writeFloatVectorArray(bytes, index, packed(values), scalarOffset, 16);
                assertArrayEquals(expected, bytes, "whole vector and sentinel neighbours " + label);
            }
        }
    }

    @Test void immutableVectorRetainsFiniteSpecialAndQuietNaNBitsAtBothOffsetUnits() {
        var seeds = new int[]{0, 0x80000000, 1, 0x80000001, 0x007fffff, 0x807fffff,
            0x00800000, 0x80800000, 0x3f800000, 0xbf800000, 0x41280000, 0xc1580000,
            0x7f7fffff, 0xff7fffff, 0x7f800000, 0xff800000,
            0x7fc00001, 0x7fc12345, 0x7fffffff, 0xffc00001, 0xffc12345, 0xffffffff};
        boolean[] scalarOffsets = {false, true, true, true, false, true};
        long[] indices = {1, 1, 2, 3, 2, 8};
        for (int sample = 0; sample < indices.length; sample++) for (int rotation = 0; rotation < seeds.length; rotation++) {
            boolean scalarOffset = scalarOffsets[sample];
            long index = indices[sample];
            var values = new int[4];
            for (int lane = 0; lane < 4; lane++) values[lane] = seeds[(rotation + lane * 5) % seeds.length];
            int offset = offsetModel(48, index, scalarOffset);
            var source = filled(48, 0x5a);
            storeModel(source, offset, values);
            var loaded = readFloatVectorArray(source, index, scalarOffset, 16);
            assertArrayEquals(values, lanes(loaded));
            var expected = filled(48, 0x5a);
            storeModel(expected, offset, values);
            var fromPack = filled(48, 0x5a);
            writeFloatVectorArray(fromPack, index, packed(values), scalarOffset, 16);
            assertArrayEquals(expected, fromPack);
            var fromLoad = filled(48, 0x5a);
            writeFloatVectorArray(fromLoad, index, loaded, scalarOffset, 16);
            assertArrayEquals(expected, fromLoad, "quiet NaN byte transport is not floating arithmetic");
        }
    }

    @Test void byteAliasesAndOverlappingStoresPreserveLoadedFloatingSnapshots() {
        var bytes = finiteBytes(48);
        var alias = bytes;
        var captured = readFloatVectorArray(bytes, 1, true, 16);
        var original = loadModel(bytes, 4);
        assertArrayEquals(lanes(readFloatVectorArray(alias, 1, false, 16)), lanes(readFloatVectorArray(bytes, 4, true, 16)));
        for (int b = 0; b <= 15; b++) {
            // Flip one bit per byte; the cleared exponent bit in finiteBytes stays clear.
            alias[4 + b] = (byte) (alias[4 + b] ^ 1);
            assertArrayEquals(loadModel(bytes, 4), lanes(readFloatVectorArray(alias, 1, true, 16)));
            assertArrayEquals(original, lanes(captured), "immutable snapshot after byte " + b + " mutation");
        }
        var expected = bytes.clone();
        storeModel(expected, 8, original);
        writeFloatVectorArray(alias, 2, captured, true, 16);
        assertArrayEquals(expected, bytes, "overlap uses retained vector snapshot");
        for (long index = 0; index <= 8; index++)
            assertArrayEquals(loadModel(expected, (int) (index * 4)), lanes(readFloatVectorArray(bytes, index, true, 16)));
        for (long index = 0; index <= 2; index++)
            assertArrayEquals(loadModel(expected, (int) (index * 16)), lanes(readFloatVectorArray(alias, index, false, 16)));
    }

    @Test void selectedSignalingNaNByteMovementIsReportedAsPlatformQualifiedObservation() {
        // Never extract/pack scalar floating lanes: scalar copies may quiet sNaNs.
        // This diagnostic remains separate from finite/quiet-NaN assertions.
        var source = filled(40, 0x35);
        storeModel(source, 4, new int[]{0x7f800001, 0x7fa12345, 0xff800001, 0xffa12345});
        var target = filled(40, 0x35);
        var expected = source.clone();
        writeFloatVectorArray(target, 1, readFloatVectorArray(source, 1, true, 16), true, 16);
        assertArrayEquals(Arrays.copyOfRange(expected, 0, 4), Arrays.copyOfRange(target, 0, 4));
        assertArrayEquals(Arrays.copyOfRange(expected, 20, 40), Arrays.copyOfRange(target, 20, 40));
        System.out.println("FloatX4 selected sNaN byte movement: arch=" + System.getProperty("os.arch")
            + ", java=" + System.getProperty("java.version") + ", nativeOrder=" + ByteOrder.nativeOrder()
            + ", input=" + hex(loadModel(source, 4)) + ", output=" + hex(loadModel(target, 4))
            + ", exact=" + Arrays.equals(source, target)
            + "; platform observation only, not a scalar pack/unpack or arithmetic guarantee");
    }
}
