// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.ControlFlowException;

/** Transfer owns only its incoming argument loan, never an outstanding scalar result. */
public final class HandoffTailCall extends ControlFlowException {
    private final RootCallTarget target;
    private final HandoffStorage arguments;
    public HandoffTailCall(RootCallTarget target, HandoffStorage arguments) { this.target = target; this.arguments = arguments; }
    public RootCallTarget getTarget() { return target; }
    public HandoffStorage getArguments() { return arguments; }
}
