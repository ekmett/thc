// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import thc.Language;
import static thc.runtime.TupleResults.requireVoidCarrier;
final class DelimitedContinuationRoot extends GuestRoot {
    private final DelimitedStack stack;
    @Child private DelimitedActionSite site;
    DelimitedContinuationRoot(Language language, DelimitedStack stack, TupleShape shape, Metrics metrics) {
        super(language, FrameDescriptor.newBuilder().build());
        this.stack = stack; site = new DelimitedActionSite(language, metrics);
        configureEntry(new boolean[] {false, false}, false); configureTupleResult(shape);
    }
    @Override public long bloom(VirtualFrame frame) { return (Long) frame.getArguments()[0] | mask; }
    @Override public Object execute(VirtualFrame frame) {
        requireVoidCarrier(frame.getArguments()[2]);
        try { return stack.resume(site, frame.materialize(), frame.getArguments()[1]); }
        catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); }
    }
}
