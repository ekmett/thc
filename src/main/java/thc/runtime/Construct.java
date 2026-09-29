// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.RuntimeFault.fault;

final class Construct extends Expr {
    private final DataLayout layout;
    private final DataLayout.Reusable reusable;
    private final int programSlot;
    private final int constructorIndex;
    @Children private Expr[] fields;
    @CompilationFinal(dimensions = 2) private final int[][] vectorSlots;
    Construct(DataLayout layout, Expr[] fields) { this(layout, fields, new int[0][]); }
    Construct(DataLayout layout, Expr[] fields, int[][] vectorSlots) {
        this.layout = layout; this.fields = fields; this.vectorSlots = vectorSlots;
        reusable = null; programSlot = constructorIndex = -1;
        prepareFields();
        setRepresentation(new CoreRepresentation(CoreKind.DATA, true, false, null, null, null, null, null, null));
    }
    Construct(DataLayout.Reusable reusable, int programSlot, int constructorIndex, Expr[] fields, int[][] vectorSlots) {
        this.reusable = reusable; this.programSlot = programSlot; this.constructorIndex = constructorIndex;
        layout = null; this.fields = fields; this.vectorSlots = vectorSlots;
        prepareFields();
        setRepresentation(new CoreRepresentation(CoreKind.DATA, true, false, null, null, null, null, null, null));
    }
    private void prepareFields() {
        for (int i = 0; i < vectorSlots.length; i++)
            if (vectorSlots[i] != null) fields[i].prepareTuple(vectorSlots[i], 0);
    }
    @ExplodeLoop @Override public DataValue execute(VirtualFrame frame) {
        if (reusable != null) return executeReusable(frame);
        if (layout.getHasBoxedValueCache()) return layout.createLong(fields[0].executeRequiredLong(frame));
        DataValue value = layout.allocate();
        for (int i = 0; i < fields.length; i++) {
            int physical = layout.fieldOffset(i);
            CoreRepresentation proof = layout.logicalProof(i);
            if (proof != null && proof.isAggregate()) {
                int[] slots = vectorSlots[i];
                if (slots == null) throw fault("Missing aggregate constructor slots");
                fields[i].executeTuple(frame, slots, 0);
                for (int leaf = 0; leaf < slots.length; leaf++) {
                    int index = physical + leaf;
                    if (layout.isVector(index)) layout.initializeVector(value, index, frame, slots, leaf);
                    else if (layout.isInt(index)) layout.initializeInt(value, index, frame.getInt(slots[leaf]));
                    else if (layout.isLong(index)) layout.initializeLong(value, index, frame.getLong(slots[leaf]));
                    else if (layout.isFloat(index)) layout.initializeFloat(value, index, frame.getFloat(slots[leaf]));
                    else if (layout.isDouble(index)) layout.initializeDouble(value, index, frame.getDouble(slots[leaf]));
                    else layout.initialize(value, index, frame.getObject(slots[leaf]));
                }
                for (int slot : slots) frame.clear(slot);
            } else if (layout.isVector(physical)) {
                int[] lanes = vectorSlots[i];
                if (lanes == null) throw fault("Missing vector constructor lane slots");
                fields[i].executeTuple(frame, lanes, 0);
                layout.initializeVector(value, physical, frame, lanes, 0);
                for (int slot : lanes) frame.clear(slot);
            } else if (layout.isInt(physical)) layout.initializeInt(value, physical, fields[i].executeRequiredInt(frame));
            else if (layout.isLong(physical)) layout.initializeLong(value, physical, fields[i].executeRequiredLong(frame));
            else if (layout.isFloat(physical)) layout.initializeFloat(value, physical, fields[i].executeRequiredFloat(frame));
            else if (layout.isDouble(physical)) layout.initializeDouble(value, physical, fields[i].executeRequiredDouble(frame));
            else layout.initialize(value, physical, fields[i].execute(frame));
        }
        return value;
    }
    @ExplodeLoop private DataValue executeReusable(VirtualFrame frame) {
        DataLayout owner = Program.instance(frame, programSlot).constructorLayout(constructorIndex);
        if (fields.length == 1 && owner.getHasBoxedValueCache() && reusable.isLong(0))
            return reusable.createLong(owner, fields[0].executeRequiredLong(frame));
        DataValue value = reusable.allocate(owner);
        for (int i = 0; i < fields.length; i++) {
            int physical = reusable.fieldOffset(i);
            CoreRepresentation proof = reusable.logicalProof(i);
            if (proof.isTypedTransport()) {
                int[] slots = vectorSlots[i];
                if (slots == null) throw fault("Missing typed constructor slots");
                fields[i].executeTuple(frame, slots, 0);
                for (int leaf = 0; leaf < slots.length; leaf++) {
                    int index = physical + leaf;
                    if (reusable.isVector(index)) reusable.initializeVector(owner, value, index, frame, slots, leaf);
                    else if (reusable.isInt(index)) reusable.initializeInt(owner, value, index, frame.getInt(slots[leaf]));
                    else if (reusable.isLong(index)) reusable.initializeLong(owner, value, index, frame.getLong(slots[leaf]));
                    else if (reusable.isFloat(index)) reusable.initializeFloat(owner, value, index, frame.getFloat(slots[leaf]));
                    else if (reusable.isDouble(index)) reusable.initializeDouble(owner, value, index, frame.getDouble(slots[leaf]));
                    else reusable.initialize(owner, value, index, frame.getObject(slots[leaf]));
                }
                for (int slot : slots) frame.clear(slot);
            } else if (reusable.isInt(physical)) reusable.initializeInt(owner, value, physical, fields[i].executeRequiredInt(frame));
            else if (reusable.isLong(physical)) reusable.initializeLong(owner, value, physical, fields[i].executeRequiredLong(frame));
            else if (reusable.isFloat(physical)) reusable.initializeFloat(owner, value, physical, fields[i].executeRequiredFloat(frame));
            else if (reusable.isDouble(physical)) reusable.initializeDouble(owner, value, physical, fields[i].executeRequiredDouble(frame));
            else reusable.initialize(owner, value, physical, fields[i].execute(frame));
        }
        return value;
    }
    @Override public DataValue executeDataValue(VirtualFrame frame) { return execute(frame); }
}
