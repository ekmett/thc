// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import static thc.runtime.RuntimeFault.fault;

final class SelfRepeater extends Node implements RepeatingNode {
    @Child private FunctionBody body;
    private final Metrics metrics;
    SelfRepeater(FunctionBody body, Metrics metrics) { this.body = body; this.metrics = metrics; }
    boolean needsCallState() { return body.needsCallState(); }
    private Metrics invocationMetrics(VirtualFrame frame) {
        return metrics != null ? metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame);
    }
    Object once(VirtualFrame frame) {
        if (!(getRootNode() instanceof FunctionRoot root)) throw fault("Invalid self-loop function root");
        root.forceEntry(frame);
        root.pollBeforeBody(this);
        HandoffEntry entry = root.getHandoff();
        if (entry != null && entry.getResultInt() && entry.destination(frame) >= 0) return entry.finishInt(frame, body.executeInt(frame));
        if (entry != null && entry.getResultLong() && entry.destination(frame) >= 0) return entry.finishLong(frame, body.executeLong(frame));
        return body.execute(frame);
    }
    @Override public boolean executeRepeating(VirtualFrame frame) { throw new IllegalStateException("value loop"); }
    @Override public Object executeRepeatingWithValue(VirtualFrame frame) {
        try { return once(frame); }
        catch (AstSelfCall ignored) {
            Metrics invocation = invocationMetrics(frame);
            if (invocation.getEnabled()) invocation.incrementSelfTailReentries();
            return CONTINUE_LOOP_STATUS;
        } catch (HandoffTailCall tail) {
            if (!(getRootNode() instanceof FunctionRoot root)) throw fault("Invalid self-loop function root");
            if (!root.isSelf(tail.getTarget())) throw tail;
            Metrics invocation = invocationMetrics(frame);
            if (invocation.getEnabled()) invocation.incrementSelfTailReentries();
            root.restoreHandoff(frame, tail.getArguments(), false);
            return CONTINUE_LOOP_STATUS;
        } catch (TailCall tail) {
            if (!(getRootNode() instanceof FunctionRoot root)) throw fault("Invalid self-loop function root");
            if (!root.isSelf(tail.getTarget())) throw tail;
            Metrics invocation = invocationMetrics(frame);
            if (invocation.getEnabled()) invocation.incrementSelfTailReentries();
            root.restoreTail(frame, tail);
            return CONTINUE_LOOP_STATUS;
        }
    }
}
