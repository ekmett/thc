// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import java.util.ArrayList;
public final class DelimitedCut extends AbstractTruffleException implements InternalGuestControl {
    private final PromptTag tag;
    private final Object handler;
    private final TupleShape inputShape;
    private final MaskingState capturedMask;
    private final StackAnnotationState capturedAnnotations;
    private final ArrayList<DelimitedFrame> frames = new ArrayList<>();
    public DelimitedCut(PromptTag tag, Object handler, TupleShape inputShape, MaskingState capturedMask, Node node) {
        super("Internal delimited continuation capture", null, 0, node);
        this.tag = tag; this.handler = handler; this.inputShape = inputShape; this.capturedMask = capturedMask;
        capturedAnnotations = StackAnnotations.current(node);
    }
    public PromptTag getTag() { return tag; }
    public Object getHandler() { return handler; }
    public TupleShape getInputShape() { return inputShape; }
    public MaskingState getCapturedMask() { return capturedMask; }
    public StackAnnotationState getCapturedAnnotations() { return capturedAnnotations; }
    public ArrayList<DelimitedFrame> getFrames() { return frames; }
    public DelimitedCut append(VirtualFrame frame, DelimitedStep step) { frames.add(new DelimitedFrame(frame.materialize(), step)); return this; }
}
