// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.ControlFlowException;

/** Not a Haskell exception: catchSTM cannot intercept validation failure. */
public final class STMConflict extends ControlFlowException {
    public static final STMConflict INSTANCE = new STMConflict();
    private STMConflict() {}
}
