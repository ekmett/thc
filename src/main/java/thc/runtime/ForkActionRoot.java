// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import thc.Language;

/** One child owns its dispatch tree; no parent frame or handoff loan crosses threads. */
final class ForkActionRoot extends ContextRoot {
    private final Language language;
    private final boolean asyncEnabled;
    private final int argumentCount;
    @Child private Force force;
    @Child private TupleDispatch dispatch;
    ForkActionRoot(Language language, TupleShape initialShape, boolean asyncEnabled) {
        this(language, initialShape, asyncEnabled, 1);
    }
    ForkActionRoot(Language language, TupleShape initialShape, boolean asyncEnabled, int argumentCount) {
        super(language, new FrameLayout().build());
        this.argumentCount = argumentCount;
        this.language = language; this.asyncEnabled = asyncEnabled;
        force = new Force(new Metrics(false), asyncEnabled);
        dispatch = initialShape == null ? null : new TupleDispatch(new ForkDestination(initialShape, language), new Metrics(false), argumentCount, false);
    }
    @Override public Object execute(VirtualFrame frame) {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L);
        Object input = frame.getArguments()[0];
        Closure action;
        try { action = Applications.requireClosure(force.execute(frame, input)); }
        catch (ThunkSuspended suspended) {
            AsyncRequest request = suspended.getAsyncRequest();
            if (request == null) throw RuntimeFault.fault("fork# action head suspended without an async request");
            throw new UncaughtForkAsync(request);
        } catch (AsyncBlocked blocked) { throw new UncaughtForkAsync(blocked.getRequest()); }
        TupleDispatch callee = dispatch;
        if (callee == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            TupleShape shape = GuestThreadOps.actionResult(action, asyncEnabled);
            callee = insert(new TupleDispatch(new ForkDestination(shape, language), new Metrics(false), argumentCount, false));
            dispatch = callee;
        }
        Object[] arguments = new Object[argumentCount];
        System.arraycopy(frame.getArguments(), 1, arguments, 0, argumentCount - 1);
        arguments[argumentCount - 1] = Unit.INSTANCE;
        callee.execute(frame, action, arguments);
        return thc.runtime.Unit.INSTANCE;
    }
    @Override public String getName() { return "THC fork action"; }
}
