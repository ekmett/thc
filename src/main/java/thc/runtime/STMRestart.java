// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/** Aborted scopes retain neither child continuation nor mutable log. */
public final class STMRestart extends AbstractTruffleException implements InternalGuestControl {
    private final AsyncRequest request;
    public STMRestart(AsyncRequest request) { super("Restart interrupted STM scope", null, 0, null); this.request = request; }
    public AsyncRequest getRequest() { return request; }
}
