// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
public final class AstTupleDestination extends TupleDestination {
    @CompilationFinal(dimensions = 1) private final int[] slots;
    private final int offset;
    public AstTupleDestination(TupleShape shape, int[] slots, int offset) { super(shape); this.slots = slots; this.offset = offset; }
    @Override public void consume(VirtualFrame frame, Node node, Object result) {
        Object answer;
        try { answer = AstControl.complete(node, result, null, getShape()); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame frame, Object input) { getShape().consume(frame, input, slots, offset); return null; }
            });
        }
        getShape().consume(frame, answer, slots, offset);
    }
}
