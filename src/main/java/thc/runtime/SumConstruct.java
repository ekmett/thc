// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.util.List;
import java.util.concurrent.Callable;

public final class SumConstruct extends Expr {
    private final TupleShape shape;
    private final int tag;
    @Child private Expr payload;
    @CompilationFinal(dimensions = 1) private final int[] intSlots;
    private final CoreRepresentation proof;
    @CompilationFinal(dimensions = 1) private final NarrowInteger[] integers;
    @CompilationFinal(dimensions = 1) private final int[] projection;
    @CompilationFinal(dimensions = 1) private final Object[] emptyReferences;
    private static final class Mapping {
        final int[] destination;
        final int offset;
        @CompilationFinal(dimensions = 1) final int[] fields;
        Mapping(int[] destination, int offset, int[] fields) { this.destination = destination; this.offset = offset; this.fields = fields; }
    }
    @CompilationFinal private volatile Mapping mapping;
    public SumConstruct(TupleShape shape, int tag, Expr payload) { this(shape, tag, payload, new int[0]); }
    public SumConstruct(TupleShape shape, int tag, Expr payload, int[] intSlots) {
        this.shape = shape; this.tag = tag; this.payload = payload; this.intSlots = intSlots;
        proof = shape.getProof().getAlternatives().get(tag - 1);
        List<CoreRepresentation> leaves = TupleShape.Companion.flatten(proof);
        integers = new NarrowInteger[leaves.size()];
        for (int i = 0; i < integers.length; i++) integers[i] = leaves.get(i).getNarrowInteger();
        List<Integer> projected = SumShape.projection(shape.getProof(), tag - 1);
        projection = new int[projected.size()];
        for (int i = 0; i < projection.length; i++) projection[i] = projected.get(i);
        emptyReferences = new Object[shape.getLeaves().length];
        for (int i = 0; i < emptyReferences.length; i++) {
            CoreRepresentation field = shape.getLeaves()[i];
            emptyReferences[i] = field.getKind() == CoreKind.ADDRESS ? ManagedAddress.nullAddress() :
                field.isVector() ? new VectorLayout(field).getSpecies().zero() : null;
        }
        if (proof.isTypedTransport()) for (int i = 0; i < integers.length; i++) if (integers[i] != null && (intSlots.length != integers.length || intSlots[i] < 0))
            throw fault("Missing narrow sum payload scratch slots");
        CoreRepresentation result = shape.getProof();
        setRepresentation(result.copy(result.getKind(), true, result.getPresent(), result.getPrimReps(), result.getComponents(), result.getVector(), result.getAlternatives(), result.getTagSlot(), result.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("Sum value requires a typed destination"); }
    @ExplodeLoop @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Mapping selected = mapping;
        if (selected == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            selected = atomic((Callable<Mapping>) () -> {
                if (mapping == null) {
                    int[] fields = new int[projection.length];
                    for (int i = 0; i < fields.length; i++) fields[i] = proof.isTypedTransport() && integers[i] != null ? intSlots[i] : slots[offset + projection[i]];
                    mapping = new Mapping(slots, offset, fields);
                }
                return mapping;
            });
        }
        if (selected.destination != slots || selected.offset != offset) throw new IllegalStateException("Check failed.");
        // Clear inactive slots before payload evaluation; no result loan exists yet.
        for (int i = 0; i < shape.getLeaves().length; i++) {
            int target = slots[offset + i];
            if (shape.getLayout().isLong(i)) FrameAccess.writeLong(frame, target, 0L);
            else if (shape.getLayout().isFloat(i)) FrameAccess.writeFloat(frame, target, 0.0f);
            else if (shape.getLayout().isDouble(i)) FrameAccess.writeDouble(frame, target, 0.0);
            else FrameAccess.write(frame, target, emptyReferences[i]);
        }
        int[] fields = selected.fields;
        if (proof.isTypedTransport()) payload.executeTuple(frame, fields, 0);
        else if (proof.getKind() == CoreKind.VOID) TupleResults.requireVoidCarrier(payload.execute(frame));
        else if (proof.isInt()) FrameAccess.writeLong(frame, fields[0], proof.getNarrowInteger().widen(payload.executeRequiredInt(frame)));
        else if (proof.isLong()) FrameAccess.writeLong(frame, fields[0], payload.executeRequiredLong(frame));
        else if (proof.isFloat()) FrameAccess.writeFloat(frame, fields[0], payload.executeRequiredFloat(frame));
        else if (proof.isDouble()) FrameAccess.writeDouble(frame, fields[0], payload.executeRequiredDouble(frame));
        else FrameAccess.write(frame, fields[0], payload.execute(frame));
        if (proof.isTypedTransport()) for (int i = 0; i < integers.length; i++) {
            NarrowInteger integer = integers[i];
            if (integer != null) FrameAccess.writeLong(frame, slots[offset + projection[i]], integer.widen(frame.getInt(fields[i])));
        }
        FrameAccess.writeLong(frame, slots[offset], (long) tag);
        return null;
    }
    private static RuntimeFault fault(String message) { CompilerDirectives.transferToInterpreterAndInvalidate(); return new RuntimeFault(message); }
}
