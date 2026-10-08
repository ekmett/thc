// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.util.Arrays;
import java.util.ArrayDeque;
import static thc.runtime.Applications.requireClosure;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Exact self entry moves primitive locals; other calls keep ordinary dispatch. */
public final class AstTailApplication extends Expr {
    private final AstSelfLayout layout;
    @CompilationFinal(dimensions = 1) private final int[] temporaries;
    private final int suppliedCount;
    @Child private Evaluate function;
    @Children private LocalBinding[] operands;
    @Child private AstSelfTarget selfTarget = new AstSelfTarget();
    @Child private Dispatch dispatch;
    @CompilationFinal(dimensions = 1) private final int[] strictPositions;
    @Children private Force[] forces;
    public AstTailApplication(Expr function, Expr[] arguments, AstSelfLayout layout, int[] temporaries, Metrics metrics) {
        setRepresentation(new CoreRepresentation(CoreKind.UNKNOWN, true, false, null, null, null, null, null, null));
        this.layout = layout; this.temporaries = temporaries;
        suppliedCount = layout.getArity() - arguments.length;
        this.function = new Evaluate(function, metrics);
        operands = new LocalBinding[arguments.length];
        boolean[] evaluated = new boolean[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            operands[i] = new LocalBinding(temporaries[i + suppliedCount], arguments[i], true, null);
            evaluated[i] = arguments[i].getRepresentation().getEvaluated();
        }
        dispatch = Dispatch.create(arguments.length, true, metrics, evaluated);
        boolean[] strict = layout.getEntryStrict();
        int[] positions = new int[strict.length];
        int count = 0;
        for (int i = 0; i < strict.length; i++)
            if (strict[i] && (i < suppliedCount || !evaluated[i - suppliedCount])) positions[count++] = i;
        strictPositions = Arrays.copyOf(positions, count);
        forces = new Force[count];
        for (int i = 0; i < count; i++) forces[i] = new Force(metrics);
    }
    @Override public Object execute(VirtualFrame frame) {
        boolean suspended = false;
        try {
            Closure fn;
            try { fn = function.executeRequiredClosure(frame); }
            catch (AstCapture cut) { throw cut.append((saved, input) -> apply(saved, requireClosure(input))); }
            catch (DelimitedCut cut) {
                throw cut.append(frame, (saved, input, ambient, outer) -> apply(saved, requireClosure(input.get())));
            }
            return apply(frame, fn);
        } catch (AstCapture cut) {
            suspended = true; throw cut.enclose(steps -> new Cleanup(this, steps));
        } catch (DelimitedCut cut) {
            suspended = true; throw cut.append(frame, new Cleanup(this));
        } finally { if (!suspended) clear(frame); }
    }
    private Object apply(VirtualFrame frame, Closure fn) {
        boolean self = !(getRootNode() instanceof FunctionRoot root && root.getRole$org_intelligence_thc() == FunctionRootRole.PASS_THROUGH) &&
            fn.arity == operands.length && fn.supplied.length == suppliedCount && selfTarget.matches(fn.target);
        return writeOperands(frame, fn, self, 0);
    }
    @ExplodeLoop private Object writeOperands(VirtualFrame frame, Closure fn, boolean self, int start) {
        for (int i = start; i < operands.length; i++) {
            try { operands[i].write(frame); }
            catch (AstCapture cut) {
                int index = i;
                throw cut.append((saved, input) -> resumeOperand(saved, fn, self, index, input));
            } catch (DelimitedCut cut) {
                int index = i;
                throw cut.append(frame, (saved, input, ambient, outer) -> resumeOperand(saved, fn, self, index, input.get()));
            }
        }
        if (self) {
            for (int i = 0; i < suppliedCount; i++) FrameAccess.INSTANCE.write(frame, temporaries[i], fn.supplied[i]);
            return forceStrict(frame, fn, 0);
        }
        Object[] values = new Object[operands.length];
        for (int i = 0; i < operands.length; i++) values[i] = FrameAccess.INSTANCE.read(frame, temporaries[i + suppliedCount]);
        return dispatch.execute(frame, fn, values);
    }
    private Object resumeOperand(VirtualFrame frame, Closure fn, boolean self, int index, Object input) {
        if (input != Unit.INSTANCE) throw fault("Invalid self operand continuation");
        return writeOperands(frame, fn, self, index + 1);
    }
    @ExplodeLoop private Object forceStrict(VirtualFrame frame, Closure fn, int start) {
        for (int i = start; i < strictPositions.length; i++) {
            int slot = temporaries[strictPositions[i]];
            Object value;
            try { value = AstControl.force(frame, this, forces[i], FrameAccess.INSTANCE.read(frame, slot)); }
            catch (AstCapture cut) {
                int index = i;
                throw cut.append((saved, input) -> resumeStrict(saved, fn, index, input));
            } catch (DelimitedCut cut) {
                int index = i;
                throw cut.append(frame, (saved, input, ambient, outer) -> resumeStrict(saved, fn, index, input.get()));
            }
            FrameAccess.INSTANCE.write(frame, slot, value);
        }
        throw layout.transfer(frame, fn, temporaries);
    }
    private Object resumeStrict(VirtualFrame frame, Closure fn, int index, Object input) {
        FrameAccess.INSTANCE.write(frame, temporaries[strictPositions[index]], input);
        return forceStrict(frame, fn, index + 1);
    }
    @ExplodeLoop private void clear(VirtualFrame frame) { for (int slot : temporaries) frame.clear(slot); }
    private static final class Cleanup implements AstResumeStep, DelimitedStep {
        private final AstTailApplication owner;
        private final ArrayDeque<AstResumeStep> steps;
        @TruffleBoundary Cleanup(AstTailApplication owner) { this(owner, new ArrayDeque<>()); }
        Cleanup(AstTailApplication owner, ArrayDeque<AstResumeStep> steps) { this.owner = owner; this.steps = steps; }
        @Override public Object resume(VirtualFrame frame, Object input) {
            boolean suspended = false;
            try { return AstContinuations.resumeAstSteps(frame, steps, input); }
            catch (AstCapture cut) {
                suspended = true; throw cut.enclose(remaining -> new Cleanup(owner, remaining));
            } catch (DelimitedCut cut) {
                suspended = true; throw cut.append(frame, new Cleanup(owner));
            } finally { if (!suspended) owner.clear(frame); }
        }
        @Override public Object resume(MaterializedFrame frame, DelimitedResume input, MaskingState ambient, DelimitedStep outerMask) {
            try { return input.get(); }
            finally { owner.clear(frame); }
        }
    }
}
