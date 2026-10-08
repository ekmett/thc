// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.bytecode.ContinuationResult;
public final class DelimitedBytecodeStep implements DelimitedTransferStep {
    private final ContinuationResult saved;
    private final TupleShape shape;
    public DelimitedBytecodeStep(ContinuationResult saved, TupleShape shape) { this.saved = saved; this.shape = shape; }
    @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
        Object answer = ContinuationResult.create(saved.getContinuationRootNode(), frame, saved.getResult()).continueWith(input);
        DelimitedControl.captureBytecode(answer, shape); return answer;
    }
    @Override public Object finish(Object result, DelimitedActionSite site) { return site.finish(result, shape); }
    @Override public boolean accepts(ControlFlowException transfer) { return transfer instanceof TailCall; }
    @Override public Object transfer(MaterializedFrame frame, ControlFlowException transfer, DelimitedActionSite site) { return site.tail(frame, (TailCall) transfer); }
}
