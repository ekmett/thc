// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
public final class AstTupleDestination extends TupleDestination {
    @CompilationFinal(dimensions = 1) private final int[] slots;
    private final int offset;
    private final boolean caughtIOAction;
    public AstTupleDestination(TupleShape shape, int[] slots, int offset) { this(shape, slots, offset, false); }
    AstTupleDestination(TupleShape shape, int[] slots, int offset, boolean caughtIOAction) {
        super(shape); this.slots = slots; this.offset = offset; this.caughtIOAction = caughtIOAction;
    }
    @Override public void consume(VirtualFrame frame, Node node, Object result) { consumeFrom(frame, node, result, getShape()); }
    @Override protected void consumeFrom(VirtualFrame frame, Node node, Object result, TupleShape producer) {
        Object answer;
        try { answer = AstControl.complete(node, result, null, producer, false, caughtIOAction); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame frame, Object input) { getShape().consume(frame, input, slots, offset, producer); return null; }
            });
        }
        getShape().consume(frame, answer, slots, offset, producer);
    }
}
