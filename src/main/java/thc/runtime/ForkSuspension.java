// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.ControlFlowException;

/** Only the independent runner consumes this complete fork suffix. */
final class ForkSuspension extends ControlFlowException {
    final Object work;
    final PendingWait wait;
    ForkSuspension(Object work, PendingWait wait) { this.work = work; this.wait = wait; }
}
