// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.MaterializedFrame;
import java.util.HashSet;
import java.util.Set;

/** Cold adaptation of a bytecode suspension; ordinary classification stays inline. */
final class BytecodeContinuations {
    private BytecodeContinuations() {}
    private static final Object WINDOWS_REQUESTS = new Object();

    /** Only explicitly owned native operation tokens, never borrowed operand
     * values or the continuations of retained shared thunk/call children. */
    @TruffleBoundary static void ownRequest(MaterializedFrame frame, WindowsNativeIo.Request request) {
        int slot = frame.getFrameDescriptor().findOrAddAuxiliarySlot(WINDOWS_REQUESTS);
        var requests = requests(frame, slot);
        if (requests == null) { requests = new HashSet<>(); frame.setAuxiliarySlot(slot, requests); }
        requests.add(request);
    }
    @SuppressWarnings("unchecked")
    private static Set<WindowsNativeIo.Request> requests(MaterializedFrame frame, int slot) {
        return (Set<WindowsNativeIo.Request>) frame.getAuxiliarySlot(slot);
    }
    @TruffleBoundary static void releaseRequest(MaterializedFrame frame, WindowsNativeIo.Request request) {
        Integer slot = frame.getFrameDescriptor().getAuxiliarySlots().get(WINDOWS_REQUESTS);
        if (slot == null) return;
        var requests = requests(frame, slot);
        if (requests != null && requests.remove(request) && requests.isEmpty()) frame.setAuxiliarySlot(slot, null);
    }
    @TruffleBoundary private static void discardFrame(MaterializedFrame frame) {
        Integer slot = frame.getFrameDescriptor().getAuxiliarySlots().get(WINDOWS_REQUESTS);
        if (slot == null) return;
        var requests = requests(frame, slot);
        // All registrations were admitted in this activation's context.
        // Validate before changing the frame; rejection leaves its owner live.
        if (requests != null) for (var request : requests) request.discardFromSavedActivation();
        frame.setAuxiliarySlot(slot, null);
    }

    @TruffleBoundary
    static SavedGuestContinuation view(ContinuationResult saved) {
        return new BytecodeSavedContinuation(saved);
    }

    /** State-5 storage keeps the original result, not this transient view. */
    static final class BytecodeSavedContinuation implements SavedGuestContinuation {
        private final ContinuationResult saved;

        private BytecodeSavedContinuation(ContinuationResult saved) {
            this.saved = saved;
        }

        @Override public Object getIdentity() { return saved; }
        @Override public Object getYielded() { return saved.getResult(); }
        @Override public Object getSourceRoot() {
            return saved.getContinuationRootNode().getSourceRootNode();
        }
        @Override public Object continueWith(Object input) { return saved.continueWith(input); }
        @Override public void discard() { discardFrame(saved.getFrame()); }
    }
}
