// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.ControlFlowException;

/** Self transfer already installed the next frame and needs no packet or target. */
public final class AstSelfCall extends ControlFlowException {
    public static final AstSelfCall INSTANCE = new AstSelfCall();
    private AstSelfCall() {}
}
