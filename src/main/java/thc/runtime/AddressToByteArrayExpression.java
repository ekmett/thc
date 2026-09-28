// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class AddressToByteArrayExpression extends Expr {
    @Child private Expr source, destination, offset, count, state;
    public AddressToByteArrayExpression(CoreRepresentation proof, Expr source, Expr destination, Expr offset, Expr count, Expr state) {
        this.source = source; this.destination = destination; this.offset = offset; this.count = count; this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        var from = source.executeRequiredAddress(frame); Object to = destination.execute(frame);
        long start = offset.executeRequiredLong(frame), length = count.executeRequiredLong(frame);
        Object token = state.execute(frame); TupleResults.requireVoidCarrier(token);
        from.copyToByteArray(to, start, length); return token;
    }
}
