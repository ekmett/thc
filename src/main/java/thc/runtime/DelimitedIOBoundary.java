// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.CompilerDirectives;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TupleResults.requireVoidCarrier;
public final class DelimitedIOBoundary extends Expr {
    private final String name;
    private final TupleShape shape;
    @Children private Expr[] operands;
    @Child private DelimitedActionSite site;
    public DelimitedIOBoundary(String name, TupleShape shape, Expr[] operands, Language language, Metrics metrics) {
        this.name = name; this.shape = shape; this.operands = operands; site = new DelimitedActionSite(language, metrics);
        CoreRepresentation proof = shape.getProof();
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(),
            proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { CompilerDirectives.transferToInterpreterAndInvalidate(); throw fault(name + " requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object action = operands[0].execute(frame);
        Object handler = name.equals("catch#") ? operands[1].execute(frame) : null;
        Object state = operands[operands.length - 1].execute(frame);
        Object result;
        try {
            if (name.equals("catch#")) result = site.caught(frame, action, handler, state, shape);
            else result = site.masked(frame, action, state, shape, switch (name) {
                case "maskAsyncExceptions#" -> MaskingState.MASKED_INTERRUPTIBLE;
                case "maskUninterruptible#" -> MaskingState.MASKED_UNINTERRUPTIBLE;
                default -> MaskingState.UNMASKED;
            });
        } catch (AstCapture cut) {
                throw cut.append((saved, value) -> { shape.consume(saved, value, slots, offset); return null; });
            } catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedTupleStep(new AstTupleDestination(shape, slots, offset), this)); }
        shape.consume(frame, result, slots, offset); return null;
    }
}
