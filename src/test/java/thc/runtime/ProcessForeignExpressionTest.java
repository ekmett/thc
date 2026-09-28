// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessForeignExpressionTest {
    private static final class Stop extends RuntimeException {}

    @Test void typedOperandsRunOnceInOrderBeforeTheStateAndNativeEffects() throws Exception {
        for (var operation : ProcessOp.values()) {
            // Independent ABI expectations, not derived from the metadata being selected.
            var expected = switch (operation) {
                case CREATE -> List.of("a0", "a1", "a2", "i3", "i4", "i5", "a6", "a7", "a8", "a9", "a10", "i11", "a12", "s13");
                case POLL, WAIT -> List.of("i0", "a1", "s2");
                case TERMINATE -> List.of("i0", "s1");
            };
            var seen = new ArrayList<String>();
            var stop = new Stop();
            var operands = new Expr[expected.size()];
            for (int index = 0; index < operands.length; index++) {
                int position = index;
                operands[index] = new Expr() {
                    @Override public int executeInt(VirtualFrame frame) { seen.add("i" + position); return Integer.MIN_VALUE; }
                    @Override public ManagedAddress executeAddress(VirtualFrame frame) { seen.add("a" + position); return ManagedAddress.nullAddress(); }
                    @Override public Object execute(VirtualFrame frame) { seen.add("s" + position); throw stop; }
                };
            }
            var descriptor = FrameDescriptor.newBuilder();
            int slot = descriptor.addSlot(FrameSlotKind.Int, "result", null);
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build());
            frame.setInt(slot, 991);
            var expression = new ProcessForeignExpression(operation, operands, CoreRepresentation.UNKNOWN);
            assertTrue(seen.isEmpty(), "construction must not evaluate operands");
            assertSame(stop, assertThrows(Stop.class, () -> expression.executeTuple(frame, new int[]{slot}, 0)));
            assertEquals(expected, seen, operation.name());
            assertEquals(991, frame.getInt(slot), "failed State operand must not publish a result");
        }
    }

    @Test void invalidPrimitiveOrAddressStopsBeforeLaterOperands() throws Exception {
        for (int invalidIndex : new int[]{0, 1}) {
            var seen = new ArrayList<Integer>();
            var operands = new Expr[3];
            for (int index = 0; index < operands.length; index++) {
                int position = index;
                operands[index] = new Expr() {
                    @Override public Object execute(VirtualFrame frame) {
                        seen.add(position);
                        if (position == invalidIndex) return position == 0 ? Long.valueOf(7) : Unit.INSTANCE;
                        return position == 0 ? Integer.valueOf(7) : position == 1 ? ManagedAddress.nullAddress() : Unit.INSTANCE;
                    }
                };
            }
            var descriptor = FrameDescriptor.newBuilder();
            int slot = descriptor.addSlot(FrameSlotKind.Int, "result", null);
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build());
            frame.setInt(slot, 991);
            var expression = new ProcessForeignExpression(ProcessOp.POLL, operands, CoreRepresentation.UNKNOWN);
            assertThrows(RuntimeFault.class, () -> expression.executeTuple(frame, new int[]{slot}, 0));
            assertEquals(invalidIndex == 0 ? List.of(0) : List.of(0, 1), seen);
            assertEquals(991, frame.getInt(slot));
        }
    }
}
