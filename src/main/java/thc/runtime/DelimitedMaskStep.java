// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
public final class DelimitedMaskStep implements DelimitedStep {
    private final Node node;
    private final MaskingState prior;
    public DelimitedMaskStep(Node node, MaskingState prior) { this.node = node; this.prior = prior; }
    public DelimitedMaskStep recapture(MaskingState ambient, DelimitedStep outerMask) { return this == outerMask ? new DelimitedMaskStep(node, ambient) : this; }
    public void unwind(MaskingState ambient, DelimitedStep outerMask) { SynchronousMasking.set(node, this == outerMask ? ambient : prior); }
    @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) { unwind(ambient, outerMask); return input.get(); }
}
