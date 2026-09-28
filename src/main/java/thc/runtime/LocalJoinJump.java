// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.*;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class LocalJoinJump extends ControlFlowException {
    private final LocalJoinTarget target;
    public LocalJoinJump(LocalJoinTarget target) { this.target = target; }
    public LocalJoinTarget getTarget() { return target; }
}
