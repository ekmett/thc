// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/** Each cloned root owns its widening state; recursive RHSs stay raw until publication. */
public final class LocalBinding extends Node {
    private final int slot;
    @Child private Expr value;
    @CompilationFinal(dimensions = 1) private final int[] vectorSlots;
    private final boolean exactLong;
    private final CoreKind referenceKind;
    @CompilationFinal private boolean generic;
    public LocalBinding(int slot, Expr value, boolean preferLong) { this(slot, value, preferLong, null); }
    public LocalBinding(int slot, Expr value, boolean preferLong, int[] vectorSlots) {
        this.slot = slot; this.value = value; this.vectorSlots = vectorSlots;
        CoreRepresentation proof = value.getRepresentation();
        exactLong = proof.isLong();
        referenceKind = proof.getEvaluated() ? proof.getKind() : CoreKind.UNKNOWN;
        generic = !preferLong || proof.getPresent() && !exactLong;
    }
    public Object evaluate(VirtualFrame frame) {
        if (referenceKind == CoreKind.DATA) return value.executeRequiredDataValue(frame);
        if (referenceKind == CoreKind.CLOSURE) return value.executeRequiredClosure(frame);
        if (referenceKind == CoreKind.ADDRESS) return value.executeRequiredAddress(frame);
        return value.execute(frame);
    }
    public void write(VirtualFrame frame) {
        try { writeValue(frame); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame frame, Object input) {
                    if (vectorSlots == null) FrameAccess.INSTANCE.write(frame, slot, input);
                    return kotlin.Unit.INSTANCE;
                }
            });
        }
    }
    private void writeValue(VirtualFrame frame) {
        if (vectorSlots != null) { value.executeTuple(frame, vectorSlots, 0); return; }
        if (value.getRepresentation().isInt()) { FrameAccess.INSTANCE.writeInt(frame, slot, value.executeRequiredInt(frame)); return; }
        if (exactLong) { FrameAccess.INSTANCE.writeLong(frame, slot, value.executeRequiredLong(frame)); return; }
        if (value.getRepresentation().isFloat()) { FrameAccess.INSTANCE.writeFloat(frame, slot, value.executeRequiredFloat(frame)); return; }
        if (value.getRepresentation().isDouble()) { FrameAccess.INSTANCE.writeDouble(frame, slot, value.executeRequiredDouble(frame)); return; }
        if (referenceKind == CoreKind.DATA) { FrameAccess.INSTANCE.write(frame, slot, value.executeRequiredDataValue(frame)); return; }
        if (referenceKind == CoreKind.CLOSURE) { FrameAccess.INSTANCE.write(frame, slot, value.executeRequiredClosure(frame)); return; }
        if (referenceKind == CoreKind.ADDRESS) { FrameAccess.INSTANCE.write(frame, slot, value.executeRequiredAddress(frame)); return; }
        if (generic) { FrameAccess.INSTANCE.write(frame, slot, value.execute(frame)); return; }
        try { FrameAccess.INSTANCE.writeLong(frame, slot, value.executeLong(frame)); }
        catch (UnexpectedResultException unexpected) {
            generic = true;
            FrameAccess.INSTANCE.write(frame, slot, unexpected.getResult());
        }
    }
}
