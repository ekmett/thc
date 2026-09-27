// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.List;

final class DataToTag extends Expr {
    private final DataTagFamily family;
    @Child private Expr operand;

    DataToTag(DataTagFamily family, Expr operand) {
        this.family = family;
        this.operand = operand;
        setRepresentation(new CoreRepresentation(CoreKind.LONG, true, false, List.of("IntRep"), null, null, null, null, null));
    }

    @Override public Long execute(VirtualFrame frame) { return executeLong(frame); }
    @Override public long executeLong(VirtualFrame frame) { return family.tag(operand.executeRequiredDataValue(frame)); }
}
