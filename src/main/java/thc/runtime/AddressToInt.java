// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class AddressToInt extends Expr {
    @Child private Expr address;
    public AddressToInt(Expr address) { this.address = address; }
    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
    @Override public long executeLong(VirtualFrame frame) { return address.executeRequiredAddress(frame).toNativeBits(); }
}
