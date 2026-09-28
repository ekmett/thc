// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.ControlFlowException;

final class UncaughtForkAsync extends ControlFlowException {
    final AsyncRequest request;
    UncaughtForkAsync(AsyncRequest request) { this.request = request; }
}
