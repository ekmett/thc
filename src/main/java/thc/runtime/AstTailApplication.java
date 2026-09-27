// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.util.Arrays;

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
    @Override @ExplodeLoop public Object execute(VirtualFrame frame) {
        Closure fn = function.executeRequiredClosure(frame);
        if (!(getRootNode() instanceof FunctionRoot root && root.getRole$org_intelligence_thc() == FunctionRootRole.PASS_THROUGH) &&
            fn.arity == operands.length && fn.supplied.length == suppliedCount && selfTarget.matches(fn.target)) {
            for (LocalBinding operand : operands) operand.write(frame);
            for (int i = 0; i < suppliedCount; i++) FrameAccess.INSTANCE.write(frame, temporaries[i], fn.supplied[i]);
            for (int i = 0; i < strictPositions.length; i++) {
                int slot = temporaries[strictPositions[i]];
                FrameAccess.INSTANCE.write(frame, slot, forces[i].execute(frame, FrameAccess.INSTANCE.read(frame, slot)));
            }
            throw layout.transfer(frame, fn, temporaries);
        }
        Object[] values = new Object[operands.length];
        for (int i = 0; i < operands.length; i++) values[i] = operands[i].evaluate(frame);
        return dispatch.execute(frame, fn, values);
    }
}
