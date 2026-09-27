// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

final class TagToEnum extends Expr {
    private final EnumFamily family;
    @Child private Expr operand;

    TagToEnum(EnumFamily family, Expr operand) {
        this.family = family;
        this.operand = operand;
        setRepresentation(new CoreRepresentation(CoreKind.DATA, true, false, null, null, null, null, null, null));
    }

    @Override public DataValue execute(VirtualFrame frame) { return executeDataValue(frame); }
    @Override public DataValue executeDataValue(VirtualFrame frame) {
        return family.select(operand.executeRequiredLong(frame));
    }
}
