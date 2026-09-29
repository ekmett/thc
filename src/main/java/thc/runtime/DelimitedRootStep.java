// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
public final class DelimitedRootStep implements DelimitedTransferStep {
    private final FunctionRoot root;
    public DelimitedRootStep(FunctionRoot root) { this.root = root; }
    FunctionRoot root() { return root; }
    void prepareImage(MaterializedFrame frame) {
        // Ingress already copied and released this packet; it is not part of
        // the guest activation. Never retain its pooled/reused carrier.
        if (root.getTypedInput() != null) frame.getArguments()[0] = null;
        if (root.getHandoff() != null) root.getHandoff().initializeOrdinary(frame);
    }
    @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) { return input.get(); }
    @Override public boolean accepts(ControlFlowException transfer) { return transfer == AstSelfCall.INSTANCE || transfer instanceof TailCall || transfer instanceof HandoffTailCall; }
    @Override public Object transfer(MaterializedFrame frame, ControlFlowException transfer, DelimitedActionSite site) { return root.resumeDelimited(frame, transfer, site); }
    @Override public Object finish(Object result, DelimitedActionSite site) { return site.finish(result, root.getTupleResult()); }
}
