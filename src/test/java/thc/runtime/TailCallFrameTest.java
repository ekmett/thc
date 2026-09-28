// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TailCallFrameTest {
    @Test void missingRepeatingNodeFailsBeforeDispatchingTheTransfer() throws Exception {
        var loop = new TailCallLoop(new Metrics(false));
        var target = new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Invalid trampoline loop reached dispatch"); }
        }.getCallTarget();
        var transfer = new TailCall(target, new Object[]{23L});
        // Break only this test's private loop invariant to retain the existing
        // cold cast failure; production construction always supplies a repeater.
        var field = TailCallLoop.class.getDeclaredField("loop"); field.setAccessible(true);
        var original = field.get(loop);
        try {
            field.set(loop, new LoopNode() {
                @Override public RepeatingNode getRepeatingNode() { return null; }
            });
            var failure = assertThrows(NullPointerException.class, () -> loop.execute(transfer));
            assertEquals("null cannot be cast to non-null type thc.runtime.TailCallRepeatingNode", failure.getMessage());
            assertArrayEquals(new Object[]{23L}, transfer.getArgs());
            assertSame(target, transfer.getTarget());
        } finally { field.set(loop, original); }
    }

    @Test void missingTargetAndTransferFailBeforeClearingOrDispatchingTheFrame() throws Exception {
        var descriptor = new FrameLayout().build();
        var repeating = new TailCallRepeatingNode(descriptor, new Metrics(false));
        var target = new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Invalid trampoline frame reached dispatch"); }
        }.getCallTarget();
        var transfer = new TailCall(target, new Object[]{0L});
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
        var sentinel = new Object();
        frame.setObject(FrameLayout.TAIL_RESULT, sentinel);
        frame.setObject(FrameLayout.TAIL_ARGUMENTS, transfer);
        var missingTarget = assertThrows(NullPointerException.class, () -> repeating.executeRepeating(frame));
        assertEquals("null cannot be cast to non-null type com.oracle.truffle.api.RootCallTarget", missingTarget.getMessage());
        assertSame(transfer, frame.getObject(FrameLayout.TAIL_ARGUMENTS));
        assertSame(sentinel, frame.getObject(FrameLayout.TAIL_RESULT));
        frame.setObject(FrameLayout.TAIL_FUNCTION, target);
        frame.setObject(FrameLayout.TAIL_ARGUMENTS, null);
        var missingTransfer = assertThrows(NullPointerException.class, () -> repeating.executeRepeating(frame));
        assertEquals("null cannot be cast to non-null type thc.runtime.TailCall", missingTransfer.getMessage());
        assertSame(target, frame.getObject(FrameLayout.TAIL_FUNCTION));
        assertSame(sentinel, frame.getObject(FrameLayout.TAIL_RESULT));
    }
}
