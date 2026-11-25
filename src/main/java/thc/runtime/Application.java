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
    @Child private PreparedDispatch dispatch;
    Application(Expr function, Expr[] arguments, boolean tail, Metrics metrics) {
        setRepresentation(CoreRepresentation.UNKNOWN);
        this.function = new Evaluate(function, metrics);
        this.arguments = arguments;
        var proofs = new ArrayList<CoreRepresentation>(arguments.length);
        boolean[] evaluated = new boolean[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            CoreRepresentation proof = arguments[i].getRepresentation();
            proofs.add(proof);
            if (proof.isEmptyTuple()) arguments[i].prepareTuple(ArgumentLayout.EMPTY_TUPLE_SLOTS, 0);
            evaluated[i] = proof.getEvaluated();
        }
        inputLayout = ArgumentLayout.fromProofs(proofs);
        dispatch = new PreparedDispatch(arguments.length, tail, metrics, evaluated, inputLayout);
        dispatch.ownAstArgumentScratch();
    }
    @ExplodeLoop @Override public Object execute(VirtualFrame frame) {
        Closure fn;
        try { fn = function.executeRequiredClosure(frame); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame frame, Object input) {
                    return apply(frame, Applications.requireClosure(input));
                }
            });
        } catch (DelimitedCut cut) {
            throw cut.append(frame, (saved, input, ambient, outer) -> apply(saved, Applications.requireClosure(input.get())));
        }
        return apply(frame, fn);
    }
    private Object apply(VirtualFrame frame, Closure fn) {
        int header = dispatch.packetHeader(fn);
        Object[] values = new Object[header + ArgumentLayout.width(inputLayout, arguments.length)];
        return applyArguments(frame, fn, values, header, 0);
    }
    @ExplodeLoop private Object applyArguments(VirtualFrame frame, Closure fn, Object[] values, int header, int start) {
        for (int i = start; i < arguments.length; i++) {
            try {
                if (inputLayout != null && inputLayout.isEmpty(i)) arguments[i].executeTuple(frame, ArgumentLayout.EMPTY_TUPLE_SLOTS, 0);
                else values[header + ArgumentLayout.offset(inputLayout, i)] = arguments[i].execute(frame);
            } catch (AstCapture cut) {
                int index = i;
                throw cut.append((saved, input) -> resumeArgument(saved, fn, values, header, index, input));
            } catch (DelimitedCut cut) {
                int index = i;
                throw cut.append(frame, (saved, input, ambient, outer) -> resumeArgument(saved, fn, values, header, index, input.get()));
            }
        }
        return dispatch.execute(frame, fn, values);
    }
    private Object resumeArgument(VirtualFrame frame, Closure fn, Object[] prefix, int header, int index, Object input) {
        // This prefix can belong to a reusable image; clone transport scratch,
        // never the guest values, before running potentially reentrant operands.
        Object[] values = prefix.clone();
        if (inputLayout == null || !inputLayout.isEmpty(index)) values[header + ArgumentLayout.offset(inputLayout, index)] = input;
        return applyArguments(frame, fn, values, header, index + 1);
    }
}
