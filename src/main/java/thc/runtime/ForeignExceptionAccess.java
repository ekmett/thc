// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;

/** Cold exception translation. Normal foreign calls allocate no bridge object. */
public final class ForeignExceptionAccess extends Node {
    @Child private Force force = new Force(new Metrics(false));
    @Child private Dispatch apply = Dispatch.create(1, false, new Metrics(false));
    @Child private InteropLibrary interop = InteropLibrary.getFactory().createDispatched(3);
    private final FrameDescriptor descriptor = FrameDescriptor.newBuilder().build();

    private Object invoke(VirtualFrame frame, Closure function, Object value) {
        return force.execute(frame, apply.execute(frame, function, new Object[]{value}));
    }

    /** Classification uses only Truffle's exception protocol. It must not call
     * user display/message/cause/stack accessors or recursively translate failure. */
    public boolean eligible(AbstractTruffleException error) {
        if (error instanceof GuestException || error instanceof InternalGuestControl) return false;
        var owner = Language.currentState(this);
        try {
            if (owner.getEnv().isHostException(error)) {
                var host = owner.getEnv().asHostException(error);
                if (!ForeignExceptionPolicy.host(host)) return false;
            }
            return interop.isException(error) && ForeignExceptionPolicy.kind(interop.getExceptionType(error));
        } catch (Exception failure) {
            // Broken ordinary protocol implementations preserve the original
            // failure. Cancellation and private transfers must never be hidden.
            if (!ForeignExceptionPolicy.host(failure)) throw propagate(failure);
            if (failure instanceof AbstractTruffleException &&
                (!owner.getEnv().isHostException(failure) ||
                    !ForeignExceptionPolicy.host(owner.getEnv().asHostException(failure)))) throw propagate(failure);
            return false;
        }
    }

    /** Called after carrier permission and pointer borrows have been restored,
     * before an opaque Throwable could pass through Force's failure memoization. */
    @TruffleBoundary public RuntimeException raise(AbstractTruffleException error) {
        var owner = Language.currentState(this);
        var bridge = getRootNode() instanceof GuestRoot root ? root.getForeignExceptionBridge() : null;
        if (bridge == null || owner.getForeignExceptionNormalization().get() || !eligible(error)) throw error;
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
        var flag = Language.currentState(this).getForeignExceptionNormalization();
        boolean active = flag.get();
        flag.set(true);
        Object payload;
        try {
            var box = Applications.requireClosure(force.execute(frame, bridge.box()));
            var project = Applications.requireClosure(force.execute(frame, bridge.project()));
            var retained = bridge.projector(project);
            owner.getForeignExceptionRegistry().register(retained);
            payload = invoke(frame, box, new ForeignFailure(owner, error, retained));
        } finally {
            flag.set(active);
        }
        throw new GuestException(payload, this, true);
    }

    /** Preserve ordinary Java failures from direct host-array operations. */
    @TruffleBoundary public RuntimeException raiseHost(RuntimeException error) {
        if (!ForeignExceptionPolicy.host(error)) throw error;
        Object value = Language.currentState(this).getEnv().asGuestValue(error);
        try { throw interop.throwException(value); }
        catch (AbstractTruffleException foreign) { throw raise(foreign); }
        catch (InteropException unavailable) { throw error; }
    }

    /** Only a compatible public exit asks the real Haskell dictionary whether a
     * lazy SomeException contains our type. Projection failure remains guest failure. */
    @TruffleBoundary public RuntimeException escaping(GuestException failure) {
        if (!failure.getSomeException()) throw failure;
        var owner = Language.currentState(this);
        var projectors = owner.getForeignExceptionRegistry().snapshot();
        if (!projectors.isEmpty()) {
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
            var flag = Language.currentState(this).getForeignExceptionNormalization();
            boolean active = flag.get();
            flag.set(true);
            try {
                // Nominally equal types still have separate authenticated storage
                // domains in separate programs. Normalize only the proven exception,
                // then route to its exact domain before asking the genuine dictionary.
                var payload = force.execute(frame, failure.getPayload());
                ForeignExceptionRegistry.Projector project = null;
                for (var candidate : projectors) {
                    if (candidate.getExceptionLayout().matches(payload)) {
                        if (project != null) { project = null; break; }
                        project = candidate;
                    }
                }
                if (project != null) {
                    var origin = invoke(frame, project.getClosure(), payload);
                    if (origin instanceof ForeignFailure foreign && foreign.getOwner() == owner) throw foreign.getOriginal();
                }
            } finally {
                flag.set(active);
            }
        }
        throw failure;
    }

    @TruffleBoundary public long text(ManagedAddress handle, long selector, long index, Object state) {
        TupleResults.requireVoidCarrier(state);
        var owner = Language.currentState(this);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
        if (!(force.execute(frame, owner.getStablePointers().dereference(handle)) instanceof ForeignFailure value))
            throw RuntimeFault.fault("Expected a genuine ForeignException origin");
        if (value.getOwner() != owner || selector < 0L || selector > 1L)
            throw RuntimeFault.fault("Foreign exception metadata owner or selector");
        int field = (int) selector;
        if (value.getText().get(field) == null) {
            // Metadata may execute foreign code and may fail. Do not mark the
            // field inspected until that operation successfully returns. Concurrent
            // first readers may query independently; first successful publication
            // wins, and no monitor is held while foreign code executes.
            var previous = owner.getThreads().enterForeign(ForeignSafety.SAFE);
            String result;
            try {
                try {
                    if (field == 0) {
                        if (interop.hasMetaObject(value.getOriginal())) {
                            var meta = interop.getMetaObject(value.getOriginal());
                            result = interop.asString(interop.getMetaQualifiedName(meta));
                        } else result = null;
                    } else {
                        result = interop.hasExceptionMessage(value.getOriginal())
                            ? interop.asString(interop.getExceptionMessage(value.getOriginal())) : null;
                    }
                } finally {
                    owner.getThreads().leaveForeign(previous);
                }
            } catch (AbstractTruffleException error) {
                throw raise(error);
            } catch (InteropException error) {
                throw propagate(error);
            }
            value.getText().compareAndSet(field, null, new ForeignFailure.Text(result));
        }
        var text = value.getText().get(field).getValue();
        if (text == null) return -1L;
        int count = text.codePointCount(0, text.length());
        if (index == -1L) return count;
        if (index < 0 || index >= count) throw RuntimeFault.fault("Foreign exception text index");
        return text.codePointAt(text.offsetByCodePoints(0, (int) index));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
