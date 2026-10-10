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
public final class DelimitedPrimitive extends Expr {
    private final String name;
    private final TupleShape shape;
    @Children private Expr[] operands;
    @Child private DelimitedActionSite site;
    public DelimitedPrimitive(String name, TupleShape shape, Expr[] operands, Language language, Metrics metrics) {
        this.name = name; this.shape = shape; this.operands = operands; site = new DelimitedActionSite(language, metrics);
        CoreRepresentation proof = shape.getProof();
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(),
            proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { CompilerDirectives.transferToInterpreterAndInvalidate(); throw fault(name + " requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (name.equals("newPromptTag#")) {
            requireVoidCarrier(operands[0].execute(frame));
            FrameAccess.write(frame, slots[offset], new PromptTag(Language.currentState(this))); return null;
        }
        Object tag = operands[0].execute(frame), action = operands[1].execute(frame), state = operands[2].execute(frame);
        if (name.equals("prompt#")) {
            Object result;
            try { result = site.prompt(frame, tag, action, state, shape); }
            catch (AstCapture cut) {
                throw cut.append((saved, value) -> { shape.consume(saved, value, slots, offset); return null; });
            } catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedTupleStep(new AstTupleDestination(shape, slots, offset), this)); }
            shape.consume(frame, result, slots, offset); return null;
        }
        requireVoidCarrier(state);
        throw new DelimitedCut(DelimitedControl.tag(this, tag), action, shape, SynchronousMasking.current(this), this)
            .append(frame, new DelimitedTupleStep(new AstTupleDestination(shape, slots, offset), this));
    }
}
