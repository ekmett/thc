// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class TupleConstruct extends Expr {
    private final TupleShape shape;
    @Children private Expr[] fields;
    public TupleConstruct(TupleShape shape, Expr[] fields) {
        this.shape = shape; this.fields = fields;
        CoreRepresentation p = shape.getProof();
        setRepresentation(p.copy(p.getKind(), true, p.getPresent(), p.getPrimReps(), p.getComponents(), p.getVector(), p.getAlternatives(), p.getTagSlot(), p.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("Tuple value requires a destination"); }
    @ExplodeLoop @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        for (int i = 0; i < fields.length; i++) {
            CoreRepresentation component = shape.getComponents()[i];
            int target = offset + shape.getOffsets()[i];
            if (component.isTypedTransport()) fields[i].executeTuple(frame, slots, target);
            else if (component.isInt()) FrameAccess.writeInt(frame, slots[target], fields[i].executeRequiredInt(frame));
            else if (component.isLong()) FrameAccess.writeLong(frame, slots[target], fields[i].executeRequiredLong(frame));
            else if (component.isFloat()) FrameAccess.writeFloat(frame, slots[target], fields[i].executeRequiredFloat(frame));
            else if (component.isDouble()) FrameAccess.writeDouble(frame, slots[target], fields[i].executeRequiredDouble(frame));
            else if (component.getKind() == CoreKind.VOID) TupleResults.requireVoidCarrier(fields[i].execute(frame));
            else if (component.getKind() == CoreKind.ADDRESS) FrameAccess.write(frame, slots[target], fields[i].executeRequiredAddress(frame));
            else FrameAccess.write(frame, slots[target], fields[i].execute(frame));
        }
        return null;
    }
}
