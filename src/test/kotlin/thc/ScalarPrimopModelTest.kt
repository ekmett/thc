// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Fixed edge cases calibrate the shared model before it judges generated native rows. */
class ScalarPrimopModelTest {
    @Test fun signedNarrowingAndUnsignedOrderingDoNotInheritJvmLongSemantics() {
        data class Case(val operation: String, val width: Int, val unsigned: Boolean,
            val left: Long, val right: Long, val expected: Long)
        for (case in listOf(
            Case("plus", 8, false, 127, 1, -128),
            Case("quot", 8, false, -7, 2, -3),
            Case("rem", 8, false, -7, 2, -1),
            Case("quot", 8, true, -1, 2, 127),
            Case("gt", 8, true, -1, 0, 1),
            Case("not", 8, true, 0x55, 0, 0xaa),
            Case("uncheckedShiftRL", 8, true, -1, 7, 1),
            Case("shiftRA", 64, false, Long.MIN_VALUE, 1, Long.MIN_VALUE shr 1),
            Case("shiftRL", 64, false, Long.MIN_VALUE, 1, Long.MIN_VALUE ushr 1),
            Case("times", 64, true, -1, 2, -2)
        )) assertEquals(case.expected, ScalarPrimopModel.scalar(
            case.operation, case.width, case.unsigned, case.left, case.right), case.toString())
    }

    @Test fun bitMovementAndExplicit64ControlsHaveFixedBoundaryResults() {
        assertEquals(8L, ScalarPrimopModel.bit("clz", 8, 0))
        assertEquals(8L, ScalarPrimopModel.bit("ctz", 8, 0))
        assertEquals(8L, ScalarPrimopModel.bit("popCnt", 8, -1))
        assertEquals(0x80L, ScalarPrimopModel.bit("bitReverse", 8, 1))
        assertEquals(0x3412L, ScalarPrimopModel.bit("byteSwap", 16, 0x1234))
        assertEquals(0x22L, ScalarPrimopModel.scalar("pdep", 8, true, 0b0101, 0xaa))
        assertEquals(0b0101L, ScalarPrimopModel.scalar("pext", 8, true, 0x22, 0xaa))
        assertEquals(0L, ScalarPrimopModel.scalar("pdep", 8, true, -1, 0x100))
        assertEquals(0L, ScalarPrimopModel.scalar("pext", 8, true, 0x100, 0x100))
        assertEquals(Long.MIN_VALUE, ScalarPrimopModel.scalar("pdep", 64, true, 1, Long.MIN_VALUE))
        assertEquals(1L, ScalarPrimopModel.scalar("pext", 64, true, Long.MIN_VALUE, Long.MIN_VALUE))
        assertEquals(-1L, ScalarPrimopModel.explicit64("literals", false, 2, 0))
        assertEquals(13L, ScalarPrimopModel.explicit64("case", true, Long.MIN_VALUE, 0))
    }
}
