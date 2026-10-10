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
    record Head(Object value, PendingWait pending) { Head(Object value) { this(value, null); } }
    record Body(SavedGuestContinuation continuation, TupleShape shape) {}
    @Override public Object execute(VirtualFrame frame) {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L);
        Object input = frame.getArguments()[0];
        if (input instanceof Body body) {
            Object result = force.drainStack(body.continuation(), body.shape());
            new ForkDestination(body.shape(), language).consume(frame, this, result);
            return Unit.INSTANCE;
        }
        if (input instanceof Head head) {
            try { if (head.pending() != null) head.pending().resume(); }
            catch (PendingWait cut) { throw new ForkSuspension(new Head(head.value(), cut), cut); }
            catch (AsyncBlocked blocked) { throw new UncaughtForkAsync(blocked.getRequest()); }
            input = head.value();
        }
        Closure action;
        try { action = Applications.requireClosure(force.execute(frame, input)); }
        catch (ThunkSuspended suspended) {
            AsyncRequest request = suspended.getAsyncRequest();
            if (request == null) {
                PendingWait wait = PendingWait.of(suspended);
                if (wait != null) throw new ForkSuspension(new Head(suspended.getThunk()), wait);
                throw RuntimeFault.fault("fork# action head suspended without an async request");
            }
            throw new UncaughtForkAsync(request);
        } catch (PendingWait cut) { throw new ForkSuspension(new Head(input, cut), cut); }
        catch (AsyncBlocked blocked) { throw new UncaughtForkAsync(blocked.getRequest()); }
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
