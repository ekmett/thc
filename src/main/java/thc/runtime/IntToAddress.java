// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class IntToAddress extends Expr {
    @Child private Expr bits;
    public IntToAddress(Expr bits) { this.bits = bits; }
    @Override public Object execute(VirtualFrame frame) { return executeAddress(frame); }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) { return NativeAddresses.current(this).recover(bits.executeRequiredLong(frame)); }
}
