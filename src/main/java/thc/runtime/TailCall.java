// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.ControlFlowException;

public final class TailCall extends ControlFlowException {
    private final RootCallTarget target;
    private final Object[] args;
    private final HandoffStorage input;
    public TailCall(RootCallTarget target, Object[] args) { this(target, args, null); }
    public TailCall(RootCallTarget target, Object[] args, HandoffStorage input) { this.target = target; this.args = args; this.input = input; }
    public RootCallTarget getTarget() { return target; }
    public Object[] getArgs() { return args; }
    public HandoffStorage getInput() { return input; }
}
