// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OriginalStdioExpressionTest {
    @Test void tupleExecutionDoesNotDispatchThroughArgumentMetadataLists() throws Exception {
        // Native Image otherwise reaches arbitrary List.get implementations from
        // this compiled root, including deferred Core decoding and compiler code.
        try (var bytes = OriginalStdioExpression.class.getResourceAsStream("OriginalStdioExpression.class")) {
            var model = ClassFile.of().parse(bytes.readAllBytes());
            var method = model.methods().stream().filter(value -> value.methodName().stringValue().equals("executeTuple")).findFirst().orElseThrow();
            for (var element : method.code().orElseThrow()) if (element instanceof InvokeInstruction call) {
                assertFalse(call.owner().asInternalName().equals("java/util/List") && call.name().stringValue().equals("get"),
                    "tuple execution must not dispatch through generic metadata List.get");
            }
        }
    }

    @Test void unixOperandsRunOnceInOrderAndInvalidStatePreventsNativeEffects() throws Exception {
        var cases = Map.of(
            OriginalStdioOp.UNIX_GETUID, List.of("s0"),
            OriginalStdioOp.UNIX_UTIMES, List.of("a0", "a1", "s2"),
            OriginalStdioOp.UNIX_TRUNCATE, List.of("a0", "l1", "s2"),
            OriginalStdioOp.UNIX_MKNOD, List.of("a0", "i1", "l2", "s3"),
            OriginalStdioOp.UNIX_FADVISE, List.of("i0", "l1", "l2", "i3", "s4"));
        for (var item : cases.entrySet()) {
            var seen = new ArrayList<String>();
            var operands = new Expr[item.getValue().size()];
            for (int index = 0; index < operands.length; index++) {
                int position = index;
                operands[index] = new Expr() {
                    @Override public int executeInt(VirtualFrame frame) { seen.add("i" + position); return Integer.MIN_VALUE; }
                    @Override public long executeLong(VirtualFrame frame) { seen.add("l" + position); return Long.MIN_VALUE; }
                    @Override public ManagedAddress executeAddress(VirtualFrame frame) { seen.add("a" + position); return ManagedAddress.nullAddress(); }
                    @Override public Object execute(VirtualFrame frame) { seen.add("s" + position); return 17L; }
                };
            }
            var frame = frame();
            var expression = new OriginalStdioExpression(item.getKey(), operands, CoreRepresentation.UNKNOWN);
            assertTrue(seen.isEmpty(), "construction must not execute children");
            assertThrows(RuntimeFault.class, () -> expression.executeTuple(frame, new int[]{0}, 0));
            assertEquals(item.getValue(), seen, item.getKey().name());
            assertEquals(991, frame.getInt(0), "invalid State must not publish a result");
        }
    }

    @Test void invalidUnixCarrierStopsBeforeLaterOperands() throws Exception {
        for (int invalidIndex = 0; invalidIndex < 3; invalidIndex++) {
            var seen = new ArrayList<Integer>();
            var operands = new Expr[4];
            int invalid = invalidIndex;
            for (int index = 0; index < operands.length; index++) {
                int position = index;
                operands[index] = new Expr() {
                    @Override public Object execute(VirtualFrame frame) {
                        seen.add(position);
                        if (position == invalid) return Unit.INSTANCE;
                        return switch (position) {
                            case 0 -> ManagedAddress.nullAddress();
                            case 1 -> Integer.valueOf(-1);
                            case 2 -> Long.valueOf(0);
                            default -> Unit.INSTANCE;
                        };
                    }
                };
            }
            var frame = frame();
            var expression = new OriginalStdioExpression(OriginalStdioOp.UNIX_MKNOD, operands, CoreRepresentation.UNKNOWN);
            assertThrows(RuntimeFault.class, () -> expression.executeTuple(frame, new int[]{0}, 0));
            assertEquals(List.of(0, 1, 2).subList(0, invalid + 1), seen);
            assertEquals(991, frame.getInt(0));
        }
    }

    private static VirtualFrame frame() {
        var descriptor = FrameDescriptor.newBuilder();
        descriptor.addSlot(FrameSlotKind.Int, "result", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build());
        frame.setInt(0, 991);
        return frame;
    }
}
