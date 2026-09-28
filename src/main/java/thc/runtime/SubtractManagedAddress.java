// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class SubtractManagedAddress extends Expr {
    @Child private Expr left, right;
    public SubtractManagedAddress(Expr left, Expr right) { this.left = left; this.right = right; }
    @Override public Long execute(VirtualFrame frame) { return executeLong(frame); }
    @Override public long executeLong(VirtualFrame frame) { return left.executeRequiredAddress(frame).difference(right.executeRequiredAddress(frame)); }
}
