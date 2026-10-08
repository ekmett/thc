// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.*;
import static thc.runtime.RuntimeServiceStatus.fault;
import com.oracle.truffle.api.Truffle;
import java.util.ArrayDeque;
public final class LocalJoinRegion extends Expr {
    private final Object group;
    private final int selector, result;
    private final TupleShape tuple;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] tupleSlots;
    private final boolean delimited;
    @Child private LocalJoinRepeater single;
    @Child private LoopNode loop;
    public LocalJoinRegion(Object group, int selector, int result, Expr[] bodies, CoreRepresentation proof, boolean recursive) {
        this(group, selector, result, bodies, proof, recursive, null, new int[0], false);
    }
    public LocalJoinRegion(Object group, int selector, int result, Expr[] bodies, CoreRepresentation proof, boolean recursive, TupleShape tuple, int[] tupleSlots) {
        this(group, selector, result, bodies, proof, recursive, tuple, tupleSlots, false);
    }
    public LocalJoinRegion(Object group, int selector, int result, Expr[] bodies, CoreRepresentation proof, boolean recursive, TupleShape tuple, int[] tupleSlots, boolean delimited) {
        this.group = group; this.selector = selector; this.result = result; this.tuple = tuple; this.tupleSlots = tupleSlots; this.delimited = delimited;
        setRepresentation(proof);
        LocalJoinRepeater repeater = new LocalJoinRepeater(group, selector, result, bodies, proof, tupleSlots, delimited);
        if (recursive) loop = Truffle.getRuntime().createLoopNode(repeater); else single = repeater;
    }
    private void run(VirtualFrame frame) { run(frame, null, 0); }
    private void run(VirtualFrame frame, int[] slots, int offset) {
        if (!delimited && !AstControl.captures(this)) { runUninterrupted(frame); return; }
        try { runUninterrupted(frame); }
        catch (AstCapture cut) { throw cut.enclose(steps -> new ResumeAsyncRegion(this, steps, slots, offset)); }
        catch (DelimitedCut cut) {
            if (!delimited) throw cut;
            throw cut.append(frame, new ResumeRegion(this, slots, offset));
        }
    }
    private static final class ResumeAsyncRegion implements AstResumeStep {
        private final LocalJoinRegion region;
        private final ArrayDeque<AstResumeStep> steps;
        private final int[] slots;
        private final int offset;
        ResumeAsyncRegion(LocalJoinRegion region, ArrayDeque<AstResumeStep> steps, int[] slots, int offset) { this.region = region; this.steps = steps; this.slots = slots; this.offset = offset; }
        @Override public Object resume(VirtualFrame frame, Object input) {
            try {
                try { AstContinuations.resumeAstSteps(frame, steps, input); }
                catch (LocalJoinJump jump) {
                    if (jump.getTarget().getGroup() != region.group) throw jump;
                    LocalJoinRepeater once = region.single;
                    if (once != null) once.executeTarget(frame, jump.getTarget().getIndex());
                    else { FrameAccess.writeLong(frame, region.selector, jump.getTarget().getIndex()); region.loop.execute(frame); }
                }
            } catch (AstCapture cut) { throw cut.enclose(remaining -> new ResumeAsyncRegion(region, remaining, slots, offset)); }
            catch (DelimitedCut cut) { throw cut.append(frame, new ResumeRegion(region, slots, offset)); }
            return slots == null ? region.resultValue(frame) : region.finishTuple(frame, slots, offset);
        }
    }
    private void runUninterrupted(VirtualFrame frame) {
        LocalJoinRepeater once = single;
        if (once != null) once.executeOnce(frame);
        else { FrameAccess.writeLong(frame, selector, 0L); loop.execute(frame); }
    }
    private static final class ResumeRegion implements DelimitedTransferStep {
        private final LocalJoinRegion region;
        private final int[] slots;
        private final int offset;
        ResumeRegion(LocalJoinRegion region, int[] slots, int offset) { this.region = region; this.slots = slots; this.offset = offset; }
        @Override public boolean accepts(ControlFlowException transfer) { return transfer instanceof LocalJoinJump jump && jump.getTarget().getGroup() == region.group; }
        @Override public Object transfer(MaterializedFrame frame, ControlFlowException transfer, DelimitedActionSite site) {
            LocalJoinJump jump = (LocalJoinJump) transfer;
            try {
                LocalJoinRepeater once = region.single;
                if (once != null) once.executeTarget(frame, jump.getTarget().getIndex());
                else { FrameAccess.writeLong(frame, region.selector, jump.getTarget().getIndex()); region.loop.execute(frame); }
            } catch (AstCapture cut) { throw cut.enclose(steps -> new ResumeAsyncRegion(region, steps, slots, offset)); }
            catch (DelimitedCut cut) { throw cut.append(frame, this); }
            return finishFrame(frame);
        }
        @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) { input.get(); return finishFrame(frame); }
        private Object finishFrame(VirtualFrame frame) { return slots == null ? region.resultValue(frame) : region.finishTuple(frame, slots, offset); }
    }
    private Object resultValue(VirtualFrame frame) {
        VectorLayout layout = getTypedVectorLayout();
        if (layout != null) return layout.read(frame, tupleSlots, 0);
        return getRepresentation().isEvaluatedReference() ? frame.getObject(result) : FrameAccess.read(frame, result);
    }
    @Override public Object execute(VirtualFrame frame) { run(frame); return resultValue(frame); }
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException { run(frame); return getRepresentation().isInt() ? frame.getInt(result) : RuntimeTypesGen.expectInteger(FrameAccess.read(frame, result)); }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException { run(frame); return getRepresentation().isLong() ? frame.getLong(result) : RuntimeTypesGen.expectLong(FrameAccess.read(frame, result)); }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException { run(frame); return getRepresentation().isFloat() ? frame.getFloat(result) : RuntimeTypesGen.expectFloat(FrameAccess.read(frame, result)); }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException { run(frame); return getRepresentation().isDouble() ? frame.getDouble(result) : RuntimeTypesGen.expectDouble(FrameAccess.read(frame, result)); }
    @Override public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException { run(frame); return RuntimeTypesGen.expectClosure(resultValue(frame)); }
    @Override public DataValue executeDataValue(VirtualFrame frame) throws UnexpectedResultException { run(frame); return RuntimeTypesGen.expectDataValue(resultValue(frame)); }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException { run(frame); return RuntimeTypesGen.expectManagedAddress(resultValue(frame)); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { run(frame, slots, offset); return finishTuple(frame, slots, offset); }
    @ExplodeLoop private Object finishTuple(VirtualFrame frame, int[] slots, int offset) {
        TupleShape shape = tuple;
        if (shape == null) throw fault("Scalar join region cannot write a tuple");
        for (int index = 0; index < tupleSlots.length; index++) {
            if (shape.getLayout().isInt(index)) FrameAccess.writeInt(frame, slots[offset + index], frame.getInt(tupleSlots[index]));
            else if (shape.getLayout().isLong(index)) FrameAccess.writeLong(frame, slots[offset + index], frame.getLong(tupleSlots[index]));
            else if (shape.getLayout().isFloat(index)) FrameAccess.writeFloat(frame, slots[offset + index], frame.getFloat(tupleSlots[index]));
            else if (shape.getLayout().isDouble(index)) FrameAccess.writeDouble(frame, slots[offset + index], frame.getDouble(tupleSlots[index]));
            else FrameAccess.write(frame, slots[offset + index], frame.getObject(tupleSlots[index]));
        }
        for (int source : tupleSlots) {
            boolean destination = false;
            for (int index = 0; index < tupleSlots.length; index++) if (slots[offset + index] == source) destination = true;
            if (!destination) frame.clear(source);
        }
        return null;
    }
}
