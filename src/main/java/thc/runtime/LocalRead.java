// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import static thc.runtime.RuntimeFault.fault;

public final class LocalRead extends Expr {
    private final int slot;
    private final boolean cell;
    private final FrameSlotKind ownedStorage;
    public LocalRead(int slot) { this(slot, true); }
    public LocalRead(int slot, boolean cell) { this(slot, cell, null); }
    LocalRead(int slot, boolean cell, FrameSlotKind ownedStorage) {
        this.slot = slot; this.cell = cell; this.ownedStorage = ownedStorage;
    }
    @Override public boolean needsCallState() { return getCoreSourceLocation() != null || slot == FrameLayout.BLOOM_FILTER; }
    boolean readsReferenceSlot(int expected) { return getCoreSourceLocation() == null && !cell && ownedStorage == FrameSlotKind.Object && slot == expected; }
    boolean readsReferenceOutside(int first, int second) {
        return getCoreSourceLocation() == null && !cell && ownedStorage == FrameSlotKind.Object && slot != first && slot != second && getRepresentation().getKind() == CoreKind.OBJECT;
    }
    // Initialized non-cell locals use their live primitive storage; widened activations retain tag checks.
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        return (!cell && frame.getFrameDescriptor().getSlotKind(slot) == FrameSlotKind.Int) || frame.isInt(slot)
            ? frame.getInt(slot) : super.executeInt(frame);
    }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        // Keep known frames virtual; isolate generic reads of incoming frames.
        return ownedStorage == FrameSlotKind.Long || (!cell && frame.getFrameDescriptor().getSlotKind(slot) == FrameSlotKind.Long) || frame.isLong(slot)
            ? frame.getLong(slot)
            : CompilerDirectives.isPartialEvaluationConstant(frame.getFrameDescriptor())
                ? super.executeLong(frame) : readLongFallback(frame.materialize());
    }
    /** Retain generic carriers and recursive-cell checks outside the primitive read graph. */
    @TruffleBoundary(transferToInterpreterOnException = false)
    private long readLongFallback(MaterializedFrame frame) throws UnexpectedResultException {
        return super.executeLong(frame);
    }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        return ownedStorage == FrameSlotKind.Float || (!cell && frame.getFrameDescriptor().getSlotKind(slot) == FrameSlotKind.Float) || frame.isFloat(slot)
            ? frame.getFloat(slot) : super.executeFloat(frame);
    }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return ownedStorage == FrameSlotKind.Double || (!cell && frame.getFrameDescriptor().getSlotKind(slot) == FrameSlotKind.Double) || frame.isDouble(slot)
            ? frame.getDouble(slot) : super.executeDouble(frame);
    }
    @Override public Object execute(VirtualFrame frame) {
        // Ordinary join formals are published with the same tag on each transfer.
        if (ownedStorage == FrameSlotKind.Long) return frame.getLong(slot);
        if (ownedStorage == FrameSlotKind.Float) return frame.getFloat(slot);
        if (ownedStorage == FrameSlotKind.Double) return frame.getDouble(slot);
        CoreRepresentation proof = getRepresentation();
        // Allocation-time Object storage remains a reference across all activations.
        // DATA/CLOSURE storage is a reference even before the value is demanded.
        Object value = !cell && (ownedStorage == FrameSlotKind.Object || proof.isEvaluatedReference() || proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.CLOSURE)
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
        if (ownedStorage != FrameSlotKind.Object && !frame.isObject(slot)) return;
        Object binding = frame.getObject(slot);
        if (binding == original) {
            // Allocation-time reference storage cannot narrow after this demand.
            if (ownedStorage == FrameSlotKind.Object) frame.setObject(slot, result);
            else FrameAccess.INSTANCE.write(frame, slot, result);
        }
        else if (cell && binding instanceof RecCell recursive) {
            synchronized (recursive) {
                if (recursive.getInitialized() && recursive.getValue() == original) recursive.setValue(result);
            }
        }
    }
}
