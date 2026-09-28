// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.VirtualFrame;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Shared lazy application and selector targets; CAS retries allocate only captures and thunks. */
public final class MutVarModifySite {
    private final CaptureLayout applicationLayout, selectorLayout;
    private final RootCallTarget applicationTarget, selectorTarget;
    public MutVarModifySite(Language language, Metrics metrics, boolean async) { this(language, metrics, async, true); }
    public MutVarModifySite(Language language, Metrics metrics, boolean async, boolean selectFirst) {
        applicationLayout = new CaptureLayout(language, new boolean[]{false, false});
        selectorLayout = selectFirst ? new CaptureLayout(language, new boolean[]{false}, new boolean[]{false}, new Class<?>[]{Thunk.class}) : null;
        applicationTarget = new ModifyApplicationRoot(language, applicationLayout, metrics, async).getCallTarget();
        selectorTarget = selectorLayout == null ? null : new ModifySelectorRoot(language, selectorLayout, metrics, async).getCallTarget();
    }
    public Thunk application(Object function, Object old) {
        return new Thunk(applicationTarget, applicationLayout.captureValues(new Object[]{function, old}));
    }
    public Thunk selector(Thunk result) {
        return new Thunk(java.util.Objects.requireNonNull(selectorTarget),
            java.util.Objects.requireNonNull(selectorLayout).captureValues(new Object[]{result}));
    }
    public Thunk stored(Thunk result) { return selectorTarget == null ? result : selector(result); }
    public static MutVarModifySite create(Language language, Metrics metrics, boolean async, boolean selectFirst) {
        return new MutVarModifySite(language, metrics, async, selectFirst);
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }

    private static final class ModifyApplicationRoot extends GuestRoot {
        private final CaptureLayout layout;
        @Child private Dispatch dispatch;
        @Child private Force force;
        ModifyApplicationRoot(TruffleLanguage<?> language, CaptureLayout layout, Metrics metrics, boolean async) {
            super(language, new FrameLayout().build());
            this.layout = layout;
            dispatch = Dispatch.Companion.create(1, false, metrics);
            force = new Force(metrics, async);
        }
        @Override public long bloom(VirtualFrame frame) { return (Long) frame.getArguments()[0] | mask; }
        private final class ResumeDispatch implements AstResumeStep {
            private final Object old;
            ResumeDispatch(Object old) { this.old = old; }
            @Override public Object resume(VirtualFrame frame, Object input) { return invoke(frame, resumed(input), old); }
        }
        private final class ResumeCall implements AstResumeStep {
            private final AstContinuation child;
            ResumeCall(AstContinuation child) { this.child = child; }
            @Override public Object resume(VirtualFrame frame, Object input) { return completeCall(frame, child.continueWith(input)); }
        }
        private Object resumed(Object input) {
            if (input instanceof ChildResume child) {
                if (child.getFailure() != null) throw child.getFailure();
                return child.getValue();
            }
            if (input instanceof Throwable failure) throw propagate(failure);
            throw fault("Invalid atomic MutVar modifier resume");
        }
        private Object completeCall(VirtualFrame frame, Object result) {
            if (result instanceof AstContinuation continuation) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return new AstCapture(continuation.getYielded(), SynchronousMasking.current(this))
                    .append(new ResumeCall(continuation)).freeze(this, frame.materialize(), false);
            }
            return result;
        }
        private Object invoke(VirtualFrame frame, Object value, Object old) {
            if (!(value instanceof Closure function)) throw fault("atomic MutVar modifier is not a function");
            Object result = dispatch.execute(frame, function, new Object[]{old});
            return result instanceof ContinuationResult continuation ? new TailYield(continuation, function.target) : completeCall(frame, result);
        }
        @Override public Object execute(VirtualFrame frame) {
            if (!(frame.getArguments()[1] instanceof CapturedFrame environment)) throw fault("Missing atomic MutVar application capture");
            Object old = layout.read(environment, 1), function;
            try { function = force.execute(frame, layout.read(environment, 0)); }
            catch (ThunkSuspended signal) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return new AstCapture(signal, SynchronousMasking.current(this)).append(new ResumeDispatch(old)).freeze(this, frame.materialize(), false);
            }
            return invoke(frame, function, old);
        }
    }
    private static final class ModifySelectorRoot extends GuestRoot {
        private final CaptureLayout layout;
        @Child private Force force;
        ModifySelectorRoot(TruffleLanguage<?> language, CaptureLayout layout, Metrics metrics, boolean async) {
            super(language, new FrameLayout().build()); this.layout = layout; force = new Force(metrics, async);
        }
        @Override public long bloom(VirtualFrame frame) { return (Long) frame.getArguments()[0] | mask; }
        private AstContinuation suspended(VirtualFrame frame, ThunkSuspended signal, AstResumeStep next) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return new AstCapture(signal, SynchronousMasking.current(this)).append(next).freeze(this, frame.materialize(), false);
        }
        private Object completed(Object input) {
            if (input instanceof ChildResume child) {
                if (child.getFailure() != null) throw child.getFailure();
                return child.getValue();
            }
            if (input instanceof Throwable failure) throw propagate(failure);
            throw fault("Invalid atomic MutVar selector resume");
        }
        private final class ResumeRecord implements AstResumeStep {
            @Override public Object resume(VirtualFrame frame, Object input) { return select(frame, completed(input)); }
        }
        private final class ResumeField implements AstResumeStep {
            @Override public Object resume(VirtualFrame frame, Object input) { return completed(input); }
        }
        private Object select(VirtualFrame frame, Object record) {
            if (!(record instanceof DataValue data)) throw fault("atomicModifyMutVar2# modifier did not return a data record");
            Object field = data.getLayout().readFirstLifted$org_intelligence_thc(data);
            try { return force.execute(frame, field); }
            catch (ThunkSuspended signal) { return suspended(frame, signal, new ResumeField()); }
        }
        @Override public Object execute(VirtualFrame frame) {
            if (!(frame.getArguments()[1] instanceof CapturedFrame environment)) throw fault("Missing atomic MutVar selector capture");
            Object result = layout.read(environment, 0), record;
            try { record = force.execute(frame, result); }
            catch (ThunkSuspended signal) { return suspended(frame, signal, new ResumeRecord()); }
            return select(frame, record);
        }
    }
}
