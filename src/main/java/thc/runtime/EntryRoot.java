// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import static thc.runtime.RuntimeFault.fault;

public final class EntryRoot extends RootNode {
    private final int arity;
    @Child private Dispatch dispatch;
    @Child private Force force;
    public EntryRoot(TruffleLanguage<?> language, int arity, Metrics metrics) {
        super(language, new FrameLayout().build());
        this.arity = arity; dispatch = Dispatch.create(arity, false, metrics); force = new Force(metrics);
    }
    @Override public Object execute(VirtualFrame frame) {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L);
        Object value = force.execute(frame, frame.getArguments()[0]);
        if (arity == 0) return value;
        if (!(value instanceof Closure fn)) throw fault("Application of a non-function");
        if (!(frame.getArguments()[1] instanceof Object[] args)) throw fault("Invalid host arguments");
        return force.execute(frame, dispatch.execute(frame, fn, args));
    }
    @Override public String getName() { return "THC host entry/" + arity; }
}
