// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

public final class AsyncDelivery extends AbstractTruffleException implements InternalGuestControl {
    private final AsyncRequest request;
    @TruffleBoundary public AsyncDelivery(AsyncRequest request, Node node) {
        super("Asynchronous guest exception", null, 0, node);
        this.request = request;
    }
    public AsyncRequest getRequest() { return request; }
}
