// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

public final class GhcBCOExpression extends Expr {
    private final String name;
    @Children private Expr[] operands;
    private final Language language;
    private final Metrics metrics;
    public GhcBCOExpression(String name, Expr[] operands, Language language, Metrics metrics, CoreRepresentation proof) {
        this.name = name; this.operands = operands; this.language = language; this.metrics = metrics;
        setRepresentation(proof.withEvaluated(true));
    }
    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw fault(name + " requires a tuple destination");
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        return evaluate(frame, slots, offset, new Object[operands.length], 0);
    }
    @ExplodeLoop
    private Object evaluate(VirtualFrame frame, int[] slots, int offset, Object[] values, int start) {
        for (int i = 0; i < operands.length; i++) {
            if (i < start) continue;
            try { values[i] = i == 3 ? operands[i].executeRequiredLong(frame) : operands[i].execute(frame); }
            catch (AstCapture cut) {
                int index = i;
                throw cut.append((saved, input) -> {
                    values[index] = input;
                    return evaluate(saved, slots, offset, values, index + 1);
                });
            }
        }
        Object value = name.equals("mkApUpd0#") ? GhcBCO.updating(this, values[0]) :
            GhcBCO.create(this, language, metrics != null ? metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame), values[0], values[1], values[2], (Long) values[3], values[4], values[5]);
        FrameAccess.write(frame, slots[offset], value);
        return null;
    }
}
