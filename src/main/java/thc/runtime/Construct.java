// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.RuntimeFault.fault;

final class Construct extends Expr {
    private final DataLayout layout;
    @Children private Expr[] fields;
    @CompilationFinal(dimensions = 2) private final int[][] vectorSlots;
    Construct(DataLayout layout, Expr[] fields) { this(layout, fields, new int[0][]); }
    Construct(DataLayout layout, Expr[] fields, int[][] vectorSlots) {
        this.layout = layout; this.fields = fields; this.vectorSlots = vectorSlots;
        setRepresentation(new CoreRepresentation(CoreKind.DATA, true, false, null, null, null, null, null, null));
    }
    @ExplodeLoop @Override public DataValue execute(VirtualFrame frame) {
        if (layout.getHasBoxedValueCache$org_intelligence_thc()) return layout.createLong$org_intelligence_thc(fields[0].executeRequiredLong(frame));
        DataValue value = layout.allocate();
        for (int i = 0; i < fields.length; i++) {
            int physical = layout.fieldOffset$org_intelligence_thc(i);
            CoreRepresentation proof = layout.logicalProof$org_intelligence_thc(i);
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
    @Override public DataValue executeDataValue(VirtualFrame frame) { return execute(frame); }
}
