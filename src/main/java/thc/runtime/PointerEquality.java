// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Compare the references themselves, including untouched or updated thunks. */
final class PointerEquality extends Expr {
    @Child private Expr left;
    @Child private Expr right;
    PointerEquality(Expr left, Expr right) {
        this.left = left; this.right = right;
        setRepresentation(new CoreRepresentation(CoreKind.LONG, true, false, null, null, null, null, null, null));
    }
    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
    @Override public long executeLong(VirtualFrame frame) {
        return left.execute(frame) == right.execute(frame) ? 1L : 0L;
    }
}
