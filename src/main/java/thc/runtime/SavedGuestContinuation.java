// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.bytecode.ContinuationResult;

/** One-shot view of a parked activation, retaining its exact published identity. */
public interface SavedGuestContinuation {
    Object getIdentity();
    Object getYielded();
    Object getSourceRoot();
    Object continueWith(Object input);
    /** Terminally discard this activation's own pending work, without
     * recursively disposing shared children retained by thunk/call owners. */
    default void discard() {}
    default AsyncRequest asyncRequest() {
        return switch (getYielded()) {
            case AsyncRequest request -> request;
            case ThunkSuspended suspended -> suspended.getAsyncRequest();
            case CallSegmentSuspended suspended -> suspended.getAsyncRequest();
            case null, default -> null;
        };
    }
    default boolean stackSpill() {
        Object marker = getYielded();
        return marker == AstStackSpill.INSTANCE ||
            marker instanceof ThunkSuspended suspended && suspended.getStackSpill() ||
            marker instanceof CallSegmentSuspended suspended && suspended.getStackSpill();
    }
}
