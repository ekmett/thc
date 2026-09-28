// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

public final class CallSegmentSuspended extends AbstractTruffleException implements InternalGuestControl {
    private final CallSegment segment;
    private final MaskingState parkedActiveMask;
    private final AsyncRequest asyncRequest;
    private final boolean stackSpill;
    public CallSegmentSuspended(CallSegment segment) { this(segment, null); }
    public CallSegmentSuspended(CallSegment segment, MaskingState parkedActiveMask) {
        this(segment, parkedActiveMask, defaultRequest(segment));
    }
    public CallSegmentSuspended(CallSegment segment, MaskingState parkedActiveMask, AsyncRequest asyncRequest) {
        this(segment, parkedActiveMask, asyncRequest, defaultSpill(segment));
    }
    public CallSegmentSuspended(CallSegment segment, MaskingState parkedActiveMask,
                                AsyncRequest asyncRequest, boolean stackSpill) {
        super("Internal bytecode call segment suspension", null, 0, null);
        this.segment = segment; this.parkedActiveMask = parkedActiveMask;
        this.asyncRequest = asyncRequest; this.stackSpill = stackSpill;
    }
    private static AsyncRequest defaultRequest(CallSegment segment) {
        SavedGuestContinuation saved = SavedGuestContinuations.savedGuestContinuation(segment.getValue());
        return saved == null ? null : SavedGuestContinuations.asyncRequest(saved);
    }
    private static boolean defaultSpill(CallSegment segment) {
        SavedGuestContinuation saved = SavedGuestContinuations.savedGuestContinuation(segment.getValue());
        return saved != null && AstStacks.stackSpill(saved);
    }
    public CallSegment getSegment() { return segment; }
    public MaskingState getParkedActiveMask() { return parkedActiveMask; }
    public AsyncRequest getAsyncRequest() { return asyncRequest; }
    public boolean getStackSpill() { return stackSpill; }
}
