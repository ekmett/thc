// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OperandRetentionTest {
    private static VirtualFrame frame(int slots) {
        var builder = FrameDescriptor.newBuilder();
        for (int i = 0; i < slots; i++) builder.addSlot(FrameSlotKind.Illegal, "slot " + i, null);
        return Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
    }
    @FunctionalInterface private interface Action { Object run(VirtualFrame frame) throws Exception; }
    private static Expr expression(Action action) {
        return new Expr() {
            @Override public Object execute(VirtualFrame frame) {
                try { return action.run(frame); }
                catch (Exception failure) { return OperandRetentionTest.<RuntimeException, Object>rethrow(failure); }
            }
        };
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T rethrow(Throwable failure) throws E { throw (E) failure; }
    private final CoreRepresentation integral = new CoreRepresentation(CoreKind.LONG, true, false, null, null, null, null, null, null);
    private static void assertCleared(VirtualFrame frame, int[] slots) throws Exception {
        // Public reads reject a cleared (Illegal) slot. Inspect the pinned
        // frame's reference storage to verify release without GC timing.
        var field = frame.getClass().getDeclaredField("indexedLocals"); field.setAccessible(true);
        var references = (Object[]) field.get(frame);
        for (int slot : slots) {
            assertEquals(FrameSlotKind.Illegal.tag, frame.getTag(slot));
            assertNull(references[slot], "Completed transfer must release scratch slot " + slot);
        }
    }
    private static List<FrameSlotKind> kinds(VirtualFrame frame, int[] slots) {
        var kinds = new ArrayList<FrameSlotKind>(slots.length);
        for (int slot : slots) kinds.add(frame.getFrameDescriptor().getSlotKind(slot));
        return kinds;
    }
    @Test void selfTransferReleasesEveryOperandIncludingUnusedFormalsWithoutWideningSlots() throws Exception {
        var frame = frame(7);
        var first = new Object(); var second = new Object(); var unused = new Object();
        int[] temporaries = {3, 4, 5, 6};
        var unknown = CoreRepresentation.UNKNOWN;
        var layout = new AstSelfLayout(null, new int[0], new int[]{0, 1, 2, -1},
            new CoreRepresentation[]{unknown, unknown, integral, unknown}, new boolean[4]);
        var target = new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Self transfer must not call its target"); }
        }.getCallTarget();
        var function = new Closure(null, 4, target);
        FrameAccess.write(frame, 0, first); FrameAccess.write(frame, 1, second);
        FrameAccess.writeLong(frame, 2, Long.MIN_VALUE);
        for (long value : new long[]{Long.MAX_VALUE, Long.MIN_VALUE, 3_000_000_017L}) {
            var oldFirst = FrameAccess.read(frame, 0); var oldSecond = FrameAccess.read(frame, 1);
            FrameAccess.write(frame, 3, oldSecond); FrameAccess.write(frame, 4, oldFirst);
            FrameAccess.writeLong(frame, 5, value); FrameAccess.write(frame, 6, unused);
            var kinds = kinds(frame, temporaries);
            assertSame(AstSelfCall.INSTANCE, assertThrows(AstSelfCall.class, () -> layout.transfer(frame, function, temporaries)));
            assertSame(oldSecond, FrameAccess.read(frame, 0)); assertSame(oldFirst, FrameAccess.read(frame, 1));
            assertEquals(value, frame.getLong(2)); assertCleared(frame, temporaries);
            assertEquals(kinds, kinds(frame, temporaries));
            assertEquals(FrameSlotKind.Long, frame.getFrameDescriptor().getSlotKind(5));
        }
    }
    @Test void joinTransferReleasesCompletedOperandsButDoesNotMoveOrClearOnOperandFailure() throws Exception {
        var frame = frame(6); var first = new Object(); var second = new Object();
        var failure = new RuntimeFault("join operand failure");
        boolean[] fail = {false};
        var order = new ArrayList<Integer>();
        var unknown = CoreRepresentation.UNKNOWN;
        var target = new LocalJoinTarget(new Object(), 0, new int[]{0, 1, 2}, new CoreRepresentation[]{unknown, unknown, integral});
        int[] temporaries = {3, 4, 5};
        var metrics = new Metrics(true);
        var call = new LocalJoinCall(new thc.Language(), target, new Expr[]{
            expression(f -> { order.add(0); return FrameAccess.read(f, 1); }),
            expression(f -> { order.add(1); return FrameAccess.read(f, 0); }),
            expression(f -> { order.add(2); if (fail[0]) throw failure; return f.getLong(2) + 1; })}, temporaries, metrics);
        FrameAccess.write(frame, 0, first); FrameAccess.write(frame, 1, second);
        FrameAccess.writeLong(frame, 2, Long.MAX_VALUE);
        assertSame(target.getJump(), assertThrows(LocalJoinJump.class, () -> call.execute(frame)));
        assertEquals(List.of(0, 1, 2), order);
        assertSame(second, FrameAccess.read(frame, 0)); assertSame(first, FrameAccess.read(frame, 1));
        assertEquals(Long.MIN_VALUE, frame.getLong(2)); assertCleared(frame, temporaries);
        assertEquals(FrameSlotKind.Long, frame.getFrameDescriptor().getSlotKind(5));
        assertEquals(1L, metrics.getLocalJoinTransfers());
        fail[0] = true; order.clear();
        assertSame(failure, assertThrows(RuntimeFault.class, () -> call.execute(frame)));
        assertEquals(List.of(0, 1, 2), order);
        assertSame(second, FrameAccess.read(frame, 0), "No destination changes before all operands finish");
        assertSame(first, FrameAccess.read(frame, 1)); assertEquals(Long.MIN_VALUE, frame.getLong(2));
        assertSame(first, FrameAccess.read(frame, 3), "An incomplete transfer has not cleared its operands");
        assertSame(second, FrameAccess.read(frame, 4)); assertEquals(1L, metrics.getLocalJoinTransfers());
    }
}
