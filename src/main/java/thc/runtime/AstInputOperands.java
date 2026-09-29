// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import java.util.ArrayList;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Operand scratch remains caller-owned across asynchronous suspension. */
public final class AstInputOperands extends Node {
    @Children private Expr[] arguments;
    private final ArgumentLayout layout;
    private final AstInputSource source;
    public AstInputOperands(Expr[] arguments, FrameLayout frameLayout) {
        this.arguments = arguments;
        ArrayList<CoreRepresentation> proofs = new ArrayList<>();
        for (Expr argument : arguments) proofs.add(argument.getRepresentation());
        layout = java.util.Objects.requireNonNull(ArgumentLayout.fromProofs(proofs));
        int[] slots = new int[layout.getPhysicalArity()];
        for (int i = 0; i < slots.length; i++) slots[i] = frameLayout.bind("<typed input " + i + ">",
            FrameLayout.carrierKind(layout.getPhysicalProofs()[i]));
        source = new AstInputSource(layout, slots);
        for (int i = 0; i < arguments.length; i++) if (layout.isTyped(i)) arguments[i].prepareTuple(slots, layout.offset(i));
    }
    public ArgumentLayout getLayout() { return layout; }
    public AstInputSource getSource() { return source; }
    public void evaluate(VirtualFrame frame) { evaluate(frame, 0); }
    @ExplodeLoop public void evaluate(VirtualFrame frame, int start) {
        for (int i = 0; i < arguments.length; i++) if (i >= start) {
            CoreRepresentation proof = layout.proof(i);
            int offset = layout.offset(i);
            try {
                if (proof.isTypedTransport()) arguments[i].executeTuple(frame, source.getSlots(), offset);
                else if (proof.isInt()) FrameAccess.writeInt(frame, source.getSlots()[offset], arguments[i].executeRequiredInt(frame));
                else if (proof.isLong()) FrameAccess.writeLong(frame, source.getSlots()[offset], arguments[i].executeRequiredLong(frame));
                else if (proof.isFloat()) FrameAccess.writeFloat(frame, source.getSlots()[offset], arguments[i].executeRequiredFloat(frame));
                else if (proof.isDouble()) FrameAccess.writeDouble(frame, source.getSlots()[offset], arguments[i].executeRequiredDouble(frame));
                else FrameAccess.write(frame, source.getSlots()[offset], arguments[i].execute(frame));
            } catch (AstCapture cut) {
                int index = i;
                throw cut.append((saved, input) -> resumeOperand(saved, index, input));
            } catch (DelimitedCut cut) {
                int index = i;
                throw cut.append(frame, (saved, input, ambient, outer) -> resumeOperand(saved, index, input.get()));
            }
        }
    }
    private Object resumeOperand(VirtualFrame frame, int index, Object input) {
        CoreRepresentation proof = layout.proof(index);
        int offset = layout.offset(index);
        if (!proof.isTypedTransport()) {
            if (proof.isInt()) FrameAccess.writeInt(frame, source.getSlots()[offset], (Integer) input);
            else if (proof.isLong()) FrameAccess.writeLong(frame, source.getSlots()[offset], (Long) input);
            else if (proof.isFloat()) FrameAccess.writeFloat(frame, source.getSlots()[offset], (Float) input);
            else if (proof.isDouble()) FrameAccess.writeDouble(frame, source.getSlots()[offset], (Double) input);
            else FrameAccess.write(frame, source.getSlots()[offset], input);
        } else if (input != null) throw fault("Invalid typed operand continuation");
        evaluate(frame, index + 1);
        return thc.runtime.Unit.INSTANCE;
    }
}
