// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class PlusManagedAddress extends Expr {
    @Child private Expr address, displacement;
    public PlusManagedAddress(Expr address, Expr displacement) { this.address = address; this.displacement = displacement; }
    @Override public ManagedAddress execute(VirtualFrame frame) {
        var value = address.executeRequiredAddress(frame); return value.plus(displacement.executeRequiredLong(frame));
    }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) { return execute(frame); }
}
