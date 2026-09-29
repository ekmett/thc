// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/** Unlifted JDK carriers flow directly through the ordinary object frame slots. */
final class VectorApiExpression extends Expr {
    @Child private VectorApiOp.Site site;
    @Children private Expr[] arguments;

    VectorApiExpression(VectorApiOp operation, Expr[] arguments, int programSlot) {
        this.site = new VectorApiOp.Site(operation, programSlot); this.arguments = arguments;
    }
    @ExplodeLoop @Override public Object execute(VirtualFrame frame) {
        Object[] values = new Object[arguments.length];
        for (int index = 0; index < values.length; index++) values[index] = arguments[index].execute(frame);
        return site.execute(frame, values);
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        FrameAccess.write(frame, slots[offset], execute(frame));
        return null;
    }
}
