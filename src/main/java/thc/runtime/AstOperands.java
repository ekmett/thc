// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.util.ArrayDeque;

/** Sequenced operands retain completed prefixes across cuts, never replaying effects. */
public final class AstOperands extends Expr {
    @Children private LocalBinding[] operands;
    @CompilationFinal(dimensions = 1) private final int[] temporaries;
    @Child private Expr body;
    public AstOperands(LocalBinding[] operands, int[] temporaries, Expr body) {
        this.operands = operands; this.temporaries = temporaries; this.body = body;
        setRepresentation(body.getRepresentation());
    }
    @Override public void prepareTuple(int[] slots, int offset) { body.prepareTuple(slots, offset); }
    @ExplodeLoop private void prepare(VirtualFrame frame, int start) {
        for (int index = start; index < operands.length; index++) {
            try { operands[index].write(frame); }
            catch (AstCapture cut) { throw appendPrepare(cut, index + 1); }
            catch (DelimitedCut cut) {
                int next = index + 1;
                throw cut.append(frame, (saved, input, ambient, outer) -> { input.get(); prepare(saved, next); return thc.runtime.Unit.INSTANCE; });
            }
        }
    }
    @TruffleBoundary private AstCapture appendPrepare(AstCapture cut, int next) {
        return cut.append((saved, input) -> { prepare(saved, next); return thc.runtime.Unit.INSTANCE; });
    }
    private void prepareBody(VirtualFrame frame, int[] slots, int offset) {
        try { prepare(frame, 0); }
        catch (AstCapture cut) { throw appendBody(cut, slots, offset); }
        catch (DelimitedCut cut) {
            throw cut.append(frame, (saved, input, ambient, outer) -> {
                input.get(); return slots == null ? body.execute(saved) : body.executeTuple(saved, slots, offset);
            });
        }
    }
    @TruffleBoundary private AstCapture appendBody(AstCapture cut, int[] slots, int offset) {
        return cut.append((saved, input) -> slots == null ? body.execute(saved) : body.executeTuple(saved, slots, offset));
    }
    @TruffleBoundary private AstCapture encloseCleanup(AstCapture cut) {
        return cut.enclose(saved -> new Cleanup(this, saved));
    }
    @ExplodeLoop private void clear(VirtualFrame frame) { for (int slot : temporaries) frame.clear(slot); }
    private static final class Cleanup implements AstResumeStep, DelimitedStep {
        private final AstOperands owner;
        private final ArrayDeque<AstResumeStep> steps;
        @TruffleBoundary Cleanup(AstOperands owner) { this(owner, new ArrayDeque<>()); }
        Cleanup(AstOperands owner, ArrayDeque<AstResumeStep> steps) { this.owner = owner; this.steps = steps; }
        @Override public Object resume(VirtualFrame frame, Object input) {
            boolean suspended = false;
            try { return AstContinuations.resumeAstSteps(frame, steps, input); }
            catch (AstCapture cut) {
                suspended = true;
                throw cut.enclose(saved -> new Cleanup(owner, saved));
            } catch (DelimitedCut cut) {
                suspended = true;
                throw cut.append(frame, new Cleanup(owner));
            } finally { if (!suspended) owner.clear(frame); }
        }
        @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
            try { return input.get(); }
            finally { owner.clear(frame); }
        }
    }
    @Override public Object execute(VirtualFrame frame) {
        boolean suspended = false;
        try {
            prepareBody(frame, null, 0);
            return body.execute(frame);
        } catch (AstCapture cut) {
            suspended = true;
            throw encloseCleanup(cut);
        } catch (DelimitedCut cut) {
            suspended = true;
            throw cut.append(frame, new Cleanup(this));
        } finally {
            // Only parked suffixes retain operand temporaries.
            if (!suspended) clear(frame);
        }
    }
    @Override public int executeInt(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
        boolean suspended = false;
        try {
            prepareBody(frame, null, 0);
            return body.executeInt(frame);
        } catch (AstCapture cut) {
            suspended = true;
            throw encloseCleanup(cut);
        } catch (DelimitedCut cut) {
            suspended = true;
            throw cut.append(frame, new Cleanup(this));
        } finally {
            // Only parked suffixes retain operand temporaries.
            if (!suspended) clear(frame);
        }
    }
    @Override public long executeLong(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
        boolean suspended = false;
        try {
            prepareBody(frame, null, 0);
            return body.executeLong(frame);
        } catch (AstCapture cut) {
            suspended = true;
            throw encloseCleanup(cut);
        } catch (DelimitedCut cut) {
            suspended = true;
            throw cut.append(frame, new Cleanup(this));
        } finally {
            // Only parked suffixes retain operand temporaries.
            if (!suspended) clear(frame);
        }
    }
    @Override public float executeFloat(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
        boolean suspended = false;
        try {
            prepareBody(frame, null, 0);
            return body.executeFloat(frame);
        } catch (AstCapture cut) {
            suspended = true;
            throw encloseCleanup(cut);
        } catch (DelimitedCut cut) {
            suspended = true;
            throw cut.append(frame, new Cleanup(this));
        } finally {
            // Only parked suffixes retain operand temporaries.
            if (!suspended) clear(frame);
        }
    }
    @Override public double executeDouble(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
        boolean suspended = false;
        try {
            prepareBody(frame, null, 0);
            return body.executeDouble(frame);
        } catch (AstCapture cut) {
            suspended = true;
            throw encloseCleanup(cut);
        } catch (DelimitedCut cut) {
            suspended = true;
            throw cut.append(frame, new Cleanup(this));
        } finally {
            // Only parked suffixes retain operand temporaries.
            if (!suspended) clear(frame);
        }
    }
    @Override public Closure executeClosure(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
        boolean suspended = false;
        try {
            prepareBody(frame, null, 0);
            return body.executeClosure(frame);
        } catch (AstCapture cut) {
            suspended = true;
            throw encloseCleanup(cut);
        } catch (DelimitedCut cut) {
            suspended = true;
            throw cut.append(frame, new Cleanup(this));
        } finally {
            // Only parked suffixes retain operand temporaries.
            if (!suspended) clear(frame);
        }
    }
    @Override public DataValue executeDataValue(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
        boolean suspended = false;
        try {
            prepareBody(frame, null, 0);
            return body.executeDataValue(frame);
        } catch (AstCapture cut) {
            suspended = true;
            throw encloseCleanup(cut);
        } catch (DelimitedCut cut) {
            suspended = true;
            throw cut.append(frame, new Cleanup(this));
        } finally {
            // Only parked suffixes retain operand temporaries.
            if (!suspended) clear(frame);
        }
    }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
        boolean suspended = false;
        try {
            prepareBody(frame, null, 0);
            return body.executeAddress(frame);
        } catch (AstCapture cut) {
            suspended = true;
            throw encloseCleanup(cut);
        } catch (DelimitedCut cut) {
            suspended = true;
            throw cut.append(frame, new Cleanup(this));
        } finally {
            // Only parked suffixes retain operand temporaries.
            if (!suspended) clear(frame);
        }
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        boolean suspended = false;
        try {
            prepareBody(frame, slots, offset);
            return body.executeTuple(frame, slots, offset);
        } catch (AstCapture cut) {
            suspended = true;
            throw encloseCleanup(cut);
        } catch (DelimitedCut cut) {
            suspended = true;
            throw cut.append(frame, new Cleanup(this));
        } finally {
            // Only parked suffixes retain operand temporaries.
            if (!suspended) clear(frame);
        }
    }
}
