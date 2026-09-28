// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class TailCallRepeatingNode extends Node implements RepeatingNode {
    private final FrameDescriptor descriptor;
    private final Metrics metrics;
    @Child private TargetCache dispatch;
    public TailCallRepeatingNode(FrameDescriptor descriptor, Metrics metrics) {
        this.descriptor = descriptor; this.metrics = metrics; dispatch = new TargetCache(metrics);
    }
    public FrameDescriptor getDescriptor() { return descriptor; }
    public void setNext(VirtualFrame frame, TailCall call) {
        frame.setObject(FrameLayout.TAIL_FUNCTION, call.getTarget());
        frame.setObject(FrameLayout.TAIL_ARGUMENTS, call);
    }
    @Override public boolean executeRepeating(VirtualFrame frame) {
        try {
            if (metrics.getEnabled()) metrics.incrementTrampolineIterations();
            RootCallTarget target = ColdCallChecks.target(frame.getObject(FrameLayout.TAIL_FUNCTION));
            TailCall transfer = ColdCallChecks.transfer(frame.getObject(FrameLayout.TAIL_ARGUMENTS));
            Object[] arguments = transfer.getArgs();
            frame.setObject(FrameLayout.TAIL_FUNCTION, null);
            frame.setObject(FrameLayout.TAIL_ARGUMENTS, null);
            HandoffStorage input = transfer.getInput();
            Object result;
            if (input != null) {
                input.getLayout().setLong(input, 0, 0L);
                TypedInputLayout layout = ColdCallChecks.guestRoot(target.getRootNode()).getTypedInput();
                if (layout == null) throw fault("Target has no typed input entry");
                long generation = input.getGeneration();
                try { result = dispatch.call(target, new Object[] {input}); }
                finally { GenericTypedInputs.releaseGenericInput(layout, input, generation); }
            } else {
                arguments[0] = 0L;
                result = dispatch.call(target, arguments);
            }
            if (result instanceof ContinuationResult saved) result = new TailYield(saved, target);
            else if (result instanceof SavedGuestContinuation saved) result = new AstTailYield(saved, target);
            frame.setObject(FrameLayout.TAIL_RESULT, result);
            return false;
        } catch (TailCall tail) { setNext(frame, tail); return true; }
    }
}
