// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Cold committed cut for one exact original catch# action. */
public final class PrivateIOUnwind extends RuntimeException {
    private final CallSegment action;
    private final Object payload;
    private final CapturedAsyncRequest request;
    public PrivateIOUnwind(CallSegment action, Object payload) { this(action, payload, null); }
    public PrivateIOUnwind(CallSegment action, Object payload, CapturedAsyncRequest request) {
        super("Private captured IO-handler unwind", null, false, false);
        this.action = action; this.payload = payload; this.request = request;
    }
    public CallSegment getAction() { return action; }
    public Object getPayload() { return payload; }
    public CapturedAsyncRequest getRequest() { return request; }
}
