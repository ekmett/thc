// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.List;

final class DataToTag extends Expr {
    private final DataTagFamily family;
    private final int programSlot;
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] constructorIndices;
    @Child private Expr operand;

    DataToTag(DataTagFamily family, Expr operand) {
        this.family = family;
        this.operand = operand; programSlot = -1; constructorIndices = null;
        setRepresentation(new CoreRepresentation(CoreKind.LONG, true, false, List.of("IntRep"), null, null, null, null, null));
    }

    DataToTag(int programSlot, int[] constructorIndices, Expr operand) {
        this.programSlot = programSlot; this.constructorIndices = constructorIndices; this.operand = operand; family = null;
        setRepresentation(new CoreRepresentation(CoreKind.LONG, true, false, List.of("IntRep"), null, null, null, null, null));
    }
    @Override public Long execute(VirtualFrame frame) { return executeLong(frame); }
    @com.oracle.truffle.api.nodes.ExplodeLoop
    @Override public long executeLong(VirtualFrame frame) {
        var value = operand.executeRequiredDataValue(frame);
        if (programSlot < 0) return family.tag(value);
        var instance = Program.instance(frame, programSlot);
        for (int i = 0; i < constructorIndices.length; i++)
            if (instance.constructorLayout(constructorIndices[i]).matches(value)) return i;
        throw RuntimeFault.fault("dataToTag: constructor does not belong to the proven family");
    }
}
