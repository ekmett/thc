// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class ByteArrayToAddressExpression extends Expr {
    @Child private Expr source, offset, destination, count, state;
    public ByteArrayToAddressExpression(CoreRepresentation proof, Expr source, Expr offset, Expr destination, Expr count, Expr state) {
        this.source = source; this.offset = offset; this.destination = destination; this.count = count; this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        Object from = source.execute(frame); long start = offset.executeRequiredLong(frame);
        var to = destination.executeRequiredAddress(frame); long length = count.executeRequiredLong(frame);
        Object token = state.execute(frame); TupleResultsKt.requireVoidCarrier(token);
        to.copyFromByteArray(from, start, length); return token;
    }
}
