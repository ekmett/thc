// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.List;
public final class IndexManagedScalarAddress extends Expr {
    private final ManagedAddressRead operation;
    @Child private Expr address, element;
    public IndexManagedScalarAddress(ManagedAddressRead operation, Expr address, Expr element) {
        this.operation = operation; this.address = address; this.element = element;
        setRepresentation(new CoreRepresentation(CoreKind.LONG, true, true, List.of(operation.getPayload()),
            null, null, null, null, null));
    }
    // Native loads and borrow retirement retain their boundaries, but invoke no guest activation.
    @Override public boolean needsCallState() { return address.needsCallState() || element.needsCallState(); }
    @Override public Object execute(VirtualFrame frame) { if (operation.isInt()) return executeInt(frame); return executeLong(frame); }
    @Override public int executeInt(VirtualFrame frame) { return operation.readInt(address.executeRequiredAddress(frame), element.executeRequiredLong(frame)); }
    @Override public long executeLong(VirtualFrame frame) { return operation.read(address.executeRequiredAddress(frame), element.executeRequiredLong(frame)); }
}
