// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import static thc.runtime.RuntimeFault.fault;

public final class LocalRead extends Expr {
    private final int slot;
    private final boolean cell;
    private final boolean referenceStorage;
    public LocalRead(int slot) { this(slot, true); }
    public LocalRead(int slot, boolean cell) { this(slot, cell, false); }
    LocalRead(int slot, boolean cell, boolean referenceStorage) {
        this.slot = slot; this.cell = cell; this.referenceStorage = referenceStorage;
    }
    @Override public boolean needsCallState() { return getCoreSourceLocation() != null || slot == FrameLayout.BLOOM_FILTER; }
    boolean readsReferenceSlot(int expected) { return getCoreSourceLocation() == null && !cell && referenceStorage && slot == expected; }
    boolean readsReferenceOutside(int first, int second) {
        return getCoreSourceLocation() == null && !cell && referenceStorage && slot != first && slot != second && getRepresentation().getKind() == CoreKind.OBJECT;
    }
    // Initialized non-cell locals use their live primitive storage; widened activations retain tag checks.
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        return (!cell && frame.getFrameDescriptor().getSlotKind(slot) == FrameSlotKind.Int) || frame.isInt(slot)
            ? frame.getInt(slot) : super.executeInt(frame);
    }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        return (!cell && frame.getFrameDescriptor().getSlotKind(slot) == FrameSlotKind.Long) || frame.isLong(slot)
            ? frame.getLong(slot) : super.executeLong(frame);
    }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        return (!cell && frame.getFrameDescriptor().getSlotKind(slot) == FrameSlotKind.Float) || frame.isFloat(slot)
            ? frame.getFloat(slot) : super.executeFloat(frame);
    }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return (!cell && frame.getFrameDescriptor().getSlotKind(slot) == FrameSlotKind.Double) || frame.isDouble(slot)
            ? frame.getDouble(slot) : super.executeDouble(frame);
    }
    @Override public Object execute(VirtualFrame frame) {
        CoreRepresentation proof = getRepresentation();
        // Allocation-time Object storage remains a reference across all activations.
        // DATA/CLOSURE storage is a reference even before the value is demanded.
        Object value = !cell && (referenceStorage || proof.isEvaluatedReference() || proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.CLOSURE)
            ? frame.getObject(slot) : FrameAccess.INSTANCE.read(frame, slot);
        // Lowering publishes ordinary bindings before use; cells carry publication state.
        if (!cell) return value;
        if (!(value instanceof RecCell recursive)) {
            if (value == null) throw fault("Uninitialized local binding");
            return value;
        }
        if (!recursive.getInitialized()) throw fault("Recursive binding read before initialization");
        return recursive.getValue();
    }
    @Override public DataValue executeDataValue(VirtualFrame frame) throws UnexpectedResultException {
        return !cell && getRepresentation().getEvaluated() && getRepresentation().getKind() == CoreKind.DATA
            ? RuntimeTypesGen.expectDataValue(frame.getObject(slot)) : super.executeDataValue(frame);
    }
    @Override public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException {
        return !cell && getRepresentation().getEvaluated() && getRepresentation().getKind() == CoreKind.CLOSURE
            ? RuntimeTypesGen.expectClosure(frame.getObject(slot)) : super.executeClosure(frame);
    }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException {
        return !cell && getRepresentation().getEvaluated() && getRepresentation().getKind() == CoreKind.ADDRESS
            ? RuntimeTypesGen.expectManagedAddress(frame.getObject(slot)) : super.executeAddress(frame);
    }
    /** Recursive captures retain cell identity until the whole group is published. */
    public void writeForced(VirtualFrame frame, Thunk original, Object result) {
        if (!referenceStorage && !frame.isObject(slot)) return;
        Object binding = frame.getObject(slot);
        if (binding == original) {
            // Allocation-time reference storage cannot narrow after this demand.
            if (referenceStorage) frame.setObject(slot, result);
            else FrameAccess.INSTANCE.write(frame, slot, result);
        }
        else if (cell && binding instanceof RecCell recursive) {
            synchronized (recursive) {
                if (recursive.getInitialized() && recursive.getValue() == original) recursive.setValue(result);
            }
        }
    }
}
