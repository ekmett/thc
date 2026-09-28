// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
public final class DelimitedFrame {
    private final MaterializedFrame frame;
    private final DelimitedStep step;
    public DelimitedFrame(MaterializedFrame frame, DelimitedStep step) { this.frame = frame; this.step = step; }
    public MaterializedFrame getFrame() { return frame; }
    public DelimitedStep getStep() { return step; }
}
