// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** The IO newtype is an erased State# transformer, not a native process. */
public final class IoMainRoot extends ContextRoot {
    @Child private Force force = new Force(new Metrics(false));
    @Child private TupleDispatch dispatch;
    public IoMainRoot(Language language, CoreRepresentation result) {
        super(language, new FrameLayout().build());
        TupleShape shape = new TupleShape(result, language);
        dispatch = new TupleDispatch(new IoResultDestination(shape, language, false), new Metrics(false), 1, false);
    }
    @Override public Object execute(VirtualFrame frame) {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L);
        if (!(force.execute(frame, frame.getArguments()[0]) instanceof Closure action)) throw fault("IO main is not a state transformer");
        if (action.arity != 1 || !(action.target.getRootNode() instanceof GuestRoot root) ||
                root.getInputProofs() == null || root.getInputProofs().size() != action.suppliedCount + 1 ||
                root.getInputProofs().getLast().getKind() != CoreKind.VOID ||
                !java.util.List.of().equals(root.getInputProofs().getLast().getPrimReps()))
            throw fault("IO main requires one exact erased state input");
        dispatch.execute(frame, action, new Object[] { thc.runtime.Unit.INSTANCE });
        return thc.runtime.Unit.INSTANCE;
    }
    @Override public String getName() { return "THC IO main"; }
}
