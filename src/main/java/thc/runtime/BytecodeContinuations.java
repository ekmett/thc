// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.Node;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** Cold adaptation of a bytecode suspension; ordinary classification stays inline. */
final class BytecodeContinuations {
    private BytecodeContinuations() {}
    private static final Object ACTIVATION = new Object();

    /** Copies share provenance and token association, never a frame-wide terminal bit.
     * ContinuationResult is final and retains Object identity equality. Values must
     * not retain their weak keys, frames or execution contexts. */
    private static final class Activation {
        WeakReference<Language.State> owner;
        final WeakHashMap<ContinuationResult, Boolean> terminal = new WeakHashMap<>();
        final Set<WindowsNativeIo.Request> requests = new HashSet<>();
    }
    private static Activation activation(MaterializedFrame frame, boolean create) {
        synchronized (frame) {
            Integer slot = frame.getFrameDescriptor().getAuxiliarySlots().get(ACTIVATION);
            if (slot == null) {
                if (!create) return null;
                slot = frame.getFrameDescriptor().findOrAddAuxiliarySlot(ACTIVATION);
            }
            Activation activation = (Activation) frame.getAuxiliarySlot(slot);
            if (activation == null && create) {
                activation = new Activation();
                frame.setAuxiliarySlot(slot, activation);
            }
            return activation;
        }
    }
    /** Called at the actual cut, before publishing its saved token. Never adopt
     * the context of a later classifier or resumer. */
    @TruffleBoundary static void recordOwner(MaterializedFrame frame, Node node) {
        var current = Language.currentState(node);
        var activation = activation(frame, true);
        synchronized (activation) {
            if (activation.owner == null) activation.owner = new WeakReference<>(current);
            else if (activation.owner.get() != current) throw fault("Bytecode continuation belongs to another execution context");
        }
    }
    /** Only explicitly owned native tokens, never retained shared children. */
    @TruffleBoundary static void ownRequest(MaterializedFrame frame, WindowsNativeIo.Request request) {
        var activation = activation(frame, true);
        synchronized (activation) { activation.requests.add(request); }
    }
    @TruffleBoundary static void releaseRequest(MaterializedFrame frame, WindowsNativeIo.Request request) {
        var activation = activation(frame, false);
        if (activation != null) synchronized (activation) { activation.requests.remove(request); }
    }
    private static Activation owned(ContinuationResult saved) {
        var activation = activation(saved.getFrame(), false);
        if (activation == null) throw fault("Bytecode continuation has no execution provenance");
        return activation;
    }
    private static void requireOwner(Activation activation, ContinuationResult saved) {
        if (activation.owner == null || activation.owner.get() != Language.currentState(
                (Node) saved.getContinuationRootNode().getSourceRootNode()))
            throw fault("Bytecode continuation belongs to another execution context");
        for (var request : activation.requests) request.preflightFromSavedActivation();
    }
    /** Authorization only, before an outer thunk/segment/request changes owner. */
    @TruffleBoundary static void preflight(ContinuationResult saved) {
        var activation = owned(saved);
        synchronized (activation) {
            requireOwner(activation, saved);
        }
    }
    static void preflight(SavedGuestContinuation saved) {
        if (saved instanceof BytecodeSavedContinuation bytecode) preflight(bytecode.saved);
    }
    /** A saved AST waiter can lead to a bytecode child. Authorize the cold
     * dependency path before inspecting logical wait ownership, without changing
     * PendingWait's publication/introspection protocol or claiming any token. */
    @TruffleBoundary static void preflightWait(Object value) {
        var seen = new java.util.IdentityHashMap<Object, Boolean>();
        for (;;) {
            if (value != null && seen.put(value, Boolean.TRUE) != null)
                throw fault("Suspended guest dependency cycle");
            if (value instanceof ThunkSuspended suspended) value = suspended.getThunk().getValue();
            else if (value instanceof CallSegmentSuspended suspended) value = suspended.getSegment().getValue();
            else if (value instanceof Force.DriverWait driver) value = driver.segment;
            else if (value instanceof Thunk thunk) value = thunk.getState() == 5 ? thunk.getValue() : null;
            else if (value instanceof CallSegment segment) value = segment.getState() == 5 ? segment.getValue() : null;
            else {
                var saved = SavedGuestContinuations.savedGuestContinuation(value);
                if (saved == null) return;
                preflight(saved);
                value = saved.getYielded();
            }
        }
    }
    @TruffleBoundary private static void claim(ContinuationResult saved) {
        var activation = owned(saved);
        synchronized (activation) {
            requireOwner(activation, saved);
            if (activation.terminal.putIfAbsent(saved, Boolean.TRUE) != null)
                throw fault("Bytecode continuation was already consumed");
        }
    }
    @TruffleBoundary private static void discard(ContinuationResult saved) {
        var activation = owned(saved);
        WindowsNativeIo.Request[] requests;
        synchronized (activation) {
            requireOwner(activation, saved);
            if (activation.terminal.putIfAbsent(saved, Boolean.TRUE) != null) return;
            requests = activation.requests.toArray(WindowsNativeIo.Request[]::new);
            activation.requests.clear();
        }
        // The terminal claim and retired registrations survive every cleanup failure.
        Throwable failure = null;
        for (var request : requests) try { request.discardFromSavedActivation(); }
        catch (RuntimeException | Error caught) {
            if (failure == null) failure = caught;
            else if (failure != caught) failure.addSuppressed(caught);
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    @TruffleBoundary static SavedGuestContinuation view(ContinuationResult saved) {
        return new BytecodeSavedContinuation(saved);
    }

    /** State-5 storage keeps the original result, not this transient view. */
    static final class BytecodeSavedContinuation implements SavedGuestContinuation {
        private final ContinuationResult saved;
        private BytecodeSavedContinuation(ContinuationResult saved) { this.saved = saved; }
        @Override public Object getIdentity() { return saved; }
        @Override public Object getYielded() { return saved.getResult(); }
        @Override public Object getSourceRoot() { return saved.getContinuationRootNode().getSourceRootNode(); }
        @Override public Object continueWith(Object input) { claim(saved); return saved.continueWith(input); }
        @Override public void discard() { BytecodeContinuations.discard(saved); }
    }
}
