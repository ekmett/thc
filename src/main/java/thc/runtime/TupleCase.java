// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
public final class TupleCase extends Expr {
    @Child private Expr scrutinee;
    @CompilationFinal(dimensions = 1) private final int[] slots;
    @Child private Expr body;
    public TupleCase(Expr scrutinee, int[] slots, Expr body) {
        this.scrutinee = scrutinee; this.slots = slots; this.body = body; setRepresentation(body.getRepresentation());
    }
    private void prepare(VirtualFrame frame) { prepare(frame, null, 0); }
    private void prepare(VirtualFrame frame, int[] destination, int offset) {
        try { scrutinee.executeTuple(frame, slots, 0); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame frame, Object input) { return destination == null ? body.execute(frame) : body.executeTuple(frame, destination, offset); }
            });
        } catch (DelimitedCut cut) {
            if (!DelimitedControl.enabled(this)) throw cut;
            throw cut.append(frame, new DelimitedStep() {
                @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
                    input.get(); return destination == null ? body.execute(frame) : body.executeTuple(frame, destination, offset);
                }
            });
        }
    }
    @Override public Object execute(VirtualFrame frame) { prepare(frame); return body.execute(frame); }
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException { prepare(frame); return body.executeInt(frame); }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException { prepare(frame); return body.executeLong(frame); }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException { prepare(frame); return body.executeFloat(frame); }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException { prepare(frame); return body.executeDouble(frame); }
    @Override public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException { prepare(frame); return body.executeClosure(frame); }
    @Override public DataValue executeDataValue(VirtualFrame frame) throws UnexpectedResultException { prepare(frame); return body.executeDataValue(frame); }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException { prepare(frame); return body.executeAddress(frame); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { prepare(frame, slots, offset); return body.executeTuple(frame, slots, offset); }
}
