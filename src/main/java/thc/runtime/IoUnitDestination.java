// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Consumes the host IO tuple and releases its loan before any lifted-field force. */
public final class IoUnitDestination extends TupleDestination {
    private final Language language;
    public IoUnitDestination(TupleShape shape, Language language) { super(shape); this.language = language; }
    @Override public void consume(VirtualFrame frame, Node node, Object result) {
        AsyncContinuations.publicResult(result, node);
        TupleShape shape = getShape();
        Object raw;
        if (result == TupleComplete.INSTANCE) {
            TupleResultPool pool = language.getHandoffState().get().getResults();
            HandoffStorage storage = pool.completed();
            try {
                if (storage.getLayout() != shape.getLayout()) throw fault("IO main returned the wrong tuple layout");
                raw = shape.getLayout().getObject(storage, 0);
            } finally { pool.releaseChecked(storage, shape.getLayout()); }
        } else {
            if (!(result instanceof HandoffStorage storage)) throw fault("IO main returned no tuple");
            if (storage.getLayout() != shape.getLayout()) throw fault("IO main returned the wrong tuple layout");
            raw = shape.getLayout().getObject(storage, 0);
        }
        frame.setObject(FrameLayout.TAIL_RESULT, raw);
    }
}
