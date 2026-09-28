// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class IndexManagedByte extends Expr {
    private final boolean signed;
    @Child private Expr address, displacement;
    public IndexManagedByte(boolean signed, Expr address, Expr displacement) { this.signed = signed; this.address = address; this.displacement = displacement; }
    @Override public Object execute(VirtualFrame frame) { return executeInt(frame); }
    @Override public int executeInt(VirtualFrame frame) {
        var value = address.executeRequiredAddress(frame);
        int bits = value.readWord8Int(displacement.executeRequiredLong(frame)); return signed ? (byte) bits : bits;
    }
}
