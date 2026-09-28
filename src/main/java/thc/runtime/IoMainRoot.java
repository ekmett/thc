// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** The IO newtype is an erased State# transformer, not a native process. */
public final class IoMainRoot extends RootNode {
    private static final String UNIT_CONSTRUCTOR_ID = "ghc-internal:GHC.Internal.Tuple.()";
    @Child private Force force = new Force(new Metrics(false));
    @Child private Force unitForce = new Force(new Metrics(false));
    @Child private TupleDispatch dispatch;
    public IoMainRoot(Language language, CoreRepresentation result) {
        super(language, new FrameLayout().build());
        TupleShape shape = new TupleShape(result, language);
        dispatch = new TupleDispatch(new IoUnitDestination(shape, language), new Metrics(false), 1, false);
    }
    @Override public Object execute(VirtualFrame frame) {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L);
        if (!(force.execute(frame, frame.getArguments()[0]) instanceof Closure action)) throw fault("IO main is not a state transformer");
        dispatch.execute(frame, action, new Object[] { thc.runtime.Unit.INSTANCE });
        if (!(unitForce.execute(frame, frame.getObject(FrameLayout.TAIL_RESULT)) instanceof DataValue value))
            throw fault("IO main did not return boxed unit");
        if (!UNIT_CONSTRUCTOR_ID.equals(value.getLayout().getId()) || value.getLayout().getArity() != 0)
            throw fault("IO main did not return boxed unit");
        return thc.runtime.Unit.INSTANCE;
    }
    @Override public String getName() { return "THC IO main"; }
}
