// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class TupleLocalRead extends Expr {
    private final TupleShape shape;
    @CompilationFinal(dimensions = 1) private final int[] sources;
    public TupleLocalRead(TupleShape shape, int[] sources) {
        this.shape = shape; this.sources = sources;
        CoreRepresentation p = shape.getProof();
        setRepresentation(p.copy(p.getKind(), true, p.getPresent(), p.getPrimReps(), p.getComponents(), p.getVector(), p.getAlternatives(), p.getTagSlot(), p.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("Tuple value requires a destination"); }
    @ExplodeLoop @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        for (int i = 0; i < sources.length; i++) {
            if (shape.getLayout().isInt(i)) FrameAccess.writeInt(frame, slots[offset + i], frame.getInt(sources[i]));
            else if (shape.getLayout().isLong(i)) FrameAccess.writeLong(frame, slots[offset + i], frame.getLong(sources[i]));
            else if (shape.getLayout().isFloat(i)) FrameAccess.writeFloat(frame, slots[offset + i], frame.getFloat(sources[i]));
            else if (shape.getLayout().isDouble(i)) FrameAccess.writeDouble(frame, slots[offset + i], frame.getDouble(sources[i]));
            else FrameAccess.write(frame, slots[offset + i], shape.checkedReference(i, frame.getObject(sources[i])));
        }
        return null;
    }
}
