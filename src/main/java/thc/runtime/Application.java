// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.util.ArrayList;

final class Application extends Expr {
    @Child private Evaluate function;
    @Children private Expr[] arguments;
    private final ArgumentLayout inputLayout;
    @Child private Dispatch dispatch;
    Application(Expr function, Expr[] arguments, boolean tail, Metrics metrics) {
        setRepresentation(new CoreRepresentation(CoreKind.UNKNOWN, true, false, null, null, null, null, null, null));
        this.function = new Evaluate(function, metrics);
        this.arguments = arguments;
        var proofs = new ArrayList<CoreRepresentation>(arguments.length);
        boolean[] evaluated = new boolean[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            CoreRepresentation proof = arguments[i].getRepresentation();
            proofs.add(proof);
            evaluated[i] = proof.getEvaluated();
        }
        inputLayout = ArgumentLayout.Companion.fromProofs(proofs);
        dispatch = Dispatch.Companion.create(arguments.length, tail, metrics, evaluated, inputLayout);
    }
    @ExplodeLoop @Override public Object execute(VirtualFrame frame) {
        Closure fn;
        try { fn = function.executeRequiredClosure(frame); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame frame, Object input) {
                    return applyArguments(frame, ApplicationKt.requireClosure(input),
                        new Object[ArgumentLayout.Companion.width(inputLayout, arguments.length)], 0);
                }
            });
        }
        Object[] values = new Object[ArgumentLayout.Companion.width(inputLayout, arguments.length)];
        return applyArguments(frame, fn, values, 0);
    }
    @ExplodeLoop private Object applyArguments(VirtualFrame frame, Closure fn, Object[] values, int start) {
        for (int i = start; i < arguments.length; i++) {
            try {
                if (inputLayout != null && inputLayout.isEmpty(i)) arguments[i].executeTuple(frame, ArgumentLayoutKt.getEMPTY_TUPLE_SLOTS(), 0);
                else values[ArgumentLayout.Companion.offset(inputLayout, i)] = arguments[i].execute(frame);
            } catch (AstCapture cut) {
                int index = i;
                throw cut.append(new AstResumeStep() {
                    @Override public Object resume(VirtualFrame frame, Object input) {
                        if (inputLayout == null || !inputLayout.isEmpty(index)) values[ArgumentLayout.Companion.offset(inputLayout, index)] = input;
                        return applyArguments(frame, fn, values, index + 1);
                    }
                });
            }
        }
        return dispatch.execute(frame, fn, values);
    }
}
