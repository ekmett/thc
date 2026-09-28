// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class CompareManagedAddress extends Expr {
    @Child private Expr left, right;
    private final boolean negate;
    public CompareManagedAddress(Expr left, Expr right, boolean negate) { this.left = left; this.right = right; this.negate = negate; }
    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
    @Override public long executeLong(VirtualFrame frame) { return left.executeRequiredAddress(frame).sameLocation(right.executeRequiredAddress(frame)) != negate ? 1L : 0L; }
}
