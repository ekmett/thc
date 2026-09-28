// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import thc.Language;

public final class ManagedExportIoRoot extends RootNode {
    private static final class ManagedExportDestination extends TupleDestination {
        private final Language language;
        ManagedExportDestination(TupleShape shape, Language language) { super(shape); this.language = language; }
        @Override public void consume(VirtualFrame frame, Node node, Object result) {
            AsyncContinuations.publicResult(result, node);
            Object raw;
            var shape = getShape();
            if (result == TupleComplete.INSTANCE) {
                var pool = language.getHandoffState().get().getResults();
                var storage = pool.completed();
                try {
                    if (storage.getLayout() != shape.getLayout()) throw RuntimeFault.fault("Managed export returned the wrong IO tuple layout");
                    raw = shape.getLayout().getObject(storage, 0);
                } finally { pool.releaseChecked(storage, shape.getLayout()); }
            } else {
                if (!(result instanceof HandoffStorage storage)) throw RuntimeFault.fault("Managed export returned no IO tuple");
                if (storage.getLayout() != shape.getLayout()) throw RuntimeFault.fault("Managed export returned the wrong IO tuple layout");
                raw = shape.getLayout().getObject(storage, 0);
            }
            // The tuple loan must end before forcing a possibly lazy boxed result.
            frame.setObject(FrameLayout.TAIL_RESULT, raw);
        }
    }
    private final TupleShape shape;
    @Child private Force force = new Force(new Metrics(false));
    @Child private Force resultForce = new Force(new Metrics(false));
    @Child private TupleDispatch dispatch;
    public ManagedExportIoRoot(Language language, CoreRepresentation result) {
        super(language, new FrameLayout().build());
        shape = new TupleShape(result, language);
        dispatch = new TupleDispatch(new ManagedExportDestination(shape, language), new Metrics(false), 1, false);
    }
    @Override public Object execute(VirtualFrame frame) {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L);
        if (!(force.execute(frame, frame.getArguments()[0]) instanceof Closure action))
            throw RuntimeFault.fault("Managed IO export is not a state transformer");
        dispatch.execute(frame, action, new Object[]{thc.runtime.Unit.INSTANCE});
        return resultForce.execute(frame, frame.getObject(FrameLayout.TAIL_RESULT));
    }
    @Override public String getName() { return "THC managed export IO"; }
}
