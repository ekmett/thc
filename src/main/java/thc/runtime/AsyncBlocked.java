// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

public final class AsyncBlocked extends AbstractTruffleException implements InternalGuestControl {
    private final AsyncRequest request;
    public AsyncBlocked(AsyncRequest request, Node node) {
        super("Asynchronous interruption before blocking operation committed", null, 0, node);
        this.request = request;
    }
    public AsyncRequest getRequest() { return request; }
}
