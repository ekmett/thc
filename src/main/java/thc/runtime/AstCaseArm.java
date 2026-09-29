// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;

/**
 * An already-selected arm: its scrutinee and constructor fields are owned by
 * the caller. Only exact live captures cross this ordinarily inlinable edge.
 * There is deliberately no tail check, Bloom reset or trampoline here.
 */
public final class AstCaseArm extends Expr {
    private final RootCallTarget target;
    private final CaptureLayout captures;
    @CompilationFinal(dimensions = 1) private final int[] sourceSlots;
    /** Original Core position, not itself a result/cleanup elision certificate. */
    private final boolean tailPosition;
    private final int programSlot;
    @Child private DirectCallNode call;
    private final TupleShape shape;

    public AstCaseArm(RootCallTarget target, CaptureLayout captures, int[] sourceSlots,
                     boolean tailPosition) {
        this(target, captures, sourceSlots, tailPosition, -1);
    }

    AstCaseArm(RootCallTarget target, CaptureLayout captures, int[] sourceSlots,
               boolean tailPosition, int programSlot) {
        this.target = target;
        this.captures = captures;
        this.sourceSlots = sourceSlots;
        this.tailPosition = tailPosition;
        this.programSlot = programSlot;
        this.call = programSlot < 0 ? DirectCallNode.create(target) : null;
        GuestRoot root = (GuestRoot) target.getRootNode();
        this.shape = root.getTupleResult$org_intelligence_thc();
        if (((FunctionRoot) root).getRole$org_intelligence_thc() != FunctionRootRole.PASS_THROUGH) {
            throw new IllegalStateException("Check failed.");
        }
        setRepresentation(root.getScalarResultProof$org_intelligence_thc());
    }

    public RootCallTarget getTarget() {
        return target;
    }

    public boolean getTailPosition() {
        return tailPosition;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        long bloom = ((GuestRoot) getRootNode()).bloom(frame);
        Object[] arguments = captures == null ? new Object[]{bloom}
                : new Object[]{bloom, captures.capture(frame, sourceSlots,
                    programSlot < 0 ? null : Program.instance(frame, programSlot))};
        // Like other prepared residual calls, allow a first lawful TailCall
        // without training DirectCallNode's separate exception profile.
        Object result = programSlot < 0 ? Calls.direct(call, arguments) : target.call(this, arguments);
        // Core tail position is only a candidate: complete also requires an exact
        // scalar identity return and an owned internal spill. Cleanup steps veto omission.
        return AstControl.INSTANCE.complete(this, result, target, shape, tailPosition);
    }

    @Override
    public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        TupleShape resultShape = shape;
        if (resultShape == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Case arm has no typed result");
        }
        Object answer;
        try {
            answer = execute(frame);
        } catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override
                public Object resume(VirtualFrame resumedFrame, Object input) {
                    resultShape.consume(resumedFrame, input, slots, offset);
                    return null;
                }
            });
        } catch (DelimitedCut cut) {
            if (!DelimitedControl.INSTANCE.enabled(this)) {
                throw cut;
            }
            CompilerDirectives.transferToInterpreter();
            throw DelimitedControl.INSTANCE.tupleCut(cut, frame.materialize(),
                    new AstTupleDestination(resultShape, slots, offset), this);
        }
        resultShape.consume(frame, answer, slots, offset);
        return null;
    }
}
