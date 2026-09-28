// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
public final class DelimitedCatchStep implements DelimitedStep {
    private final DelimitedActionSite site;
    private final Object handler;
    private final TupleShape shape;
    public DelimitedCatchStep(DelimitedActionSite site, Object handler, TupleShape shape) { this.site = site; this.handler = handler; this.shape = shape; }
    @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
        return input.getFailure() == null ? input.getValue() : site.handleException(frame, handler, input.getFailure(), shape);
    }
}
