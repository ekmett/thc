// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WindowsCpuAffinityTest {
    private fun records(vararg values: IntArray): MemorySegment {
        val bytes = ByteBuffer.allocate(values.sumOf { it[0] }).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) {
            val start = bytes.position()
            bytes.putInt(value[0]).putInt(value[1])
            if (value[1] == 0) {
                bytes.putInt(value[2]).putShort(value[3].toShort()).put(value[4].toByte())
                bytes.put(start + 19, value[5].toByte())
            }
            bytes.position(start + value[0])
        }
        return MemorySegment.ofArray(bytes.array())
    }

    @Test fun sizedRecordsRetainGroupIdentityAndSkipForeignReservations() {
        val parsed = WindowsCpuAffinity.decodeCpuSets(records(
            intArrayOf(40, 0, 10, 2, 63, 0),
            intArrayOf(8, 99),
            intArrayOf(32, 0, 11, 3, 0, 2),
            intArrayOf(32, 0, 12, 3, 1, 6),
            intArrayOf(32, 0, 13, 3, 2, 1)))
        assertEquals(listOf(WindowsCpuAffinity.Cpu(10, 2, 63),
            WindowsCpuAffinity.Cpu(12, 3, 1), WindowsCpuAffinity.Cpu(13, 3, 2)), parsed)
    }

    @Test fun malformedAndDuplicateRecordsAreUnavailable() {
        assertNull(WindowsCpuAffinity.decodeCpuSets(MemorySegment.ofArray(ByteArray(7))))
        assertNull(WindowsCpuAffinity.decodeCpuSets(MemorySegment.ofArray(ByteArray(32))))
        assertNull(WindowsCpuAffinity.decodeCpuSets(records(intArrayOf(32, 0, 1, 0, 64, 0))))
        assertNull(WindowsCpuAffinity.decodeCpuSets(records(
            intArrayOf(32, 0, 1, 0, 1, 0), intArrayOf(32, 0, 1, 0, 2, 0))))
        assertNull(WindowsCpuAffinity.decodeCpuSets(records(
            intArrayOf(32, 0, 1, 0, 1, 0), intArrayOf(32, 0, 2, 0, 1, 0))))
    }

    @Test fun selectionRespectsBothGroupAndSparseHardMask() {
        val cpu = WindowsCpuAffinity.Cpu(81, 4, 63)
        assertEquals(CpuCoordinate(4, 63), cpu.coordinate, "OS group/processor is not CPU Set ID 81")
        assertTrue(WindowsCpuAffinity.eligible(cpu, 4, Long.MIN_VALUE, intArrayOf()))
        assertTrue(WindowsCpuAffinity.eligible(cpu, 4, Long.MIN_VALUE, intArrayOf(81)))
        assertFalse(WindowsCpuAffinity.eligible(cpu, 0, Long.MIN_VALUE, intArrayOf()))
        assertFalse(WindowsCpuAffinity.eligible(cpu, 4, 1L, intArrayOf()))
        assertFalse(WindowsCpuAffinity.eligible(cpu, 4, Long.MIN_VALUE, intArrayOf(80)))
    }

    @Test fun virtualThreadsNeverBindTheirCarrier() {
        val result = java.util.concurrent.FutureTask { WindowsCpuAffinity.discover() }
        Thread.ofVirtual().start(result).join()
        assertNull(result.get())
    }
}
