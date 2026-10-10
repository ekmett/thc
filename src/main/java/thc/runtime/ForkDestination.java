// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;

/** Discards a fork action's lifted result and releases its tuple loan. */
final class ForkDestination extends TupleDestination {
    private final Language language;
    ForkDestination(TupleShape shape, Language language) { super(shape); this.language = language; }
    @Override public void consume(VirtualFrame frame, Node node, Object result) { consumeFrom(frame, node, result, getShape()); }
    @Override protected void consumeFrom(VirtualFrame frame, Node node, Object result, TupleShape shape) {
        SavedGuestContinuation ast = result instanceof SavedGuestContinuation saved ? saved :
            result instanceof AstTailYield tail ? tail.getContinuation() : null;
        if (ast != null) {
            AsyncRequest request = ast.asyncRequest();
            if (request == null) {
                PendingWait wait = PendingWait.of(ast);
                if (wait != null) throw new ForkSuspension(new ForkActionRoot.Body(ast, shape), wait);
                throw RuntimeFault.fault("Fork action suspended without an async request");
            }
            throw new UncaughtForkAsync(request);
        }
        ContinuationResult continuation = result instanceof ContinuationResult saved ? saved :
            result instanceof TailYield tail ? tail.getContinuation() : null;
        if (continuation != null) {
            AsyncRequest request = AsyncContinuations.request(continuation);
            if (request == null) {
                PendingWait wait = PendingWait.of(continuation);
                if (wait != null) throw new ForkSuspension(new ForkActionRoot.Body(SavedGuestContinuations.savedGuestContinuation(continuation), shape), wait);
                throw RuntimeFault.fault("Fork action suspended without an async request");
            }
            throw new UncaughtForkAsync(request);
        }
        if (result == TupleComplete.INSTANCE) {
            var pool = language.getHandoffState().get().getResults();
            HandoffStorage storage = pool.completed();
            try {
                if (storage.getLayout() != shape.getLayout()) throw RuntimeFault.fault("Fork action returned the wrong tuple layout");
            } finally { pool.releaseChecked(storage, getShape().getLayout()); }
        } else {
            if (!(result instanceof HandoffStorage storage)) throw RuntimeFault.fault("Fork action returned no tuple");
            if (storage.getLayout() != shape.getLayout()) throw RuntimeFault.fault("Fork action returned the wrong tuple layout");
        }
    }
}
