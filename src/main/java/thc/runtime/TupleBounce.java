// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Residual calls consume the pooled token in the caller frame. */
public final class TupleBounce extends Node {
    private final TupleDestination destination;
    private final Metrics metrics;
    @Child private IndirectCallNode call = IndirectCallNode.create();
    public TupleBounce(TupleDestination destination, Metrics metrics) { this.destination = destination; this.metrics = metrics; }
    public void execute(VirtualFrame frame, TailCall initial) {
        Metrics metrics = this.metrics != null ? this.metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame);
        TailCall next = initial;
        while (true) {
            try { TruffleSafepoint.poll(this); }
            catch (Throwable failure) {
                if (next.getInput() != null) TypedInputs.discardTypedInput(destination.getShape().getLanguage(), next.getInput());
                throw failure;
            }
            if (!(next.getTarget().getRootNode() instanceof GuestRoot root) || !root.hasTupleResult(destination.getShape())) {
                if (next.getInput() != null) TypedInputs.discardTypedInput(destination.getShape().getLanguage(), next.getInput());
                throw fault("Tuple tail target result shape mismatch");
            }
            try {
                if (metrics.getEnabled()) metrics.incrementTrampolineIterations();
                HandoffStorage input = next.getInput();
                Object result;
                if (input != null) {
                    input.getLayout().setLong(input, 0, 0L);
                    var targetRoot = next.getTarget().getRootNode();
                    if (targetRoot == null) {
                        CompilerDirectives.transferToInterpreter();
                        throw new NullPointerException("null cannot be cast to non-null type thc.runtime.GuestRoot");
                    }
                    TypedInputLayout inputLayout = ((GuestRoot) targetRoot).getTypedInput();
                    if (inputLayout == null) throw fault("Target has no typed input entry");
                    long generation = input.getGeneration();
                    try { result = (this.metrics == null ? next.getTarget().call(this, new Object[]{input}) : Calls.indirect(call, next.getTarget(), new Object[]{input})); }
                    finally { GenericTypedInputs.releaseGenericInput(inputLayout, input, generation); }
                } else {
                    next.getArgs()[0] = 0L;
                    result = (this.metrics == null ? next.getTarget().call(this, next.getArgs()) : Calls.indirect(call, next.getTarget(), next.getArgs()));
                }
                destination.consume(frame, this, result instanceof SavedGuestContinuation continuation ? new AstTailYield(continuation, next.getTarget()) : result, root.getTupleResult());
                return;
            } catch (TailCall transfer) { next = transfer; }
            catch (DelimitedCut cut) {
                if (!DelimitedControl.enabled(this)) throw cut;
                throw DelimitedControl.tupleCut(cut, frame.materialize(), destination, this, root.getTupleResult());
            }
        }
    }
}
