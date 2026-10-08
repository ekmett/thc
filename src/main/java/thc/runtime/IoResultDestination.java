// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Authenticates and releases the IO tuple; executable answers are discarded lazily. */
public final class IoResultDestination extends TupleDestination {
    private final Language language;
    private final boolean retainAnswer;
    public IoResultDestination(TupleShape shape, Language language, boolean retainAnswer) {
        super(shape); this.language = language; this.retainAnswer = retainAnswer;
    }
    @Override public void consume(VirtualFrame frame, Node node, Object result) { consumeFrom(frame, node, result, getShape()); }
    @Override protected void consumeFrom(VirtualFrame frame, Node node, Object result, TupleShape shape) {
        AsyncContinuations.publicResult(result, node);
        Object raw = null;
        if (result == TupleComplete.INSTANCE) {
            TupleResultPool pool = language.getHandoffState().get().getResults();
            HandoffStorage storage = pool.completed();
            try {
                if (storage.getLayout() != shape.getLayout()) throw fault("IO main returned the wrong tuple layout");
                if (retainAnswer) raw = shape.getLayout().getObject(storage, 0);
            } finally { pool.releaseChecked(storage, getShape().getLayout()); }
        } else {
            if (!(result instanceof HandoffStorage storage)) throw fault("IO main returned no tuple");
            if (storage.getLayout() != shape.getLayout()) throw fault("IO main returned the wrong tuple layout");
            if (retainAnswer) raw = shape.getLayout().getObject(storage, 0);
        }
        if (retainAnswer) frame.setObject(FrameLayout.TAIL_RESULT, raw);
    }
}
