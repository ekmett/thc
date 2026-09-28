// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class CompareOrderedManagedAddress extends Expr {
    @Child private Expr left, right;
    private final ManagedAddressOrder order;
    public CompareOrderedManagedAddress(Expr left, Expr right, ManagedAddressOrder order) { this.left = left; this.right = right; this.order = order; }
    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
    @Override public long executeLong(VirtualFrame frame) { return order.accepts(left.executeRequiredAddress(frame).compareWithinAllocation(right.executeRequiredAddress(frame))) ? 1L : 0L; }
}
