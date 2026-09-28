// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import jdk.incubator.vector.IntVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.VectorMemory.*;

class Int32VectorStorageTest {
    private int[] lanes(IntVector value) {
        return new int[]{value.lane(0), value.lane(1), value.lane(2), value.lane(3)};
    }
    private IntVector vector(int[] values) {
        return IntVector.broadcast(IntVector.SPECIES_128, values[0])
            .withLane(1, values[1]).withLane(2, values[2]).withLane(3, values[3]);
    }
    private int shift(int b) { return 8 * (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : 3 - b); }
    private int[] loadModel(byte[] bytes, int offset) {
        var values = new int[4];
        for (int lane = 0; lane < 4; lane++) for (int b = 0; b < 4; b++)
            values[lane] |= (bytes[offset + lane * 4 + b] & 255) << shift(b);
        return values;
    }
    private void storeModel(byte[] bytes, int offset, int[] values) {
        for (int lane = 0; lane < 4; lane++) for (int b = 0; b < 4; b++)
            bytes[offset + lane * 4 + b] = (byte) (values[lane] >>> shift(b));
    }
    private Integer offsetModel(int size, long index, boolean scalarOffset) {
        var offset = BigInteger.valueOf(index).multiply(BigInteger.valueOf(scalarOffset ? 4 : 16));
        return offset.signum() < 0 || offset.add(BigInteger.valueOf(16)).compareTo(BigInteger.valueOf(size)) > 0
            ? null : offset.intValueExact();
    }

    @Test void smallArrayBoundariesAndFullWidthIndicesMatchIndependentByteModels() {
        var indices = new ArrayList<Long>();
        for (long i = -2; i <= 12; i++) indices.add(i);
        indices.addAll(List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE,
            (long) Integer.MIN_VALUE, (long) Integer.MAX_VALUE - 1, (long) Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1,
            (1L << 32) - 1, 1L << 32, (1L << 32) + 1,
            (1L << 60) - 1, 1L << 60, (1L << 60) + 1,
            (1L << 62) - 1, 1L << 62, (1L << 62) + 1));
        for (int stride : new int[]{4, 16}) for (long delta = -1; delta <= 1; delta++)
            indices.add((long) Integer.MAX_VALUE / stride + delta);
        var values = new int[]{Integer.MIN_VALUE, 0x01234567, -1, Integer.MAX_VALUE};
        for (int size = 0; size <= 40; size++) for (boolean scalarOffset : new boolean[]{false, true}) for (long index : indices) {
            var bytes = new byte[size];
            for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 73 + size * 17 + 129);
            var before = bytes.clone();
            var offset = offsetModel(size, index, scalarOffset);
            var label = "size=" + size + "/index=" + index + "/scalar=" + scalarOffset;
            if (offset == null) {
                assertThrows(RuntimeFault.class, () -> readIntVectorArray(bytes, index, scalarOffset, "Int32X4", 16), label);
                assertArrayEquals(before, bytes, "failed read " + label);
                assertThrows(RuntimeFault.class, () -> writeIntVectorArray(bytes, index, vector(values), scalarOffset, "Int32X4", 16), label);
                assertArrayEquals(before, bytes, "no partial write " + label);
            } else {
                assertArrayEquals(loadModel(before, offset), lanes(readIntVectorArray(bytes, index, scalarOffset, "Int32X4", 16)), label);
                assertArrayEquals(before, bytes, "read preserved storage " + label);
                var expected = before.clone();
                storeModel(expected, offset, values);
                writeIntVectorArray(bytes, index, vector(values), scalarOffset, "Int32X4", 16);
                assertArrayEquals(expected, bytes, "full vector and sentinel neighbours " + label);
            }
        }
    }

    @Test void everyLaneBitAndSignedPatternsUseNativeOrderAtBothOffsetUnits() {
        var patterns = new ArrayList<int[]>();
        patterns.add(new int[]{0, Integer.MIN_VALUE, Integer.MAX_VALUE, -1});
        patterns.add(new int[]{0x01234567, 0x89abcdef, 0x55aa55aa, 0xaa55aa55});
        patterns.add(new int[]{0x00000080, 0x00008000, 0x00800000, Integer.MIN_VALUE});
        for (int bit = 0; bit <= 31; bit++) patterns.add(new int[]{
            1 << bit, ~(1 << bit), 1 << ((bit + 11) % 32), 1 << ((bit + 23) % 32)});
        boolean[] scalarOffsets = {false, true, true, true};
        long[] indices = {1, 1, 2, 3};
        for (int sample = 0; sample < indices.length; sample++) for (var values : patterns) {
            boolean scalarOffset = scalarOffsets[sample];
            long index = indices[sample];
            int offset = offsetModel(48, index, scalarOffset);
            var expected = new byte[48];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 29 + 83);
            var actual = expected.clone();
            storeModel(expected, offset, values);
            assertArrayEquals(values, lanes(readIntVectorArray(expected, index, scalarOffset, "Int32X4", 16)));
            writeIntVectorArray(actual, index, vector(values), scalarOffset, "Int32X4", 16);
            assertArrayEquals(expected, actual);
        }
    }

    @Test void byteAliasesAndOverlappingStoresObserveWritesButKeepLoadedLanes() {
        var bytes = new byte[48];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 19 + 131);
        var alias = bytes;
        var captured = readIntVectorArray(bytes, 1, true, "Int32X4", 16);
        var original = loadModel(bytes, 4);
        assertArrayEquals(loadModel(bytes, 16), lanes(readIntVectorArray(alias, 1, false, "Int32X4", 16)));
        assertArrayEquals(lanes(readIntVectorArray(alias, 1, false, "Int32X4", 16)), lanes(readIntVectorArray(bytes, 4, true, "Int32X4", 16)));
        for (int b = 0; b <= 15; b++) {
            alias[4 + b] = (byte) (alias[4 + b] ^ 0x80);
            assertArrayEquals(loadModel(bytes, 4), lanes(readIntVectorArray(bytes, 1, true, "Int32X4", 16)));
            assertArrayEquals(original, lanes(captured), "loaded lane snapshot after byte " + b + " mutation");
        }
        var expected = bytes.clone();
        storeModel(expected, 8, original);
        writeIntVectorArray(alias, 2, captured, true, "Int32X4", 16);
        assertArrayEquals(expected, bytes, "overlapping store uses captured lane values");
        for (long index = 0; index <= 8; index++)
            assertArrayEquals(loadModel(expected, (int) (index * 4)), lanes(readIntVectorArray(bytes, index, true, "Int32X4", 16)));
        for (long index = 0; index <= 2; index++)
            assertArrayEquals(loadModel(expected, (int) (index * 16)), lanes(readIntVectorArray(alias, index, false, "Int32X4", 16)));
    }
}
