// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;

public final class TailCallLoop extends Node {
    @Child private LoopNode loop;
    public TailCallLoop(Metrics metrics) { loop = Truffle.getRuntime().createLoopNode(new TailCallRepeatingNode(new FrameLayout().build(), metrics)); }
    public Object execute(TailCall tail) {
        return execute(tail, null);
    }
    public Object execute(TailCall tail, Metrics invocation) {
        var repeatingValue = loop.getRepeatingNode();
        if (repeatingValue == null) {
            CompilerDirectives.transferToInterpreter();
            throw new NullPointerException("null cannot be cast to non-null type thc.runtime.TailCallRepeatingNode");
        }
        TailCallRepeatingNode repeating = (TailCallRepeatingNode) repeatingValue;
        VirtualFrame frame = Truffle.getRuntime().createVirtualFrame(
            invocation == null ? new Object[0] : new Object[]{invocation}, repeating.getDescriptor());
        repeating.setNext(frame, tail);
        loop.execute(frame);
        return frame.getObject(FrameLayout.TAIL_RESULT);
    }
}
