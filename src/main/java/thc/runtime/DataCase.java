// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

final class DataCase extends Case {
    DataCase(Expr scrutinee, int binder, Alternative[] alternatives, Metrics metrics, CoreRepresentation proof, boolean delimited) {
        super(scrutinee, binder, alternatives, metrics, proof, delimited);
    }
    @Override protected boolean matches(VirtualFrame frame, Alternative alternative) { return alternative.matchesData((DataValue) frame.getObject(binderSlot)); }
}
