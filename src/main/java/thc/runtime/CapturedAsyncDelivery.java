// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/** Only checkpointed catch# may unwrap this origin tag for its handler. */
public final class CapturedAsyncDelivery extends AbstractTruffleException implements InternalGuestControl {
    private final Object payload;
    private final CapturedAsyncRequest request;
    public CapturedAsyncDelivery(Object payload) { this(payload, null); }
    public CapturedAsyncDelivery(Object payload, CapturedAsyncRequest request) {
        super("Private captured IO-handler delivery", null, 0, null);
        this.payload = payload; this.request = request;
    }
    public Object getPayload() { return payload; }
    public CapturedAsyncRequest getRequest() { return request; }
}
