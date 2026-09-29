// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

final class TagToEnum extends Expr {
    private final EnumFamily family;
    private final int programSlot;
    @CompilationFinal(dimensions = 1) private final int[] constructorIndices;
    @Child private Expr operand;

    TagToEnum(EnumFamily family, Expr operand) {
        this.family = family;
        programSlot = -1; constructorIndices = null;
        this.operand = operand;
        setRepresentation(new CoreRepresentation(CoreKind.DATA, true, false, null, null, null, null, null, null));
    }
    TagToEnum(int programSlot, int[] constructorIndices, Expr operand) {
        family = null; this.programSlot = programSlot; this.constructorIndices = constructorIndices; this.operand = operand;
        setRepresentation(new CoreRepresentation(CoreKind.DATA, true, false, null, null, null, null, null, null));
    }

    @Override public DataValue execute(VirtualFrame frame) { return executeDataValue(frame); }
    @Override public DataValue executeDataValue(VirtualFrame frame) {
        long tag = operand.executeRequiredLong(frame);
        if (family != null) return family.select(tag);
        int index = EnumFamily.index(tag, constructorIndices.length);
        return Program.instance(frame, programSlot).constructorLayout(constructorIndices[index]).allocate();
    }
}
