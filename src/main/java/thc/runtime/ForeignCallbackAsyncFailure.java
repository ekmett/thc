// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

public final class ForeignCallbackAsyncFailure extends AbstractTruffleException implements InternalGuestControl {
    private final Object payload;
    public ForeignCallbackAsyncFailure(Object payload, GuestException guest, Node node) {
        super("Uncaught asynchronous guest callback", guest, 0, node);
        this.payload = payload;
    }
    public Object getPayload() { return payload; }
}
