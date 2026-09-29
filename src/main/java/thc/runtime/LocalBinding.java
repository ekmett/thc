// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/** Each cloned root owns its widening state; recursive RHSs stay raw until publication. */
public final class LocalBinding extends Node {
    private final int slot;
    @Child private Expr value;
    @CompilationFinal(dimensions = 1) private final int[] typedSlots;
    private final boolean exactLong;
    private final CoreKind referenceKind;
    @CompilationFinal private boolean generic;
    public LocalBinding(int slot, Expr value, boolean preferLong) { this(slot, value, preferLong, null); }
    public LocalBinding(int slot, Expr value, boolean preferLong, int[] typedSlots) {
        this.slot = slot; this.value = value; this.typedSlots = typedSlots;
        if (typedSlots != null) value.prepareTuple(typedSlots, 0);
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
        catch (AstCapture cut) { throw appendWrite(cut); }
        catch (DelimitedCut cut) { throw cut.append(frame, (saved, input, ambient, outer) -> resumeWrite(saved, input.get())); }
    }
    @TruffleBoundary private AstCapture appendWrite(AstCapture cut) {
        return cut.append(this::resumeWrite);
    }
    private Object resumeWrite(VirtualFrame frame, Object input) {
        if (typedSlots == null) FrameAccess.write(frame, slot, input);
        return thc.runtime.Unit.INSTANCE;
    }
    private void writeValue(VirtualFrame frame) {
        if (typedSlots != null) { value.executeTuple(frame, typedSlots, 0); return; }
        if (value.getRepresentation().isInt()) { FrameAccess.INSTANCE.writeInt(frame, slot, value.executeRequiredInt(frame)); return; }
        if (exactLong) {
            long result = value.executeRequiredLong(frame);
            // Compiler-owned scalar operands normally have their declared kind.
            // Keep the live check: a sibling activation or resumed RHS can widen it.
            FrameDescriptor descriptor = frame.getFrameDescriptor();
            if (descriptor.getSlotKind(slot) == FrameSlotKind.Long || prepareLongFallback(descriptor)) frame.setLong(slot, result);
            else frame.setObject(slot, result);
            return;
        }
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
    @TruffleBoundary private boolean prepareLongFallback(FrameDescriptor descriptor) {
        if (FrameAccess.primitiveKind(descriptor, slot, FrameSlotKind.Long)) return true;
        FrameAccess.objectKind(descriptor, slot);
        return false;
    }
}
