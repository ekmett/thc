// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

final class TruffleStringExpression extends Expr {
    @Child private TruffleStringOp.Site site;
    @Children private Expr[] arguments;

    TruffleStringExpression(TruffleStringOp operation, Expr[] arguments) {
        this.site = new TruffleStringOp.Site(operation);
        this.arguments = arguments;
    }
    @ExplodeLoop @Override public Object execute(VirtualFrame frame) {
        Object[] values = new Object[arguments.length];
        for (int i = 0; i < values.length; i++) values[i] = arguments[i].execute(frame);
        return site.execute(values);
    }
}
