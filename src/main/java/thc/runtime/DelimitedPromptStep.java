// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
public final class DelimitedPromptStep implements DelimitedStep {
    private final PromptTag tag;
    private final DelimitedActionSite site;
    private final TupleShape shape;
    public DelimitedPromptStep(PromptTag tag, DelimitedActionSite site, TupleShape shape) { this.tag = tag; this.site = site; this.shape = shape; }
    public PromptTag getTag() { return tag; }
    public DelimitedActionSite getSite() { return site; }
    public TupleShape getShape() { return shape; }
    @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) { return input.get(); }
    public Object handle(MaterializedFrame frame, DelimitedCut cut) { return site.handle(frame, cut, shape); }
}
