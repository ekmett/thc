// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Fixed edge cases calibrate the shared model before it judges generated native rows. */
public final class ScalarPrimopModelTest {
    @Test void signedNarrowingAndUnsignedOrderingDoNotInheritJvmLongSemantics() {
        record Case(String operation, int width, boolean unsigned, long left, long right, long expected) {}
        for (Case c : List.of(
                new Case("plus", 8, false, 127, 1, -128),
                new Case("quot", 8, false, -7, 2, -3),
                new Case("rem", 8, false, -7, 2, -1),
                new Case("quot", 8, true, -1, 2, 127),
                new Case("gt", 8, true, -1, 0, 1),
                new Case("not", 8, true, 0x55, 0, 0xaa),
                new Case("uncheckedShiftRL", 8, true, -1, 7, 1),
                new Case("shiftRA", 64, false, Long.MIN_VALUE, 1, Long.MIN_VALUE >> 1),
                new Case("shiftRL", 64, false, Long.MIN_VALUE, 1, Long.MIN_VALUE >>> 1),
                new Case("times", 64, true, -1, 2, -2))) {
            assertEquals(c.expected(), ScalarPrimopModel.scalar(
                c.operation(), c.width(), c.unsigned(), c.left(), c.right()), c.toString());
        }
    }

    @Test void bitMovementAndExplicit64ControlsHaveFixedBoundaryResults() {
        assertEquals(8L, ScalarPrimopModel.bit("clz", 8, 0));
        assertEquals(8L, ScalarPrimopModel.bit("ctz", 8, 0));
        assertEquals(8L, ScalarPrimopModel.bit("popCnt", 8, -1));
        assertEquals(0x80L, ScalarPrimopModel.bit("bitReverse", 8, 1));
        assertEquals(0x3412L, ScalarPrimopModel.bit("byteSwap", 16, 0x1234));
        assertEquals(0x22L, ScalarPrimopModel.scalar("pdep", 8, true, 0b0101, 0xaa));
        assertEquals(0b0101L, ScalarPrimopModel.scalar("pext", 8, true, 0x22, 0xaa));
        assertEquals(0L, ScalarPrimopModel.scalar("pdep", 8, true, -1, 0x100));
        assertEquals(0L, ScalarPrimopModel.scalar("pext", 8, true, 0x100, 0x100));
        assertEquals(Long.MIN_VALUE, ScalarPrimopModel.scalar("pdep", 64, true, 1, Long.MIN_VALUE));
        assertEquals(1L, ScalarPrimopModel.scalar("pext", 64, true, Long.MIN_VALUE, Long.MIN_VALUE));
        assertEquals(-1L, ScalarPrimopModel.explicit64("literals", false, 2, 0));
        assertEquals(13L, ScalarPrimopModel.explicit64("case", true, Long.MIN_VALUE, 0));
    }

    @Test void invalidWidthsUnknownOperationsAndDivisionByZeroStillReject() {
        for (int width : new int[] {0, 65}) {
            assertThrows(IllegalArgumentException.class,
                () -> ScalarPrimopModel.scalar("identity", width, false, 1, 0));
            assertThrows(IllegalArgumentException.class, () -> ScalarPrimopModel.bit("popCnt", width, 1));
        }
        for (boolean unsigned : new boolean[] {false, true}) {
            for (String operation : List.of("quot", "rem")) {
                assertThrows(ArithmeticException.class,
                    () -> ScalarPrimopModel.scalar(operation, 8, unsigned, 1, 0));
            }
        }
        assertThrows(IllegalStateException.class,
            () -> ScalarPrimopModel.scalar("unknown", 64, false, 1, 0));
        assertThrows(IllegalStateException.class, () -> ScalarPrimopModel.bit("unknown", 64, 1));
    }
}
