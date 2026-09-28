// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class RegisterMainThread extends Expr {
    @Child private Expr weak;
    @Child private Expr state;

    RegisterMainThread(Expr weak, Expr state) {
        this.weak = weak;
        this.state = state;
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Main-thread registration requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var handle = weak.execute(frame);
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        CoreMainThreadForeign.register(this, handle);
        return null;
    }
}
