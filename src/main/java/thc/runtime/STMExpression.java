// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

public final class STMExpression extends Expr {
    private final STMOp operation;
    @Children private Expr[] operands;
    private final TupleShape shape;
    private final Metrics metrics;
    @Child private Expr nested;
    @Child private Expr blocked;
    private final boolean async;
    @Child private volatile STMCall call;
    @CompilationFinal(dimensions = 1) private int[] destinationSlots;
    @CompilationFinal private int destinationOffset = -1;
    public STMExpression(STMOp operation, CoreRepresentation proof, Expr[] operands, TupleShape shape, Metrics metrics, Expr nested) {
        this(operation, proof, operands, shape, metrics, nested, false);
    }
    public STMExpression(STMOp operation, CoreRepresentation proof, Expr[] operands, TupleShape shape, Metrics metrics, Expr nested, boolean async) {
        this(operation, proof, operands, shape, metrics, nested, async, null);
    }
    public STMExpression(STMOp operation, CoreRepresentation proof, Expr[] operands, TupleShape shape, Metrics metrics, Expr nested, boolean async, Expr blocked) {
        this.operation = operation; this.operands = operands; this.shape = shape; this.metrics = metrics; this.nested = nested; this.async = async; this.blocked = blocked;
        setRepresentation(proof.withEvaluated(true));
    }
    @Override public Object execute(VirtualFrame frame) {
        if (operation != STMOp.WRITE) throw fault("STM tuple primitive requires a destination");
        Object cell = operands[0].execute(frame), value = operands[1].execute(frame);
        TupleResults.requireVoidCarrier(operands[2].execute(frame));
        Language.currentState(this).stm.write(cell, value);
        return thc.runtime.Unit.INSTANCE;
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object first = operation == STMOp.RETRY ? null : operands[0].execute(frame);
        Object second = operation == STMOp.OR_ELSE || operation == STMOp.CATCH ? operands[1].execute(frame) : null;
        TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
        if (operation.getCallback()) {
            if (call == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                atomic(() -> {
                    if (call == null) {
                        if (shape == null) throw new IllegalStateException("Required value was null.");
                        call = insert(new STMCall(operation, new AstTupleDestination(shape, slots, offset), metrics, async));
                        destinationSlots = slots; destinationOffset = offset;
                    }
                    return null;
                });
            }
            if (destinationSlots != slots || destinationOffset != offset) throw new IllegalStateException("Check failed.");
            Object payload;
            try { payload = nested == null ? null : nested.execute(frame); }
            catch (AstCapture cut) { throw cut.append((saved, input) -> { invoke(saved, first, second, input); return null; }); }
            invoke(frame, first, second, payload);
        } else {
            var stm = Language.currentState(this).stm;
            Object value = switch (operation) {
                case NEW -> stm.newTVar(first);
                case READ -> stm.read(first);
                case READ_IO -> stm.readIO(first);
                case RETRY -> throw stm.retry();
                default -> throw new IllegalStateException("Not an STM tuple primitive: " + operation);
            };
            FrameAccess.write(frame, slots[offset], value);
        }
        return null;
    }
    private void invoke(VirtualFrame frame, Object action, Object alternative, Object nested) {
        finish(frame, action, alternative, nested, null, null);
    }
    private void finish(VirtualFrame frame, Object action, Object alternative, Object nested,
            java.util.ArrayDeque<AstResumeStep> steps, Object input) {
        try {
            Object result = steps == null ? call.execute(frame, action, alternative, nested, blocked == null ? null : blocked.execute(frame))
                : AstContinuations.resumeAstSteps(frame, steps, input);
            if (steps != null || call.captures()) shape.consume(frame, result, destinationSlots, destinationOffset);
        } catch (AstCapture cut) {
            throw cut.enclose(remaining -> (resumed, value) -> {
                finish(resumed, action, alternative, nested, remaining, value);
                return null;
            });
        }
        catch (STMRestart restart) {
            // A NEW capture: resuming the abandoned child would run without an attempt.
            throw new AstCapture(restart.getRequest(), SynchronousMasking.current(this)).append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame resumedFrame, Object input) {
                    invoke(resumedFrame, action, alternative, nested);
                    return null;
                }
            });
        }
    }
}
