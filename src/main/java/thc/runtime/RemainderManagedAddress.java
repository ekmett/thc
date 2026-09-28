// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class RemainderManagedAddress extends Expr {
    @Child private Expr address, divisor;
    public RemainderManagedAddress(Expr address, Expr divisor) { this.address = address; this.divisor = divisor; }
    @Override public Long execute(VirtualFrame frame) { return executeLong(frame); }
    @Override public long executeLong(VirtualFrame frame) { return address.executeRequiredAddress(frame).remainder(divisor.executeRequiredLong(frame)); }
}
