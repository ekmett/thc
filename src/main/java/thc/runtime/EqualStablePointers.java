// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

final class EqualStablePointers extends Expr {
    @Child private Expr left;
    @Child private Expr right;
    EqualStablePointers(Expr left, Expr right) { this.left = left; this.right = right; }
    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
    @Override public long executeLong(VirtualFrame frame) {
        return StablePointers.current(this).equal(left.executeRequiredAddress(frame), right.executeRequiredAddress(frame)) ? 1L : 0L;
    }
}
