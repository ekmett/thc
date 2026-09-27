// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindowsCpuAffinityTest {
    private MemorySegment records(int[]... values) {
        int size = 0;
        for (var value : values) size += value[0];
        var bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            int start = bytes.position();
            bytes.putInt(value[0]).putInt(value[1]);
            if (value[1] == 0) {
                bytes.putInt(value[2]).putShort((short) value[3]).put((byte) value[4]);
                bytes.put(start + 19, (byte) value[5]);
            }
            bytes.position(start + value[0]);
        }
        return MemorySegment.ofArray(bytes.array());
    }

    @Test void sizedRecordsRetainGroupIdentityAndSkipForeignReservations() {
        var parsed = WindowsCpuAffinity.decodeCpuSets(records(
            new int[] {40, 0, 10, 2, 63, 0},
            new int[] {8, 99},
            new int[] {32, 0, 11, 3, 0, 2},
            new int[] {32, 0, 12, 3, 1, 6},
            new int[] {32, 0, 13, 3, 2, 1}));
        assertEquals(List.of(new WindowsCpuAffinity.Cpu(10, 2, 63),
            new WindowsCpuAffinity.Cpu(12, 3, 1), new WindowsCpuAffinity.Cpu(13, 3, 2)), parsed);
    }

    @Test void malformedAndDuplicateRecordsAreUnavailable() {
        assertNull(WindowsCpuAffinity.decodeCpuSets(MemorySegment.ofArray(new byte[7])));
        assertNull(WindowsCpuAffinity.decodeCpuSets(MemorySegment.ofArray(new byte[32])));
        assertNull(WindowsCpuAffinity.decodeCpuSets(records(new int[] {32, 0, 1, 0, 64, 0})));
        assertNull(WindowsCpuAffinity.decodeCpuSets(records(
            new int[] {32, 0, 1, 0, 1, 0}, new int[] {32, 0, 1, 0, 2, 0})));
        assertNull(WindowsCpuAffinity.decodeCpuSets(records(
            new int[] {32, 0, 1, 0, 1, 0}, new int[] {32, 0, 2, 0, 1, 0})));
    }

    @Test void selectionRespectsBothGroupAndSparseHardMask() {
        var cpu = new WindowsCpuAffinity.Cpu(81, 4, 63);
        assertEquals(new CpuCoordinate(4, 63), cpu.coordinate(), "OS group/processor is not CPU Set ID 81");
        assertTrue(WindowsCpuAffinity.eligible(cpu, 4, Long.MIN_VALUE, new int[0]));
        assertTrue(WindowsCpuAffinity.eligible(cpu, 4, Long.MIN_VALUE, new int[] {81}));
        assertFalse(WindowsCpuAffinity.eligible(cpu, 0, Long.MIN_VALUE, new int[0]));
        assertFalse(WindowsCpuAffinity.eligible(cpu, 4, 1L, new int[0]));
        assertFalse(WindowsCpuAffinity.eligible(cpu, 4, Long.MIN_VALUE, new int[] {80}));
        assertFalse(WindowsCpuAffinity.eligible(new WindowsCpuAffinity.Cpu(81, 4, -1), 4, -1L, new int[0]));
        assertFalse(WindowsCpuAffinity.eligible(new WindowsCpuAffinity.Cpu(81, 4, 64), 4, -1L, new int[0]));
    }

    @Test void unsignedSizesAndGroupCoordinatesRemainDistinct() {
        assertEquals(List.of(new WindowsCpuAffinity.Cpu(-1, 65535, 63), new WindowsCpuAffinity.Cpu(1, 0, 63)),
            WindowsCpuAffinity.decodeCpuSets(records(new int[] {32, 0, -1, 65535, 63, 0}, new int[] {32, 0, 1, 0, 63, 0})));
        assertEquals(List.of(), WindowsCpuAffinity.decodeCpuSets(MemorySegment.ofArray(new byte[0])));
        for (int size : new int[] {7, 31, 33, -1, Integer.MIN_VALUE}) {
            var bytes = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(size);
            assertNull(WindowsCpuAffinity.decodeCpuSets(MemorySegment.ofArray(bytes.array())), "size=" + size);
        }
    }

    @Test void virtualThreadsNeverBindTheirCarrier() throws Exception {
        var result = new FutureTask<>(WindowsCpuAffinity::discover);
        Thread.ofVirtual().start(result).join();
        assertNull(result.get());
    }
}
