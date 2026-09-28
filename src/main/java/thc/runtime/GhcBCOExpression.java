// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
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
        Object value;
        if (name.equals("mkApUpd0#")) value = GhcBCO.updating(this, operands[0].execute(frame));
        else {
            Object code = operands[0].execute(frame), literals = operands[1].execute(frame);
            Object pointers = operands[2].execute(frame);
            long arity = operands[3].executeRequiredLong(frame);
            Object bitmap = operands[4].execute(frame), state = operands[5].execute(frame);
            value = GhcBCO.create(this, language, metrics, code, literals, pointers, arity, bitmap, state);
        }
        FrameAccess.write(frame, slots[offset], value);
        return null;
    }
}
